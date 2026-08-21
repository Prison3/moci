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
        val texts = parseHypotheses(raw)
        if (texts.isEmpty()) return
        synchronized(heard) {
            heard.addAll(texts)
        }
        Log.d(TAG, "Heard += $texts → $heard")
    }

    private fun finishWithHeard() {
        if (cancelled.get() || !finished.compareAndSet(false, true)) return
        clearTimeout()
        val texts = synchronized(heard) { heard.toList() }
        Log.i(TAG, "Finish results=$texts")
        main.post {
            teardownService()
            val cb = callback
            this@MociSpeech.callback = null
            if (cancelled.get()) {
                cb?.onEnd()
                return@post
            }
            if (texts.isEmpty()) {
                cb?.onError("no-match")
            } else {
                cb?.onResults(texts)
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
        val term = expectedTerm?.trim()?.lowercase().orEmpty()
        val recognizer = if (term.isNotEmpty()) {
            val grammar = JSONArray()
                .put(term)
                .put("[unk]")
                .toString()
            Recognizer(model, SAMPLE_RATE, grammar)
        } else {
            Recognizer(model, SAMPLE_RATE)
        }
        runCatching { recognizer.setMaxAlternatives(3) }
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
        private const val SAMPLE_RATE = 16_000.0f
        private const val DEFAULT_TIMEOUT_MS = 6_000
    }
}
