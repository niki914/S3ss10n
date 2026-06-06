package com.niki914.demo

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.niki914.composebase.ComposeMVIViewModel
import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.SessionProtocols
import com.niki914.s3ss10n.ToolCallKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class McpServerEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class DemoTurn(
    val userMsg: String,
    val aiText: String,
    val isGenerating: Boolean = false,
    val isError: Boolean = false
)

data class ChatState(
    val pairs: List<DemoTurn> = emptyList(),
    val isGenerating: Boolean = false,
    val selectedProtocol: String = "OpenAI",
    val config: SessionConfig,
    val mcpServers: List<McpServerEntry> = emptyList()
)

sealed interface ChatIntent {
    data class Send(val msg: String) : ChatIntent
    data class SetConfig(
        val block: (SessionConfig.() -> Unit)
    ) : ChatIntent
    data class SetProtocol(val protocol: String) : ChatIntent
    data class AddMcpServer(val name: String, val url: String, val headersJson: String) : ChatIntent
    data class RemoveMcpServer(val id: String) : ChatIntent
    data object NewRoom : ChatIntent
}

sealed interface ChatEffect {
    data object ConfigUnset : ChatEffect
    data class ErrorOccurred(val message: String) : ChatEffect
    data object NewRoomCreated : ChatEffect
}

class ChatViewModel
    : ComposeMVIViewModel<ChatIntent, ChatState, ChatEffect>() {

    private var session: Session? = null

    val uiState: ChatState
        @Composable
        get() = uiStateFlow.collectAsStateWithLifecycle().value

    override fun initUiState(): ChatState {
        return ChatState(
            isGenerating = false,
            config = SessionConfig()
        )
    }

    override suspend fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SetProtocol -> {
                updateState { copy(selectedProtocol = intent.protocol) }
            }

            is ChatIntent.AddMcpServer -> {
                val headers = runCatching { parseHeadersJson(intent.headersJson) }.getOrElse { error ->
                    sendEffect(ChatEffect.ErrorOccurred("Invalid MCP headers JSON: ${error.message}"))
                    return
                }
                updateState {
                    copy(
                        mcpServers = mcpServers + McpServerEntry(
                            name = intent.name,
                            url = intent.url,
                            headers = headers
                        )
                    )
                }
            }

            is ChatIntent.RemoveMcpServer -> {
                updateState {
                    copy(mcpServers = mcpServers.filter { it.id != intent.id })
                }
            }

            is ChatIntent.SetConfig -> {
                intent.block(currentState.config)
                val configBlock: SessionConfig.Builder.() -> Unit = {
                    endpoint = currentState.config.endpoint
                    apiKey = currentState.config.apiKey
                    model = currentState.config.model
                    systemPrompt = currentState.config.systemPrompt
                    temperature = currentState.config.temperature
                    maxTokens = currentState.config.maxTokens

                    hooks {
                        when (kind) {
                            ToolCallKind.Local -> {
                                if (name == "send_toast") {
                                    ok("""{"shown":true}""")
                                } else {
                                    error("Unknown tool: $name")
                                }
                            }
                            is ToolCallKind.Mcp -> {
                                delegate()
                            }
                        }
                    }

                    localTools {
                        add("send_toast") {
                            description = "Send a Toast notification to the user's device."
                            string("message") {
                                description = "The message you'd like to tell the user."
                                required = true
                            }
                        }
                    }

                    mcp {
                        currentState.mcpServers.forEach { server ->
                            add(server.name) {
                                headers = server.headers
                                http { url = server.url }
                            }
                        }
                    }
                }
                session = when (currentState.selectedProtocol) {
                    "Anthropic" -> Session.open<SessionProtocols.Anthropic>(configBlock)
                    "DeepSeek" -> Session.open<SessionProtocols.DeepSeek>(configBlock)
                    else -> Session.open<SessionProtocols.OpenAI>(configBlock)
                }
            }

            is ChatIntent.Send -> {
                val s = session ?: run {
                    sendEffect(ChatEffect.ConfigUnset)
                    return
                }
                updateState { copy(isGenerating = true) }
                s.send(intent.msg) { event ->
                    when (event) {
                        is SessionEvent.RoundStarted -> {
                            updateState { 
                                val newTurn = DemoTurn(userMsg = event.input, aiText = "", isGenerating = true)
                                copy(isGenerating = true, pairs = pairs + newTurn) 
                            }
                        }
                        is SessionEvent.TextDelta -> {
                            updateState {
                                val currentPairs = pairs.toMutableList()
                                if (currentPairs.isNotEmpty()) {
                                    val last = currentPairs.last()
                                    currentPairs[currentPairs.size - 1] = last.copy(aiText = event.fullText)
                                }
                                copy(pairs = currentPairs)
                            }
                        }
                        is SessionEvent.ToolRunning -> {
                            updateState {
                                val currentPairs = pairs.toMutableList()
                                if (currentPairs.isNotEmpty()) {
                                    val last = currentPairs.last()
                                    currentPairs[currentPairs.size - 1] = last.copy(aiText = last.aiText + "\n[Tool running: ${event.toolName}]")
                                }
                                copy(pairs = currentPairs)
                            }
                        }
                        is SessionEvent.ToolSucceeded -> {
                            updateState {
                                val currentPairs = pairs.toMutableList()
                                if (currentPairs.isNotEmpty()) {
                                    val last = currentPairs.last()
                                    currentPairs[currentPairs.size - 1] = last.copy(aiText = last.aiText + "\n[Tool succeeded: ${event.toolName}]")
                                }
                                copy(pairs = currentPairs)
                            }
                        }
                        is SessionEvent.ToolFailed -> {
                            updateState {
                                val currentPairs = pairs.toMutableList()
                                if (currentPairs.isNotEmpty()) {
                                    val last = currentPairs.last()
                                    currentPairs[currentPairs.size - 1] = last.copy(aiText = last.aiText + "\n[Tool failed: ${event.toolName}]")
                                }
                                copy(pairs = currentPairs)
                            }
                        }
                        is SessionEvent.RoundCompleted -> {
                            updateState {
                                val currentPairs = pairs.toMutableList()
                                if (currentPairs.isNotEmpty()) {
                                    val last = currentPairs.last()
                                    currentPairs[currentPairs.size - 1] = last.copy(aiText = event.fullText, isGenerating = false)
                                }
                                copy(isGenerating = false, pairs = currentPairs)
                            }
                        }
                        is SessionEvent.Error -> {
                            updateState {
                                val currentPairs = pairs.toMutableList()
                                if (currentPairs.isNotEmpty()) {
                                    val last = currentPairs.last()
                                    currentPairs[currentPairs.size - 1] = last.copy(isGenerating = false, isError = true)
                                }
                                copy(isGenerating = false, pairs = currentPairs)
                            }
                            sendEffect(ChatEffect.ErrorOccurred(event.message))
                        }
                    }
                }
            }

            is ChatIntent.NewRoom -> {
                viewModelScope.launch {
                    session?.resetConversation()
                    sendEffect(ChatEffect.NewRoomCreated)
                    updateState {
                        copy(isGenerating = false, pairs = emptyList())
                    }
                }
            }
        }
    }

    private fun parseHeadersJson(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()
        val json = JSONObject(trimmed)
        return json.keys().asSequence().associateWith { key ->
            json.getString(key)
        }
    }
}
