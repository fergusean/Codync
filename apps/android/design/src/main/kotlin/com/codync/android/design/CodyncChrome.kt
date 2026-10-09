package com.codync.android.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight

enum class Glyph { Person, Down, Plus, Back, More, Search, Bots, State, Chart, Microphone, Send, Close, Computer, Pin, Stop, Refresh,
    Check, QR, Forward, SignOut, Trash, Bell }

/** Small original vector glyphs, using Android's font and Codync's existing visual hierarchy. */
@Composable fun CodyncIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(22.dp)) {
        val stroke = Stroke(1.8f, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x, y), Offset(x2, y2), 1.8f, StrokeCap.Round)
        fun path(vararg points: Float) {
            val value = Path(); value.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) value.lineTo(points[i], points[i + 1])
            drawPath(value, color, style = stroke)
        }
        withTransform({ scale(size.minDimension / 24, size.minDimension / 24, Offset.Zero) }) {
            when (glyph) {
                Glyph.Person -> { drawCircle(color, 3.5f, Offset(12f, 7f)); drawRoundRect(color, Offset(5f, 13f), Size(14f, 8f), CornerRadius(5f)) }
                Glyph.Down -> path(7f, 10f, 12f, 15f, 17f, 10f)
                Glyph.Plus -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                Glyph.Back -> path(15f, 5f, 8f, 12f, 15f, 19f)
                Glyph.Close -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
                Glyph.Check -> path(5f, 12f, 10f, 17f, 19f, 6f)
                Glyph.Forward -> path(9f, 5f, 16f, 12f, 9f, 19f)
                Glyph.QR -> {
                    for ((x, y) in listOf(3f to 3f, 15f to 3f, 3f to 15f)) {
                        drawRect(color, Offset(x, y), Size(6f, 6f), style = stroke)
                        drawRect(color, Offset(x + 2f, y + 2f), Size(2f, 2f))
                    }
                    path(15f, 15f, 18f, 15f, 18f, 18f, 21f, 18f, 21f, 21f)
                    line(15f, 18f, 15f, 21f)
                    line(21f, 12f, 21f, 15f)
                }
                Glyph.SignOut -> {
                    path(12f, 3f, 3f, 3f, 3f, 21f, 12f, 21f)
                    line(9f, 12f, 21f, 12f)
                    path(17f, 8f, 21f, 12f, 17f, 16f)
                }
                Glyph.Trash -> {
                    line(5f, 5f, 19f, 5f); line(9f, 3f, 15f, 3f)
                    path(6f, 8f, 7f, 21f, 17f, 21f, 18f, 8f)
                    line(10f, 10f, 10f, 18f); line(14f, 10f, 14f, 18f)
                }
                Glyph.Bell -> {
                    drawArc(color, 180f, 180f, false, Offset(7f, 4f), Size(10f, 10f), style = stroke)
                    path(7f, 9f, 7f, 14f, 5f, 18f, 19f, 18f, 17f, 14f, 17f, 9f)
                    line(12f, 2f, 12f, 4f); drawCircle(color, 1.4f, Offset(12f, 21f))
                }
                Glyph.More -> for (x in listOf(5f, 12f, 19f)) drawCircle(color, 1.7f, Offset(x, 12f))
                Glyph.Search -> { drawCircle(color, 6.5f, Offset(10f, 10f), style = stroke); line(15f, 15f, 21f, 21f) }
                Glyph.Bots -> { drawRoundRect(color, Offset(3f, 3f), Size(14f, 12f), CornerRadius(3f), style = stroke); path(5f, 15f, 5f, 19f, 9f, 15f); path(18f, 8f, 21f, 8f, 21f, 20f, 17f, 18f, 12f, 18f) }
                Glyph.State -> { line(7f, 3f, 17f, 3f); line(5f, 6f, 19f, 6f); drawRoundRect(color, Offset(3f, 9f), Size(18f, 13f), CornerRadius(3f), style = stroke) }
                Glyph.Chart -> { line(5f, 20f, 5f, 12f); line(12f, 20f, 12f, 5f); line(19f, 20f, 19f, 9f) }
                Glyph.Microphone -> { drawRoundRect(color, Offset(9f, 3f), Size(6f, 12f), CornerRadius(3f), style = stroke); path(5f, 11f, 5f, 14f, 8f, 18f, 16f, 18f, 19f, 14f, 19f, 11f); line(12f, 18f, 12f, 22f) }
                Glyph.Send -> { path(12f, 20f, 12f, 4f); path(5f, 11f, 12f, 4f, 19f, 11f) }
                Glyph.Computer -> { drawRoundRect(color, Offset(2f, 3f), Size(20f, 14f), CornerRadius(2f), style = stroke); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
                Glyph.Pin -> { path(9f, 3f, 15f, 3f, 15f, 9f, 18f, 13f, 6f, 13f, 9f, 9f, 9f, 3f); line(12f, 13f, 12f, 21f) }
                Glyph.Refresh -> { drawArc(color, 45f, 285f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke); path(20f, 3f, 20f, 9f, 14f, 9f) }
                Glyph.Stop -> drawRoundRect(color, Offset(6f, 6f), Size(12f, 12f), CornerRadius(2f))
            }
        }
    }
}

@Composable fun CodyncIconButton(label: String, glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, filled: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(48.dp).clip(CircleShape).background(if (filled) colors.primary else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        CodyncIcon(glyph, color = (if (filled) colors.onPrimary else colors.onSurface).copy(alpha = if (enabled) 1f else .35f))
    }
}

@Composable fun CodyncTopBar(title: @Composable () -> Unit, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    Layout(content = {
        Row(verticalAlignment = Alignment.CenterVertically) { leading() }
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) { title() }
    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { children, constraints ->
        val edges = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth / 3)
        val left = children[0].measure(edges)
        val right = children[1].measure(edges)
        val middle = children[2].measure(constraints.copy(minWidth = 0, minHeight = 0,
            maxWidth = (constraints.maxWidth - 2 * maxOf(left.width, right.width)).coerceAtLeast(0)))
        val height = constraints.constrainHeight(maxOf(56.dp.roundToPx(), left.height, right.height, middle.height))
        layout(constraints.maxWidth, height) {
            left.placeRelative(0, (height - left.height) / 2)
            right.placeRelative(constraints.maxWidth - right.width, (height - right.height) / 2)
            middle.placeRelative((constraints.maxWidth - middle.width) / 2, (height - middle.height) / 2)
        }
    }
}

@Composable fun CodyncFloatingTabs(selected: String, select: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, glyph) in listOf("Bots" to Glyph.Bots, "State" to Glyph.State)) {
            val active = selected == label
            Column(Modifier.widthIn(min = 76.dp).heightIn(min = 50.dp).clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = .08f) else Color.Transparent)
                .selectable(active, role = Role.Tab, onClick = { select(label) }).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                CodyncIcon(glyph, Modifier.size(18.dp), if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Wrapping choices keep their touch targets and selection state at large text sizes. */
@Composable fun CodyncChoices(selected: String, options: List<Pair<String, String>>, select: (String) -> Unit,
    modifier: Modifier = Modifier, role: Role = Role.RadioButton) {
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((id, label) in options) {
            val active = id == selected
            val fill = animateColorAsState(if (active) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerLow, label = "Choice selection")
            Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(fill.value)
                .selectable(active, role = role, onClick = { select(id) }).padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
