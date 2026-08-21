package com.moci.words

import android.content.Context
import java.time.LocalDate

/** 今日任务奖励小游戏：每天最多玩 [MAX_PLAYS] 局。 */
object RewardQuota {
    private const val PREFS = "moci_reward"
    private const val KEY_DATE = "plays_date"
    private const val KEY_COUNT = "plays_count"
    const val MAX_PLAYS = 3

    fun used(context: Context): Int {
        syncDay(context)
        return prefs(context).getInt(KEY_COUNT, 0)
    }

    fun remaining(context: Context): Int = (MAX_PLAYS - used(context)).coerceAtLeast(0)

    fun canPlay(context: Context): Boolean = remaining(context) > 0

    /** 消耗一局。成功返回 true；已达上限返回 false。 */
    fun consume(context: Context): Boolean {
        syncDay(context)
        val p = prefs(context)
        val count = p.getInt(KEY_COUNT, 0)
        if (count >= MAX_PLAYS) return false
        p.edit().putInt(KEY_COUNT, count + 1).apply()
        return true
    }

    private fun syncDay(context: Context) {
        val today = LocalDate.now().toString()
        val p = prefs(context)
        if (p.getString(KEY_DATE, "").orEmpty() != today) {
            p.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_COUNT, 0)
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
