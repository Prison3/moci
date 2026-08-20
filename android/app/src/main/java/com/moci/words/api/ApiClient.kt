package com.moci.words.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(
    message: String,
    val code: String = "error",
    val httpStatus: Int = 0,
) : Exception(message)

/** 登录/角色状态变化回调，用于跳转登录页或重建主界面。 */
interface SessionListener {
    fun onUnauthorized()
}

/**
 * /api/v1 客户端。认证基于 Flask session cookie，持久化到 SharedPreferences；
 * 写操作自动附带登录时获取的 X-CSRF-Token。
 */
class ApiClient(context: Context, private val baseUrl: String) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("moci_session", Context.MODE_PRIVATE)

    var listener: SessionListener? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    var csrfToken: String?
        get() = prefs.getString("csrf", null)
        private set(value) {
            prefs.edit().putString("csrf", value).apply()
        }

    /** 本地缓存的当前用户（用于冷启动时先渲染框架，再后台校验）。 */
    var cachedUser: User?
        get() = prefs.getString("user", null)?.let {
            runCatching { User.from(JSONObject(it)) }.getOrNull()
        }
        private set(value) {
            prefs.edit().putString("user", value?.let { userToJson(it).toString() }).apply()
        }

    val hasSession: Boolean
        get() = !prefs.getString("session", null).isNullOrEmpty()

    private fun userToJson(u: User) = JSONObject().apply {
        put("id", u.id)
        put("username", u.username)
        put("role", u.role)
        put("status", u.status)
        put("daily_words", u.dailyWords)
        put("daily_review", u.dailyReview)
        put("know_speak", if (u.knowSpeak) 1 else 0)
        put("know_spell", if (u.knowSpell) 1 else 0)
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    // ------------------------------------------------------------------
    // 底层请求

    private suspend fun execute(
        method: String,
        path: String,
        body: JSONObject? = null,
        query: Map<String, String?> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val urlBuilder = StringBuilder(baseUrl.trimEnd('/') + path)
        val params = query.filterValues { it != null }
        if (params.isNotEmpty()) {
            urlBuilder.append('?')
            params.entries.joinTo(urlBuilder, "&") {
                "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
        }
        val builder = Request.Builder().url(urlBuilder.toString())
        prefs.getString("session", null)?.let { builder.header("Cookie", "session=$it") }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                csrfToken?.let { builder.header("X-CSRF-Token", it) }
                val payload = (body ?: JSONObject()).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                if (method.uppercase() == "PUT") builder.put(payload) else builder.post(payload)
            }
        }
        val response = runCatching { client.newCall(builder.build()).execute() }.getOrElse {
            throw ApiException("无法连接服务器，请检查网络。", "network")
        }
        response.use { resp ->
            // Flask 通过 Set-Cookie 滚动更新 session，这里每次响应都保存最新值
            resp.headers("Set-Cookie").forEach { header ->
                val pair = header.substringBefore(';')
                if (pair.startsWith("session=")) {
                    val value = pair.removePrefix("session=")
                    prefs.edit().putString("session", value).apply()
                }
            }
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw ApiException("服务器响应异常（${resp.code}）。", "bad_response", resp.code)
            }
            if (!resp.isSuccessful || !json.optBoolean("ok")) {
                val code = json.optString("error", "error")
                if (resp.code == 401) {
                    clearSession()
                    withContext(Dispatchers.Main) { listener?.onUnauthorized() }
                }
                throw ApiException(
                    json.optString("message", "请求失败，请重试。"),
                    code,
                    resp.code,
                )
            }
            json
        }
    }

    private fun saveAuth(json: JSONObject) {
        csrfToken = json.optString("csrf_token", csrfToken)
        json.optJSONObject("user")?.let { cachedUser = User.from(it) }
    }

    // ------------------------------------------------------------------
    // 认证

    suspend fun login(username: String, password: String): User {
        val json = execute("POST", "/api/v1/auth/login", JSONObject().apply {
            put("username", username)
            put("password", password)
        })
        saveAuth(json)
        return User.from(json.getJSONObject("user"))
    }

    suspend fun register(
        username: String,
        password: String,
        confirm: String,
        role: String,
    ): JSONObject {
        val json = execute("POST", "/api/v1/auth/register", JSONObject().apply {
            put("username", username)
            put("password", password)
            put("confirm", confirm)
            put("role", role)
        })
        if (json.optBoolean("auto_login")) saveAuth(json)
        return json
    }

    suspend fun logout() {
        runCatching { execute("POST", "/api/v1/auth/logout") }
        clearSession()
    }

    suspend fun me(): User {
        val json = execute("GET", "/api/v1/auth/me")
        saveAuth(json)
        return User.from(json.getJSONObject("user"))
    }

    // ------------------------------------------------------------------
    // 首页 / 学习

    suspend fun homeLearner(): LearnerHome = LearnerHome.from(execute("GET", "/api/v1/home"))

    suspend fun homeParent(date: String? = null, userId: Int? = null, kind: String? = null): ParentHome =
        ParentHome.from(
            execute(
                "GET", "/api/v1/home",
                query = mapOf(
                    "date" to date,
                    "user_id" to userId?.toString(),
                    "kind" to kind,
                ),
            )
        )

    suspend fun homeAdmin(): AdminHome = AdminHome.from(execute("GET", "/api/v1/home"))

    suspend fun reviewCards(): CardsData = CardsData.from(execute("GET", "/api/v1/review/cards"))

    suspend fun submitReview(
        wordId: Int,
        rating: String,
        spelling: String? = null,
        spoken: String? = null,
    ): JSONObject = execute("POST", "/api/v1/review/$wordId", JSONObject().apply {
        put("rating", rating)
        if (spelling != null) put("spelling", spelling)
        if (spoken != null) put("spoken", spoken)
    })

    suspend fun studyDay(date: String): List<DayWord> =
        DayWord.listFrom(execute("GET", "/api/v1/study-day", query = mapOf("date" to date)).optJSONArray("words"))

    // ------------------------------------------------------------------
    // 我的 / 家长设置 / 切换账号

    suspend fun profile(): ProfileData = ProfileData.from(execute("GET", "/api/v1/profile"))

    suspend fun saveChildSettings(
        childId: Int,
        dailyWords: Int,
        dailyReview: Int,
        knowSpeak: Boolean,
        knowSpell: Boolean,
    ): String {
        val json = execute("POST", "/api/v1/profile/child/$childId/settings", JSONObject().apply {
            put("daily_words", dailyWords)
            put("daily_review", dailyReview)
            put("know_speak", knowSpeak)
            put("know_spell", knowSpell)
        })
        return json.optString("message", "已保存。")
    }

    suspend fun switchAccount(targetId: Int, password: String? = null): User {
        val json = execute("POST", "/api/v1/switch", JSONObject().apply {
            put("target_id", targetId)
            if (password != null) put("password", password)
        })
        saveAuth(json)
        return User.from(json.getJSONObject("user"))
    }

    // ------------------------------------------------------------------
    // 词库（管理员）

    suspend fun words(q: String = ""): Pair<List<Word>, Int> {
        val json = execute("GET", "/api/v1/words", query = mapOf("q" to q.ifBlank { null }))
        val arr = json.optJSONArray("words") ?: org.json.JSONArray()
        return (0 until arr.length()).map { Word.from(arr.getJSONObject(it)) } to json.optInt("total")
    }

    suspend fun wordCreate(word: Word): String =
        execute("POST", "/api/v1/words", wordJson(word)).optString("message", "已录入。")

    suspend fun wordUpdate(id: Int, word: Word): String =
        execute("PUT", "/api/v1/words/$id", wordJson(word)).optString("message", "已保存。")

    suspend fun wordDelete(id: Int): String =
        execute("DELETE", "/api/v1/words/$id").optString("message", "已删除。")

    private fun wordJson(w: Word) = JSONObject().apply {
        put("term", w.term)
        put("phonetic", w.phonetic)
        put("meaning", w.meaning)
        put("phrase", w.phrase)
        put("example", w.example)
        put("notes", w.notes)
    }

    // ------------------------------------------------------------------
    // 管理员：用户与学情

    suspend fun adminUsers(): AdminUsers = AdminUsers.from(execute("GET", "/api/v1/admin/users"))

    suspend fun adminSetStatus(userId: Int, status: String): String =
        execute("POST", "/api/v1/admin/users/$userId/status", JSONObject().put("status", status))
            .optString("message", "已完成。")

    suspend fun adminSetRole(userId: Int, role: String): String =
        execute("POST", "/api/v1/admin/users/$userId/role", JSONObject().put("role", role))
            .optString("message", "已完成。")

    suspend fun adminBindChild(parentId: Int, childId: Int): String =
        execute("POST", "/api/v1/admin/users/$parentId/children", JSONObject().put("child_id", childId))
            .optString("message", "已绑定。")

    suspend fun adminUnbindChild(parentId: Int, childId: Int): String =
        execute("DELETE", "/api/v1/admin/users/$parentId/children/$childId")
            .optString("message", "已取消绑定。")

    suspend fun adminLearning(date: String? = null, userId: Int? = null): LearningData =
        LearningData.from(
            execute(
                "GET", "/api/v1/admin/learning",
                query = mapOf("date" to date, "user_id" to userId?.toString()),
            )
        )
}
