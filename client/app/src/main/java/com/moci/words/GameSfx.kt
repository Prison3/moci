package com.moci.words

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 奖励小游戏音效：用短促正弦波，不依赖资源文件。
 */
object GameSfx {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "moci-game-sfx").apply { isDaemon = true }
    }

    private const val SAMPLE_RATE = 22050
    private const val VOLUME = 0.32

    fun tap() = play(880, 45)
    fun flip() = play(660, 55)
    fun match() = play(1046, 90)
    fun miss() = play(220, 140)
    fun hit() = play(988, 70)
    fun eat() = play(784, 80)
    fun shoot() = play(1200, 35)
    fun boom() = play(160, 180)
    fun hurt() = play(300, 160)
    fun go() = play(523, 100)
    fun early() = play(200, 200)
    fun win() {
        executor.execute {
            beep(523, 80)
            beep(659, 80)
            beep(784, 120)
        }
    }
    fun lose() = play(180, 280)
    fun start() = play(440, 70)

    private fun play(freqHz: Int, durationMs: Int) {
        executor.execute { beep(freqHz, durationMs) }
    }

    private fun beep(freqHz: Int, durationMs: Int) {
        val n = SAMPLE_RATE * durationMs / 1000
        if (n <= 0) return
        val buf = ShortArray(n)
        val attack = min(n / 8, SAMPLE_RATE / 50)
        val release = min(n / 4, SAMPLE_RATE / 20)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = when {
                i < attack -> i.toDouble() / attack
                i > n - release -> (n - i).toDouble() / release
                else -> 1.0
            }
            buf[i] = (sin(2.0 * PI * freqHz * t) * Short.MAX_VALUE * VOLUME * env)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, n * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(buf, 0, n)
            track.play()
            Thread.sleep((durationMs + 20).toLong())
        } catch (_: Exception) {
            // 忽略音效失败，不影响游戏
        } finally {
            runCatching {
                track.stop()
                track.release()
            }
        }
    }
}
