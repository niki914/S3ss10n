package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.ChatEvent
import com.niki914.s3ss10n.chat.ChatService
import com.niki914.s3ss10n.chat.protocol.ChatApiRequestBody
import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.chat.protocol.beans.system
import com.niki914.s3ss10n.net.OkhttpClientManager
import com.niki914.s3ss10n.util.ConfigBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A low-level client for streaming Chat Completions over SSE.
 *
 * For a higher-level API with history and tool calling coordination, use ChatSession.
 */
class ChatClient(
    baseUrl: String,
    apiKey: String,
    modelName: String,
    prompt: String? = null,
    tools: List<ToolDefinition>? = null
) {
    private val clientManager = OkhttpClientManager()
    internal val config: Config
        get() = clientManager.config

    private val service by lazy {
        ChatService(
            client = clientManager.okHttpClient
        )
    }

    init {
        clientManager.updateConfig {
            this.baseUrl = baseUrl
            this.apiKey = apiKey
            this.modelName = modelName
            this.prompt = prompt
            this.tools = tools
        }
    }

    private val systemMessage: Message?
        get() {
            val prompt = clientManager.config.prompt
            return if (prompt.isNullOrBlank()) null else system(
                prompt
            )
        }

    fun preConnect() = service.preConnect()

    /**
     * Returns whether the current configuration is valid enough to start a request.
     */
    fun isConfigValid(): Boolean {
        return config.baseUrl.isHTTPProtocol() && config.modelName.isNotBlank() // 密钥不作限制，可能有什么 Ollama 之类的
    }

    private fun String.isHTTPProtocol(): Boolean {
        return startsWith("http://") || startsWith("https://")
    }

    fun updateConfig(block: ConfigBuilder.() -> Unit) {
        clientManager.updateConfig(block)
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

        return service.newChat(
            requestBody = ChatApiRequestBody(
                model = config.modelName,
                messages = messages.filterNotNull(),
                tools = config.tools
            )
        )
    }
}