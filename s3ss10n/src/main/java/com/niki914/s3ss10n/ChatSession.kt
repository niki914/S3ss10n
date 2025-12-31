package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.AIContent
import com.niki914.s3ss10n.chat.ChatEvent
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.chat.protocol.beans.user
import com.niki914.s3ss10n.util.ConfigBuilder
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import com.zephyr.log.logE
import com.zephyr.provider.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A session-level streaming chat wrapper.
 *
 * It keeps the conversation history, streams assistant output, and coordinates tool calling.
 */
class ChatSession(
    baseUrl: String,
    apiKey: String,
    modelName: String,
    prompt: String? = null,
    tools: List<ToolDefinition>? = null
) {

    constructor() : this("", "", "", null, null)

    /**
     * Receives lifecycle events and streamed content from a running session.
     *
     * When a tool call is requested, return a Message.Tool that contains the tool result.
     * The returned toolCallId should match the incoming ToolCall.id.
     */
    interface Callback {
        fun onConfigInvalid()
        fun onStarted()
        fun onUpdated()
        fun onContent(aiContent: AIContent)
        fun onError(message: String, cause: Throwable?)
        suspend fun onToolCall(toolCall: ToolCall): Message.Tool
        fun onCompleted(isSuccess: Boolean, cause: Throwable?)
    }

    private val client = ChatClient(
        baseUrl,
        apiKey,
        modelName,
        prompt,
        tools
    )

    var callback: Callback? = null
    private val scope = CoroutineScope(SupervisorJob())
    private var currJob: Job? = null
    private val chatMutex = Mutex()

    private val historyKeeper = HistoryKeeper()
    private val toolCallWaiter = ToolCallWaiter(scope) { toolCall ->
        callback?.onToolCall(toolCall)
            ?: throw IllegalStateException("SESSION: Callback unset for tool-call!")
    }

    /**
     * Cancels the current round and clears all history.
     */
    suspend fun reset() {
        cleanUpCurrWork()
        historyKeeper.clear()
        callback?.onUpdated()
    }

    private suspend fun cleanUpCurrWork() {
        currJob?.cancel()
        currJob?.join()
        toolCallWaiter.cancelAndClear(join = true)
    }

    /**
     * Sends a user message and starts a new streaming round.
     */
    fun sendMessage(userMsg: String) = scope.launch {
        chatMutex.withLock {
            cleanUpCurrWork()
            currJob = launch {
                sendMessage(user(userMsg))
            }
        }
    }

    private suspend fun sendMessage(message: Message.User?) {
        message?.let { // 支持不加消息直接请求
            historyKeeper.addUserMsg(message)
        }

        client.sendMessages(
            messages = historyKeeper.getMessages(),
            includeSystemPrompt = true
        ).collect { chatEvent ->
            when (chatEvent) {
                ChatEvent.Start -> {
                    historyKeeper.setLatestPairState(
                        ChatPair.RoundState.Generating
                    )
                    logE(TAG, "SESSION: Started")
                    callback?.onStarted()
                }

                is ChatEvent.AI -> {
                    when (val aIContent = chatEvent.content) {
                        is AIContent.Else -> {
                            logE(TAG, "SESSION: Unimplemented case: $aIContent")
                        }

                        is AIContent.Text -> {
                            historyKeeper.appendTextToLastAIMsg(aIContent.content)
                            callback?.onContent(aIContent)
                        }
                    }
                }

                is ChatEvent.ToolCallIntent -> {
                    historyKeeper.appendToolCallToLastAIMsg(chatEvent.toolCall)
                    logE(TAG, "SESSION: Inject tool-call: ${chatEvent.toolCall.function?.name}")
                    toolCallWaiter.enqueue(chatEvent.toolCall)
                    callback?.onToolCall(chatEvent.toolCall)
                }

                is ChatEvent.Error -> {
                    if (chatEvent.cause is ConfigInvalidException) {
                        logE(TAG, "SESSION: Config is invalid")
                        callback?.onConfigInvalid()
                    } else {
                        logE(TAG, "SESSION: Error occurred")
                        logE(TAG, chatEvent.cause?.stackTraceToString() ?: chatEvent.msg)
                        callback?.onError(chatEvent.msg, chatEvent.cause)
                    }
                }

                is ChatEvent.Complete -> {
                    val isSuccess = chatEvent.isSuccess

                    historyKeeper.setLatestPairState(
                        if (isSuccess)
                            ChatPair.RoundState.Succeeded
                        else
                            ChatPair.RoundState.Failed
                    )

                    if (!toolCallWaiter.isEmpty() && isSuccess) {
                        logE(TAG, "SESSION: Prepare for tool responding")
                        responseToolCalls()
                    } else {
                        callback?.onCompleted(isSuccess, chatEvent.cause)
                    }
                }
            }
        }
    }

    private suspend fun responseToolCalls() {
        val results = toolCallWaiter.awaitAll()
        historyKeeper.addToolResults(results)
        sendMessage(null)
    }

    /**
     * Returns the current conversation history as pairs of user and assistant/tool messages.
     */
    suspend fun getHistory(): List<ChatPair> = historyKeeper.getHistory()

    /**
     * Performs a lightweight request to warm up connections.
     */
    fun preConnect() {
        if (client.isConfigValid())
            client.preConnect()
        else
            callback?.onConfigInvalid()
    }

    /**
     * Updates the session configuration.
     */
    fun updateConfig(block: ConfigBuilder.() -> Unit) = client.updateConfig(block)
}