package com.codync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codync.android.core.Bot
import com.codync.android.design.*

@Composable internal fun BotAvatar(bot: Bot, bots: List<Bot>, size: Dp = 44.dp) {
    Box(Modifier.size(size).clearAndSetSemantics { }) {
        if (bot.kind == "group") GroupAvatar(bot.members.mapNotNull { id -> bots.firstOrNull { it.id == id } }
            .map { AvatarFace(it.avatarShape, it.avatarColor, it.status) }, size)
        else CharacterAvatar(bot.avatarShape, bot.avatarColor, size, bot.status)
        if (bot.status == "needsInput") Box(Modifier.size(size * .36f).align(Alignment.BottomEnd)
            .background(Color(0xFFF0A030), CircleShape), contentAlignment = Alignment.Center) {
            Text("!", color = Color.White, style = MaterialTheme.typography.labelSmall)
        } else if (bot.unread > 0) Box(Modifier.size(size * .28f).align(Alignment.BottomEnd)
            .background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}
