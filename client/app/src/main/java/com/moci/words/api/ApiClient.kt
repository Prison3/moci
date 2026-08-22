package com.moci.words.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.moci.words.db.ApiCache
import com.moci.words.db.MociDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
 * GET 响应写入本地 Room（moci_cache.db），默认 30 分钟内复用，避免切 Tab 反复打服务器。
 */
class ApiClient(context: Context, defaultBaseUrl: String) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("moci_session", Context.MODE_PRIVATE)
    private val accountPrefs: SharedPreferences =
        appContext.getSharedPreferences("moci_accounts", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences =
        appContext.getSharedPreferences("moci_settings", Context.MODE_PRIVATE)
    private val cache = ApiCache(MociDatabase.get(appContext).cacheDao())

    private var baseUrl: String = loadBaseUrl(appContext, defaultBaseUrl)

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

    private fun cacheUserId(): Int = cachedUser?.id ?: 0

    /** 手动清空本地 API 缓存（下拉重试 / 切账号时用）。 */
    suspend fun invalidateLocalCache() = withContext(Dispatchers.IO) {
        val uid = cacheUserId()
        if (uid != 0) cache.clearUser(uid) else cache.clearAll()
    }

    private suspend fun invalidateLearnerProgress() {
        val uid = cacheUserId()
        if (uid == 0) return
        cache.remove(uid, "home:learner")
        cache.remove(uid, "review:cards")
        cache.remove(uid, "profile")
        cache.removePrefix(uid, "study-day:")
        cache.removePrefix(uid, "my-words:")
        cache.removePrefix(uid, "home:parent:")
    }

    /**
     * 带本地库的 GET：新鲜缓存直接返回；否则打网络并写入；
     * 网络失败时回退到任意年龄的本地副本。
     */
    private suspend fun <T> cachedGet(
        key: String,
        force: Boolean = false,
        parse: (JSONObject) -> T,
        fetch: suspend () -> JSONObject,
    ): T = withContext(Dispatchers.IO) {
        val uid = cacheUserId()
        if (!force) {
            cache.read(uid, key)?.let { body ->
                return@withContext parse(JSONObject(body))
            }
        }
        try {
            val json = fetch()
            cache.write(uid, key, json.toString())
            parse(json)
        } catch (e: Exception) {
            cache.read(uid, key, maxAgeMs = Long.MAX_VALUE)?.let { body ->
                return@withContext parse(JSONObject(body))
            }
            throw e
        }
    }

    @Volatile
    private var lastClearedUserId: Int = 0

    fun clearSession() {
        lastClearedUserId = cacheUserId()
        prefs.edit()
            .remove("session")
            .remove("csrf")
            .remove("user")
            .apply()
    }

    private suspend fun clearCacheAfterSessionGone() {
        val uid = lastClearedUserId
        lastClearedUserId = 0
        if (uid != 0) cache.clearUser(uid) else cache.clearAll()
    }

    fun lastUsername(): String = recentUsernames().firstOrNull().orEmpty()

    fun recentUsernames(): List<String> {
        val raw = accountPrefs.getString("recent_usernames", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val names = (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).trim().takeIf { it.isNotEmpty() }
        }.toMutableList()
        cachedUser?.username?.trim()?.takeIf { it.isNotEmpty() }?.let { current ->
            if (names.none { it.equals(current, ignoreCase = true) }) {
                names.add(0, current)
                accountPrefs.edit().putString("recent_usernames", JSONArray(names).toString()).apply()
            }
        }
        return names
    }

    fun rememberUsername(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val next = (listOf(n) + recentUsernames().filter { !it.equals(n, ignoreCase = true) }).take(8)
        accountPrefs.edit().putString("recent_usernames", JSONArray(next).toString()).apply()
    }

    fun forgetUsername(name: String) {
        val next = recentUsernames().filter { !it.equals(name, ignoreCase = true) }
        accountPrefs.edit().putString("recent_usernames", JSONArray(next).toString()).apply()
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
                    cachedUser?.username?.let { rememberUsername(it) }
                    clearSession()
                    clearCacheAfterSessionGone()
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
        csrfToken = json.optString("csrf_token").ifBlank { csrfToken }
        json.optJSONObject("user")?.let {
            val user = User.from(it)
            cachedUser = user
            rememberUsername(user.username)
        }
    }

    // ------------------------------------------------------------------
    // 认证

    suspend fun login(username: String, password: String): User {
        val json = execute("POST", "/api/v1/auth/login", JSONObject().apply {
            put("username", username)
            put("password", password)
        })
        saveAuth(json)
        rememberUsername(username)
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
        cachedUser?.username?.let { rememberUsername(it) }
        runCatching { execute("POST", "/api/v1/auth/logout") }
        clearSession()
        clearCacheAfterSessionGone()
    }

    suspend fun me(): User {
        val json = execute("GET", "/api/v1/auth/me")
        saveAuth(json)
        return User.from(json.getJSONObject("user"))
    }

    // ------------------------------------------------------------------
    // 首页 / 学习

    suspend fun homeLearner(force: Boolean = false): LearnerHome =
        cachedGet("home:learner", force, LearnerHome::from) {
            execute("GET", "/api/v1/home")
        }

    suspend fun homeParent(
        date: String? = null,
        userId: Int? = null,
        kind: String? = null,
        force: Boolean = false,
    ): ParentHome {
        val key = "home:parent:${date.orEmpty()}:${userId ?: 0}:${kind.orEmpty()}"
        return cachedGet(key, force, ParentHome::from) {
            execute(
                "GET", "/api/v1/home",
                query = mapOf(
                    "date" to date,
                    "user_id" to userId?.toString(),
                    "kind" to kind,
                ),
            )
        }
    }

    suspend fun homeAdmin(force: Boolean = false): AdminHome =
        cachedGet("home:admin", force, AdminHome::from) {
            execute("GET", "/api/v1/home")
        }

    suspend fun reviewCards(force: Boolean = false): CardsData =
        cachedGet("review:cards", force, CardsData::from) {
            val json = execute("GET", "/api/v1/review/cards")
            Log.i(
                "MociHide",
                "[HIDE] api /review/cards speak=${json.opt("speak")} spell=${json.opt("spell")} " +
                    "cards=${json.optJSONArray("cards")?.length()}",
            )
            json
        }

    suspend fun submitReview(
        wordId: Int,
        rating: String,
        spelling: String? = null,
        spoken: String? = null,
    ): JSONObject {
        val json = execute("POST", "/api/v1/review/$wordId", JSONObject().apply {
            put("rating", rating)
            if (spelling != null) put("spelling", spelling)
            if (spoken != null) put("spoken", spoken)
        })
        invalidateLearnerProgress()
        return json
    }

    suspend fun studyDay(date: String, force: Boolean = false): List<DayWord> =
        cachedGet("study-day:$date", force, { DayWord.listFrom(it.optJSONArray("words")) }) {
            execute("GET", "/api/v1/study-day", query = mapOf("date" to date))
        }

    // ------------------------------------------------------------------
    // 我的 / 家长设置 / 切换账号

    suspend fun profile(force: Boolean = false): ProfileData =
        cachedGet("profile", force, ProfileData::from) {
            execute("GET", "/api/v1/profile")
        }

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
        invalidateLearnerProgress()
        return json.optString("message", "已保存。")
    }

    suspend fun switchAccount(targetId: Int, password: String? = null): User {
        val prev = cacheUserId()
        val json = execute("POST", "/api/v1/switch", JSONObject().apply {
            put("target_id", targetId)
            if (password != null) put("password", password)
        })
        saveAuth(json)
        if (prev != 0) cache.clearUser(prev)
        return User.from(json.getJSONObject("user"))
    }

    // ------------------------------------------------------------------
    // 词库（管理员）

    suspend fun words(q: String = "", force: Boolean = false): Pair<List<Word>, Int> =
        cachedGet("words:${q.trim()}", force, { json ->
            val arr = json.optJSONArray("words") ?: JSONArray()
            (0 until arr.length()).map { Word.from(arr.getJSONObject(it)) } to json.optInt("total")
        }) {
            execute("GET", "/api/v1/words", query = mapOf("q" to q.ifBlank { null }))
        }

    /** 学生按进度查看自己的单词：status 为空即全部。 */
    suspend fun myWords(status: String = "", q: String = "", force: Boolean = false): Pair<List<Word>, Int> =
        cachedGet("my-words:${status.trim()}:${q.trim()}", force, { json ->
            val arr = json.optJSONArray("words") ?: JSONArray()
            (0 until arr.length()).map { Word.from(arr.getJSONObject(it)) } to json.optInt("total")
        }) {
            execute(
                "GET", "/api/v1/me/words",
                query = mapOf(
                    "status" to status.ifBlank { null },
                    "q" to q.ifBlank { null },
                ),
            )
        }

    suspend fun wordCreate(word: Word): String {
        val msg = execute("POST", "/api/v1/words", wordJson(word)).optString("message", "已录入。")
        cache.removePrefix(cacheUserId(), "words:")
        return msg
    }

    suspend fun wordUpdate(id: Int, word: Word): String {
        val msg = execute("PUT", "/api/v1/words/$id", wordJson(word)).optString("message", "已保存。")
        cache.removePrefix(cacheUserId(), "words:")
        return msg
    }

    suspend fun wordDelete(id: Int): String {
        val msg = execute("DELETE", "/api/v1/words/$id").optString("message", "已删除。")
        cache.removePrefix(cacheUserId(), "words:")
        return msg
    }

    private fun wordJson(w: Word) = JSONObject().apply {
        put("term", w.term)
        put("phonetic", w.phonetic)
        put("pos", w.pos)
        put("meaning", w.meaning)
        put("phrase", w.phrase)
        put("phrase_zh", w.phraseZh)
        put("example", w.example)
        put("example_zh", w.exampleZh)
        put("notes", w.notes)
    }

    // ------------------------------------------------------------------
    // 管理员：用户与学情

    suspend fun adminUsers(force: Boolean = false): AdminUsers =
        cachedGet("admin:users", force, AdminUsers::from) {
            execute("GET", "/api/v1/admin/users")
        }

    suspend fun adminSetStatus(userId: Int, status: String): String {
        val msg = execute("POST", "/api/v1/admin/users/$userId/status", JSONObject().put("status", status))
            .optString("message", "已完成。")
        cache.remove(cacheUserId(), "admin:users")
        cache.remove(cacheUserId(), "home:admin")
        return msg
    }

    suspend fun adminSetRole(userId: Int, role: String): String {
        val msg = execute("POST", "/api/v1/admin/users/$userId/role", JSONObject().put("role", role))
            .optString("message", "已完成。")
        cache.remove(cacheUserId(), "admin:users")
        cache.remove(cacheUserId(), "home:admin")
        return msg
    }

    suspend fun adminBindChild(parentId: Int, childId: Int): String {
        val msg = execute("POST", "/api/v1/admin/users/$parentId/children", JSONObject().put("child_id", childId))
            .optString("message", "已绑定。")
        cache.remove(cacheUserId(), "admin:users")
        return msg
    }

    suspend fun adminUnbindChild(parentId: Int, childId: Int): String {
        val msg = execute("DELETE", "/api/v1/admin/users/$parentId/children/$childId")
            .optString("message", "已取消绑定。")
        cache.remove(cacheUserId(), "admin:users")
        return msg
    }

    suspend fun adminLearning(
        date: String? = null,
        userId: Int? = null,
        force: Boolean = false,
    ): LearningData {
        val key = "admin:learning:${date.orEmpty()}:${userId ?: 0}"
        return cachedGet(key, force, LearningData::from) {
            execute(
                "GET", "/api/v1/admin/learning",
                query = mapOf("date" to date, "user_id" to userId?.toString()),
            )
        }
    }

    fun currentBaseUrl(): String = baseUrl

    fun currentBaseUrlDisplay(): String = stripScheme(baseUrl)

    fun saveBaseUrl(url: String) {
        baseUrl = normalizeBaseUrl(url)
        settingsPrefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"

        fun loadBaseUrl(context: Context, defaultUrl: String): String {
            val saved = context.getSharedPreferences("moci_settings", Context.MODE_PRIVATE)
                .getString(KEY_BASE_URL, null)
            return normalizeBaseUrl(saved ?: defaultUrl)
        }

        fun stripScheme(url: String): String {
            var u = url.trim()
            if (u.startsWith("https://")) u = u.removePrefix("https://")
            else if (u.startsWith("http://")) u = u.removePrefix("http://")
            return u.trimEnd('/')
        }

        fun normalizeBaseUrl(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "http://$u"
            }
            return u.trimEnd('/')
        }
    }
}
