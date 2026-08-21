package com.moci.words

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 英文语音识别（朗读检查）。
 * SpeechRecognizer 必须在主线程创建/使用；不能在 listener 回调里同步 destroy，
 * 否则会出现点了「开始朗读」立刻失败、或识别器被自己取消的情况。
 */
class MociSpeech(private val appContext: Context) {

    interface Callback {
        fun onStart()
        fun onResults(alternatives: List<String>)
        fun onError(code: String)
        fun onEnd()
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null
    @Volatile private var cancelled = false
    @Volatile private var busyRetries = 0

    fun isAvailable(context: Context = appContext): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /** 系统语音输入面板（部分国产机没有应用内 RecognitionService，用这个更稳）。 */
    fun recognizeIntent(lang: String = "en-US", maxAlternatives: Int = 5): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlternatives.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请朗读这个单词")
        }

    fun start(context: Context, lang: String = "en-US", maxAlternatives: Int = 5, callback: Callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(context, lang, maxAlternatives, callback) }
            return
        }
        stopInternal(notify = false)
        cancelled = false
        busyRetries = 0
        this.callback = callback
        bindAndListen(context, lang, maxAlternatives, callback)
    }

    private fun bindAndListen(context: Context, lang: String, maxAlternatives: Int, cb: Callback) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cb.onError("service-not-available")
            cb.onEnd()
            return
        }
        val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
        if (rec == null) {
            cb.onError("service-not-available")
            cb.onEnd()
            return
        }
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!cancelled) deliver { it.onStart() }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (cancelled) {
                    finishQuietly()
                    return
                }
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetries < 1) {
                    busyRetries += 1
                    main.postDelayed({
                        if (!cancelled) bindAndListen(context, lang, maxAlternatives, cb)
                    }, 350)
                    return
                }
                val code = mapError(error)
                main.post {
                    if (cancelled) {
                        finishQuietly()
                        return@post
                    }
                    destroyRecognizer()
                    cb.onError(code)
                    cb.onEnd()
                    if (callback === cb) callback = null
                }
            }

            override fun onResults(results: Bundle?) {
                if (cancelled) {
                    finishQuietly()
                    return
                }
                val texts = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                main.post {
                    if (cancelled) {
                        finishQuietly()
                        return@post
                    }
                    destroyRecognizer()
                    cb.onResults(texts)
                    cb.onEnd()
                    if (callback === cb) callback = null
                }
            }
        })
        val intent = recognizeIntent(lang, maxAlternatives)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { rec.startListening(intent) }.onFailure {
            destroyRecognizer()
            cb.onError("service-not-available")
            cb.onEnd()
        }
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel() }
            return
        }
        cancelled = true
        stopInternal(notify = true)
    }

    private fun stopInternal(notify: Boolean) {
        val cb = callback
        destroyRecognizer()
        callback = null
        if (notify) {
            cb?.onEnd()
        }
    }

    private fun finishQuietly() {
        main.post {
            destroyRecognizer()
            val cb = callback
            callback = null
            cb?.onEnd()
        }
    }

    private fun destroyRecognizer() {
        val rec = recognizer
        recognizer = null
        rec?.setRecognitionListener(null)
        runCatching { rec?.stopListening() }
        runCatching { rec?.cancel() }
        runCatching { rec?.destroy() }
    }

    private fun deliver(block: (Callback) -> Unit) {
        val cb = callback ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) block(cb)
        else main.post { if (!cancelled) callback?.let(block) }
    }

    fun destroy() {
        cancel()
    }

    private fun mapError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_CLIENT -> "aborted"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "network"
        SpeechRecognizer.ERROR_NO_MATCH -> "no-match"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        else -> "unknown"
    }
}
