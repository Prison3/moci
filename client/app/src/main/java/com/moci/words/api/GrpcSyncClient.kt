package com.moci.words.api

import android.net.Uri
import android.util.Log
import com.moci.words.grpc.ClientMessage
import com.moci.words.grpc.Hello
import com.moci.words.grpc.Ping
import com.moci.words.grpc.ServerMessage
import com.moci.words.grpc.SyncServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * gRPC 双向流：维持长连接，接收服务端推送（设置变更、词库变更等）。
 */
class GrpcSyncClient(
    private val host: String,
    private val port: Int,
    private val sessionProvider: () -> String?,
    private val onSettingsUpdated: (suspend (User) -> Unit)? = null,
    private val onWordsUpdated: (suspend () -> Unit)? = null,
    private val onUnauthorized: () -> Unit,
) {
    private var streamJob: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        streamJob = scope.launch {
            while (isActive) {
                try {
                    runStream(this)
                } catch (e: Exception) {
                    Log.w(TAG, "gRPC stream ended: ${e.message}")
                }
                delay(RECONNECT_MS)
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    private suspend fun runStream(scope: CoroutineScope) = suspendCancellableCoroutine { cont ->
        val session = sessionProvider()?.takeIf { it.isNotBlank() }
        if (session == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }

        val channel: ManagedChannel = OkHttpChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .build()

        var requestObserver: StreamObserver<ClientMessage>? = null
        var closed = false
        var pingJob: Job? = null

        fun closeStream() {
            if (closed) return
            closed = true
            pingJob?.cancel()
            runCatching { requestObserver?.onCompleted() }
            channel.shutdown()
            if (cont.isActive) cont.resume(Unit)
        }

        val responseObserver = object : StreamObserver<ServerMessage> {
            override fun onNext(value: ServerMessage) {
                when (value.bodyCase) {
                    ServerMessage.BodyCase.READY -> Unit
                    ServerMessage.BodyCase.PONG -> Unit
                    ServerMessage.BodyCase.SETTINGS_UPDATED -> {
                        val u = value.settingsUpdated.user
                        val user = User(
                            id = u.id,
                            username = u.username,
                            role = u.role,
                            status = u.status,
                            dailyWords = u.dailyWords,
                            dailyReview = u.dailyReview,
                            knowSpeak = u.knowSpeak,
                            knowSpell = u.knowSpell,
                            knowPos = u.knowPos,
                            knowPhonetic = u.knowPhonetic,
                        )
                        scope.launch {
                            runCatching { onSettingsUpdated?.invoke(user) }
                        }
                    }
                    ServerMessage.BodyCase.WORDS_UPDATED -> {
                        scope.launch {
                            runCatching { onWordsUpdated?.invoke() }
                        }
                    }
                    ServerMessage.BodyCase.ERROR -> {
                        if (value.error.code == "unauthorized") {
                            onUnauthorized()
                        }
                        closeStream()
                    }
                    else -> Unit
                }
            }

            override fun onError(t: Throwable) {
                closeStream()
            }

            override fun onCompleted() {
                closeStream()
            }
        }

        requestObserver = SyncServiceGrpc.newStub(channel).connect(responseObserver)
        requestObserver.onNext(
            ClientMessage.newBuilder()
                .setHello(Hello.newBuilder().setSession(session))
                .build(),
        )

        pingJob = scope.launch {
            while (isActive && !closed) {
                delay(PING_MS)
                if (closed) break
                runCatching {
                    requestObserver?.onNext(
                        ClientMessage.newBuilder().setPing(Ping.newBuilder()).build(),
                    )
                }
            }
        }

        cont.invokeOnCancellation { closeStream() }
    }

    companion object {
        private const val TAG = "GrpcSync"
        private const val RECONNECT_MS = 3_000L
        private const val PING_MS = 30_000L

        fun hostFromBaseUrl(baseUrl: String): String {
            val uri = Uri.parse(baseUrl.trim())
            return uri.host ?: "127.0.0.1"
        }
    }
}
