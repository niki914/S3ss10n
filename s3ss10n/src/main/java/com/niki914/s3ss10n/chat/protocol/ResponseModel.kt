package com.niki914.s3ss10n.chat.protocol

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 * A single choice in a streamed response.
 */
@Keep
internal data class Choice(
    @SerializedName("delta") val delta: Delta?
)

/**
 * Incremental content in streaming.
 */
@Keep
internal data class Delta(
    @SerializedName("content") val content: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall?>?
)

/**
 * A tool call request emitted by the model.
 */
@Keep
data class ToolCall(
    @SerializedName("id") val id: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("function") val function: FunctionCall?
)

/**
 * Function call detail of a tool call.
 */
@Keep
data class FunctionCall(
    @SerializedName("name") val name: String?,
    @SerializedName("arguments") val arguments: String?
)