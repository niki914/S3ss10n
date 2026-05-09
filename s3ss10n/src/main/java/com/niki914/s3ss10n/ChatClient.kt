package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.ChatEvent
import com.niki914.s3ss10n.chat.ChatService
import com.niki914.s3ss10n.chat.protocol.ChatApiRequestBody
import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.chat.protocol.beans.system
import com.niki914.s3ss10n.net.OkhttpClientManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Thrown when baseUrl or modelName is not set to a valid value.
 */
class ConfigInvalidException() :
    IllegalAccessException("Config is invalid. Set BaseUrl and Model first!")

/**
 * A low-level client for streaming Chat Completions over SSE.
 *
 * For a higher-level API with history and tool calling coordination, use ChatSession.
 */
class ChatClient(
    private val configSupplier: () -> SessionConfig
) {
    private val clientManager = OkhttpClientManager(configSupplier)

    private val service by lazy {
        ChatService(
            client = clientManager.okHttpClient
        )
    }

    private val systemMessage: Message?
        get() {
            val prompt = configSupplier().systemPrompt
            return if (prompt.isNullOrBlank()) null else system(
                prompt
            )
        }

    fun preConnect() = service.preConnect()

    /**
     * Returns whether the current configuration is valid enough to start a request.
     */
    fun isConfigValid(): Boolean {
        val config = configSupplier()
        return config.endpoint.isHTTPProtocol() && config.model.isNotBlank() // 密钥不作限制，可能有什么 Ollama 之类的
    }

    private fun String.isHTTPProtocol(): Boolean {
        return startsWith("http://") || startsWith("https://")
    }

    /**
     * Starts a streaming request with the given messages.
     */
    fun sendMessages(
        messages: List<Message>,
        includeSystemPrompt: Boolean = true
    ): Flow<ChatEvent> {
        val prefix = if (includeSystemPrompt) systemMessage else null
        return performStream(listOf(prefix) + messages)
    }

    private fun performStream(messages: List<Message?>): Flow<ChatEvent> {
        if (!isConfigValid()) {
            val cause = ConfigInvalidException()
            return flowOf(
                ChatEvent.Start,
                ChatEvent.Error(
                    msg = cause.message!!,
                    cause = cause
                ),
                ChatEvent.Complete(
                    isSuccess = false,
                    cause = cause
                )
            )
        }

        val config = configSupplier()
        return service.newChat(
            requestBody = ChatApiRequestBody(
                model = config.model,
                messages = messages.filterNotNull(),
                tools = config.buildToolDefinitions().ifEmpty { null },
                temperature = config.temperature
            )
        )
    }
}
