package com.moci.words.api

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.optFlag(key: String, default: Boolean = true): Boolean {
    if (!has(key) || isNull(key)) return default
    return when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> default
    }
}

// 与服务端 /api/v1 响应对应的数据模型。字段名沿用服务端 snake_case 的直接映射。

data class User(
    val id: Int,
    val username: String,
    val role: String,        // admin | parent | user
    val status: String,      // pending | approved | rejected
    val dailyWords: Int,
    val dailyReview: Int,
    val knowSpeak: Boolean,
    val knowSpell: Boolean,
) {
    val isAdmin get() = role == "admin"
    val isParent get() = role == "parent"
    val isLearner get() = role == "user"
    val roleLabel get() = when (role) {
        "admin" -> "管理员"
        "parent" -> "家长"
        else -> "学生"
    }

    companion object {
        fun from(o: JSONObject) = User(
            id = o.getInt("id"),
            username = o.getString("username"),
            role = o.getString("role"),
            status = o.optString("status", "approved"),
            dailyWords = o.optInt("daily_words", 8),
            dailyReview = o.optInt("daily_review", 8),
            knowSpeak = o.optInt("know_speak", 1) == 1,
            knowSpell = o.optInt("know_spell", 1) == 1,
        )
    }
}

data class Word(
    val id: Int,
    val term: String,
    val phonetic: String,
    val pos: String = "",
    val meaning: String,
    val phrase: String,
    val example: String,
    val notes: String,
    val status: String = "",
    val updatedAt: String = "",
) {
    val posTags: List<String> get() = parsePosTags(pos)

    companion object {
        fun from(o: JSONObject) = Word(
            id = o.getInt("id"),
            term = o.getString("term"),
            phonetic = o.optString("phonetic"),
            pos = o.optString("pos"),
            meaning = o.optString("meaning"),
            phrase = o.optString("phrase"),
            example = o.optString("example"),
            notes = o.optString("notes"),
            status = o.optString("status"),
            updatedAt = o.optString("updated_at"),
        )
    }
}

/** 学习卡片（含当前进度状态） */
data class Card(
    val id: Int,
    val term: String,
    val phonetic: String,
    val pos: String = "",
    val meaning: String,
    val phrase: String,
    val example: String,
    val notes: String,
    val status: String,
    val kind: String, // new | review
) {
    val posTags: List<String> get() = parsePosTags(pos)

    companion object {
        fun from(o: JSONObject) = Card(
            id = o.getInt("id"),
            term = o.getString("term"),
            phonetic = o.optString("phonetic"),
            pos = o.optString("pos"),
            meaning = o.optString("meaning"),
            phrase = o.optString("phrase"),
            example = o.optString("example"),
            notes = o.optString("notes"),
            status = o.optString("status", "new"),
            kind = o.optString("kind", "new"),
        )
    }
}

/** 一词可有多个词性，存成 “n. / v.” 一类文本。 */
fun parsePosTags(raw: String): List<String> =
    raw.replace(Regex("""[/|,，、;；|]+"""), " ")
        .split(Regex("""\s+"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

fun joinPosTags(tags: Collection<String>): String =
    tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" / ")

/** 小学常用词性，录入时可多选。 */
val COMMON_POS_TAGS = listOf(
    "n.", "v.", "adj.", "adv.", "prep.", "conj.", "pron.", "art.", "num.", "interj.",
)

data class TaskPart(val quota: Int, val done: Int, val remaining: Int) {
    companion object {
        fun from(o: JSONObject) =
            TaskPart(o.optInt("quota"), o.optInt("done"), o.optInt("remaining"))
    }
}

data class TodayTask(val new: TaskPart, val review: TaskPart, val quota: Int, val done: Int, val remaining: Int) {
    companion object {
        fun from(o: JSONObject) = TodayTask(
            new = TaskPart.from(o.getJSONObject("new")),
            review = TaskPart.from(o.getJSONObject("review")),
            quota = o.optInt("quota"),
            done = o.optInt("done"),
            remaining = o.optInt("remaining"),
        )
    }
}

data class WordStats(val total: Int, val newCount: Int, val learning: Int, val mastered: Int, val due: Int) {
    companion object {
        fun from(o: JSONObject) = WordStats(
            total = o.optInt("total"),
            newCount = o.optInt("new_count"),
            learning = o.optInt("learning"),
            mastered = o.optInt("mastered"),
            due = o.optInt("due"),
        )
    }
}

data class CalCell(
    val blank: Boolean,
    val day: Int = 0,
    val date: String = "",
    val newN: Int = 0,
    val reviewN: Int = 0,
    val studied: Boolean = false,
    val complete: Boolean = false,
    val today: Boolean = false,
    val future: Boolean = false,
    val selected: Boolean = false,
) {
    companion object {
        fun from(o: JSONObject): CalCell {
            if (o.optBoolean("blank")) return CalCell(blank = true)
            return CalCell(
                blank = false,
                day = o.optInt("day"),
                date = o.optString("date"),
                newN = o.optInt("new_n"),
                reviewN = o.optInt("review_n"),
                studied = o.optBoolean("studied"),
                complete = o.optBoolean("complete"),
                today = o.optBoolean("today"),
                future = o.optBoolean("future"),
                selected = o.optBoolean("selected"),
            )
        }
    }
}

data class MonthCal(
    val year: Int,
    val month: Int,
    val title: String,
    val cells: List<CalCell>,
    val studiedDays: Int,
    val newQuota: Int,
    val reviewQuota: Int,
    val today: String = "",
    val prevDate: String = "",
    val nextDate: String = "",
) {
    companion object {
        fun from(o: JSONObject): MonthCal {
            val arr = o.optJSONArray("cells") ?: JSONArray()
            val cells = (0 until arr.length()).map { CalCell.from(arr.getJSONObject(it)) }
            return MonthCal(
                year = o.optInt("year"),
                month = o.optInt("month"),
                title = o.optString("title"),
                cells = cells,
                studiedDays = o.optInt("studied_days"),
                newQuota = o.optInt("new_quota"),
                reviewQuota = o.optInt("review_quota"),
                today = o.optString("today"),
                prevDate = o.optString("prev_date"),
                nextDate = o.optString("next_date"),
            )
        }
    }
}

/** 某天的学习记录单词 */
data class DayWord(
    val term: String,
    val meaning: String,
    val phrase: String,
    val example: String,
    val status: String,
    val rating: String,
    val kind: String,
    val username: String? = null,
) {
    companion object {
        fun from(o: JSONObject) = DayWord(
            term = o.optString("term"),
            meaning = o.optString("meaning"),
            phrase = o.optString("phrase"),
            example = o.optString("example"),
            status = o.optString("status", "new"),
            rating = o.optString("rating"),
            kind = o.optString("kind", "new"),
            username = if (o.has("username")) o.optString("username") else null,
        )

        fun listFrom(arr: JSONArray?): List<DayWord> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { from(arr.getJSONObject(it)) }
        }
    }
}

data class Summary(
    val reviews: Int, val learners: Int, val againN: Int,
    val easyN: Int, val newN: Int, val reviewN: Int,
) {
    companion object {
        fun from(o: JSONObject?) = Summary(
            reviews = o?.optInt("reviews") ?: 0,
            learners = o?.optInt("learners") ?: 0,
            againN = o?.optInt("again_n") ?: 0,
            easyN = o?.optInt("easy_n") ?: 0,
            newN = o?.optInt("new_n") ?: 0,
            reviewN = o?.optInt("review_n") ?: 0,
        )
    }
}

data class ByUser(
    val id: Int, val username: String, val reviews: Int, val words: Int,
    val easyN: Int, val againN: Int, val newN: Int, val reviewN: Int, val lastAt: String,
) {
    companion object {
        fun from(o: JSONObject) = ByUser(
            id = o.getInt("id"),
            username = o.getString("username"),
            reviews = o.optInt("reviews"),
            words = o.optInt("words"),
            easyN = o.optInt("easy_n"),
            againN = o.optInt("again_n"),
            newN = o.optInt("new_n"),
            reviewN = o.optInt("review_n"),
            lastAt = o.optString("last_at"),
        )

        fun listFrom(arr: JSONArray?): List<ByUser> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { from(arr.getJSONObject(it)) }
        }
    }
}

data class ChildInfo(val user: User, val stats: WordStats?, val task: TodayTask) {
    companion object {
        fun from(o: JSONObject) = ChildInfo(
            user = User.from(o.getJSONObject("user")),
            stats = o.optJSONObject("stats")?.let { WordStats.from(it) },
            task = TodayTask.from(o.getJSONObject("task")),
        )
    }
}

data class PendingUser(
    val id: Int,
    val username: String,
    val role: String,
    val status: String,
    val createdAt: String,
) {
    companion object {
        fun from(o: JSONObject) = PendingUser(
            id = o.getInt("id"),
            username = o.getString("username"),
            role = o.optString("role"),
            status = o.optString("status", "approved"),
            createdAt = o.optString("created_at"),
        )

        fun listFrom(arr: JSONArray?): List<PendingUser> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { from(arr.getJSONObject(it)) }
        }
    }
}

/** 学生端首页数据 */
data class LearnerHome(
    val user: User,
    val stats: WordStats,
    val task: TodayTask,
    val calendar: MonthCal,
    val dayWords: List<DayWord>,
    val speak: Boolean,
    val spell: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = LearnerHome(
            user = User.from(o.getJSONObject("user")),
            stats = WordStats.from(o.getJSONObject("stats")),
            task = TodayTask.from(o.getJSONObject("task")),
            calendar = MonthCal.from(o.getJSONObject("calendar")),
            dayWords = DayWord.listFrom(o.optJSONArray("day_words")),
            speak = o.optFlag("speak", true),
            spell = o.optFlag("spell", true),
        )
    }
}

/** 家长端首页数据 */
data class ParentHome(
    val user: User,
    val children: List<ChildInfo>,
    val day: String,
    val detailId: Int?,
    val kind: String,
    val kindLabel: String,
    val calendar: MonthCal,
    val summary: Summary,
    val byUser: List<ByUser>,
    val logs: List<DayWord>,
) {
    companion object {
        fun from(o: JSONObject): ParentHome {
            val childrenArr = o.optJSONArray("children") ?: JSONArray()
            val detail = if (o.isNull("detail_id")) null else o.optInt("detail_id")
            return ParentHome(
                user = User.from(o.getJSONObject("user")),
                children = (0 until childrenArr.length()).map { ChildInfo.from(childrenArr.getJSONObject(it)) },
                day = o.optString("day"),
                detailId = detail,
                kind = o.optString("kind", "new"),
                kindLabel = o.optString("kind_label"),
                calendar = MonthCal.from(o.getJSONObject("calendar")),
                summary = Summary.from(o.optJSONObject("summary")),
                byUser = ByUser.listFrom(o.optJSONArray("by_user")),
                logs = DayWord.listFrom(o.optJSONArray("logs")),
            )
        }
    }
}

/** 管理员首页数据 */
data class AdminHome(
    val user: User,
    val total: Int,
    val userCount: Int,
    val parentCount: Int,
    val pendingCount: Int,
    val pending: List<PendingUser>,
    val recent: List<Word>,
) {
    companion object {
        fun from(o: JSONObject): AdminHome {
            val recentArr = o.optJSONArray("recent") ?: JSONArray()
            return AdminHome(
                user = User.from(o.getJSONObject("user")),
                total = o.getJSONObject("stats").optInt("total"),
                userCount = o.optInt("user_count"),
                parentCount = o.optInt("parent_count"),
                pendingCount = o.optInt("pending_count"),
                pending = PendingUser.listFrom(o.optJSONArray("pending")),
                recent = (0 until recentArr.length()).map { Word.from(recentArr.getJSONObject(it)) },
            )
        }
    }
}

data class AdminUser(
    val id: Int, val username: String, val role: String, val status: String, val createdAt: String,
) {
    val roleLabel get() = when (role) {
        "admin" -> "管理员"
        "parent" -> "家长"
        else -> "学生"
    }
    val statusLabel get() = when (status) {
        "approved" -> "已通过"
        "rejected" -> "已拒绝"
        else -> "待审核"
    }

    companion object {
        fun from(o: JSONObject) = AdminUser(
            id = o.getInt("id"),
            username = o.getString("username"),
            role = o.optString("role"),
            status = o.optString("status"),
            createdAt = o.optString("created_at"),
        )

        fun listFrom(arr: JSONArray?): List<AdminUser> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { from(arr.getJSONObject(it)) }
        }
    }
}

data class AdminUsers(
    val users: List<AdminUser>,
    val adminCount: Int,
    val pendingCount: Int,
    val students: List<AdminUser>,
    /** parent_id(字符串) -> 孩子列表 */
    val childrenMap: Map<String, List<Pair<Int, String>>>,
) {
    companion object {
        fun from(o: JSONObject): AdminUsers {
            val mapObj = o.optJSONObject("children_map")
            val map = mutableMapOf<String, List<Pair<Int, String>>>()
            mapObj?.keys()?.forEach { key ->
                val arr = mapObj.getJSONArray(key)
                map[key] = (0 until arr.length()).map {
                    val item = arr.getJSONObject(it)
                    item.getInt("id") to item.getString("username")
                }
            }
            return AdminUsers(
                users = AdminUser.listFrom(o.optJSONArray("users")),
                adminCount = o.optInt("admin_count"),
                pendingCount = o.optInt("pending_count"),
                students = AdminUser.listFrom(o.optJSONArray("students")),
                childrenMap = map,
            )
        }
    }
}

data class LearningData(
    val day: String,
    val calendar: MonthCal,
    val summary: Summary,
    val byUser: List<ByUser>,
    val detailUserId: Int?,
    val detailUsername: String,
    val logs: List<DayWord>,
) {
    companion object {
        fun from(o: JSONObject): LearningData {
            val detail = o.optJSONObject("detail_user")
            return LearningData(
                day = o.optString("day"),
                calendar = MonthCal.from(o.getJSONObject("calendar")),
                summary = Summary.from(o.optJSONObject("summary")),
                byUser = ByUser.listFrom(o.optJSONArray("by_user")),
                detailUserId = detail?.optInt("id"),
                detailUsername = detail?.optString("username") ?: "",
                logs = DayWord.listFrom(o.optJSONArray("logs")),
            )
        }
    }
}

data class ProfileData(
    val user: User,
    val stats: WordStats?,
    val parents: List<PendingUser>,
    val children: List<ChildInfo>,
) {
    companion object {
        fun from(o: JSONObject): ProfileData {
            val childrenArr = o.optJSONArray("children")
            return ProfileData(
                user = User.from(o.getJSONObject("user")),
                stats = o.optJSONObject("stats")?.let { WordStats.from(it) },
                parents = PendingUser.listFrom(o.optJSONArray("parents")),
                children = if (childrenArr == null) emptyList()
                else (0 until childrenArr.length()).map { ChildInfo.from(childrenArr.getJSONObject(it)) },
            )
        }
    }
}

data class CardsData(
    val cards: List<Card>,
    val task: TodayTask,
    val stats: WordStats,
    val speak: Boolean,
    val spell: Boolean,
) {
    companion object {
        fun from(o: JSONObject): CardsData {
            val arr = o.optJSONArray("cards") ?: JSONArray()
            return CardsData(
                cards = (0 until arr.length()).map { Card.from(arr.getJSONObject(it)) },
                task = TodayTask.from(o.getJSONObject("task")),
                stats = WordStats.from(o.getJSONObject("stats")),
                speak = o.optFlag("speak", true),
                spell = o.optFlag("spell", true),
            )
        }
    }
}
