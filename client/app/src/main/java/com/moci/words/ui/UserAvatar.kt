package com.moci.words.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var photoBitmap by remember(avatar) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatar) {
        photoBitmap = if (isPhotoAvatar(avatar)) {
            withContext(Dispatchers.Default) { decodeAvatarImageBitmap(avatar) }
        } else {
            null
        }
    }

    val emoji = avatar.takeIf { it.isNotBlank() && !isPhotoAvatar(it) }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (emoji != null || photoBitmap != null) Paper2 else Pine.copy(alpha = 0.14f))
            .border(1.dp, Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photoBitmap != null -> {
                Image(
                    bitmap = photoBitmap!!,
                    contentDescription = "头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            emoji != null -> {
                Text(emoji, fontSize = (size.value * 0.48f).sp)
            }
            else -> {
                Text(
                    username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    fontSize = (size.value * 0.36f).sp,
                    fontWeight = FontWeight.Bold,
                    color = Pine,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(
    current: String,
    username: String,
    saving: Boolean,
    onConfirm: (String) -> Unit,
    onPickPhoto: () -> Unit,
) {
    val currentEmoji = current.takeIf { !isPhotoAvatar(it) && it.isNotBlank() }
    var emojiExpanded by remember { mutableStateOf(false) }
    var pending by remember(current) {
        mutableStateOf(currentEmoji ?: AVATAR_OPTIONS.first())
    }
    LaunchedEffect(current) {
        pending = currentEmoji ?: AVATAR_OPTIONS.first()
        emojiExpanded = false
    }
    val dirty = pending != current

    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            UserAvatar(current, username, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    "设置头像",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "可选 Emoji 或从相册上传照片",
                    fontSize = 13.sp,
                    color = InkSoft,
                )
            }
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Pine,
                    strokeWidth = 2.dp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarModeButton(
                label = "Emoji 头像",
                selected = emojiExpanded,
                enabled = !saving,
                modifier = Modifier.weight(1f),
                onClick = { emojiExpanded = !emojiExpanded },
            )
            AvatarModeButton(
                label = "照片头像",
                selected = false,
                enabled = !saving,
                modifier = Modifier.weight(1f),
                onClick = onPickPhoto,
            )
        }

        if (emojiExpanded) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AVATAR_OPTIONS.forEach { emoji ->
                    val selected = emoji == pending
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
                            .clickable(enabled = !saving) { pending = emoji },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MociButton(
                if (saving) "保存中…" else "确认选择",
                enabled = dirty && !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onConfirm(pending) },
            )
        }
    }
}

@Composable
private fun AvatarModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Pine.copy(alpha = 0.12f) else Paper)
            .border(
                1.dp,
                if (selected) Pine else Line,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Pine else Ink,
        )
    }
}
