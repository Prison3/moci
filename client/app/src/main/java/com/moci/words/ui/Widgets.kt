package com.moci.words.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiClient
import com.moci.words.api.ApiException
import com.moci.words.api.CalCell
import com.moci.words.api.DayWord
import com.moci.words.api.MonthCal
import kotlinx.coroutines.launch

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------------------
// 异步数据加载

class DataState<T> {
    var data by mutableStateOf<T?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var reload: () -> Unit = {}
}

@Composable
fun <T> rememberData(loader: suspend ApiClient.() -> T): DataState<T> {
    val app = LocalContext.current.applicationContext as MociApp
    val state = remember { DataState<T>() }
    val scope = rememberCoroutineScope()
    val load: (force: Boolean) -> Unit = { force ->
        state.loading = true
        state.error = null
        scope.launch {
            state.data = try {
                if (force) app.api.invalidateLocalCache()
                app.api.loader()
            } catch (e: ApiException) {
                state.error = e.message; null
            } catch (e: Exception) {
                state.error = "加载失败，请稍后重试。"; null
            }
            state.loading = false
        }
    }
    LaunchedEffect(Unit) { load(false) }
    state.reload = { load(true) }
    return state
}

// ---------------------------------------------------------------------------
// 页面骨架

@Composable
fun MociTopBar(subtitle: String, username: String, onUserClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Moci", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pine, fontFamily = SerifFamily)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 12.sp, color = InkSoft)
            }
        }
        Text(
            username,
            fontSize = 14.sp,
            color = Pine,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Paper2)
                .border(1.dp, Line, RoundedCornerShape(999.dp))
                .clickable(onClick = onUserClick)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Paper2)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun PanelTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        action?.invoke()
    }
    Spacer(Modifier.height(10.dp))
}

// ---------------------------------------------------------------------------
// 按钮 / 徽章 / 输入

enum class BtnKind { Primary, Ghost, Danger }

@Composable
fun MociButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when (kind) {
        BtnKind.Primary -> Pine
        BtnKind.Danger -> Cinnabar
        BtnKind.Ghost -> Color.Transparent
    }
    val fg = when (kind) {
        BtnKind.Ghost -> Pine
        else -> Paper2
    }
    val borderColor = when (kind) {
        BtnKind.Ghost -> Line
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MociBadge(text: String, color: Color) {
    Text(
        text,
        fontSize = 11.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun MociTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val hidePassword = isPassword && !passwordVisible
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (hidePassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = if (isPassword) {
            keyboardOptions.copy(keyboardType = KeyboardType.Password)
        } else {
            keyboardOptions
        },
        trailingIcon = when {
            isPassword -> {
                {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        tint = Pine,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { passwordVisible = !passwordVisible },
                    )
                }
            }
            trailingContent != null -> trailingContent
            else -> null
        },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Pine,
            unfocusedBorderColor = Line,
            focusedLabelColor = Pine,
            unfocusedLabelColor = InkSoft,
            cursorColor = Pine,
            focusedContainerColor = Paper2,
            unfocusedContainerColor = Paper2,
            focusedTrailingIconColor = Pine,
            unfocusedTrailingIconColor = Pine,
        ),
    )
}

// ---------------------------------------------------------------------------
// 朗读按钮

@Composable
fun SpeakIconButton(text: String, modifier: Modifier = Modifier, size: Int = 22) {
    val app = LocalContext.current.applicationContext as MociApp
    val speaking by app.tts.speakingText.collectAsState()
    val isSpeaking = speaking == text
    IconButton(
        onClick = { app.tts.speak(text) },
        modifier = modifier.size((size + 14).dp),
    ) {
        Icon(
            MociIcons.Speaker,
            contentDescription = "朗读",
            tint = if (isSpeaking) Pine2 else Pine,
            modifier = Modifier.size(size.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 统计 / 空态 / 加载

@Composable
fun StatGrid(
    items: List<Pair<String, String>>,
    onItemClick: ((Int) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEachIndexed { index, (value, label) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Paper2)
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .then(
                        if (onItemClick != null) Modifier.clickable { onItemClick(index) }
                        else Modifier,
                    )
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pine, fontFamily = SerifFamily)
                Text(label, fontSize = 12.sp, color = InkSoft)
            }
        }
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Pine)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = Ink, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        MociButton("重试", onClick = onRetry)
    }
}

@Composable
fun EmptyBox(title: String, message: String, action: @Composable (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(message, color = InkSoft, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** 今日任务完成后领取奖励：打开内置小游戏大厅。 */
@Composable
fun TaskRewardButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    MociButton(
        "领取任务奖励",
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    )
}

// ---------------------------------------------------------------------------
// 月历

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun MonthCalendar(
    cal: MonthCal,
    selectedDate: String?,
    onSelect: (CalCell) -> Unit,
    modifier: Modifier = Modifier,
    titleSuffix: String = "学情",
    onPrevMonth: (() -> Unit)? = null,
    onNextMonth: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPrevMonth != null && cal.prevDate.isNotEmpty()) {
                    IconButton(onClick = onPrevMonth, modifier = Modifier.size(28.dp)) {
                        Icon(MociIcons.ChevronLeft, contentDescription = "上个月", tint = Pine)
                    }
                }
                Text(
                    "${cal.title}$titleSuffix",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                if (onNextMonth != null && cal.nextDate.isNotEmpty()) {
                    IconButton(onClick = onNextMonth, modifier = Modifier.size(28.dp)) {
                        Icon(MociIcons.ChevronRight, contentDescription = "下个月", tint = Pine)
                    }
                }
            }
            Text("打卡 ${cal.studiedDays} 天", fontSize = 12.sp, color = InkSoft)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach { d ->
                Text(
                    d,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    color = InkSoft,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        cal.cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CalCellView(
                            cell = cell,
                            selected = cell.date == selectedDate,
                            newQuota = cal.newQuota,
                            reviewQuota = cal.reviewQuota,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
        Text(
            "左上新词 · 左下复习 · 绿点已打卡 · 红点未打卡",
            fontSize = 11.sp,
            color = InkSoft,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CalCellView(
    cell: CalCell,
    selected: Boolean,
    newQuota: Int,
    reviewQuota: Int,
    onSelect: (CalCell) -> Unit,
) {
    if (cell.blank) {
        Box(Modifier.size(44.dp))
        return
    }
    val bg = when {
        selected -> Pine
        cell.complete -> Pine.copy(alpha = 0.14f)
        cell.studied -> Pine2.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val fg = if (selected) Paper2 else if (cell.future) InkSoft.copy(alpha = 0.45f) else Ink
    Column(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = !cell.future) { onSelect(cell) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${cell.day}", fontSize = 13.sp, color = fg, fontWeight = if (cell.today) FontWeight.Bold else FontWeight.Normal)
        if (!cell.future && (cell.newN > 0 || cell.studied || cell.complete)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (cell.newN > 0) {
                    Text("${cell.newN}", fontSize = 8.sp, color = if (selected) Paper2 else Pine2)
                }
                Text(
                    "${cell.reviewN}",
                    fontSize = 8.sp,
                    color = if (selected) Paper2 else if (cell.reviewN > 0) Warn else InkSoft.copy(alpha = 0.5f),
                )
            }
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            selected -> Paper2
                            cell.studied || cell.complete -> Pine
                            else -> Cinnabar
                        }
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 某天学习单词列表

@Composable
fun DayWordList(words: List<DayWord>, emptyText: String = "这一天还没有学习记录。") {
    if (words.isEmpty()) {
        Text(emptyText, color = InkSoft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEach { w -> DayWordItem(w) }
    }
}

@Composable
fun DayWordItem(w: DayWord) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Paper)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.term, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, fontFamily = SerifFamily)
                    Spacer(Modifier.width(4.dp))
                    SpeakIconButton(w.term, size = 18)
                    w.username?.let {
                        Spacer(Modifier.width(6.dp))
                        MociBadge(it, InkSoft)
                    }
                }
                if (w.meaning.isNotEmpty()) {
                    Text(w.meaning, fontSize = 13.sp, color = InkSoft)
                }
            }
        }
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MociBadge(
                if (w.kind == "review") "复习" else "学新词",
                if (w.kind == "review") Pine2 else Pine,
            )
            MociBadge(
                if (w.rating == "easy") "学会" else "不认识",
                if (w.rating == "easy") Pine else Cinnabar,
            )
            MociBadge(
                when (w.status) {
                    "learning" -> "现为了解"
                    "mastered" -> "现为掌握"
                    else -> "现为新词"
                },
                when (w.status) {
                    "learning" -> Pine2
                    "mastered" -> Pine
                    else -> Warn
                },
            )
        }
        if (w.phrase.isNotEmpty()) {
            ExtraLine("短语", w.phrase)
        }
        if (w.example.isNotEmpty()) {
            ExtraLine("例句", w.example)
        }
    }
}

@Composable
fun ExtraLine(label: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Pine, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, color = InkSoft, modifier = Modifier.weight(1f))
        SpeakIconButton(text, size = 16)
    }
}
