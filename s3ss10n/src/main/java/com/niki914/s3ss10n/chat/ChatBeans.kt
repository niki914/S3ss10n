package com.niki914.s3ss10n.chat

import com.niki914.s3ss10n.chat.protocol.ToolCall

/**
 * A streamed piece of assistant output.
 */
sealed interface AIContent {
    data class Text(val content: String) : AIContent
    data class Else(val raw: String) : AIContent

    fun toStr(): String {
        return when (this) {
            is Else -> raw
            is Text -> content
        }
    }
}

/**
 * Events emitted during a streaming chat request.
 */
sealed interface ChatEvent {
    data object Start : ChatEvent

    data class AI(val content: AIContent) : ChatEvent {
        companion object {
            fun text(content: String): AI {
                return AI(AIContent.Text(content))
            }
        }
    }

    data class ToolCallIntent(val toolCall: ToolCall) : ChatEvent

    data class Error(
        val msg: String,
        val cause: Throwable?
    ) : ChatEvent

    data class Complete(
        val isSuccess: Boolean,
        val cause: Throwable?
    ) : ChatEvent

    fun toStr(): String {
        return when (this) {
            is AI -> "AI Content: ${content.toStr()}"
            is Complete -> "Complete: is successful: $isSuccess"
            is Error -> "Error: $msg\n${cause?.stackTraceToString()}"
            Start -> "Start: Chat Api started"
            is ToolCallIntent -> "Tool-call Intent: ${toolCall.function?.name}"
        }
    }
}