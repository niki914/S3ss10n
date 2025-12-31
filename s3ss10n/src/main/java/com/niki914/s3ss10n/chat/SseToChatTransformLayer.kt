package com.niki914.s3ss10n.chat

import com.niki914.s3ss10n.chat.protocol.ChatApiResponseFrame
import com.niki914.s3ss10n.net.sse.SseEvent
import com.niki914.s3ss10n.util.ToolCallHandler
import com.niki914.s3ss10n.util.gson

internal class SseToChatTransformLayer {

    companion object {
        private const val PARSE_ERROR_MSG = "Failed to transform data to response frame"
    }

    val toolCallHandler = ToolCallHandler()

    fun transformEvent(event: SseEvent): List<ChatEvent> {
        val list = mutableListOf<ChatEvent>()

        when (event) {
            SseEvent.Start ->
                list.add(ChatEvent.Start)

            is SseEvent.Complete ->
                list.add(ChatEvent.Complete(event.isSuccess, event.cause))

            is SseEvent.Data ->
                list.addAll(transformData(event))

            is SseEvent.IOError ->
                list.add(
                    ChatEvent.Error(
                        msg = event.cause.message
                            ?: "Failed with ${event.cause.javaClass.simpleName ?: "unknown error"}",
                        cause = event.cause
                    )
                )

            is SseEvent.RequestFailedError ->
                list.add(
                    ChatEvent.Error(
                        msg = "Request failed with ${event.code}, body is: ${event.body}",
                        cause = null
                    )
                )
        }

        return list.toList()
    }

    private fun transformData(data: SseEvent.Data): List<ChatEvent> {
        val frame = transform<ChatApiResponseFrame>(data) ?: return listOf(
            ChatEvent.Error(
                msg = PARSE_ERROR_MSG,
                IllegalStateException(PARSE_ERROR_MSG)
            )
        )

        val list = mutableListOf<ChatEvent>()
        val delta = frame.choices?.getOrNull(0)?.delta

        delta?.content?.let { content ->
            if (content.isNotEmpty()) {
                list.add(ChatEvent.AI.text(content))
            }
        }

        delta?.toolCalls?.forEach { toolCallDelta ->
            toolCallDelta?.let {
                val toolCall = toolCallHandler.push(it) ?: return@let
                list.add(ChatEvent.ToolCallIntent(toolCall))
            }
        }

        return list.toList()
    }

    inline fun <reified T> transform(event: SseEvent): T? {
        try {
            if (event !is SseEvent.Data)
                return null
            return gson.fromJson(event.content, T::class.java)
        } catch (_: Exception) {
            return null
        }
    }
}