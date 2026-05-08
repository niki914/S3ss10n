package com.niki914.demo

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.niki914.composebase.ComposeMVIViewModel
import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.ToolCallKind
import kotlinx.coroutines.launch

data class ChatState(
    val isGenerating: Boolean,
    val config: SessionConfig
)

sealed interface ChatIntent {
    data class Send(val msg: String) : ChatIntent
    data class SetConfig(
        val block: (SessionConfig.() -> Unit)
    ) : ChatIntent
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
            is ChatIntent.SetConfig -> {
                intent.block(currentState.config)
                session = Session.open {
                    endpoint = currentState.config.endpoint
                    apiKey = currentState.config.apiKey
                    model = currentState.config.model
                    systemPrompt = currentState.config.systemPrompt
                    temperature = currentState.config.temperature

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
                                error("MCP not supported yet")
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
                            updateState { copy(isGenerating = true) }
                        }
                        is SessionEvent.TextDelta -> {
                            // UI updates via event stream
                        }
                        is SessionEvent.ToolRunning -> {
                            println("Tool running: ${event.toolName}")
                        }
                        is SessionEvent.ToolSucceeded -> {
                            println("Tool succeeded: ${event.toolName}")
                        }
                        is SessionEvent.ToolFailed -> {
                            println("Tool failed: ${event.toolName} - ${event.message}")
                        }
                        is SessionEvent.RoundCompleted -> {
                            updateState { copy(isGenerating = false) }
                        }
                        is SessionEvent.Error -> {
                            updateState { copy(isGenerating = false) }
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
                        copy(isGenerating = false)
                    }
                }
            }
        }
    }
}
