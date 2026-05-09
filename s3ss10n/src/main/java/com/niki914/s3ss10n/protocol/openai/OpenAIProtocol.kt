package com.niki914.s3ss10n.protocol.openai

import android.util.Log
import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.ToolCallSpec
import com.niki914.s3ss10n.json.GsonJsonCodec
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.protocol.ChatProtocol
import com.niki914.s3ss10n.protocol.ProtocolEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// TODO(T7): replace try/catch with xTry
class OpenAIProtocol(
    private val codec: JsonCodec = GsonJsonCodec()
) : ChatProtocol {

    override fun withCodec(codec: JsonCodec): ChatProtocol {
        return OpenAIProtocol(codec)
    }

    override fun buildRequestBody(
        snapshot: SessionConfig,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): String {
        val messages = mutableListOf<OpenAIMessage>()
        snapshot.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { messages += OpenAIMessage.System(it) }
        history.forEach { turn ->
            messages += turn.toOpenAIMessage()
        }
        pendingUserInput
            ?.takeIf { it.isNotBlank() }
            ?.let { messages += OpenAIMessage.User(it) }
        return codec.encode(
            OpenAIChatRequestBody(
                model = snapshot.model,
                messages = messages,
                tools = snapshot.buildToolDefinitions().ifEmpty { null },
                temperature = snapshot.temperature
            )
        )
    }

    override fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent> = flow {
        val toolCallAccumulator = ToolCallAccumulator(codec)
        rawSseLines.collect { line ->
            val frame = codec.decode(line, OpenAIChatResponseFrame::class.java)
            if (frame == null) {
                Log.e("qwerqwer", "OpenAIProtocol.parseStream failed to decode frame")
                emit(ProtocolEvent.Error(RuntimeException("Failed to decode SSE frame"), SessionEvent.Stage.Parse))
                return@collect
            }

            val delta = frame.choices?.firstOrNull()?.delta ?: return@collect
            delta.content
                ?.takeIf { it.isNotEmpty() }
                ?.let { emit(ProtocolEvent.TextDelta(it)) }
            delta.tool_calls.orEmpty().forEach { toolCallDelta ->
                toolCallDelta ?: return@forEach
                val ready = toolCallAccumulator.push(toolCallDelta) ?: return@forEach
                emit(
                    ProtocolEvent.ToolCallReady(
                        callId = ready.callId,
                        toolName = ready.toolName,
                        argumentsJson = ready.argumentsJson
                    )
                )
            }
        }
        emit(ProtocolEvent.Completed)
    }

    override fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult {
        return ChatTurn.ToolResult(
            callId = callId,
            toolName = toolName,
            resultJson = resultJson
        )
    }

    private fun ChatTurn.toOpenAIMessage(): OpenAIMessage {
        return when (this) {
            is ChatTurn.System -> OpenAIMessage.System(content)
            is ChatTurn.User -> OpenAIMessage.User(content)
            is ChatTurn.Assistant -> OpenAIMessage.Assistant(
                content = content.ifEmpty { null },
                tool_calls = toolCalls.map { it.toOpenAIToolCall() }.ifEmpty { null }
            )
            is ChatTurn.ToolResult -> OpenAIMessage.Tool(
                tool_call_id = callId,
                name = toolName,
                content = resultJson
            )
        }
    }

    private fun ToolCallSpec.toOpenAIToolCall(): ToolCall {
        return ToolCall(
            id = callId,
            type = "function",
            function = FunctionCall(
                name = toolName,
                arguments = argumentsJson
            )
        )
    }
}

private class ToolCallAccumulator(
    private val codec: JsonCodec
) {
    private var currentActiveToolCall: PendingToolCall? = null

    fun push(toolCallDelta: ToolCall): ToolCallSpec? {
        if (toolCallDelta.id != null) {
            currentActiveToolCall = PendingToolCall(
                callId = toolCallDelta.id,
                toolName = toolCallDelta.function?.name.orEmpty(),
                argumentsBuilder = StringBuilder()
            )
        } else if (toolCallDelta.function?.name != null) {
            currentActiveToolCall = currentActiveToolCall?.copy(
                toolName = toolCallDelta.function.name
            )
        }

        toolCallDelta.function?.arguments?.let {
            currentActiveToolCall?.argumentsBuilder?.append(it)
        }

        val current = currentActiveToolCall ?: return null
        val argumentsJson = current.argumentsBuilder.toString()
        if (argumentsJson.isEmpty() || !isJson(argumentsJson)) {
            return null
        }

        currentActiveToolCall = null
        return ToolCallSpec(
            callId = current.callId,
            toolName = current.toolName,
            argumentsJson = argumentsJson
        )
    }

    private fun isJson(content: String): Boolean {
        return codec.decodeMap(content) != null
    }

    private data class PendingToolCall(
        val callId: String,
        val toolName: String,
        val argumentsBuilder: StringBuilder
    )
}
