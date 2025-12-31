package com.niki914.demo

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.niki914.composebase.ComposeMVIViewModel
import com.niki914.s3ss10n.ChatPair
import com.niki914.s3ss10n.ChatSession
import com.niki914.s3ss10n.chat.AIContent
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toolbase.ToolManager
import com.niki914.s3ss10n.util.ConfigBuilder
import com.zephyr.provider.Zephyr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ChatState(
    val pairs: List<ChatPair>,
    val isGenerating: Boolean,
    val config: ConfigBuilder
)

sealed interface ChatIntent {
    data class Send(val msg: String) : ChatIntent
    data class SetConfig(
        val block: (ConfigBuilder.() -> Unit)
    ) : ChatIntent

    data object NewRoom : ChatIntent
}

sealed interface ChatEffect {
    data object ConfigUnset : ChatEffect
    data class ErrorOccurred(val message: String) : ChatEffect
    data object NewRoomCreated : ChatEffect
}

class ChatViewModel
    : ComposeMVIViewModel<ChatIntent, ChatState, ChatEffect>(), ChatSession.Callback {

    private val toolManager = ToolManager().apply {
        registerTool<DemoToastModel>()
//       or: registerTool(DemoToastModel())
    }
    private val chatSession = ChatSession().apply {
        callback = this@ChatViewModel
    }
    private var updateJob: Job? = null

    val uiState: ChatState
        @Composable
        get() = uiStateFlow.collectAsStateWithLifecycle().value

    override fun initUiState(): ChatState {
        return ChatState(
            pairs = emptyList(),
            isGenerating = false,
            config = ConfigBuilder()
        )
    }

    private fun newRoom() = viewModelScope.launch {
        chatSession.reset()
        updateJob?.cancel()
        sendEffect(ChatEffect.NewRoomCreated)
        updateState {
            copy(
                pairs = emptyList(),
                isGenerating = false
            )
        }
    }

    private fun updatePairs() {
        updateJob?.cancel()
        updateJob = viewModelScope.launch(Dispatchers.Default) {
            val history = chatSession.getHistory()
            val last = history.lastOrNull() ?: return@launch

            // 性能优化，但是从触发时机来看应该不会的，又不是轮询
            if (last == currentState.pairs.lastOrNull())
                return@launch

            updateState {
                copy(
                    pairs = history,
                    isGenerating = (last.state == ChatPair.RoundState.Generating || last.state == ChatPair.RoundState.Pending)
                )
            }
        }
    }

    override suspend fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.Send -> {
                chatSession.sendMessage(intent.msg)
            }

            is ChatIntent.SetConfig -> {
                chatSession.updateConfig {
                    intent.block(this)
                    tools = (tools ?: emptyList()) + toolManager.descriptions
                }
                chatSession.preConnect()
            }

            is ChatIntent.NewRoom -> newRoom()
        }
    }

    // --- --- --- ---

    override fun onConfigInvalid() {
        sendEffect(ChatEffect.ConfigUnset)
    }

    override fun onStarted() {
        updatePairs()
    }

    override fun onUpdated() {
        updatePairs()
    }

    override fun onContent(aiContent: AIContent) {
        updatePairs()
    }

    override fun onError(message: String, cause: Throwable?) {
        updatePairs()
        sendEffect(ChatEffect.ErrorOccurred(message))
    }

    override suspend fun onToolCall(toolCall: ToolCall): Message.Tool {
        updatePairs()

        val result = toolManager.exec(
            toolCall = toolCall,
            appParams = mapOf(
                "application" to Zephyr.application // TODO
            )
        )

        return Message.Tool(
            toolCallId = toolCall.id!!,
            name = toolCall.function!!.name!!,
            content = result
        )
    }

    override fun onCompleted(isSuccess: Boolean, cause: Throwable?) {
        updatePairs()
    }
}