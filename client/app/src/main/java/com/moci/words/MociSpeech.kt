package com.moci.words

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地离线英文语音识别（Vosk）。
 * 模型放在 assets/model-en-us，首次启动解压到应用私有目录。
 *
 * 注意：SpeechService 带 timeout 的 startListening 在超时时只回调 [RecognitionListener.onTimeout]，
 * **不会**给出 final result。因此这里用不带超时的 startListening，到时主动 stop() 以拿到结果。
 */
class MociSpeech(private val appContext: Context) {

    interface Callback {
        fun onStart()
        fun onResults(alternatives: List<String>)
        fun onError(code: String)
        fun onEnd()
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var model: Model? = null
    @Volatile private var modelError: String? = null
    @Volatile private var loading = false

    private var speechService: SpeechService? = null
    private var callback: Callback? = null
    private var timeoutRunnable: Runnable? = null
    private var expectedNormalized: String = ""
    private val cancelled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    /** 会话中收集到的假设（partial / intermediate / final） */
    private val heard = linkedSetOf<String>()

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        ensureModel()
    }

    /** 模型已就绪时可离线识别。 */
    fun isAvailable(context: Context = appContext): Boolean = model != null

    fun isLoading(): Boolean = loading && model == null

    fun modelErrorMessage(): String? = modelError

    fun ensureModel() {
        if (model != null || loading) return
        loading = true
        modelError = null
        Log.i(TAG, "Unpacking Vosk model from assets/$ASSET_MODEL …")
        StorageService.unpack(
            appContext,
            ASSET_MODEL,
            "model",
            { unpacked ->
                model = unpacked
                loading = false
                modelError = null
                Log.i(TAG, "Vosk model ready")
            },
            { exc ->
                loading = false
                modelError = exc.message ?: "模型加载失败"
                Log.e(TAG, "Vosk model unpack failed", exc)
            },
        )
    }

    /**
     * 开始听写。[expectedTerm] 若提供，会用语法约束提高单词命中率。
     * [timeoutMs] 到时主动 stop，取 final result（默认 6 秒）。
     */
    fun start(
        context: Context,
        expectedTerm: String? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        callback: Callback,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(context, expectedTerm, timeoutMs, callback) }
            return
        }
        stopInternal(notify = false)
        cancelled.set(false)
        finished.set(false)
        heard.clear()
        expectedNormalized = normalizeHeard(expectedTerm.orEmpty())
        this.callback = callback

        val ready = model
        if (ready == null) {
            ensureModel()
            val err = when {
                isLoading() -> "model-loading"
                modelError != null -> "service-not-available"
                else -> "service-not-available"
            }
            Log.w(TAG, "start aborted: model not ready ($err)")
            callback.onError(err)
            callback.onEnd()
            this.callback = null
            return
        }

        try {
            val recognizer = buildRecognizer(ready, expectedTerm)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            Log.i(TAG, "Listening start term=${expectedTerm.orEmpty()} timeout=${timeoutMs}ms")
            callback.onStart()

            val started = service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    collectHypothesis(hypothesis)
                }

                override fun onResult(hypothesis: String?) {
                    collectHypothesis(hypothesis)
                }

                override fun onFinalResult(hypothesis: String?) {
                    collectHypothesis(hypothesis)
                    finishWithHeard()
                }

                override fun onError(e: Exception?) {
                    if (cancelled.get() || !finished.compareAndSet(false, true)) return
                    Log.e(TAG, "Vosk recognition error", e)
                    clearTimeout()
                    main.post {
                        teardownService()
                        val cb = callback
                        this@MociSpeech.callback = null
                        if (!cancelled.get()) {
                            cb.onError(mapException(e))
                        }
                        cb.onEnd()
                    }
                }

                override fun onTimeout() {
                    // 不用带 timeout 的 API；若仍收到则按已收集结果收尾
                    Log.w(TAG, "Unexpected onTimeout; finishing with heard=${heard.toList()}")
                    finishWithHeard()
                }
            })

            if (!started) {
                Log.e(TAG, "SpeechService.startListening returned false")
                teardownService()
                callback.onError("busy")
                callback.onEnd()
                this.callback = null
                return
            }

            val stopAt = Runnable {
                if (cancelled.get() || finished.get()) return@Runnable
                Log.i(TAG, "Timeout → stop() for final result; partial heard=${heard.toList()}")
                // stop() 会触发 onFinalResult
                runCatching { speechService?.stop() }
            }
            timeoutRunnable = stopAt
            main.postDelayed(stopAt, timeoutMs.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk", e)
            clearTimeout()
            teardownService()
            callback.onError(mapException(e))
            callback.onEnd()
            this.callback = null
        }
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel() }
            return
        }
        cancelled.set(true)
        stopInternal(notify = true)
    }

    fun destroy() {
        cancel()
        synchronized(lock) {
            runCatching { model?.close() }
            model = null
        }
    }

    private fun collectHypothesis(raw: String?) {
        val texts = parseHypotheses(raw).map { cleanHeard(it) }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return
        synchronized(heard) {
            heard.addAll(texts)
        }
        Log.d(TAG, "Heard += $texts (want=$expectedNormalized)")
        if (expectedNormalized.isNotEmpty()) {
            val candidates = heardCandidates(synchronized(heard) { heard.toList() })
            val hit = candidates.any { hypothesisMatches(it, expectedNormalized, loose = false) }
            if (hit) {
                Log.i(TAG, "Early match for expected=$expectedNormalized")
                finishWithHeard()
            }
        }
    }

    private fun finishWithHeard() {
        if (cancelled.get() || !finished.compareAndSet(false, true)) return
        clearTimeout()
        val texts = synchronized(heard) {
            heard.map { cleanHeard(it) }.filter { it.isNotEmpty() }.distinct()
        }
        val candidates = heardCandidates(texts)
        val matched = expectedNormalized.isNotEmpty() &&
            candidates.any { hypothesisMatches(it, expectedNormalized, loose = true) }
        Log.i(
            TAG,
            "Finish results=$candidates want=$expectedNormalized matched=$matched",
        )
        // 已匹配时优先回传目标文本，方便服务端校验
        val out = if (matched) {
            listOf(expectedNormalized) + candidates.filter { it != expectedNormalized }
        } else {
            candidates
        }
        main.post {
            teardownService()
            val cb = callback
            this@MociSpeech.callback = null
            if (cancelled.get()) {
                cb?.onEnd()
                return@post
            }
            if (out.isEmpty()) {
                cb?.onError("no-match")
            } else {
                cb?.onResults(out)
            }
            cb?.onEnd()
        }
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun stopInternal(notify: Boolean) {
        val cb = callback
        finished.set(true)
        clearTimeout()
        // cancel() 不投递 final；stop() 会。取消时用 cancel。
        val svc = speechService
        speechService = null
        if (notify) {
            runCatching { svc?.cancel() }
            runCatching { svc?.shutdown() }
            callback = null
            cb?.onEnd()
        } else {
            runCatching { svc?.cancel() }
            runCatching { svc?.shutdown() }
            callback = null
        }
    }

    private fun teardownService() {
        clearTimeout()
        val svc = speechService
        speechService = null
        runCatching { svc?.stop() }
        runCatching { svc?.shutdown() }
    }

    private fun buildRecognizer(model: Model, expectedTerm: String?): Recognizer {
        val expanded = normalizeHeard(expectedTerm.orEmpty())
        val tokenCount = expanded.split(" ").count { it.isNotEmpty() }
        // 只有单词用语法约束；短语/例句一律用语言模型自由识别（语法会把长句听成 [unk] 或单个词）
        val recognizer = if (tokenCount == 1) {
            val grammar = JSONArray()
                .put(expanded)
                .put("[unk]")
                .toString()
            Recognizer(model, SAMPLE_RATE, grammar)
        } else {
            Recognizer(model, SAMPLE_RATE)
        }
        runCatching { recognizer.setMaxAlternatives(5) }
        runCatching { recognizer.setWords(true) }
        runCatching { recognizer.setPartialWords(true) }
        return recognizer
    }

    private fun parseHypotheses(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val obj = JSONObject(raw)
            val texts = linkedSetOf<String>()
            obj.optString("text").trim().takeIf { it.isNotEmpty() }?.let { texts.add(it) }
            val alts = obj.optJSONArray("alternatives")
            if (alts != null) {
                for (i in 0 until alts.length()) {
                    alts.optJSONObject(i)
                        ?.optString("text")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { texts.add(it) }
                }
            }
            val partial = obj.optString("partial").trim()
            if (partial.isNotEmpty()) texts.add(partial)
            // setWords 时可能只有 result 数组
            val words = obj.optJSONArray("result")
            if (words != null && words.length() > 0) {
                val joined = buildString {
                    for (i in 0 until words.length()) {
                        val w = words.optJSONObject(i)?.optString("word").orEmpty().trim()
                        if (w.isNotEmpty() && w != "[unk]") {
                            if (isNotEmpty()) append(' ')
                            append(w)
                        }
                    }
                }
                if (joined.isNotEmpty()) texts.add(joined)
            }
            texts.toList()
        } catch (_: Exception) {
            listOf(raw.trim()).filter { it.isNotEmpty() }
        }
    }

    private fun mapException(e: Exception?): String {
        val msg = e?.message.orEmpty().lowercase()
        return when {
            "permission" in msg -> "not-allowed"
            "microphone" in msg || "recorder" in msg -> "not-allowed"
            "init" in msg || "model" in msg -> "service-not-available"
            else -> "unknown"
        }
    }

    companion object {
        private const val TAG = "MociSpeech"
        private const val ASSET_MODEL = "model-en-us"
        private const val SAMPLE_RATE = 16000.0f
        private const val DEFAULT_TIMEOUT_MS = 6000

        private val APOSTROPHE_CONTRACTIONS = listOf(
            "won't" to "will not",
            "can't" to "can not",
            "don't" to "do not",
            "doesn't" to "does not",
            "didn't" to "did not",
            "isn't" to "is not",
            "aren't" to "are not",
            "wasn't" to "was not",
            "weren't" to "were not",
            "haven't" to "have not",
            "hasn't" to "has not",
            "hadn't" to "had not",
            "wouldn't" to "would not",
            "shouldn't" to "should not",
            "couldn't" to "could not",
            "let's" to "let us",
            "i'm" to "i am",
            "you're" to "you are",
            "we're" to "we are",
            "they're" to "they are",
            "it's" to "it is",
            "that's" to "that is",
            "what's" to "what is",
            "who's" to "who is",
            "where's" to "where is",
            "how's" to "how is",
            "he's" to "he is",
            "she's" to "she is",
            "there's" to "there is",
            "here's" to "here is",
        )

        /** Vosk 常把缩写听成无撇号形式。 */
        private val ASR_CONTRACTIONS = mapOf(
            "wont" to listOf("will", "not"),
            "cant" to listOf("can", "not"),
            "dont" to listOf("do", "not"),
            "doesnt" to listOf("does", "not"),
            "didnt" to listOf("did", "not"),
            "isnt" to listOf("is", "not"),
            "arent" to listOf("are", "not"),
            "wasnt" to listOf("was", "not"),
            "werent" to listOf("were", "not"),
            "havent" to listOf("have", "not"),
            "hasnt" to listOf("has", "not"),
            "hadnt" to listOf("had", "not"),
            "wouldnt" to listOf("would", "not"),
            "shouldnt" to listOf("should", "not"),
            "couldnt" to listOf("could", "not"),
            "lets" to listOf("let", "us"),
            "im" to listOf("i", "am"),
            "youre" to listOf("you", "are"),
            "theyre" to listOf("they", "are"),
            "thats" to listOf("that", "is"),
            "whats" to listOf("what", "is"),
            "whos" to listOf("who", "is"),
            "wheres" to listOf("where", "is"),
            "hows" to listOf("how", "is"),
        )

        private val STOP_WORDS = setOf(
            "a", "an", "the", "to", "of", "in", "on", "at", "for", "and", "or", "is", "are",
            "am", "be", "was", "were", "do", "does", "did", "have", "has", "had",
        )

        /** 去掉 Vosk 的 [unk]，展开常见缩写，只留可匹配英文。 */
        fun cleanHeard(text: String): String = normalizeHeard(text)

        /** 撇号直接去掉，不拆词：let's → lets。用于短语法。 */
        fun compactHeard(text: String): String =
            squishHeard(
                text.lowercase()
                    .replace('\u2019', '\'')
                    .replace('\u2018', '\'')
                    .replace("[unk]", " ")
                    .replace(Regex("\\bunk\\b"), " ")
                    .replace("'", ""),
            )

        fun normalizeHeard(text: String): String {
            var value = text.lowercase()
                .replace('\u2019', '\'')
                .replace('\u2018', '\'')
                .replace("[unk]", " ")
                .replace(Regex("\\bunk\\b"), " ")
            for ((from, to) in APOSTROPHE_CONTRACTIONS.sortedByDescending { it.first.length }) {
                value = value.replace(from, to)
            }
            return squishHeard(value.replace("'", ""))
        }

        private fun squishHeard(text: String): String =
            text.replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** 从多次 partial 里取最有价值的候选，不要把所有 partial 拼成一长串噪声。 */
        fun heardCandidates(texts: List<String>): List<String> {
            val cleaned = texts.map { cleanHeard(it) }.filter { it.isNotEmpty() }.distinct()
            if (cleaned.isEmpty()) return emptyList()
            val byLen = cleaned.sortedByDescending { it.split(" ").size }
            val longest = byLen.first()
            val out = linkedSetOf<String>()
            out.add(longest)
            // 再保留词数接近最长句的其他候选（通常是 final 与 partial 之一）
            val bestCount = longest.split(" ").size
            cleaned.filter { it.split(" ").size >= bestCount - 1 }.forEach { out.add(it) }
            return out.toList()
        }

        private fun wordsSimilar(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length < 3 || b.length < 3) return false
            // 常见 ASR 混淆
            val pairs = setOf(
                "to" to "two", "two" to "to", "too" to "to",
                "a" to "the", "an" to "a",
                "hear" to "here", "there" to "their",
            )
            if ((a to b) in pairs || (b to a) in pairs) return true
            if (a.length >= 4 && b.length >= 4) {
                val dist = editDistance(a, b)
                if (dist <= 1) return true
                if (a.length >= 5 && b.length >= 5 && a.take(4) == b.take(4)) return true
            }
            return false
        }

        private fun editDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val dp = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                var prev = dp[0]
                dp[0] = i + 1
                for (j in b.indices) {
                    val tmp = dp[j + 1]
                    dp[j + 1] = if (a[i] == b[j]) {
                        prev
                    } else {
                        minOf(prev, dp[j], dp[j + 1]) + 1
                    }
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        fun hypothesisMatches(
            spoken: String,
            expectedNormalized: String,
            loose: Boolean = true,
        ): Boolean {
            val said = cleanHeard(spoken)
            val want = cleanHeard(expectedNormalized)
            if (said.isEmpty() || want.isEmpty()) return false
            val saidTokens = said.split(" ").filter { it.isNotEmpty() }
            val wantTokens = want.split(" ").filter { it.isNotEmpty() }
            if (wantTokens.isEmpty() || saidTokens.isEmpty()) return false
            for (saidVar in tokenVariants(saidTokens)) {
                for (wantVar in tokenVariants(wantTokens)) {
                    if (tokensMatch(saidVar, wantVar, loose)) return true
                }
            }
            return false
        }

        private fun tokenVariants(tokens: List<String>): List<List<String>> {
            val expanded = tokens.flatMap { ASR_CONTRACTIONS[it] ?: listOf(it) }
            return if (expanded == tokens) listOf(tokens) else listOf(tokens, expanded)
        }

        private fun tokensMatch(saidTokens: List<String>, wantTokens: List<String>, loose: Boolean): Boolean {
            if (saidTokens == wantTokens) return true

            // 连续子序列命中（允许词近似）
            for (i in 0..saidTokens.size - wantTokens.size) {
                if (wantTokens.indices.all { j ->
                        wordsSimilar(saidTokens[i + j], wantTokens[j])
                    }
                ) {
                    return true
                }
            }

            // 按顺序覆盖目标词（允许近似匹配）
            var wi = 0
            for (token in saidTokens) {
                if (wi < wantTokens.size && wordsSimilar(token, wantTokens[wi])) wi += 1
            }
            if (wi == wantTokens.size) return true
            if (!loose || wantTokens.size < 2) return false

            val coverage = wi.toFloat() / wantTokens.size
            val need = when {
                wantTokens.size >= 6 -> 0.60f
                wantTokens.size >= 4 -> 0.65f
                else -> 0.75f
            }
            if (coverage + 1e-6f >= need && wi >= 2) return true

            val wantCore = wantTokens.filter { it !in STOP_WORDS }
            val saidCore = saidTokens.filter { it !in STOP_WORDS }
            if (wantCore.size >= 2) {
                var ci = 0
                for (token in saidCore) {
                    if (ci < wantCore.size && wordsSimilar(token, wantCore[ci])) ci += 1
                }
                if (ci == wantCore.size) return true
                val coreNeed = if (wantCore.size >= 4) 0.60f else 0.70f
                if (ci >= maxOf(2, (wantCore.size * coreNeed).toInt())) return true
            }
            return false
        }
    }
}
