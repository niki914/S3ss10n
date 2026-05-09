package com.niki914.s3ss10n.protocol.openai

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
internal data class OpenAIChatRequestBody(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<OpenAIMessage>,
    @SerializedName("tools") val tools: List<ToolDefinition>? = null,
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("stream") val stream: Boolean = true
)

@Keep
internal sealed class OpenAIMessage(@SerializedName("role") val role: String) {
    abstract val content: String?

    @Keep
    data class System(@SerializedName("content") override val content: String) : OpenAIMessage("system")

    @Keep
    data class User(@SerializedName("content") override val content: String) : OpenAIMessage("user")

    @Keep
    data class Assistant(
        @SerializedName("content") override val content: String?,
        @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null
    ) : OpenAIMessage("assistant")

    @Keep
    data class Tool(
        @SerializedName("tool_call_id") val toolCallId: String,
        @SerializedName("name") val name: String,
        @SerializedName("content") override val content: String
    ) : OpenAIMessage("tool")
}

@Keep
data class ToolDefinition(
    @SerializedName("function") val function: FunctionTool
) {
    @SerializedName("type")
    val type: String = "function"
}

@Keep
data class FunctionTool(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: FunctionParameters
)

@Keep
data class FunctionParameters(
    @SerializedName("type") val type: String,
    @SerializedName("properties") val properties: Map<String, PropertyDefinition>,
    @SerializedName("required") val required: List<String>? = null
)

@Keep
data class PropertyDefinition(
    @SerializedName("type") val type: String,
    @SerializedName("description") val description: String
)

@Keep
internal data class OpenAIChatResponseFrame(
    @SerializedName("choices") val choices: List<Choice?>?
)

@Keep
internal data class Choice(
    @SerializedName("delta") val delta: Delta?
)

@Keep
internal data class Delta(
    @SerializedName("content") val content: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall?>?
)

@Keep
internal data class ToolCall(
    @SerializedName("id") val id: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("function") val function: FunctionCall?
)

@Keep
internal data class FunctionCall(
    @SerializedName("name") val name: String?,
    @SerializedName("arguments") val arguments: String?
)
