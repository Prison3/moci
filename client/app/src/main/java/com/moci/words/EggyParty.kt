package com.moci.words

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate

/**
 * 今日任务奖励：拉起已安装的「蛋仔派对」。
 * 各渠道包名不同，按常见渠道依次尝试。
 */
object EggyParty {
    private const val PREFS = "moci_reward"
    private const val KEY_CLAIMED = "claimed_date"
    private const val STORE_PACKAGE = "com.netease.party"

    private val packages = listOf(
        "com.netease.party",
        "com.netease.party.huawei",
        "com.netease.party.mi",
        "com.netease.party.honor",
        "com.netease.party.oppo",
        "com.netease.party.vivo",
        "com.netease.party.bilibili",
        "com.netease.party.nearme.gamecenter",
        "com.netease.party.uc",
        "com.netease.party.m4399",
        "com.netease.party.baidu",
        "com.netease.eggypartyhmt",
    )

    fun claimedToday(context: Context): Boolean {
        val today = LocalDate.now().toString()
        return prefs(context).getString(KEY_CLAIMED, "").orEmpty() == today
    }

    fun markClaimed(context: Context) {
        prefs(context).edit().putString(KEY_CLAIMED, LocalDate.now().toString()).apply()
    }

    /** 找到已安装的蛋仔派对并启动。成功返回 true。 */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in packages) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        return false
    }

    fun openStore(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$STORE_PACKAGE"))
            .addFlags(flags)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.taptap.cn/app/221872"),
            ).addFlags(flags)
            runCatching { context.startActivity(web) }
        }
    }

    fun queryPackages(): List<String> = packages

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
