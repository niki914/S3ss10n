package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.AIContent
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toolbase.ToolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class SessionImpl(
    private val config: SessionConfig
) : Session, ChatSession.Callback {

    private var userOnEvent: ((SessionEvent) -> Unit)? = null
    private var currentInput: String = ""
    private val textAccumulator = StringBuilder()

    private val scope = CoroutineScope(SupervisorJob())

    private val toolManager = ToolManager()

    private val chatSession: ChatSession = ChatSession(
        baseUrl = config.endpoint,
        apiKey = config.apiKey,
        modelName = config.model,
        prompt = config.systemPrompt,
        tools = config.buildToolDefinitions().ifEmpty { null }
    ).apply {
        callback = this@SessionImpl
    }

    override suspend fun send(text: String, onEvent: (SessionEvent) -> Unit) {
        userOnEvent = onEvent
        currentInput = text
        applyConfig()
        chatSession.sendMessage(text)
    }

    override suspend fun getHistory(): List<ChatPair> = chatSession.getHistory()

    override suspend fun resetConversation() {
        chatSession.reset()
    }

    override suspend fun close() {
        scope.cancel()
    }

    private fun applyConfig() {
        chatSession.updateConfig {
            baseUrl = config.endpoint
            apiKey = config.apiKey
            modelName = config.model
            prompt = config.systemPrompt
            temperature = config.temperature
            readTimeout = config.readTimeoutSeconds
            connectTimeout = config.connectTimeoutSeconds
            writeTimeout = config.writeTimeoutSeconds
            tools = config.buildToolDefinitions().ifEmpty { null }
        }
    }

    // --- ChatSession.Callback implementation ---

    override fun onConfigInvalid() {
        userOnEvent?.invoke(
            SessionEvent.Error(
                stage = SessionEvent.Stage.Session,
                message = "Config is invalid. Set endpoint and model first."
            )
        )
    }

    override fun onStarted() {
        textAccumulator.clear()
        userOnEvent?.invoke(
            SessionEvent.RoundStarted(input = currentInput)
        )
    }

    override fun onUpdated() {
        // No-op for MVP
    }

    override fun onContent(aiContent: AIContent) {
        when (aiContent) {
            is AIContent.Text -> {
                textAccumulator.append(aiContent.content)
                userOnEvent?.invoke(
                    SessionEvent.TextDelta(
                        delta = aiContent.content,
                        fullText = textAccumulator.toString()
                    )
                )
            }
            is AIContent.Else -> { /* ignore */ }
        }
    }

    override fun onError(message: String, cause: Throwable?) {
        userOnEvent?.invoke(
            SessionEvent.Error(
                stage = SessionEvent.Stage.Transport,
                message = message,
                cause = cause
            )
        )
    }

    override suspend fun onToolCall(toolCall: ToolCall): Message.Tool {
        val request = buildToolCallRequest(toolCall)

        userOnEvent?.invoke(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = config.hooksBlock
        return if (hooks != null) {
            val result = request.hooks()
            if ("error" in result.content.lowercase()) {
                userOnEvent?.invoke(
                    SessionEvent.ToolFailed(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        message = result.content,
                        resultJson = result.content
                    )
                )
            } else {
                userOnEvent?.invoke(
                    SessionEvent.ToolSucceeded(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        resultJson = result.content
                    )
                )
            }
            result
        } else {
            userOnEvent?.invoke(
                SessionEvent.ToolFailed(
                    callId = request.id,
                    toolName = request.name,
                    kind = request.kind,
                    message = "No hooks configured",
                    resultJson = null
                )
            )
            Message.Tool(
                toolCallId = request.id,
                name = request.name,
                content = """{"error":"No hooks configured"}"""
            )
        }
    }

    override fun onCompleted(isSuccess: Boolean, cause: Throwable?) {
        if (isSuccess) {
            userOnEvent?.invoke(
                SessionEvent.RoundCompleted(fullText = textAccumulator.toString())
            )
        } else {
            userOnEvent?.invoke(
                SessionEvent.Error(
                    stage = SessionEvent.Stage.Session,
                    message = "Round failed",
                    cause = cause
                )
            )
        }
    }

    private fun buildToolCallRequest(toolCall: ToolCall): ToolCallRequest {
        return LocalToolCallRequest(
            toolCall = toolCall,
            toolManager = toolManager,
            appParams = config.buildAppParams()
        )
    }
}
