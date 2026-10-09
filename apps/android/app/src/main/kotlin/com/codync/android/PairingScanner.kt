package com.codync.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codync.android.core.PairingQr
import com.codync.android.core.PairingScan
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable fun PairingScanner(found: (String) -> Unit, close: () -> Unit, inPopover: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val hardware = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    CodyncSheetBackHandler(onBack = close)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Surface(Modifier.fillMaxSize()) {
        Column((if (inPopover) Modifier else Modifier.safeDrawingPadding()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = close) { Text("Back") }
                Text("Scan pairing code", style = MaterialTheme.typography.headlineMedium)
            }
            when {
                !hardware || !permitted -> CameraPermissionContent(hardware,
                    { permission.launch(Manifest.permission.CAMERA) },
                    { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) })
                else -> key(attempt) {
                    CameraPairingPreview(Modifier.weight(1f).fillMaxWidth(), found) { error = it }
                    Text("Point the camera at your computer's Codync pairing code.")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { error = null; attempt++ }) { Text("Try camera again") } }
            TextButton(onClick = close) { Text("Use pairing link") }
        }
    }
}

@Composable internal fun CameraPermissionContent(available: Boolean, request: () -> Unit, settings: () -> Unit) {
    if (!available) Text("This device has no camera. Use your computer's pairing link instead.")
    else {
        Text("Allow camera access to scan the code on your computer. The camera is used only while this screen is open.")
        Button(onClick = request) { Text("Allow camera") }
        TextButton(onClick = settings) { Text("Camera settings") }
    }
}

/** CameraX owns foreground lifecycle. Every analyzed frame closes; no image or pairing link is saved. */
@Composable private fun CameraPairingPreview(modifier: Modifier, found: (String) -> Unit, error: (String?) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onFound by rememberUpdatedState(found)
    val onError by rememberUpdatedState(error)
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    DisposableEffect(owner, view) {
        val closed = AtomicBoolean(false)
        val accepted = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = PairingQr()
        val preview = Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
        var lastFrame = 0L
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!closed.get() && !accepted.get() && now - lastFrame >= 150) {
                    lastFrame = now
                    val plane = image.planes.first()
                    val pixels = PairingQr.luminance(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                    val result = try { reader.decode(pixels, image.width, image.height) } finally { pixels.fill(0) }
                    if (result != null) main.execute {
                        if (!closed.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) when (result) {
                            is PairingScan.Found -> if (accepted.compareAndSet(false, true)) onFound(result.link)
                            is PairingScan.Invalid -> onError(result.message)
                        }
                    }
                }
            } catch (_: Exception) { /* A damaged/partial camera frame can be skipped. */ }
            finally { image.close() }
        }
        var provider: ProcessCameraProvider? = null
        val cameraState = Observer<CameraState> { state ->
            if (!closed.get() && state.error != null) onError("Couldn't open the camera. Retry or use a pairing link.")
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!closed.get()) try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    onError("Allow camera access or use a pairing link.")
                    return@addListener
                }
                val resolved = future.get()
                provider = resolved
                val selector = if (resolved.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val bound = resolved.bindToLifecycle(owner, selector, preview, analysis)
                camera = bound
                bound.cameraInfo.cameraState.observe(owner, cameraState)
            } catch (_: Exception) { onError("Couldn't open the camera. Retry or use a pairing link.") }
        }, main)
        onDispose {
            closed.set(true); analysis.clearAnalyzer()
            camera?.cameraInfo?.cameraState?.removeObserver(cameraState)
            provider?.unbind(preview, analysis)
            camera = null
            executor.shutdownNow()
        }
    }
    Box(modifier) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
    if (camera?.cameraInfo?.hasFlashUnit() == true) TextButton(onClick = {
        val active = camera ?: return@TextButton
        val next = !torch
        val result = active.cameraControl.enableTorch(next)
        result.addListener({
            if (camera === active) try { result.get(); torch = next }
            catch (_: Exception) { onError("The flashlight is unavailable. Try again.") }
        }, ContextCompat.getMainExecutor(context))
    }) { Text(if (torch) "Turn flashlight off" else "Turn flashlight on") }
}
