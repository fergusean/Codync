package com.codync.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.testTag
import com.codync.android.core.BotDraft
import com.codync.android.design.AvatarPalette
import com.codync.android.design.CharacterAvatar
import com.codync.android.design.CodyncIcon
import com.codync.android.design.Glyph

@Composable internal fun BotEditorAvatar(shape: String, color: String, chooseShape: (String) -> Unit, chooseColor: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(18.dp)
            .semantics { contentDescription = "Bot avatar" }) {
            CharacterAvatar(shape, color, 96.dp)
        }
        LazyRow(Modifier.fillMaxWidth().selectableGroup().testTag("Bot avatar shapes"), horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            items(AvatarPalette.shapes, key = { it }) { option ->
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .border(2.dp, if (option == shape) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .selectable(option == shape, role = Role.RadioButton, onClick = { chooseShape(option) })
                    .semantics { contentDescription = "$option shape" }, contentAlignment = Alignment.Center) {
                    CharacterAvatar(option, color, 36.dp)
                }
            }
        }
        LazyRow(Modifier.fillMaxWidth().selectableGroup().testTag("Bot avatar colors"), horizontalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            items(AvatarPalette.colors.keys.toList(), key = { it }) { option ->
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .selectable(option == color, role = Role.RadioButton, onClick = { chooseColor(option) })
                    .semantics { contentDescription = option.replaceFirstChar { it.uppercase() } }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(34.dp).border(2.dp,
                        if (option == color) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(26.dp).background(AvatarPalette.tint(option), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable internal fun BotEditorField(label: String, value: String, change: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, minLines: Int = 1, maxLines: Int = 1, monospace: Boolean = false) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value, change, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics { contentDescription = label },
            singleLine = maxLines == 1, minLines = minLines, maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    field()
                }
            })
    }
}

@Composable internal fun BotEditorOptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Row(Modifier.widthIn(max = 190.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable internal fun BotEditorModelField(value: String, change: (String) -> Unit) {
    BasicTextField(value, change, Modifier.widthIn(min = 80.dp, max = 140.dp).heightIn(min = 44.dp)
        .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.background)
        .padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = "Model ID" },
        singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { field -> Box(contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) Text("Default", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            field()
        } })
}

@Composable internal fun BotEditorChoice(name: String, selected: String, options: List<Pair<String, String>>,
    label: String? = null, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember(name) { mutableStateOf(false) }
    val value = label ?: options.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.background)
            .clickable(enabled = enabled, role = Role.Button, onClick = { expanded = true })
            .semantics { contentDescription = name }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            CodyncIcon(Glyph.Down, Modifier.size(14.dp))
        }
        DropdownMenu(expanded, { expanded = false }, shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text, maxLines = 2) }, onClick = { expanded = false; choose(id) },
                    modifier = Modifier.semantics { this.selected = id == selected },
                    trailingIcon = { if (id == selected) CodyncIcon(Glyph.Check, Modifier.size(18.dp)) })
            }
        }
    }
}

@Composable internal fun BotEditorToggle(title: String, detail: String, value: Boolean, change: (Boolean) -> Unit) {
    val fill = animateColorAsState(if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "$title switch color")
    val thumb = animateDpAsState(if (value) 16.dp else 2.dp, label = "$title switch position")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, role = Role.Switch, onValueChange = change)
        .semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.width(34.dp).height(20.dp).background(fill.value, CircleShape)) {
            Box(Modifier.offset { IntOffset(thumb.value.roundToPx(), 2.dp.roundToPx()) }.size(16.dp)
                .background(if (value) MaterialTheme.colorScheme.onPrimary else Color.White, CircleShape))
        }
    }
}

@Composable internal fun BotEditorPlugins(state: AppState, draft: BotDraft, change: (BotDraft) -> Unit) {
    val plugins = state.plugins
    val loading = "plugins" in state.busy
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connectors", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BotEditorCard {
                if (plugins == null) Text(if (loading) "Loading installed connectors…" else "Connect to this computer to load its connectors.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (plugins.connectors.isEmpty()) Text("No connectors yet. Add GitHub, Linear, Notion and more from Plugins.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    val selected = draft.connectors ?: plugins.connectors.map { it.id }
                    plugins.connectors.forEach { item ->
                        BotEditorToggle(item.name, if (item.needsSignIn) "Sign-in needed"
                            else item.command ?: item.url ?: item.description, item.id in selected) {
                            change(draft.copy(connectors = if (it) (selected - item.id) + item.id else selected - item.id))
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Skills", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BotEditorCard {
                if (plugins == null) Text(if (loading) "Loading installed skills…" else "Connect to this computer to load its skills.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (plugins.skills.isEmpty()) Text("No skills yet. Get some from Plugins, or write your own.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else plugins.skills.forEach { item ->
                    BotEditorToggle(item.name, item.description, item.id in draft.skills) {
                        change(draft.copy(skills = if (it) (draft.skills - item.id) + item.id else draft.skills - item.id))
                    }
                }
            }
        }
    }
}
