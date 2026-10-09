package com.codync.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** The popup occupies a separate window, so opening it never grows or scrolls a message. */
@Composable internal fun MessageActionBubble(
    viewport: Rect, enabled: Boolean, reactions: List<String>, reacting: Boolean,
    react: (String) -> Unit, reply: (() -> Unit)?, trace: (() -> Unit)?, copy: () -> Unit,
    modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var anchor by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    fun open(point: Offset?) {
        val layout = coordinates?.takeIf { it.isAttached } ?: return
        val visible = layout.boundsInWindow().intersect(viewport)
        if (visible.isEmpty) return
        anchor = point ?: visible.center
        expanded = true
    }
    // A local function reference compares equal across recompositions even when its viewport changes.
    val currentOpen by rememberUpdatedState<(Offset?) -> Unit>({ point -> open(point) })
    val actions = if (enabled) Modifier
        // Capture a hold before a child link's click recognizer, while leaving taps and drags alone.
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val layout = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val downPoint = layout.localToWindow(down.position)
                val releasedOrDragged = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        val currentLayout = coordinates?.takeIf { it.isAttached }
                        if (change == null || !change.pressed || event.changes.count { it.pressed } > 1 ||
                            currentLayout == null || (currentLayout.localToWindow(change.position) - downPoint).getDistance() > viewConfiguration.touchSlop)
                            return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (releasedOrDragged == null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOpen(downPoint)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
        .clickable(onClickLabel = "Message actions", onClick = { open(null) })
        .semantics {
            onClick("Message actions") { open(null); true }
            onLongClick("Message actions") { open(null); true }
            customActions = listOf(CustomAccessibilityAction("Message actions") { open(null); true })
        } else Modifier
    Box(modifier.onGloballyPositioned { coordinates = it }.then(actions)) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        MessageActionsMenu(expanded, anchor, viewport, reactions, reacting, react, reply, trace, copy) { expanded = false }
    }
}

@Composable private fun MessageActionsMenu(
    expanded: Boolean, anchor: Offset, viewport: Rect, reactions: List<String>, reacting: Boolean,
    react: (String) -> Unit, reply: (() -> Unit)?, trace: (() -> Unit)?, copy: () -> Unit, close: () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(anchor, viewport, margin) { MessageActionsPositionProvider(anchor, viewport, margin) }
    if (transition.currentState || transition.targetState) {
        Popup(popupPositionProvider = position, onDismissRequest = close,
            properties = PopupProperties(focusable = true)) {
            AnimatedVisibility(transition, enter = fadeIn() + scaleIn(initialScale = .96f),
                exit = fadeOut() + scaleOut(targetScale = .96f)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 8.dp, modifier = Modifier
                        .widthIn(max = with(density) { (viewport.width - 2 * margin).coerceAtLeast(1f).toDp() })
                        .width(300.dp)
                        .heightIn(max = with(density) { (viewport.height - 2 * margin).coerceAtLeast(1f).toDp() })
                        .semantics { paneTitle = "Message actions" }) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
                        FlowRow {
                            for (emoji in listOf("👍", "❤️", "😂", "🎉", "👀", "✅")) {
                                val chosen = emoji in reactions
                                TextButton(enabled = !reacting, onClick = { close(); react(emoji) },
                                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)
                                        .background(if (chosen) MaterialTheme.colorScheme.surfaceContainerHighest
                                            else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                                        .semantics { contentDescription = if (chosen) "Remove reaction $emoji" else "React $emoji"; selected = chosen }) {
                                    Text(emoji, fontSize = 24.sp)
                                }
                            }
                        }
                        MenuAction("Copy") { close(); copy() }
                        reply?.let { MenuAction("Reply in thread") { close(); it() } }
                        trace?.let { MenuAction("Show what it did") { close(); it() } }
                    }
                }
            }
        }
    }
}

/** Use the touched part of a long message, with a visible-center fallback for accessibility. */
internal class MessageActionsPositionProvider(private val point: Offset, private val viewport: Rect,
    private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val left = maxOf(margin, viewport.left.roundToInt() + margin)
        val top = maxOf(margin, viewport.top.roundToInt() + margin)
        val right = minOf(windowSize.width - margin, viewport.right.roundToInt() - margin)
        val bottom = minOf(windowSize.height - margin, viewport.bottom.roundToInt() - margin)
        val x = (point.x.roundToInt() - popupContentSize.width / 2).coerceIn(left, maxOf(left, right - popupContentSize.width))
        val below = point.y.roundToInt() + margin
        val above = point.y.roundToInt() - margin - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= bottom -> below
            above >= top -> above
            else -> below.coerceIn(top, maxOf(top, bottom - popupContentSize.height))
        }.coerceIn(top, maxOf(top, bottom - popupContentSize.height))
        return IntOffset(x, y)
    }
}
