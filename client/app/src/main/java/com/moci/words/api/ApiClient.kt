package com.moci.words.api

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.moci.words.db.ApiCache
import com.moci.words.db.MociDatabase
import com.moci.words.notify.MociNotifier
import com.moci.words.sync.SyncForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
 * /api/v1 客户端。认证基于 Flask session，持久化到 SharedPreferences。
 * 全部 API（含登录）经 gRPC ApiService.Invoke 转发；实时推送走 SyncService 双向流。
 * GET 响应写入本地 Room（moci_cache.db），默认 30 分钟内复用，避免切 Tab 反复打服务器。
 */
class ApiClient(context: Context, defaultBaseUrl: String, private val grpcPort: Int) {

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

    var onSettingsUpdated: (suspend (User) -> Unit)? = null

    var onWordsUpdated: (suspend (action: String) -> Unit)? = null

    @Volatile
    var wordsSyncKey: Long = 0
        private set

    private var grpcSync: GrpcSyncClient? = null
    private var syncScope: CoroutineScope? = null

    private fun grpcHost(): String = GrpcApiClient.hostFromBaseUrl(baseUrl)

    private fun effectiveGrpcPort(): Int =
        GrpcApiClient.portFromBaseUrl(baseUrl, grpcPort)

    private var grpcApiClient: GrpcApiClient? = null
    private var grpcApiEndpoint: String? = null

    private fun grpcEndpointKey(): String = "${grpcHost()}:${effectiveGrpcPort()}"

    private fun grpcApi(): GrpcApiClient {
        val key = grpcEndpointKey()
        val existing = grpcApiClient
        if (existing != null && grpcApiEndpoint == key) return existing
        existing?.shutdown()
        return GrpcApiClient(grpcHost(), effectiveGrpcPort()).also {
            grpcApiClient = it
            grpcApiEndpoint = key
        }
    }

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
        put("know_pos", if (u.knowPos) 1 else 0)
        put("know_phonetic", if (u.knowPhonetic) 1 else 0)
        put("reward_minutes", u.rewardMinutes)
        put("word_levels", JSONArray(u.wordLevels))
        put("avatar", u.avatar)
    }

    private fun cacheUserId(): Int = cachedUser?.id ?: 0

    /** 手动清空本地 API 缓存（下拉重试 / 切账号时用）。 */
    suspend fun invalidateLocalCache() = withContext(Dispatchers.IO) {
        val uid = cacheUserId()
        if (uid != 0) cache.clearUser(uid) else cache.clearAll()
    }

    /** 启动 gRPC 双向流，接收服务端推送（设置变更、词库变更）并弹出系统通知。 */
    fun startSync(scope: CoroutineScope) {
        if (!hasSession) {
            stopSync()
            return
        }
        stopSync()
        syncScope = scope
        grpcSync = GrpcSyncClient(
            host = grpcHost(),
            port = effectiveGrpcPort(),
            sessionProvider = { prefs.getString("session", null) },
            onSettingsUpdated = { user ->
                if (cachedUser?.isLearner == true || user.isLearner) {
                    invalidateLearnerProgress()
                    cachedUser = user
                    onSettingsUpdated?.invoke(user)
                }
                MociNotifier.notifySettingsUpdated(appContext)
            },
            onWordsUpdated = { action ->
                invalidateWordsLibrary()
                onWordsUpdated?.invoke(action)
                MociNotifier.notifyWordsUpdated(appContext, action)
            },
            onUnauthorized = {
                cachedUser?.username?.let { rememberUsername(it) }
                clearSession()
                syncScope?.launch { clearCacheAfterSessionGone() }
                listener?.onUnauthorized()
                SyncForegroundService.stop(appContext)
            },
        )
        grpcSync?.start(scope)
    }

    fun stopSync() {
        grpcSync?.stop()
        grpcSync = null
        syncScope = null
    }

    /** 学生端：拉取最新账号设置并清掉依赖设置的本地缓存。 */
    suspend fun syncLearnerSettings(): User {
        invalidateLearnerProgress()
        return me()
    }

    private suspend fun invalidateParentProfile() {
        val uid = cacheUserId()
        if (uid == 0) return
        cache.removePrefix(uid, "profile")
        cache.removePrefix(uid, "home:parent:")
    }

    private suspend fun invalidateLearnerProgress() {
        val uid = cacheUserId()
        if (uid == 0) return
        cache.removePrefix(uid, "home:learner")
        cache.remove(uid, "review:cards")
        cache.removePrefix(uid, "profile")
        cache.removePrefix(uid, "study-day:")
        cache.removePrefix(uid, "my-words:")
        cache.removePrefix(uid, "home:parent:")
    }

    suspend fun invalidateWordsLibrary() {
        val uid = cacheUserId()
        if (uid != 0) {
            cache.removePrefix(uid, "words:")
            cache.remove(uid, "home:admin")
            cache.removePrefix(uid, "home:learner")
            cache.remove(uid, "review:cards")
            cache.removePrefix(uid, "study-day:")
            cache.removePrefix(uid, "my-words:")
            cache.removePrefix(uid, "home:parent:")
            cache.removePrefix(uid, "profile")
        }
        wordsSyncKey++
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
        val result = grpcApi().invoke(
            method = method,
            path = path,
            session = prefs.getString("session", null),
            csrfToken = csrfToken,
            bodyJson = body?.toString(),
            query = query,
        )
        result.session?.let { value ->
            prefs.edit().putString("session", value).apply()
        }
        result.csrfToken?.let { csrfToken = it }
        val json = runCatching { JSONObject(result.bodyJson.ifBlank { "{}" }) }.getOrElse {
            throw ApiException("服务器响应异常（${result.httpStatus}）。", "bad_response", result.httpStatus)
        }
        if (!result.ok || !json.optBoolean("ok")) {
            val code = result.error.ifBlank { json.optString("error", "error") }
            if (result.httpStatus == 401 || code == "unauthorized") {
                cachedUser?.username?.let { rememberUsername(it) }
                clearSession()
                clearCacheAfterSessionGone()
                withContext(Dispatchers.Main) { listener?.onUnauthorized() }
            }
            throw ApiException(
                result.message.ifBlank { json.optString("message", "请求失败，请重试。") },
                code,
                result.httpStatus,
            )
        }
        json
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
    // 应用版本

    suspend fun appInfo(): AppReleaseInfo {
        val json = execute("GET", "/api/v1/app/info")
        val info = AppReleaseInfo.from(json)
        return info.copy(downloadUrl = resolveDownloadUrl(info.downloadUrl))
    }

    /** gRPC 转发时服务端可能返回 127.0.0.1 或相对路径，统一按当前服务器地址解析。 */
    private fun resolveDownloadUrl(raw: String): String {
        val path = when {
            raw.isBlank() -> "/download/moci.apk"
            raw.startsWith("/") -> raw
            else -> {
                val uri = Uri.parse(raw)
                if (isLoopbackHost(uri.host)) {
                    uri.path?.takeIf { it.isNotBlank() } ?: "/download/moci.apk"
                } else {
                    return raw
                }
            }
        }
        return downloadHttpBaseUrl().trimEnd('/') + path
    }

    private fun isLoopbackHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        return host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" ||
            host == "::1"
    }

    /** APK 走 Flask HTTP 端口（5000），与 gRPC 端口（50051）区分。 */
    private fun downloadHttpBaseUrl(): String {
        val uri = Uri.parse(baseUrl.trim())
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: "127.0.0.1"
        val httpPort = when (val port = uri.port) {
            -1 -> 5000
            50051, 50052 -> 5000
            else -> port
        }
        return "$scheme://$host:$httpPort"
    }

    // ------------------------------------------------------------------
    // 认证

    suspend fun login(username: String, password: String): User {
        val json = execute("POST", "/api/v1/auth/login", JSONObject().apply {
            put("username", username)
            put("password", password)
        })
        saveAuth(json)
        if (!hasSession) {
            throw ApiException("登录失败：服务器未返回会话，请确认服务端已更新。", "no_session")
        }
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
        stopSync()
        SyncForegroundService.stop(appContext)
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

    suspend fun homeLearner(force: Boolean = false): LearnerHome {
        val levels = cachedUser?.wordLevels?.joinToString(",") ?: ""
        return cachedGet("home:learner:$levels", force, LearnerHome::from) {
            execute("GET", "/api/v1/home")
        }
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
                    "pos=${json.opt("pos")} phonetic=${json.opt("phonetic")} " +
                    "cards=${json.optJSONArray("cards")?.length()}",
            )
            json
        }

    suspend fun submitReview(
        wordId: Int,
        rating: String,
        spelling: String? = null,
        spoken: String? = null,
        spokenPhrase: String? = null,
        spokenExample: String? = null,
        posTags: List<String>? = null,
        phonetic: String? = null,
    ): JSONObject {
        val json = execute("POST", "/api/v1/review/$wordId", JSONObject().apply {
            put("rating", rating)
            if (spelling != null) put("spelling", spelling)
            if (spoken != null) put("spoken", spoken)
            if (spokenPhrase != null) put("spoken_phrase", spokenPhrase)
            if (spokenExample != null) put("spoken_example", spokenExample)
            if (posTags != null) put("pos_tags", JSONArray(posTags))
            if (phonetic != null) put("phonetic", phonetic)
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

    suspend fun profile(force: Boolean = false): ProfileData {
        val levels = cachedUser?.wordLevels?.joinToString(",") ?: ""
        return cachedGet("profile:$levels", force, ProfileData::from) {
            execute("GET", "/api/v1/profile")
        }
    }

    suspend fun saveAvatar(avatar: String): User {
        val json = execute("POST", "/api/v1/profile/avatar", JSONObject().apply {
            put("avatar", avatar)
        })
        val user = User.from(json.getJSONObject("user"))
        cachedUser = user
        val uid = cacheUserId()
        if (uid != 0) cache.removePrefix(uid, "profile")
        return user
    }

    suspend fun saveAvatarImage(jpegBytes: ByteArray): User {
        val json = execute("POST", "/api/v1/profile/avatar", JSONObject().apply {
            put("avatar_image", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
        })
        val user = User.from(json.getJSONObject("user"))
        cachedUser = user
        val uid = cacheUserId()
        if (uid != 0) cache.removePrefix(uid, "profile")
        return user
    }

    suspend fun saveChildSettings(
        childId: Int,
        dailyWords: Int,
        dailyReview: Int,
        knowSpeak: Boolean,
        knowSpell: Boolean,
        knowPos: Boolean,
        knowPhonetic: Boolean,
        rewardMinutes: Int,
        wordLevels: List<String> = listOf("primary", "junior", "senior", "college"),
    ): String {
        val json = execute("POST", "/api/v1/profile/child/$childId/settings", JSONObject().apply {
            put("daily_words", dailyWords)
            put("daily_review", dailyReview)
            put("know_speak", knowSpeak)
            put("know_spell", knowSpell)
            put("know_pos", knowPos)
            put("know_phonetic", knowPhonetic)
            put("reward_minutes", rewardMinutes)
            put("word_levels", JSONArray(wordLevels))
        })
        invalidateParentProfile()
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

    suspend fun words(
        q: String = "",
        level: String = "",
        force: Boolean = false,
    ): Pair<List<Word>, Int> =
        cachedGet(wordsCacheKey(q, level), force, ::parseWordsResponse) {
            execute(
                "GET",
                "/api/v1/words",
                query = mapOf(
                    "q" to q.ifBlank { null },
                    "level" to level.ifBlank { null },
                ),
            )
        }

    /** 仅读本地 Room 缓存，用于词库页首屏即时展示。 */
    suspend fun wordsCached(q: String = "", level: String = ""): Pair<List<Word>, Int>? =
        withContext(Dispatchers.IO) {
            val body = cache.read(cacheUserId(), wordsCacheKey(q, level)) ?: return@withContext null
            parseWordsResponse(JSONObject(body))
        }

    private fun wordsCacheKey(q: String, level: String) = "words:${q.trim()}:${level.trim()}"

    private fun parseWordsResponse(json: JSONObject): Pair<List<Word>, Int> {
        val arr = json.optJSONArray("words") ?: JSONArray()
        return (0 until arr.length()).map { Word.from(arr.getJSONObject(it)) } to json.optInt("total")
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
        put("level", w.level)
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

    suspend fun rankMastery(force: Boolean = false): MasteryRankData =
        cachedGet("rank:mastery", force, MasteryRankData::from) {
            execute("GET", "/api/v1/rank/mastery")
        }

    suspend fun rankGames(game: String, force: Boolean = false): GameRankData =
        cachedGet("rank:games:$game", force, GameRankData::from) {
            execute("GET", "/api/v1/rank/games", query = mapOf("game" to game))
        }

    suspend fun submitGameScore(game: String, score: Int) {
        execute("POST", "/api/v1/game-scores", JSONObject().apply {
            put("game", game)
            put("score", score)
        })
        val uid = cacheUserId()
        if (uid != 0) cache.removePrefix(uid, "rank:games:")
    }

    fun currentBaseUrl(): String = baseUrl

    fun currentBaseUrlDisplay(): String = stripScheme(baseUrl)

    fun saveBaseUrl(url: String) {
        baseUrl = normalizeBaseUrl(url)
        settingsPrefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
        grpcApiClient?.shutdown()
        grpcApiClient = null
        grpcApiEndpoint = null
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
