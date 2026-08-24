package com.moci.words.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AVATAR_OPTIONS = listOf(
    "🦊", "🐼", "🦁", "🐸", "🦄", "🐱", "🐶", "🐰",
    "🐻", "🐯", "🐨", "🐷", "🐮", "🐵", "🦉", "🐧",
    "🐬", "🦋", "🌸", "⭐",
)

@Composable
fun UserAvatar(
    avatar: String,
    username: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val emoji = avatar.takeIf { it.isNotBlank() }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (emoji != null) Paper2 else Pine.copy(alpha = 0.14f))
            .border(1.dp, Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji != null) {
            Text(emoji, fontSize = (size.value * 0.48f).sp)
        } else {
            Text(
                username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold,
                color = Pine,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(
    current: String,
    username: String,
    saving: Boolean,
    onPick: (String) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            UserAvatar(current, username, size = 64.dp)
            Text(
                "选一个代表你的头像",
                fontSize = 14.sp,
                color = InkSoft,
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AVATAR_OPTIONS.forEach { emoji ->
                val selected = emoji == current
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (selected) Pine.copy(alpha = 0.16f) else Paper)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Pine else Line,
                            shape = CircleShape,
                        )
                        .clickable(enabled = !saving) { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 22.sp)
                }
            }
        }
    }
}
