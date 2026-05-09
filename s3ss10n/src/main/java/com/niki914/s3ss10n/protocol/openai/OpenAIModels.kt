package com.niki914.s3ss10n.protocol.openai

import androidx.annotation.Keep

@Keep
internal data class OpenAIChatRequestBody(
    val model: String,
    val messages: List<OpenAIMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Float? = null,
    val stream: Boolean = true
)

@Keep
internal sealed class OpenAIMessage(val role: String) {
    abstract val content: String?

    @Keep
    data class System(override val content: String) : OpenAIMessage("system")

    @Keep
    data class User(override val content: String) : OpenAIMessage("user")

    @Keep
    data class Assistant(
        override val content: String?,
        val tool_calls: List<ToolCall>? = null,
        val reasoning_content: String? = null
    ) : OpenAIMessage("assistant")

    @Keep
    data class Tool(
        val tool_call_id: String,
        val name: String,
        override val content: String
    ) : OpenAIMessage("tool")
}

@Keep
data class ToolDefinition(
    val function: FunctionTool
) {
    val type: String = "function"
}

@Keep
data class FunctionTool(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

@Keep
data class FunctionParameters(
    val type: String,
    val properties: Map<String, PropertyDefinition>,
    val required: List<String>? = null
)

@Keep
data class PropertyDefinition(
    val type: String,
    val description: String
)

@Keep
internal data class OpenAIChatResponseFrame(
    val choices: List<Choice?>?
)

@Keep
internal data class Choice(
    val delta: Delta?
)

@Keep
internal data class Delta(
    val content: String?,
    val reasoning_content: String?,
    val tool_calls: List<ToolCall?>?
)

@Keep
internal data class ToolCall(
    val id: String?,
    val type: String?,
    val function: FunctionCall?
)

@Keep
internal data class FunctionCall(
    val name: String?,
    val arguments: String?
)
