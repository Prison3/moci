package com.moci.words

import android.content.Context
import java.time.LocalDate

/** 今日任务奖励小游戏：每天最多玩 [DEFAULT_LIMIT_MINUTES] 分钟，可由家长改。 */
object RewardQuota {
    const val DEFAULT_LIMIT_MINUTES = 30
    const val MAX_LIMIT_MINUTES = 180

    private const val PREFS = "moci_reward"
    private const val KEY_DATE = "play_date"
    private const val KEY_USED_MS = "play_used_ms"
    private const val KEY_USER = "play_user"

    fun remainingMs(context: Context, userId: Int, limitMinutes: Int): Long {
        syncDay(context, userId)
        val limitMs = limitMs(limitMinutes)
        val used = prefs(context).getLong(KEY_USED_MS, 0L).coerceAtLeast(0L)
        return (limitMs - used).coerceAtLeast(0L)
    }

    fun canPlay(context: Context, userId: Int, limitMinutes: Int): Boolean =
        remainingMs(context, userId, limitMinutes) > 0L

    /** 累加已玩时长，返回剩余毫秒。 */
    fun addUsed(context: Context, userId: Int, limitMinutes: Int, deltaMs: Long): Long {
        syncDay(context, userId)
        val p = prefs(context)
        val limitMs = limitMs(limitMinutes)
        val used = (p.getLong(KEY_USED_MS, 0L) + deltaMs.coerceAtLeast(0L)).coerceAtMost(limitMs)
        p.edit().putLong(KEY_USED_MS, used).apply()
        return (limitMs - used).coerceAtLeast(0L)
    }

    fun formatSeconds(totalSec: Int): String {
        val sec = totalSec.coerceAtLeast(0)
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    private fun limitMs(limitMinutes: Int): Long =
        limitMinutes.coerceIn(0, MAX_LIMIT_MINUTES) * 60_000L

    private fun syncDay(context: Context, userId: Int) {
        val today = LocalDate.now().toString()
        val p = prefs(context)
        val sameDay = p.getString(KEY_DATE, "").orEmpty() == today
        val sameUser = p.getInt(KEY_USER, 0) == userId
        if (!sameDay || !sameUser) {
            p.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_USER, userId)
                .putLong(KEY_USED_MS, 0L)
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
