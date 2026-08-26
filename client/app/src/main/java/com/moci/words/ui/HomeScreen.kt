package com.moci.words.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.CalCell
import com.moci.words.api.ChildInfo
import com.moci.words.api.DayWord
import com.moci.words.api.ParentHome
import com.moci.words.api.User
import com.moci.words.api.levelLabelOf
import kotlinx.coroutines.launch

/** 首页：按角色分学生 / 家长 / 管理员三种。 */
@Composable
fun HomeScreen(
    user: User,
    settingsKey: String = user.settingsKey,
    wordsKey: Long = 0L,
    onStartStudy: () -> Unit,
    onUserChanged: (User) -> Unit,
    onNavigate: (String) -> Unit = {},
    onGameImmersiveChange: (Boolean) -> Unit = {},
) {
    when {
        user.isAdmin -> AdminHomeScreen(wordsKey, onNavigate)
        user.isParent -> ParentHomeScreen(wordsKey, onStartStudy, onGameImmersiveChange)
        else -> LearnerHomeScreen(user, settingsKey, wordsKey, onStartStudy, onGameImmersiveChange)
    }
}

// ---------------------------------------------------------------------------
// 学生首页

@Composable
private fun LearnerHomeScreen(
    user: User,
    settingsKey: String,
    wordsKey: Long,
    onStartStudy: () -> Unit,
    onGameImmersiveChange: (Boolean) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MociApp
    val state = rememberData { homeLearner() }
    LaunchedEffect(settingsKey, wordsKey) { state.reload() }
    val data = state.data
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var dayWords by remember { mutableStateOf<List<DayWord>?>(null) }
    var wordFilter by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showRewards by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showRewards) {
        RewardGamesScreen(
            onBack = { showRewards = false },
            onImmersiveChange = onGameImmersiveChange,
        )
        return
    }

    val filter = wordFilter
    if (filter != null) {
        ProgressWordsScreen(
            status = filter.first,
            title = filter.second,
            wordsKey = wordsKey,
            onBack = { wordFilter = null },
        )
        return
    }

    when {
        state.loading && data == null -> LoadingBox()
        state.error != null && data == null -> ErrorBox(state.error!!, state.reload)
        data != null -> {
            val cal = data.calendar
            val shownWords = dayWords ?: data.dayWords
            val selectedCell = cal.cells.firstOrNull { it.date == (selectedDate ?: cal.today) }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                StatGrid(
                    listOf(
                        "${data.stats.total}" to "单词",
                        "${data.stats.newCount}" to "新词",
                        "${data.stats.learning}" to "了解",
                        "${data.stats.mastered}" to "掌握",
                    ),
                    onItemClick = { index ->
                        wordFilter = when (index) {
                            0 -> "" to "全部单词"
                            1 -> "new" to "新词"
                            2 -> "learning" to "了解"
                            else -> "mastered" to "掌握"
                        }
                    },
                )
                Text(
                    "统计范围：${data.user.wordLevels.joinToString("、") { levelLabelOf(it) }}（家长设置）",
                    fontSize = 12.sp,
                    color = InkSoft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )

                // 今日任务
                PanelCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        UserAvatar(user.avatar, user.username, size = 44.dp)
                        Column {
                            Text(
                                "${user.username} · 学生",
                                fontSize = 13.sp,
                                color = InkSoft,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    if (data.task.remaining > 0) "今日待完成" else "今日任务完成",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink,
                                )
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text("${data.task.remaining}", style = MociType.heroNumber)
                            }
                        }
                    }
                    Text(
                        "新词 ${data.task.new.done} / ${data.task.new.quota} · 复习 ${data.task.review.done} / ${data.task.review.quota}",
                        fontSize = 13.sp,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (data.task.remaining > 0) {
                        MociButton("开始学习", onClick = onStartStudy)
                    } else {
                        TaskRewardButton { showRewards = true }
                    }
                }

                // 月历
                PanelCard {
                    MonthCalendar(
                        cal = cal,
                        selectedDate = selectedDate ?: cal.today,
                        onSelect = { cell ->
                            if (cell.date.isNotEmpty()) {
                                selectedDate = cell.date
                                scope.launch {
                                    dayWords = try {
                                        app.api.studyDay(cell.date)
                                    } catch (e: Exception) {
                                        shownWords
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    selectedCell?.let { cell ->
                        Text(
                            calDetailText(cell, cal.newQuota, cal.reviewQuota),
                            fontSize = 13.sp,
                            color = Ink,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    DayWordList(shownWords)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun calDetailText(cell: CalCell, newQuota: Int, reviewQuota: Int): String {
    val prefix = if (cell.today) "今天" else "${cell.day}日"
    val state = when {
        cell.future -> "还没到"
        cell.complete -> "已完成"
        cell.studied -> "进行中"
        else -> "还没学"
    }
    return "$prefix：新词 ${cell.newN} / $newQuota · 复习 ${cell.reviewN} / $reviewQuota · $state"
}

// ---------------------------------------------------------------------------
// 家长首页

@Composable
private fun ParentHomeScreen(
    wordsKey: Long,
    onStartStudy: () -> Unit,
    onGameImmersiveChange: (Boolean) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var date by remember { mutableStateOf<String?>(null) }
    var detailId by remember { mutableStateOf<Int?>(null) }
    var kind by remember { mutableStateOf("new") }
    var data by remember { mutableStateOf<ParentHome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showRewards by remember { mutableStateOf(false) }

    fun load(d: String? = date, uid: Int? = detailId, k: String = kind) {
        loading = true
        error = null
        scope.launch {
            try {
                val res = app.api.homeParent(date = d, userId = uid, kind = k)
                data = res
                date = res.day
                detailId = res.detailId
                kind = res.kind
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "加载失败，请稍后重试。"
            }
            loading = false
        }
    }
    LaunchedEffect(wordsKey) { load() }

    if (showRewards) {
        RewardGamesScreen(
            onBack = { showRewards = false },
            onImmersiveChange = onGameImmersiveChange,
        )
        return
    }

    val d = data
    when {
        loading && d == null -> LoadingBox()
        error != null && d == null -> ErrorBox(error!!) { load() }
        d != null -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                ParentSelfCard(
                    user = d.user,
                    task = d.selfTask,
                    stats = d.selfStats,
                    onStartStudy = onStartStudy,
                    onReward = { showRewards = true },
                )

                if (d.children.isEmpty()) {
                    PanelCard {
                        Text(
                            "还没有绑定孩子。请联系管理员，把学生账号绑到你的家长账号下。",
                            fontSize = 14.sp,
                            color = InkSoft,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    return@Column
                }

                val selectedChild = d.children.firstOrNull { it.user.id == d.detailId }
                    ?: d.children.first()
                val selectedIndex = d.children.indexOfFirst { it.user.id == selectedChild.user.id }
                    .coerceAtLeast(0)
                val accent = childAccent(selectedIndex)

                PanelCard {
                    PanelTitle("孩子")
                    if (d.children.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            d.children.forEachIndexed { index, child ->
                                ChildChip(
                                    child = child,
                                    selected = child.user.id == selectedChild.user.id,
                                    accent = childAccent(index),
                                    onClick = {
                                        detailId = child.user.id
                                        load(d = date, uid = child.user.id, k = kind)
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    ChildHero(selectedChild, accent)
                }

                selectedChild.stats?.let { stats ->
                    StatGrid(
                        listOf(
                            "${stats.total}" to "单词",
                            "${stats.newCount}" to "新词",
                            "${stats.learning}" to "了解",
                            "${stats.mastered}" to "掌握",
                        ),
                    )
                }

                // kind 切换
                PanelCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("new" to "新词学习", "review" to "复习").forEach { (k, label) ->
                            val active = kind == k
                            MociButton(
                                label,
                                kind = if (active) BtnKind.Primary else BtnKind.Ghost,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (kind != k) {
                                    kind = k
                                    load(d = date, uid = detailId, k = k)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    MonthCalendar(
                        cal = d.calendar,
                        selectedDate = date,
                        onSelect = { cell ->
                            if (cell.date.isNotEmpty()) {
                                date = cell.date
                                load(d = cell.date, uid = detailId, k = kind)
                            }
                        },
                        onPrevMonth = { load(d = d.calendar.prevDate, uid = detailId, k = kind) },
                        onNextMonth = { load(d = d.calendar.nextDate, uid = detailId, k = kind) },
                    )

                    Spacer(Modifier.height(10.dp))
                    val label = d.kindLabel.ifEmpty { "学习" }
                    Text(
                        "${d.day} · $label ${d.logs.size} 词",
                        fontSize = 13.sp,
                        color = Ink,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    DayWordList(
                        d.logs,
                        emptyText = if (kind == "review") "这一天还没有复习记录。" else "这一天还没有新词学习记录。",
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private val CHILD_ACCENTS = listOf(NavHome, NavStudy, NavRank, NavMe, NavWords, NavUsers)

private fun childAccent(index: Int): Color = CHILD_ACCENTS[index % CHILD_ACCENTS.size]

@Composable
private fun ParentSelfCard(
    user: User,
    task: com.moci.words.api.TodayTask?,
    stats: com.moci.words.api.WordStats?,
    onStartStudy: () -> Unit,
    onReward: () -> Unit,
) {
    stats?.let {
        StatGrid(
            listOf(
                "${it.total}" to "单词",
                "${it.newCount}" to "新词",
                "${it.learning}" to "了解",
                "${it.mastered}" to "掌握",
            ),
        )
    }
    PanelCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UserAvatar(user.avatar, user.username, size = 44.dp)
            Column {
                Text("${user.username} · 家长", fontSize = 13.sp, color = InkSoft)
                Spacer(Modifier.height(6.dp))
                val remaining = task?.remaining ?: 0
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (remaining > 0) "今日待完成" else "今日任务完成",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    if (remaining > 0) {
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("$remaining", style = MociType.heroNumber)
                    }
                }
            }
        }
        if (task != null) {
            Text(
                "新词 ${task.new.done} / ${task.new.quota} · 复习 ${task.review.done} / ${task.review.quota}",
                fontSize = 13.sp,
                color = InkSoft,
            )
            Spacer(Modifier.height(14.dp))
            if (task.remaining > 0) {
                MociButton("开始学习", onClick = onStartStudy)
            } else {
                TaskRewardButton(onClick = onReward)
            }
        } else {
            Spacer(Modifier.height(14.dp))
            MociButton("开始学习", onClick = onStartStudy)
        }
    }
}

@Composable
private fun ChildChip(
    child: ChildInfo,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) accent else Paper)
            .border(1.dp, if (selected) accent else Line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        UserAvatar(child.user.avatar, child.user.username, size = 28.dp)
        Text(
            child.user.username,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Paper2 else Ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChildHero(child: ChildInfo, accent: Color) {
    val remaining = child.task.remaining
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(child.user.avatar, child.user.username, size = 52.dp)
        Column(Modifier.weight(1f)) {
            Text(
                child.user.username,
                fontSize = 13.sp,
                color = InkSoft,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (remaining > 0) "今日待完成" else "今日任务完成",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                if (remaining > 0) {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("$remaining", style = MociType.heroNumber.copy(fontSize = 28.sp, color = accent))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    ChildTaskLine("新词", child.task.new.done, child.task.new.quota, accent)
    Spacer(Modifier.height(8.dp))
    ChildTaskLine("复习", child.task.review.done, child.task.review.quota, NavRank)
}

@Composable
private fun ChildTaskLine(label: String, done: Int, quota: Int, color: Color) {
    val frac = if (quota <= 0) 0f else (done.toFloat() / quota).coerceIn(0f, 1f)
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 12.sp, color = InkSoft)
            Text("$done / $quota", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(color.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(frac)
                    .background(color),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 管理员首页

@Composable
private fun AdminHomeScreen(wordsKey: Long, onNavigate: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as MociApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberData { homeAdmin() }
    LaunchedEffect(wordsKey) { state.reload() }
    val data = state.data

    fun act(action: suspend () -> String) {
        scope.launch {
            try {
                context.toast(action())
            } catch (e: ApiException) {
                context.toast(e.message ?: "操作失败")
            } catch (e: Exception) {
                context.toast("操作失败，请重试。")
            }
            state.reload()
        }
    }

    when {
        state.loading && data == null -> LoadingBox()
        state.error != null && data == null -> ErrorBox(state.error!!, state.reload)
        data != null -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                PanelCard {
                    Text("${data.user.username} · 管理员", fontSize = 13.sp, color = InkSoft)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("词库", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("${data.total}", style = MociType.heroNumber)
                        Text("词", fontSize = 16.sp, color = Ink, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text(
                        "学生 ${data.userCount} 人 · 家长 ${data.parentCount} 人" +
                            if (data.pendingCount > 0) " · 待审核 ${data.pendingCount} 人" else "",
                        fontSize = 13.sp,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MociButton("录入单词", modifier = Modifier.weight(1f)) { onNavigate("words") }
                        MociButton("管理用户", kind = BtnKind.Ghost, modifier = Modifier.weight(1f)) {
                            onNavigate("users")
                        }
                    }
                }

                if (data.pending.isNotEmpty()) {
                    PanelCard {
                        PanelTitle("待审核注册") {
                            Text(
                                "全部用户",
                                fontSize = 13.sp,
                                color = Pine,
                                modifier = Modifier.clickable { onNavigate("users") },
                            )
                        }
                        data.pending.forEach { u ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(u.username, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                                    Text(
                                        "${if (u.role == "parent") "家长" else "学生"} · ${u.createdAt}",
                                        fontSize = 12.sp,
                                        color = InkSoft,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MociButton("同意") {
                                        act { app.api.adminSetStatus(u.id, "approved") }
                                    }
                                    MociButton("拒绝", kind = BtnKind.Danger) {
                                        act { app.api.adminSetStatus(u.id, "rejected") }
                                    }
                                }
                            }
                        }
                    }
                }

                PanelCard {
                    PanelTitle("最近录入") {
                        Text(
                            "全部词库",
                            fontSize = 13.sp,
                            color = Pine,
                            modifier = Modifier.clickable { onNavigate("words") },
                        )
                    }
                    if (data.recent.isEmpty()) {
                        Text("词库还是空的，先录入一些单词供大家学习。", fontSize = 13.sp, color = InkSoft)
                    } else {
                        data.recent.forEach { w ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        w.term,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Ink,
                                        fontFamily = SerifFamily,
                                    )
                                    if (w.phonetic.isNotEmpty()) {
                                        Text("  ${w.phonetic}", fontSize = 12.sp, color = InkSoft)
                                    }
                                }
                                Text(w.meaning, fontSize = 13.sp, color = InkSoft)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
