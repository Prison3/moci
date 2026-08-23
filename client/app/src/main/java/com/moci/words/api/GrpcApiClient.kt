package com.moci.words.api

import android.net.Uri
import com.moci.words.grpc.ApiInvokeRequest
import com.moci.words.grpc.ApiServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 通过 gRPC ApiService.Invoke 调用全部 /api/v1 接口（含登录）。
 */
class GrpcApiClient(
    private val host: String,
    private val port: Int,
) {
    private val channelLazy = lazy {
        OkHttpChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .build()
    }
    private val channel: ManagedChannel by channelLazy

    fun shutdown() {
        if (channelLazy.isInitialized() && !channel.isShutdown) {
            channel.shutdown()
        }
    }

    suspend fun invoke(
        method: String,
        path: String,
        session: String?,
        csrfToken: String?,
        bodyJson: String?,
        query: Map<String, String?>,
    ): GrpcApiResult = withContext(Dispatchers.IO) {
        val builder = ApiInvokeRequest.newBuilder()
            .setMethod(method.uppercase())
            .setPath(path)
        session?.let { builder.session = it }
        csrfToken?.let { builder.csrfToken = it }
        bodyJson?.let { builder.bodyJson = it }
        query.filterValues { it != null }.forEach { (k, v) -> builder.putQuery(k, v!!) }

        val resp = runCatching {
            ApiServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(20, TimeUnit.SECONDS)
                .invoke(builder.build())
        }.getOrElse {
            throw ApiException("无法连接服务器，请检查网络。", "network")
        }

        GrpcApiResult(
            ok = resp.ok,
            error = resp.error,
            message = resp.message,
            bodyJson = resp.bodyJson,
            httpStatus = resp.httpStatus,
            session = resp.session.takeIf { it.isNotBlank() },
            csrfToken = resp.csrfToken.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        fun hostFromBaseUrl(baseUrl: String): String {
            val uri = Uri.parse(baseUrl.trim())
            return uri.host ?: "127.0.0.1"
        }

        /** 兼容旧版 HTTP 端口配置（5000/5002），自动映射到 gRPC 默认端口。 */
        fun portFromBaseUrl(baseUrl: String, defaultGrpcPort: Int): Int {
            val uri = Uri.parse(baseUrl.trim())
            val p = uri.port
            if (p == -1) return defaultGrpcPort
            if (p == 5000 || p == 5002) return defaultGrpcPort
            return p
        }
    }
}

data class GrpcApiResult(
    val ok: Boolean,
    val error: String,
    val message: String,
    val bodyJson: String,
    val httpStatus: Int,
    val session: String?,
    val csrfToken: String?,
)
