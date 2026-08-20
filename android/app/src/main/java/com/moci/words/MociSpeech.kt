package com.moci.words

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 英文语音识别，封装系统 SpeechRecognizer（朗读检查用）。
 * 同一时间只允许一次识别；结果通过回调返回候选文本列表。
 */
class MociSpeech(private val context: Context) {

    interface Callback {
        fun onStart()
        fun onResults(alternatives: List<String>)
        fun onError(code: String)
        fun onEnd()
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean
        get() = Build.VERSION.SDK_INT < 31 || SpeechRecognizer.isRecognitionAvailable(context)

    val isListening: Boolean
        get() = recognizer != null

    fun start(lang: String = "en-US", maxAlternatives: Int = 5, callback: Callback) {
        cancel()
        val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (rec == null) {
            callback.onError("service-not-available")
            callback.onEnd()
            return
        }
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callback.onStart()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                callback.onError(mapError(error))
                cleanup()
                callback.onEnd()
            }

            override fun onResults(results: Bundle?) {
                val texts = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                callback.onResults(texts)
                cleanup()
                callback.onEnd()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlternatives.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { rec.startListening(intent) }.onFailure {
            callback.onError("service-not-available")
            cleanup()
            callback.onEnd()
        }
    }

    fun cancel() {
        main.post {
            recognizer?.cancel()
            cleanup()
        }
    }

    private fun cleanup() {
        recognizer?.destroy()
        recognizer = null
    }

    fun destroy() {
        main.post { cleanup() }
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
