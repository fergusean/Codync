package com.codync.android

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.design.CodyncTheme
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CameraScannerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cameraPermissionIsExplicitAndSettingsRemainAvailableAfterDenial() {
        var requested = false; var settings = false
        compose.setContent { CodyncTheme { Column { CameraPermissionContent(true, { requested = true }, { settings = true }) } } }
        assertFalse(requested)
        compose.onNodeWithText("Allow camera").performClick()
        assertTrue(requested)
        compose.onNodeWithText("Camera settings").performClick()
        assertTrue(settings)
    }

    @Test fun aDeviceWithoutACameraExplainsThePairingLinkFallback() {
        compose.setContent { CodyncTheme { CameraPermissionContent(false, {}, {}) } }
        compose.onNodeWithText("This device has no camera. Use your computer's pairing link instead.").assertIsDisplayed()
        compose.onNodeWithText("Allow camera").assertDoesNotExist()
    }

    /** Explicit emulator gate. The ordinary suite does not silently grant physical camera access. */
    @Test fun aRealCameraClosesInBackgroundReopensAndReleasesWhenLeavingScan() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectCamera") == "true")
        assertTrue("Native camera fixture belongs on an emulator", android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val unavailable = ConcurrentHashMap.newKeySet<String>()
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) { unavailable.add(cameraId) }
            override fun onCameraAvailable(cameraId: String) { unavailable.remove(cameraId) }
        }
        manager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
        val visible = mutableStateOf(true)
        try {
            compose.setContent { CodyncTheme { if (visible.value) PairingScanner({ fail("The empty emulator camera must not find a pairing code") }, { visible.value = false }) else Text("Scanner closed") } }
            compose.waitUntil(15_000) { unavailable.isNotEmpty() }
            compose.onNodeWithText("Couldn't open the camera.", substring = true).assertDoesNotExist()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(15_000) { unavailable.isEmpty() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(15_000) { unavailable.isNotEmpty() }
            compose.onNodeWithText("Use pairing link").performClick()
            compose.onNodeWithText("Scanner closed").assertIsDisplayed()
            compose.waitUntil(15_000) { unavailable.isEmpty() }
        } finally {
            compose.runOnUiThread { visible.value = false }
            manager.unregisterAvailabilityCallback(callback)
        }
    }
}
