package com.codync.android

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

private class SheetBackDispatcher {
    val handlers = mutableListOf<() -> Unit>()
    fun dispatch(): Boolean {
        val handler = handlers.lastOrNull() ?: return false
        handler()
        return true
    }
}

private val LocalSheetBackDispatcher = staticCompositionLocalOf<SheetBackDispatcher?> { null }

/** Dialog windows receive Back before the Activity: route it to the visible sheet's page. */
@Composable internal fun CodyncSheetBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val dispatcher = LocalSheetBackDispatcher.current
    val currentBack by rememberUpdatedState(onBack)
    DisposableEffect(dispatcher, enabled) {
        val handler: () -> Unit = { currentBack() }
        if (enabled) dispatcher?.handlers?.add(handler)
        onDispose { dispatcher?.handlers?.remove(handler) }
    }
    if (dispatcher == null) BackHandler(enabled, onBack)
}

/** Thread presentation uses the same mobile sheet as account and bot settings. */
@Composable internal fun RepliesPopover(title: String = "Thread", close: () -> Unit, back: (() -> Unit) -> Unit,
    content: @Composable (close: () -> Unit) -> Unit) {
    CodyncSheet(title, close, back, content)
}

/** Matches the iPhone sheet while keeping its presenting screen alive underneath. */
@Composable internal fun CodyncSheet(title: String, close: () -> Unit, back: (() -> Unit) -> Unit = { it() },
    content: @Composable (close: () -> Unit) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss: () -> Unit = { if (!closing) { keyboard?.hide(); closing = true } }
    val currentBack by rememberUpdatedState(back)
    val currentClose by rememberUpdatedState(close)
    val backDispatcher = remember { SheetBackDispatcher() }
    val progress = animateFloatAsState(if (shown && !closing) 1f else 0f, tween(220), label = "Sheet presentation")
    val settle = animateFloatAsState(if (dragging) drag else 0f, tween(160), label = "Sheet drag")
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(closing, progress.value) { if (closing && progress.value <= .001f) currentClose() }
    Dialog(onDismissRequest = { if (!backDispatcher.dispatch()) currentBack(dismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            // API 29 needs resize for the dialog's IME insets; modern Android also uses imePadding.
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            onDispose { }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f * progress.value))
                .pointerInput(Unit) { detectTapGestures { currentBack(dismiss) } }.clearAndSetSemantics {})
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                val wide = maxWidth >= 600.dp
                val threshold = with(LocalDensity.current) { 96.dp.toPx() }
                Surface(color = MaterialTheme.colorScheme.background,
                    shape = if (wide) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(if (wide) .9f else .96f)
                        .graphicsLayer { translationY = (1f - progress.value) * size.height + if (dragging) drag else settle.value }
                        .semantics { paneTitle = title }) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(22.dp).pointerInput(threshold) {
                            detectVerticalDragGestures(onDragStart = { drag = 0f; dragging = true },
                                onVerticalDrag = { change, amount -> change.consume(); drag = (drag + amount).coerceAtLeast(0f) },
                                onDragEnd = { if (drag > threshold) currentBack(dismiss); dragging = false },
                                onDragCancel = { dragging = false })
                        }.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                            Box(Modifier.width(36.dp).height(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), RoundedCornerShape(3.dp)))
                        }
                        Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalSheetBackDispatcher provides backDispatcher) { content(dismiss) }
                        }
                    }
                }
            }
        }
    }
}
