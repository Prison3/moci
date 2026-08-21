package com.moci.words

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 英文朗读，封装系统 TextToSpeech。
 * [speakingText] 为当前正在朗读的文本（用于界面高亮），朗读结束回到 null。
 */
class MociTts(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    @Volatile
    private var initFailed = false
    private var retriedAfterFail = false

    /** 引擎未就绪时暂存最近一次朗读请求，就绪后自动播。 */
    @Volatile
    private var pending: String? = null

    private val utteranceSeq = AtomicInteger(0)

    private val _speakingText = MutableStateFlow<String?>(null)
    val speakingText: StateFlow<String?> = _speakingText

    init {
        openEngine()
    }

    private fun openEngine() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(appContext) { status ->
            if (engine !== tts) return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                initFailed = true
                Log.e(TAG, "TextToSpeech init failed status=$status")
                return@TextToSpeech
            }
            val ok = configure(engine!!)
            ready = ok
            initFailed = !ok
            if (!ok) {
                Log.e(TAG, "TextToSpeech has no usable English voice")
                return@TextToSpeech
            }
            pending?.let { queued ->
                pending = null
                speak(queued)
            }
        }
        tts = engine
    }

    private fun configure(engine: TextToSpeech): Boolean {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setSpeechRate(0.9f)
        val locales = listOf(
            Locale.US,
            Locale.UK,
            Locale.ENGLISH,
            Locale.forLanguageTag("en"),
        )
        for (locale in locales) {
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.i(TAG, "TTS language=$locale result=$result")
                return true
            }
        }
        return false
    }

    /** 朗读一段英文；自动打断上一段。未就绪时会排队，就绪后补播。 */
    fun speak(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return

        val engine = tts
        if (engine == null || !ready) {
            pending = value
            if (initFailed && !retriedAfterFail) {
                retriedAfterFail = true
                runCatching { tts?.shutdown() }
                tts = null
                openEngine()
            }
            Log.w(TAG, "TTS not ready, queued “$value”")
            return
        }

        val utteranceId = "moci-${utteranceSeq.incrementAndGet()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                _speakingText.value = value
            }

            override fun onDone(id: String?) {
                if (_speakingText.value == value) _speakingText.value = null
            }

            @Deprecated("deprecated")
            override fun onError(id: String?) {
                if (_speakingText.value == value) _speakingText.value = null
            }

            override fun onError(id: String?, errorCode: Int) {
                Log.e(TAG, "TTS speak error code=$errorCode id=$id")
                if (_speakingText.value == value) _speakingText.value = null
            }
        })

        // 立刻高亮，避免等 onStart（部分机型回调偏晚或丢失）
        _speakingText.value = value

        val result = engine.speak(value, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR for “$value”")
            _speakingText.value = null
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
        _speakingText.value = null
    }

    fun shutdown() {
        pending = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        _speakingText.value = null
    }

    companion object {
        private const val TAG = "MociTts"
    }
}
