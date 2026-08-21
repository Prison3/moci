package com.moci.words

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 英文朗读，封装系统 TextToSpeech。
 * [speakingText] 为当前正在朗读的文本（用于界面高亮），朗读结束回到 null。
 */
class MociTts(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false

    private val _speakingText = MutableStateFlow<String?>(null)
    val speakingText: StateFlow<String?> = _speakingText

    init {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.US
                engine?.setSpeechRate(0.9f)
            }
        }
        tts = engine
    }

    /** 朗读一段英文；自动打断上一段。 */
    fun speak(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val engine = tts ?: return
        if (!ready) return
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speakingText.value = value
            }

            override fun onDone(utteranceId: String?) {
                if (_speakingText.value == value) _speakingText.value = null
            }

            @Deprecated("deprecated")
            override fun onError(utteranceId: String?) {
                if (_speakingText.value == value) _speakingText.value = null
            }
        })
        engine.language = Locale.US
        engine.setSpeechRate(0.9f)
        engine.speak(value, TextToSpeech.QUEUE_FLUSH, null, "moci-${value.hashCode()}")
    }

    fun stop() {
        tts?.stop()
        _speakingText.value = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _speakingText.value = null
    }
}
