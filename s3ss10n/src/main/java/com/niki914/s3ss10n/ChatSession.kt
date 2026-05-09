package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.AIContent
import com.niki914.s3ss10n.chat.ChatEvent
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.chat.protocol.beans.user
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import com.zephyr.log.logE
import com.zephyr.provider.TAG
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A session-level streaming chat wrapper.
 *
 * It keeps the conversation history, streams assistant output, coordinates tool calling,
 * and emits [SessionEvent] directly to the caller's onEvent lambda.
 *
 * Implements [Session] to provide the public API for chat interactions.
 */
class ChatSession(
    config: SessionConfig
) : Session {

    private val configRef = AtomicReference(config)

    private var userOnEvent: ((SessionEvent) -> Unit)? = null
    private var currentInput: String = ""
    private val textAccumulator = StringBuilder()

    private val client = ChatClient {
        configRef.get()
    }

    private val scope = CoroutineScope(SupervisorJob())
    private var currJob: Job? = null
    private val chatMutex = Mutex()

    private val historyKeeper = HistoryKeeper()
    private val toolCallWaiter = ToolCallWaiter(scope) { toolCall, snap ->
        handleToolCall(toolCall, snap)
    }

    // --- Session interface implementation ---

    override suspend fun send(text: String, onEvent: (SessionEvent) -> Unit) {
        userOnEvent = onEvent
        currentInput = text
        textAccumulator.clear()
        
        val snap = configRef.get().snapshot()
        sendMessage(text, snap)
    }

    override fun update(block: SessionConfig.() -> Unit) {
        configRef.updateAndGet { current ->
            current.snapshot().apply(block)
        }
    }

    override suspend fun getHistory(): List<ChatPair> = historyKeeper.getHistory()

    override suspend fun resetConversation() {
        reset()
    }

    override suspend fun close() {
        scope.cancel()
    }

    // --- Internal methods ---

    /**
     * Cancels the current round and clears all history.
     */
    private suspend fun reset() {
        cleanUpCurrWork()
        historyKeeper.clear()
    }

    private suspend fun cleanUpCurrWork() {
        currJob?.cancel()
        currJob?.join()
        toolCallWaiter.cancelAndClear(join = true)
    }

    /**
     * Sends a user message and starts a new streaming round.
     */
    private fun sendMessage(userMsg: String, snap: SessionConfig) = scope.launch {
        chatMutex.withLock {
            cleanUpCurrWork()
            currJob = launch {
                sendMessage(user(userMsg), snap)
            }
        }
    }

    private suspend fun sendMessage(message: Message.User?, snap: SessionConfig) {
        message?.let {
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
                    userOnEvent?.invoke(
                        SessionEvent.RoundStarted(input = currentInput)
                    )
                }

                is ChatEvent.AI -> {
                    when (val aIContent = chatEvent.content) {
                        is AIContent.Else -> {
                            logE(TAG, "SESSION: Unimplemented case: $aIContent")
                        }

                        is AIContent.Text -> {
                            historyKeeper.appendTextToLastAIMsg(aIContent.content)
                            textAccumulator.append(aIContent.content)
                            userOnEvent?.invoke(
                                SessionEvent.TextDelta(
                                    delta = aIContent.content,
                                    fullText = textAccumulator.toString()
                                )
                            )
                        }
                    }
                }

                is ChatEvent.ToolCallIntent -> {
                    historyKeeper.appendToolCallToLastAIMsg(chatEvent.toolCall)
                    logE(TAG, "SESSION: Inject tool-call: ${chatEvent.toolCall.function?.name}")
                    toolCallWaiter.enqueue(chatEvent.toolCall, snap)
                }

                is ChatEvent.Error -> {
                    if (chatEvent.cause is ConfigInvalidException) {
                        logE(TAG, "SESSION: Config is invalid")
                        userOnEvent?.invoke(
                            SessionEvent.Error(
                                stage = SessionEvent.Stage.Session,
                                message = "Config is invalid. Set endpoint and model first."
                            )
                        )
                    } else if (chatEvent.cause is com.google.gson.JsonSyntaxException || chatEvent.cause is java.lang.IllegalStateException) {
                        logE(TAG, "SESSION: Parse error occurred")
                        logE(TAG, chatEvent.cause?.stackTraceToString() ?: chatEvent.msg)
                        userOnEvent?.invoke(
                            SessionEvent.Error(
                                stage = SessionEvent.Stage.Parse,
                                message = chatEvent.msg,
                                cause = chatEvent.cause
                            )
                        )
                    } else {
                        logE(TAG, "SESSION: Error occurred")
                        logE(TAG, chatEvent.cause?.stackTraceToString() ?: chatEvent.msg)
                        userOnEvent?.invoke(
                            SessionEvent.Error(
                                stage = SessionEvent.Stage.Transport,
                                message = chatEvent.msg,
                                cause = chatEvent.cause
                            )
                        )
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
                        responseToolCalls(snap)
                    } else {
                        if (isSuccess) {
                            userOnEvent?.invoke(
                                SessionEvent.RoundCompleted(
                                    fullText = textAccumulator.toString()
                                )
                            )
                        } else {
                            userOnEvent?.invoke(
                                SessionEvent.Error(
                                    stage = SessionEvent.Stage.Session,
                                    message = "Round failed",
                                    cause = chatEvent.cause
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun responseToolCalls(snap: SessionConfig) {
        val results = toolCallWaiter.awaitAll()
        historyKeeper.addToolResults(results)
        sendMessage(null, snap)
    }

    /**
     * Handles a tool call: builds a [ToolCallRequest], dispatches through hooks,
     * and emits [SessionEvent.ToolRunning]/[SessionEvent.ToolSucceeded]/[SessionEvent.ToolFailed].
     */
    private suspend fun handleToolCall(toolCall: ToolCall, snap: SessionConfig): Message.Tool {
        val request = buildToolCallRequest(toolCall, snap)

        userOnEvent?.invoke(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = snap.hooksBlock
        return if (hooks != null) {
            val result = try {
                request.hooks()
            } catch (t: Throwable) {
                userOnEvent?.invoke(
                    SessionEvent.ToolFailed(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        message = t.message ?: "hooks threw exception",
                        resultJson = null
                    )
                )
                userOnEvent?.invoke(
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Tool,
                        message = t.message ?: "hooks threw exception",
                        cause = t
                    )
                )
                return request.error(t.message ?: "hooks threw exception")
            }

            val outcome = when (request) {
                is LocalToolCallRequest -> request.lastOutcome
                is McpToolCallRequest -> request.lastOutcome
            }

            when (outcome) {
                is ToolCallOutcome.Success -> {
                    userOnEvent?.invoke(
                        SessionEvent.ToolSucceeded(
                            callId = request.id,
                            toolName = request.name,
                            kind = request.kind,
                            resultJson = outcome.resultJson
                        )
                    )
                }
                is ToolCallOutcome.Failure -> {
                    userOnEvent?.invoke(
                        SessionEvent.ToolFailed(
                            callId = request.id,
                            toolName = request.name,
                            kind = request.kind,
                            message = outcome.errorMessage,
                            resultJson = outcome.resultJson
                        )
                    )
                }
                null -> {
                    userOnEvent?.invoke(
                        SessionEvent.ToolFailed(
                            callId = request.id,
                            toolName = request.name,
                            kind = request.kind,
                            message = "No outcome recorded",
                            resultJson = result.content
                        )
                    )
                }
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
            userOnEvent?.invoke(
                SessionEvent.Error(
                    stage = SessionEvent.Stage.Tool,
                    message = "no hooks configured"
                )
            )
            request.error("No hooks configured")
        }
    }

    private fun buildToolCallRequest(toolCall: ToolCall, snap: SessionConfig): ToolCallRequest {
        return LocalToolCallRequest(
            toolCall = toolCall,
            appParams = snap.appParamsSnapshot()
        )
    }

    /**
     * Performs a lightweight request to warm up connections.
     */
    fun preConnect() {
        if (client.isConfigValid())
            client.preConnect()
    }
}
