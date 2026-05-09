package com.niki914.s3ss10n.net

import com.niki914.s3ss10n.xTry
import com.niki914.s3ss10n.xTrySuspend
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OkHttpEngine : HttpEngine {
    private val client = OkHttpClient.Builder().build()

    override fun stream(request: HttpRequest): Flow<String> = callbackFlow {
        xTrySuspend("OkHttpEngine.stream", onError = { e ->
            close(e)
        }) {
            val callClient = client.newBuilder()
                .connectTimeout(request.timeoutMs.connectMs, TimeUnit.MILLISECONDS)
                .readTimeout(request.timeoutMs.readMs, TimeUnit.MILLISECONDS)
                .writeTimeout(request.timeoutMs.writeMs, TimeUnit.MILLISECONDS)
                .build()

            val requestBody = request.body?.toRequestBody("application/json".toMediaTypeOrNull())
            val okHttpRequest = Request.Builder()
                .url(request.url)
                .method(request.method, requestBody)
                .headers(request.headers.toHeaders())
                .build()

            val call = callClient.newCall(okHttpRequest)
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    close(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { currentResponse ->
                        if (!currentResponse.isSuccessful) {
                            val responseBody = currentResponse.body?.string()?.trim().orEmpty()
                            val bodySuffix = if (responseBody.isEmpty()) {
                                ""
                            } else {
                                ", body=$responseBody"
                            }
                            close(
                                IllegalStateException(
                                    "HTTP ${currentResponse.code} ${currentResponse.message}$bodySuffix"
                                )
                            )
                            return
                        }
                        val body = currentResponse.body ?: run {
                            close(IllegalStateException("Response body is null"))
                            return
                        }
                        body.charStream().buffered().useLines { lines ->
                            lines.forEach { line ->
                                if (!line.startsWith("data:")) {
                                    return@forEach
                                }
                                val payload = line.substring(5).trim()
                                if (payload == "[DONE]") {
                                    close()
                                    return@useLines
                                }
                                trySend(payload)
                            }
                        }
                        close()
                    }
                }
            })

            awaitClose {
                call.cancel()
            }
        }
    }

    override fun close() {
        xTry("OkHttpEngine.close") {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
