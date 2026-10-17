package com.codync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.codync.android.design.*
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

// The settings-form pieces of iOS `BotEditorView`: labelled fields, switch rows, the avatar
// picker and the connector/skill toggles.

/** A labeled input inside a section row: label (and an optional note) over the field. */
@Composable internal fun Field(label: String, detail: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = Type.body, color = Palette.text)
            if (detail != null) Text(detail, style = Type.caption.copy(lineHeight = Type.caption.lineHeight * 1.12f), color = Palette.secondary)
        }
        content()
    }
}

/** A rounded input box (`fieldBox`): a plain field on the ground color. */
@Composable internal fun FieldBox(text: String, onChange: (String) -> Unit, prompt: String, minLines: Int = 1, maxLines: Int = 1,
    style: TextStyle = Type.body, fill: Color = Palette.background, capitalize: Boolean = true, imeAction: ImeAction = ImeAction.Default,
    actions: KeyboardActions = KeyboardActions.Default) {
    Box(Modifier.fillMaxWidth().clip(RoundedRectangle(12.dp)).background(fill).padding(horizontal = 14.dp, vertical = 11.dp)) {
        PlainTextField(text, onChange, Modifier.fillMaxWidth(), prompt, singleLine = maxLines == 1, style = style, minLines = minLines,
            maxLines = maxLines, keyboard = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                autoCorrectEnabled = capitalize, imeAction = imeAction), actions = actions)
    }
}

/** An on/off row: title and a short note, the switch on the right. */
@Composable internal fun SwitchRow(title: String, detail: String? = null, isOn: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    CodyncSwitch(isOn, onChange, Modifier.fillMaxWidth(), enabled) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.body, color = Palette.text)
            if (detail != null) Text(detail, style = Type.caption.copy(lineHeight = Type.caption.lineHeight * 1.12f), color = Palette.secondary)
        }
    }
}

/** The bot's face: a row of shapes over a row of colors, each with a ring when chosen. */
@Composable internal fun AvatarPicker(shape: String, color: String, chooseShape: (String) -> Unit, chooseColor: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (option in AvatarPalette.shapes) Box(Modifier.clip(Capsule()).border(2.dp, if (option == shape) Palette.accent else Color.Transparent, Capsule())
                .clickable(role = Role.RadioButton) { chooseShape(option) }.semantics { contentDescription = "$option shape"; selected = option == shape }
                .padding(4.dp)) { CharacterAvatar(option, color, 36.dp) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((id, hex) in AvatarPalette.colors) Box(Modifier.clip(Capsule()).border(2.dp, if (id == color) Palette.accent else Color.Transparent, Capsule())
                .clickable(role = Role.RadioButton) { chooseColor(id) }.semantics { contentDescription = id.replaceFirstChar(Char::uppercase); selected = id == color }
                .padding(4.dp)) { Box(Modifier.size(26.dp).clip(Capsule()).background(Color(hex))) }
        }
    }
}

/** A card of on/off switches for the connectors or skills installed on the computer. */
@Composable internal fun PluginToggles(title: String, empty: String, items: List<Triple<String, String, String>>, selection: List<String>,
    change: (List<String>) -> Unit, info: ((String) -> Unit)? = null) {
    CardSection(title) {
        if (items.isEmpty()) row { Text(empty, style = Type.body, color = Palette.secondary) }
        for ((id, name, detail) in items) row {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) {
                    SwitchRow(name, detail.ifEmpty { null }, id in selection, { on -> change(selection.filter { it != id } + if (on) listOf(id) else emptyList()) })
                }
                if (info != null) IconButton("About $name", "info.circle") { info(id) }
            }
        }
    }
}
