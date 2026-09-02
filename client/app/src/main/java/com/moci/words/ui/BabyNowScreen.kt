package com.moci.words.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.schedule.Grade1Class2Schedule
import com.moci.words.schedule.ScheduleMoment
import com.moci.words.schedule.ScheduleSlot
import com.moci.words.schedule.SlotKind
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** 根据课表查看宝贝此刻在干什么，也可按日期查看当天安排。 */
@Composable
fun BabyNowScreen() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val today = now.toLocalDate()
    var selectedDate by remember { mutableStateOf(now.toLocalDate()) }
    val isToday = selectedDate == today

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(30_000)
        }
    }

    val moment = remember(now, isToday) {
        if (isToday) Grade1Class2Schedule.momentAt(now) else null
    }
    val dayLabel = remember(selectedDate) { Grade1Class2Schedule.dayLabelFor(selectedDate) }
    val daySlots = remember(selectedDate) { Grade1Class2Schedule.slotsFor(selectedDate) }
    val isSchoolDay = remember(selectedDate) { Grade1Class2Schedule.isSchoolDay(selectedDate) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        DatePickerBar(
            selectedDate = selectedDate,
            today = today,
            dayLabel = dayLabel,
            onPrev = { selectedDate = selectedDate.minusDays(1) },
            onNext = { selectedDate = selectedDate.plusDays(1) },
            onToday = { selectedDate = today },
        )
        WeekDayChips(
            selectedDate = selectedDate,
            today = today,
            onSelect = { selectedDate = it },
        )
        Spacer(Modifier.height(4.dp))

        if (isToday && moment != null) {
            NowHeroCard(moment, selectedDate, dayLabel)
            Spacer(Modifier.height(8.dp))
            if (moment.next != null && moment.isSchoolDay) {
                PanelCard {
                    Text("接下来", fontSize = 13.sp, color = InkSoft)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        moment.next.subject,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        "${moment.next.periodLabel} · ${moment.next.start.format(TIME_FMT)}–${moment.next.end.format(TIME_FMT)}",
                        fontSize = 13.sp,
                        color = InkSoft,
                    )
                }
            }
        } else {
            DayPlanHeroCard(
                date = selectedDate,
                dayLabel = dayLabel,
                isSchoolDay = isSchoolDay,
                classCount = Grade1Class2Schedule.classCount(selectedDate),
            )
            Spacer(Modifier.height(8.dp))
        }

        if (daySlots.isNotEmpty()) {
            PanelCard {
                PanelTitle(if (isToday) "今日课表 · $dayLabel" else "这天安排 · $dayLabel")
                daySlots.forEach { slot ->
                    TimelineRow(
                        slot = slot,
                        active = isToday && moment?.slot == slot,
                        past = isToday && now.toLocalTime() >= slot.end,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else if (!isSchoolDay) {
            PanelCard {
                Text(
                    "这天没有校内课程安排",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = InkSoft,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "周末好好休息，下周见。",
                    fontSize = 13.sp,
                    color = InkSoft,
                )
            }
        }

        PanelCard {
            Text(Grade1Class2Schedule.SCHOOL_NAME, fontSize = 13.sp, color = InkSoft)
            Text(Grade1Class2Schedule.CLASS_NAME, fontSize = 13.sp, color = InkSoft)
            Spacer(Modifier.height(6.dp))
            Text("2026 学年第一学期周课表", fontSize = 12.sp, color = InkSoft)
        }
    }
}

@Composable
private fun DatePickerBar(
    selectedDate: LocalDate,
    today: LocalDate,
    dayLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    PanelCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DateNavButton(contentDescription = "前一天", onClick = onPrev) {
                Icon(MociIcons.ChevronLeft, contentDescription = null, tint = Pine, modifier = Modifier.size(22.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    selectedDate.format(DATE_FMT),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    fontFamily = SerifFamily,
                )
                Text(dayLabel, fontSize = 13.sp, color = InkSoft)
            }
            DateNavButton(contentDescription = "后一天", onClick = onNext) {
                Icon(MociIcons.ChevronRight, contentDescription = null, tint = Pine, modifier = Modifier.size(22.dp))
            }
        }
        if (selectedDate != today) {
            Spacer(Modifier.height(10.dp))
            MociButton("回到今天", kind = BtnKind.Ghost, modifier = Modifier.fillMaxWidth(), onClick = onToday)
        }
    }
}

@Composable
private fun DateNavButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Paper)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun WeekDayChips(
    selectedDate: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val weekFields = WeekFields.of(Locale.CHINA)
    val weekStart = selectedDate.with(weekFields.dayOfWeek(), 1L)
    val days = (0L..6L).map { weekStart.plusDays(it) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.forEach { date ->
            val selected = date == selectedDate
            val isToday = date == today
            val label = when (date.dayOfWeek.value) {
                1 -> "一"
                2 -> "二"
                3 -> "三"
                4 -> "四"
                5 -> "五"
                6 -> "六"
                else -> "日"
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            selected -> Pine.copy(alpha = 0.16f)
                            isToday -> NavNow.copy(alpha = 0.12f)
                            else -> Paper2
                        },
                    )
                    .border(
                        1.dp,
                        when {
                            selected -> Pine
                            isToday -> NavNow.copy(alpha = 0.5f)
                            else -> Line
                        },
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Pine else Ink,
                )
                Text(
                    date.dayOfMonth.toString(),
                    fontSize = 11.sp,
                    color = InkSoft,
                )
            }
        }
    }
}

@Composable
private fun NowHeroCard(moment: ScheduleMoment, date: LocalDate, dayLabel: String) {
    val headline = Grade1Class2Schedule.headline(moment)
    val subtitle = Grade1Class2Schedule.subtitle(moment)
    val accent = slotAccent(moment.slot?.kind)

    PanelCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("此刻正在干啥", fontSize = 14.sp, color = InkSoft)
                Spacer(Modifier.height(4.dp))
                Text("${date.format(DATE_FMT)} · $dayLabel", fontSize = 13.sp, color = InkSoft)
            }
            Text(
                moment.nowText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Pine,
                fontFamily = SerifFamily,
            )
        }
        Spacer(Modifier.height(16.dp))
        HeroContentBox(headline, subtitle, accent, moment.slot?.let { moment.progress })
    }
}

@Composable
private fun DayPlanHeroCard(
    date: LocalDate,
    dayLabel: String,
    isSchoolDay: Boolean,
    classCount: Int,
) {
    val headline = if (isSchoolDay) "查看这天安排" else "周末休息中"
    val subtitle = when {
        !isSchoolDay -> "没有校内课程，好好放松"
        classCount > 0 -> "共 $classCount 节正课 · 8:20 上学 · 18:00 课后服务结束"
        else -> "暂无课程"
    }
    PanelCard {
        Text("按日期查看", fontSize = 14.sp, color = InkSoft)
        Spacer(Modifier.height(4.dp))
        Text("${date.format(DATE_FMT)} · $dayLabel", fontSize = 13.sp, color = InkSoft)
        Spacer(Modifier.height(16.dp))
        HeroContentBox(headline, subtitle, if (isSchoolDay) Pine else InkSoft, progress = null)
    }
}

@Composable
private fun HeroContentBox(
    headline: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    progress: Float?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Column {
            Text(
                headline,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                fontFamily = SerifFamily,
            )
            Spacer(Modifier.height(8.dp))
            Text(subtitle, fontSize = 14.sp, color = InkSoft)
            if (progress != null) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = accent,
                    trackColor = Line,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "本节进度 ${(progress * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = InkSoft,
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    slot: ScheduleSlot,
    active: Boolean,
    past: Boolean,
) {
    val accent = slotAccent(slot.kind)
    val bg = when {
        active -> accent.copy(alpha = 0.14f)
        past -> Paper
        else -> Paper2
    }
    val border = when {
        active -> accent
        else -> Line
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(0.32f)) {
            Text(
                "${slot.start.format(TIME_FMT)}–${slot.end.format(TIME_FMT)}",
                fontSize = 11.sp,
                color = if (active) accent else InkSoft,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
            Text(slot.periodLabel, fontSize = 11.sp, color = InkSoft)
        }
        Text(
            slot.subject,
            Modifier.weight(0.68f),
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (past && !active) InkSoft else Ink,
        )
    }
}

private fun slotAccent(kind: SlotKind?): androidx.compose.ui.graphics.Color = when (kind) {
    SlotKind.Class -> Pine
    SlotKind.Activity -> NavRank
    SlotKind.Service -> NavStudy
    SlotKind.Meal -> Warn
    SlotKind.Rest -> InkSoft
    SlotKind.Break -> Line
    null -> Pine
}
