package com.niki914.s3ss10n.chat

import com.niki914.s3ss10n.chat.protocol.ChatApiRequestBody
import com.niki914.s3ss10n.net.sse.SseClient
import com.niki914.s3ss10n.util.gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

internal class ChatService(
    private val client: OkHttpClient
) {

    private val transformLayer = SseToChatTransformLayer()
    private val sseClient = SseClient()

    fun preConnect() = runCatching {
        val request = initRequest()
            .get()
            .build()

        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {}
            }
        )
    }

    fun newChat(requestBody: ChatApiRequestBody): Flow<ChatEvent> = flow {
        val requestBodyJson = gson.toJson(requestBody)
        val body = requestBodyJson.toRequestBody("application/json".toMediaType())

        val request = initRequest()
            .post(body)
            .build()

        val call = client.newCall(request)
        sseClient.execute<ChatEvent>(
            call = call,
            collector = this,
            onEvent = { event ->
                transformLayer.transformEvent(event)
            }
        )
    }

    private fun initRequest(): Request.Builder {
        return Request.Builder()
            .url("https://okhttp/interceptor/will/update/this/") // <--- 占位符，最后会被配置项动态修改
    }
}