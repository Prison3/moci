package com.moci.words.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class SlotKind {
    Class,
    Break,
    Meal,
    Rest,
    Activity,
    Service,
}

data class ScheduleSlot(
    val periodLabel: String,
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val kind: SlotKind,
)

data class ScheduleMoment(
    val slot: ScheduleSlot?,
    val next: ScheduleSlot?,
    val dayLabel: String,
    val isSchoolDay: Boolean,
    val isBeforeSchool: Boolean,
    val isAfterSchool: Boolean,
    val progress: Float,
    val nowText: String,
)

private data class BlockTemplate(
    val periodLabel: String,
    val start: LocalTime,
    val end: LocalTime,
    val kind: SlotKind,
    val subjects: List<String>,
)

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** 2026 学年第一学期 · 一年级 2 班周课表（华盛怀少学校） */
object Grade1Class2Schedule {
    const val SCHOOL_NAME = "上海嘉定区民办华盛怀少学校"
    const val CLASS_NAME = "一年级 2 班"

    private val blocks = listOf(
        BlockTemplate("第一节", t(8, 20), t(8, 55), SlotKind.Class, listOf("外语", "语文", "数学", "数学", "语文")),
        BlockTemplate("大课间", t(8, 55), t(9, 25), SlotKind.Activity, listOf("大课间活动", "大课间活动", "大课间活动", "大课间活动", "大课间活动")),
        BlockTemplate("第二节", t(9, 25), t(10, 0), SlotKind.Class, listOf("数学", "语文", "语文", "科学", "综合实践活动")),
        BlockTemplate("眼保健操", t(10, 0), t(10, 15), SlotKind.Break, listOf("课间 · 眼保健操", "课间 · 眼保健操", "课间 · 眼保健操", "课间 · 眼保健操", "课间 · 眼保健操")),
        BlockTemplate("第三节", t(10, 15), t(10, 50), SlotKind.Class, listOf("语文", "科学", "外语", "语文", "艺术 · 美术")),
        BlockTemplate("课间", t(10, 50), t(11, 5), SlotKind.Break, listOf("课间休息", "课间休息", "课间休息", "课间休息", "课间休息")),
        BlockTemplate("第四节", t(11, 5), t(11, 40), SlotKind.Class, listOf("语文", "校本 · 外语", "艺术 · 音乐", "语文", "体育与健康")),
        BlockTemplate("午餐", t(11, 40), t(12, 10), SlotKind.Meal, listOf("午餐", "午餐", "午餐", "午餐", "午餐")),
        BlockTemplate("午休", t(12, 10), t(13, 0), SlotKind.Rest, listOf("午休", "午休", "午休", "午休", "午休")),
        BlockTemplate("第五节", t(13, 0), t(13, 35), SlotKind.Class, listOf("艺术 · 美术", "劳动", "道德与法治", "体育与健康", "班队活动")),
        BlockTemplate("课间", t(13, 35), t(13, 50), SlotKind.Break, listOf("课间休息", "课间休息", "课间休息", "课间休息", "课间休息")),
        BlockTemplate("第六节", t(13, 50), t(14, 25), SlotKind.Class, listOf("体育与健康", "体育与健康", "校本 · 体育", "道德与法治", "艺术 · 音乐")),
        BlockTemplate("课间", t(14, 25), t(14, 40), SlotKind.Break, listOf("课间休息", "课间休息", "课间休息", "课间休息", "课间休息")),
        BlockTemplate("体育活动", t(14, 40), t(15, 15), SlotKind.Activity, listOf("有氧时光 · 体育活动", "有氧时光 · 体育活动", "有氧时光 · 体育活动", "有氧时光 · 体育活动", "有氧时光 · 体育活动")),
        BlockTemplate("课后服务①", t(15, 15), t(16, 30), SlotKind.Service, listOf("课后服务", "课后服务", "课后服务", "课后服务", "课后服务")),
        BlockTemplate("课后服务②", t(16, 30), t(17, 30), SlotKind.Service, listOf("课后服务", "课后服务", "课后服务", "课后服务", "课后服务")),
        BlockTemplate("课后服务③", t(17, 30), t(18, 0), SlotKind.Service, listOf("课后服务", "课后服务", "课后服务", "课后服务", "课后服务")),
    )

    private val dayLabels = listOf("星期一", "星期二", "星期三", "星期四", "星期五")

    fun dayLabelFor(date: LocalDate): String {
        val index = weekdayIndex(date.dayOfWeek)
        if (index != null) return dayLabels[index]
        return when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> "星期六"
            DayOfWeek.SUNDAY -> "星期日"
            else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINA)
        }
    }

    fun isSchoolDay(date: LocalDate): Boolean = weekdayIndex(date.dayOfWeek) != null

    fun classCount(date: LocalDate): Int =
        slotsFor(date).count { it.kind == SlotKind.Class }

    fun slotsFor(date: LocalDate): List<ScheduleSlot> {
        val index = weekdayIndex(date.dayOfWeek) ?: return emptyList()
        return blocks.map { block ->
            ScheduleSlot(
                periodLabel = block.periodLabel,
                start = block.start,
                end = block.end,
                subject = block.subjects[index],
                kind = block.kind,
            )
        }
    }

    fun momentAt(now: LocalDateTime = LocalDateTime.now()): ScheduleMoment {
        val date = now.toLocalDate()
        val time = now.toLocalTime()
        val dayIndex = weekdayIndex(date.dayOfWeek)
        val dayLabel = when (dayIndex) {
            null -> when (date.dayOfWeek) {
                DayOfWeek.SATURDAY -> "星期六"
                DayOfWeek.SUNDAY -> "星期日"
                else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINA)
            }
            else -> dayLabels[dayIndex]
        }

        if (dayIndex == null) {
            return ScheduleMoment(
                slot = null,
                next = null,
                dayLabel = dayLabel,
                isSchoolDay = false,
                isBeforeSchool = false,
                isAfterSchool = false,
                progress = 0f,
                nowText = time.format(TIME_FMT),
            )
        }

        val slots = slotsFor(date)
        val schoolStart = blocks.first().start
        val schoolEnd = blocks.last().end
        val current = slots.firstOrNull { time >= it.start && time < it.end }
        val next = if (current != null) {
            slots.firstOrNull { it.start >= current.end }
        } else {
            slots.firstOrNull { it.start > time }
        }

        val beforeSchool = time < schoolStart
        val afterSchool = time >= schoolEnd
        val progress = current?.let { slot ->
            val total = java.time.Duration.between(slot.start, slot.end).toMillis().coerceAtLeast(1)
            val done = java.time.Duration.between(slot.start, time).toMillis()
            (done.toFloat() / total).coerceIn(0f, 1f)
        } ?: 0f

        return ScheduleMoment(
            slot = current,
            next = next,
            dayLabel = dayLabel,
            isSchoolDay = true,
            isBeforeSchool = beforeSchool,
            isAfterSchool = afterSchool,
            progress = progress,
            nowText = time.format(TIME_FMT),
        )
    }

    fun headline(moment: ScheduleMoment): String = when {
        !moment.isSchoolDay -> "周末休息中"
        moment.slot != null -> moment.slot.subject
        moment.isBeforeSchool -> "还没上学"
        moment.isAfterSchool -> "已经放学啦"
        else -> "课间空档"
    }

    fun subtitle(moment: ScheduleMoment): String = when {
        !moment.isSchoolDay -> "好好放松，下周再见"
        moment.slot != null -> "${moment.slot.periodLabel} · ${moment.slot.start.format(TIME_FMT)}–${moment.slot.end.format(TIME_FMT)}"
        moment.isBeforeSchool -> "第一节 ${blocks.first().start.format(TIME_FMT)} 开始"
        moment.isAfterSchool -> "今日课程已结束"
        moment.next != null -> "下一节：${moment.next.subject}（${moment.next.start.format(TIME_FMT)}）"
        else -> "当前没有排课条目"
    }

    private fun weekdayIndex(day: DayOfWeek): Int? = when (day) {
        DayOfWeek.MONDAY -> 0
        DayOfWeek.TUESDAY -> 1
        DayOfWeek.WEDNESDAY -> 2
        DayOfWeek.THURSDAY -> 3
        DayOfWeek.FRIDAY -> 4
        else -> null
    }

    private fun t(h: Int, m: Int) = LocalTime.of(h, m)
}
