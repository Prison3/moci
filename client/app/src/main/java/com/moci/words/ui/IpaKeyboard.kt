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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 与服务端 normalize_phonetic 对齐：去斜杠/空白/音节点，ASCII g → ɡ。 */
fun normalizePhonetic(text: String): String =
    text.trim()
        .trim('/')
        .replace('g', 'ɡ')
        .replace(Regex("[\\s./]"), "")

/** 小学英式音标里按最长优先切分的音素。 */
private val IPA_UNITS = listOf(
    "tʃ", "dʒ",
    "iː", "uː", "ɑː", "ɔː", "ɜː",
    "eɪ", "aɪ", "ɔɪ", "əʊ", "oʊ", "aʊ",
    "ɪə", "eə", "ʊə",
).sortedByDescending { it.length }

fun tokenizePhonetic(raw: String): List<String> {
    val text = raw.trim().trim('/')
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val unit = IPA_UNITS.firstOrNull { text.startsWith(it, i) }
        if (unit != null) {
            out += unit
            i += unit.length
        } else {
            val ch = text[i]
            if (!ch.isWhitespace() && ch != '.' && ch != '/') {
                out += ch.toString()
            }
            i += 1
        }
    }
    return out
}

private enum class IpaPage { Vowel, Consonant, Mark }

private val IPA_VOWEL_ROWS = listOf(
    listOf("iː", "ɪ", "e", "æ", "ɑː", "ɒ", "ɔː", "ʊ", "uː"),
    listOf("ʌ", "ɜː", "ə", "i", "u", "ɔ"),
    listOf("eɪ", "aɪ", "ɔɪ", "əʊ", "aʊ", "ɪə", "eə", "ʊə"),
)

private val IPA_CONSONANT_ROWS = listOf(
    listOf("p", "b", "t", "d", "k", "ɡ"),
    listOf("f", "v", "θ", "ð", "s", "z"),
    listOf("ʃ", "ʒ", "h", "m", "n", "ŋ"),
    listOf("l", "r", "w", "j", "tʃ", "dʒ"),
)

private val IPA_MARK_ROWS = listOf(
    listOf("ˈ", "ˌ"),
)

/**
 * 音标方格 + 应用内音标键盘。系统输入法会被收起。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneticBoxesInput(
    expected: String,
    value: String,
    isWrong: Boolean,
    onValueChange: (String) -> Unit,
) {
    val slots = remember(expected) { tokenizePhonetic(expected) }
    val typed = remember(value) { tokenizePhonetic(value) }
    val systemKeyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(expected) {
        systemKeyboard?.hide()
    }

    fun appendUnit(unit: String) {
        if (typed.size >= slots.size) return
        onValueChange((typed + unit).joinToString(""))
    }

    fun deleteUnit() {
        if (typed.isEmpty()) return
        onValueChange(typed.dropLast(1).joinToString(""))
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "共 ${slots.size} 个音标方块",
            fontSize = 13.sp,
            color = InkSoft,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Paper2)
                .border(
                    1.dp,
                    if (isWrong) Cinnabar else Line,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slots.forEachIndexed { idx, _ ->
                    PhoneticSymbolCell(
                        text = typed.getOrNull(idx),
                        active = typed.size == idx,
                        isWrong = isWrong,
                    )
                }
            }
        }
        if (typed.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "/${typed.joinToString("")}/",
                fontSize = 14.sp,
                fontFamily = FontFamily.Serif,
                color = InkSoft,
            )
        }
        Spacer(Modifier.height(10.dp))
        IpaKeyboard(
            onUnit = ::appendUnit,
            onDelete = ::deleteUnit,
            canType = typed.size < slots.size,
            canDelete = typed.isNotEmpty(),
        )
    }
}

@Composable
private fun IpaKeyboard(
    onUnit: (String) -> Unit,
    onDelete: () -> Unit,
    canType: Boolean,
    canDelete: Boolean,
) {
    var page by remember { mutableStateOf(IpaPage.Vowel) }
    val rows = when (page) {
        IpaPage.Vowel -> IPA_VOWEL_ROWS
        IpaPage.Consonant -> IPA_CONSONANT_ROWS
        IpaPage.Mark -> IPA_MARK_ROWS
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IpaPage.entries.forEach { item ->
                val on = page == item
                Text(
                    when (item) {
                        IpaPage.Vowel -> "元音"
                        IpaPage.Consonant -> "辅音"
                        IpaPage.Mark -> "符号"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) Paper2 else Ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Pine else Paper2)
                        .border(1.dp, if (on) Pine else Line, RoundedCornerShape(8.dp))
                        .clickable { page = item }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IpaKey(
                label = null,
                enabled = canDelete,
                modifier = Modifier.widthIn(min = 56.dp),
                onClick = onDelete,
            ) {
                Icon(
                    Icons.Filled.Backspace,
                    contentDescription = "删除",
                    tint = if (canDelete) Ink else InkSoft.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { unit ->
                    IpaKey(
                        label = unit,
                        enabled = canType,
                        modifier = Modifier.weight(1f),
                        onClick = { onUnit(unit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.IpaKey(
    label: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Paper2 else Paper)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Text(
                text = label.orEmpty(),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (enabled) Ink else InkSoft.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun PhoneticSymbolCell(
    text: String?,
    active: Boolean,
    isWrong: Boolean,
) {
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isWrong -> Cinnabar.copy(alpha = 0.08f)
                    text != null -> Pine.copy(alpha = 0.10f)
                    else -> Paper
                },
            )
            .border(
                width = if (active) 2.dp else 1.dp,
                color = when {
                    isWrong -> Cinnabar
                    active -> Pine
                    text != null -> Pine2
                    else -> Line
                },
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.orEmpty(),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Ink,
            textAlign = TextAlign.Center,
        )
    }
}
