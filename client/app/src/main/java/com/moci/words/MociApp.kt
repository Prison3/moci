package com.moci.words

import android.app.Application
import com.moci.words.api.ApiClient
import com.moci.words.notify.MociNotifier

class MociApp : Application() {

    lateinit var api: ApiClient
        private set
    lateinit var tts: MociTts
        private set
    lateinit var speech: MociSpeech
        private set

    override fun onCreate() {
        super.onCreate()
        MociNotifier.ensureChannels(this)
        api = ApiClient(this, BuildConfig.BASE_URL, BuildConfig.GRPC_PORT)
        tts = MociTts(this)
        speech = MociSpeech(this)
    }

    override fun onTerminate() {
        tts.shutdown()
        speech.destroy()
        super.onTerminate()
    }
}
