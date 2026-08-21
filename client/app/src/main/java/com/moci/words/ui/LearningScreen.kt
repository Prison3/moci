package com.moci.words.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.MociApp
import com.moci.words.api.ApiException
import com.moci.words.api.LearningData
import kotlinx.coroutines.launch

/** 管理员学情：月历 + 当日汇总 + 按学生明细。 */
@Composable
fun LearningScreen(
    initialUserId: Int? = null,
    onConsumedInitial: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MociApp
    val scope = rememberCoroutineScope()

    var date by remember { mutableStateOf<String?>(null) }
    var detailId by remember { mutableIntStateOf(initialUserId ?: 0) }
    var data by remember { mutableStateOf<LearningData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun load(d: String? = date, uid: Int = detailId) {
        loading = true
        error = null
        scope.launch {
            try {
                val res = app.api.adminLearning(date = d, userId = if (uid > 0) uid else null)
                data = res
                date = res.day
                detailId = res.detailUserId ?: 0
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "加载失败，请稍后重试。"
            }
            loading = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        load()
        onConsumedInitial()
    }

    val d = data
    when {
        loading && d == null -> LoadingBox()
        error != null && d == null -> ErrorBox(error!!) { load() }
        d != null -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PanelCard {
                MonthCalendar(
                    cal = d.calendar,
                    selectedDate = date,
                    onSelect = { cell ->
                        if (cell.date.isNotEmpty()) {
                            date = cell.date
                            load(d = cell.date)
                        }
                    },
                    onPrevMonth = { load(d = d.calendar.prevDate) },
                    onNextMonth = { load(d = d.calendar.nextDate) },
                )
            }

            PanelCard {
                PanelTitle("${d.day} 学习汇总")
                val s = d.summary
                if (s.reviews == 0) {
                    Text("这一天还没有学生的学习记录。", fontSize = 13.sp, color = InkSoft)
                } else {
                    StatGrid(
                        listOf(
                            "${s.learners}" to "学习人数",
                            "${s.reviews}" to "学习次数",
                            "${s.newN}" to "新词",
                            "${s.reviewN}" to "复习",
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "学会 ${s.easyN} · 不认识 ${s.againN}",
                        fontSize = 13.sp,
                        color = InkSoft,
                    )
                }
            }

            if (d.byUser.isNotEmpty()) {
                PanelCard {
                    PanelTitle("按学生")
                    d.byUser.forEach { bu ->
                        val selected = d.detailUserId == bu.id
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    detailId = if (selected) 0 else bu.id
                                    load(uid = detailId)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    bu.username,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Pine else Ink,
                                )
                                Text(
                                    "${bu.words} 词 · ${bu.reviews} 次",
                                    fontSize = 13.sp,
                                    color = InkSoft,
                                )
                            }
                            Text(
                                "新词 ${bu.newN} · 复习 ${bu.reviewN} · 学会 ${bu.easyN} · 不认识 ${bu.againN}" +
                                    if (bu.lastAt.isNotEmpty()) " · 最后 ${bu.lastAt.take(16)}" else "",
                                fontSize = 12.sp,
                                color = InkSoft,
                            )
                        }
                    }
                }
            }

            if (d.detailUserId != null) {
                PanelCard {
                    PanelTitle("${d.detailUsername} 当天学习的单词")
                    DayWordList(d.logs, emptyText = "这一天还没有学习记录。")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
