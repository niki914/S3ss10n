package com.niki914.s3ss10n.chat.protocol.beans

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.niki914.s3ss10n.chat.protocol.ToolCall

/**
 * Creates a system message.
 */
fun system(content: String): Message.System = Message.System(content)

/**
 * Creates a user message.
 */
fun user(content: String): Message.User = Message.User(content)

/**
 * A single message in a chat conversation.
 */
@Keep
sealed class Message(@SerializedName("role") val role: String) {
    abstract val content: String?

    @Keep
    data class System(@SerializedName("content") override val content: String) : Message("system")

    @Keep
    data class User(@SerializedName("content") override val content: String) : Message("user")

    @Keep
    data class Assistant(
        @SerializedName("content") override val content: String?,
        @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null
    ) : Message("assistant")

    @Keep
    data class Tool(
        @SerializedName("tool_call_id") val toolCallId: String,
        @SerializedName("name") val name: String,
        @SerializedName("content") override val content: String
    ) : Message("tool")
}