package com.moci.words.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.GameRankData
import com.moci.words.api.MasteryRankData
import com.moci.words.api.RANK_GAMES
import com.moci.words.api.User
import kotlinx.coroutines.launch

private enum class RankSection { Mastery, Games }

@Composable
fun RankScreen(user: User) {
    val app = LocalContext.current.applicationContext as MociApp
    val scope = rememberCoroutineScope()

    var section by remember { mutableStateOf(RankSection.Mastery) }
    var game by remember { mutableStateOf("stars") }
    var mastery by remember { mutableStateOf<MasteryRankData?>(null) }
    var games by remember { mutableStateOf<GameRankData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun loadMastery() {
        loading = true
        error = null
        scope.launch {
            try {
                mastery = app.api.rankMastery(force = true)
            } catch (e: ApiException) {
                error = e.message
            } catch (_: Exception) {
                error = "加载失败，请稍后重试。"
            }
            loading = false
        }
    }

    fun loadGames() {
        loading = true
        error = null
        scope.launch {
            try {
                games = app.api.rankGames(game, force = true)
            } catch (e: ApiException) {
                error = e.message
            } catch (_: Exception) {
                error = "加载失败，请稍后重试。"
            }
            loading = false
        }
    }

    fun reload() {
        when (section) {
            RankSection.Mastery -> loadMastery()
            RankSection.Games -> loadGames()
        }
    }

    androidx.compose.runtime.LaunchedEffect(section) {
        when (section) {
            RankSection.Mastery -> if (mastery == null) loadMastery()
            RankSection.Games -> if (games == null || games?.game != game) loadGames()
        }
    }

    androidx.compose.runtime.LaunchedEffect(game) {
        if (section == RankSection.Games) loadGames()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        PanelCard {
            PanelTitle("排行榜")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionChip(
                    label = "掌握度",
                    active = section == RankSection.Mastery,
                    onClick = { section = RankSection.Mastery },
                    modifier = Modifier.weight(1f),
                )
                SectionChip(
                    label = "游戏成绩",
                    active = section == RankSection.Games,
                    onClick = { section = RankSection.Games },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        when {
            loading && mastery == null && games == null -> LoadingBox()
            error != null && mastery == null && games == null -> ErrorBox(error!!) { reload() }
            section == RankSection.Mastery -> {
                val data = mastery
                if (data == null && loading) {
                    LoadingBox()
                } else if (data != null) {
                    MasteryRankContent(data, user, onRefresh = { loadMastery() })
                }
            }
            else -> {
                val data = games
                PanelCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RANK_GAMES.forEach { (id, label) ->
                            SectionChip(
                                label = label,
                                active = game == id,
                                onClick = { game = id },
                            )
                        }
                    }
                }
                if (data == null && loading) {
                    LoadingBox()
                } else if (data != null) {
                    GameRankContent(data, user, onRefresh = { loadGames() })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Pine else Paper2)
            .border(1.dp, if (active) Pine else Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        fontSize = 14.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) Paper2 else Ink,
    )
}

@Composable
private fun MasteryRankContent(data: MasteryRankData, user: User, onRefresh: () -> Unit) {
    data.me?.let { me ->
        PanelCard {
            PanelTitle(if (user.isLearner) "我的排名" else "参考")
            StatGrid(
                listOf(
                    "#${me.rank}" to "排名",
                    "${me.mastered}" to "掌握",
                    "${me.learning}" to "了解",
                    "${me.pct.toInt()}%" to "掌握率",
                ),
            )
            if (data.totalWords > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "词库共 ${data.totalWords} 词，按掌握数量排序",
                    fontSize = 12.sp,
                    color = InkSoft,
                )
            }
        }
    }

    PanelCard {
        PanelTitle("掌握度排行")
        if (data.items.isEmpty()) {
            EmptyBox("暂无排行", "还没有学生数据。")
        } else {
            data.items.forEach { item ->
                RankRow(
                    rank = item.rank,
                    username = item.username,
                    highlight = user.isLearner && item.userId == user.id,
                    detail = "掌握 ${item.mastered} · 了解 ${item.learning} · ${item.pct.toInt()}%",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MociButton("刷新", kind = BtnKind.Ghost, onClick = onRefresh)
    }
}

@Composable
private fun GameRankContent(data: GameRankData, user: User, onRefresh: () -> Unit) {
    val hint = if (data.lowerBetter) "数值越小越好" else "数值越大越好"

    data.me?.let { me ->
        PanelCard {
            PanelTitle(if (user.isLearner) "我的最好成绩" else "参考")
            StatGrid(
                listOf(
                    "#${me.rank}" to "排名",
                    me.scoreLabel to data.gameLabel,
                ),
            )
        }
    }

    PanelCard {
        PanelTitle("${data.gameLabel} · $hint")
        if (data.items.isEmpty()) {
            EmptyBox("暂无成绩", "还没有人玩过，去奖励游戏里冲榜吧。")
        } else {
            data.items.forEach { item ->
                RankRow(
                    rank = item.rank,
                    username = item.username,
                    highlight = user.isLearner && item.userId == user.id,
                    detail = item.scoreLabel,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MociButton("刷新", kind = BtnKind.Ghost, onClick = onRefresh)
    }
}

@Composable
private fun RankRow(
    rank: Int,
    username: String,
    highlight: Boolean,
    detail: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (rank) {
                1 -> "🥇"
                2 -> "🥈"
                3 -> "🥉"
                else -> "#$rank"
            },
            modifier = Modifier.padding(end = 10.dp),
            fontSize = if (rank <= 3) 18.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) Pine else InkSoft,
        )
        Column(Modifier.weight(1f)) {
            Text(
                username,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlight) Pine else Ink,
            )
            Text(detail, fontSize = 12.sp, color = InkSoft)
        }
    }
}
