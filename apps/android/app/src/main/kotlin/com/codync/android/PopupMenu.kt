package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.codync.android.core.*
import com.codync.android.design.*

@Composable internal fun CodyncPopupMenu(expanded: Boolean, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val transition = remember { MutableTransitionState(false) }; transition.targetState = expanded
    val offset = with(LocalDensity.current) { 48.dp.roundToPx() }
    if (transition.currentState || transition.targetState) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offset),
        onDismissRequest = close, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(transition, enter = fadeIn() + scaleIn(initialScale = .96f), exit = fadeOut() + scaleOut(targetScale = .96f)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
                Column(Modifier.widthIn(min = 210.dp, max = 280.dp).padding(6.dp), content = content)
            }
        }
    }
}

@Composable internal fun MenuAction(label: String, enabled: Boolean = true, action: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled, role = Role.Button, onClick = action)
        .padding(horizontal = 12.dp, vertical = 13.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .35f), style = MaterialTheme.typography.bodyMedium)
}

@Composable internal fun InlineError(message: String, clear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        CodyncIconButton("Dismiss", Glyph.Close, clear)
    }
}
