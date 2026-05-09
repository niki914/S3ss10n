package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.AIContent
import com.niki914.s3ss10n.chat.ChatEvent
import com.niki914.s3ss10n.chat.ChatService
import com.niki914.s3ss10n.chat.protocol.ChatApiRequestBody
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.chat.protocol.beans.system
import com.niki914.s3ss10n.chat.protocol.beans.user
import com.niki914.s3ss10n.net.OkhttpClientManager
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import com.zephyr.log.logE
import com.zephyr.provider.TAG
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConfigInvalidException() :
    IllegalAccessException("Config is invalid. Set BaseUrl and Model first!")

/**
 * A session-level streaming chat wrapper.
 *
 * It keeps the conversation history, streams assistant output, coordinates tool calling,
 * and emits [SessionEvent] directly to the caller's onEvent lambda.
 *
 * Implements [Session] to provide the public API for chat interactions.
 */
class ChatSession internal constructor(
    initialConfig: SessionConfig
) : Session {

    private val configRef = AtomicReference(initialConfig)

    private val clientManager = OkhttpClientManager { configRef.get() }
    private val service by lazy { ChatService(clientManager.okHttpClient) }

    private val scope = CoroutineScope(SupervisorJob())
    private var currJob: Job? = null
    private val chatMutex = Mutex()

    private val historyKeeper = HistoryKeeper()
    private val toolCallWaiter = ToolCallWaiter<RoundContext>(scope) { toolCall, ctx ->
        handleToolCall(toolCall, ctx)
    }

    private class RoundContext(
        val configSnapshot: SessionConfig,
        val onEvent: (SessionEvent) -> Unit,
        val initialInput: String,
        val textAccumulator: StringBuilder = StringBuilder()
    )

    // --- Session interface implementation ---

    override suspend fun send(text: String, onEvent: (SessionEvent) -> Unit) {
        val ctx = RoundContext(configRef.get().snapshot(), onEvent, text)
        runRound(ctx, userInput = text)
    }

    override fun update(block: SessionConfig.() -> Unit) {
        configRef.updateAndGet { current ->
            current.snapshot().apply(block)
        }
    }

    override suspend fun resetConversation() {
        reset()
    }

    override suspend fun close() {
        scope.cancel()
    }

    // --- Internal methods ---

    private fun isConfigValid(snap: SessionConfig): Boolean {
        return snap.endpoint.isHTTPProtocol() && snap.model.isNotBlank()
    }

    private fun String.isHTTPProtocol(): Boolean {
        return startsWith("http://") || startsWith("https://")
    }

    private fun streamRequest(snap: SessionConfig, messages: List<Message>): Flow<ChatEvent> {
        if (!isConfigValid(snap)) {
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

        val sysPrompt = snap.systemPrompt
        val prefix = if (!sysPrompt.isNullOrBlank()) {
            system(sysPrompt)
        } else null

        val allMessages = listOfNotNull(prefix) + messages

        return service.newChat(
            requestBody = ChatApiRequestBody(
                model = snap.model,
                messages = allMessages,
                tools = snap.buildToolDefinitions().ifEmpty { null },
                temperature = snap.temperature
            )
        )
    }

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
    private fun runRound(ctx: RoundContext, userInput: String?) = scope.launch {
        chatMutex.withLock {
            cleanUpCurrWork()
            currJob = launch {
                val msg = userInput?.let { user(it) }
                doRound(ctx, msg)
            }
        }
    }

    private suspend fun doRound(ctx: RoundContext, message: Message.User?) {
        message?.let {
            historyKeeper.addUserMsg(message)
        }

        streamRequest(
            snap = ctx.configSnapshot,
            messages = historyKeeper.getMessages()
        ).collect { chatEvent ->
            when (chatEvent) {
                ChatEvent.Start -> {
                    historyKeeper.setLatestPairState(
                        ChatPair.RoundState.Generating
                    )
                    logE(TAG, "SESSION: Started")
                    ctx.onEvent(
                        SessionEvent.RoundStarted(input = ctx.initialInput)
                    )
                }

                is ChatEvent.AI -> {
                    when (val aIContent = chatEvent.content) {
                        is AIContent.Else -> {
                            logE(TAG, "SESSION: Unimplemented case: $aIContent")
                        }

                        is AIContent.Text -> {
                            historyKeeper.appendTextToLastAIMsg(aIContent.content)
                            ctx.textAccumulator.append(aIContent.content)
                            ctx.onEvent(
                                SessionEvent.TextDelta(
                                    delta = aIContent.content,
                                    fullText = ctx.textAccumulator.toString()
                                )
                            )
                        }
                    }
                }

                is ChatEvent.ToolCallIntent -> {
                    historyKeeper.appendToolCallToLastAIMsg(chatEvent.toolCall)
                    logE(TAG, "SESSION: Inject tool-call: ${chatEvent.toolCall.function?.name}")
                    toolCallWaiter.enqueue(chatEvent.toolCall, ctx)
                }

                is ChatEvent.Error -> {
                    if (chatEvent.cause is ConfigInvalidException) {
                        logE(TAG, "SESSION: Config is invalid")
                        ctx.onEvent(
                            SessionEvent.Error(
                                stage = SessionEvent.Stage.Session,
                                message = "Config is invalid. Set endpoint and model first."
                            )
                        )
                    } else if (chatEvent.cause is com.google.gson.JsonSyntaxException || chatEvent.cause is java.lang.IllegalStateException) {
                        logE(TAG, "SESSION: Parse error occurred")
                        logE(TAG, chatEvent.cause?.stackTraceToString() ?: chatEvent.msg)
                        ctx.onEvent(
                            SessionEvent.Error(
                                stage = SessionEvent.Stage.Parse,
                                message = chatEvent.msg,
                                cause = chatEvent.cause
                            )
                        )
                    } else {
                        logE(TAG, "SESSION: Error occurred")
                        logE(TAG, chatEvent.cause?.stackTraceToString() ?: chatEvent.msg)
                        ctx.onEvent(
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
                        responseToolCalls(ctx)
                    } else {
                        if (isSuccess) {
                            ctx.onEvent(
                                SessionEvent.RoundCompleted(
                                    fullText = ctx.textAccumulator.toString()
                                )
                            )
                        } else {
                            ctx.onEvent(
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

    private suspend fun responseToolCalls(ctx: RoundContext) {
        val results = toolCallWaiter.awaitAll()
        historyKeeper.addToolResults(results)
        doRound(ctx, null)
    }

    /**
     * Handles a tool call: builds a [ToolCallRequest], dispatches through hooks,
     * and emits [SessionEvent.ToolRunning]/[SessionEvent.ToolSucceeded]/[SessionEvent.ToolFailed].
     */
    private suspend fun handleToolCall(toolCall: ToolCall, ctx: RoundContext): Message.Tool {
        val request = buildToolCallRequest(toolCall, ctx.configSnapshot)

        ctx.onEvent(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = ctx.configSnapshot.hooksBlock
        return if (hooks != null) {
            val result = try {
                request.hooks()
            } catch (t: Throwable) {
                ctx.onEvent(
                    SessionEvent.ToolFailed(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        message = t.message ?: "hooks threw exception",
                        resultJson = null
                    )
                )
                ctx.onEvent(
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
                    ctx.onEvent(
                        SessionEvent.ToolSucceeded(
                            callId = request.id,
                            toolName = request.name,
                            kind = request.kind,
                            resultJson = outcome.resultJson
                        )
                    )
                }
                is ToolCallOutcome.Failure -> {
                    ctx.onEvent(
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
                    ctx.onEvent(
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
            ctx.onEvent(
                SessionEvent.ToolFailed(
                    callId = request.id,
                    toolName = request.name,
                    kind = request.kind,
                    message = "No hooks configured",
                    resultJson = null
                )
            )
            ctx.onEvent(
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
}
