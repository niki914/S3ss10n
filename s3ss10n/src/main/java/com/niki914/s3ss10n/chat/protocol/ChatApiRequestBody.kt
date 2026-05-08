package com.niki914.s3ss10n.chat.protocol

import com.google.gson.annotations.SerializedName
import com.niki914.s3ss10n.chat.protocol.beans.Message

/**
 * Request body for the Chat Completions API.
 */
data class ChatApiRequestBody(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<Message>,
    @SerializedName("tools") val tools: List<ToolDefinition>? = null, // 工具列表，可为空
    @SerializedName("temperature") val temperature: Float? = null
) {
    @SerializedName("stream")
    val stream: Boolean = true
}