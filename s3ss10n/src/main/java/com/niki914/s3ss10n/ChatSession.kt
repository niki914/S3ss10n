package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.AIContent
import com.niki914.s3ss10n.chat.ChatEvent
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.chat.protocol.beans.user
import com.niki914.s3ss10n.toolbase.ToolManager
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import com.zephyr.log.logE
import com.zephyr.provider.TAG
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
    baseUrl: String,
    apiKey: String,
    modelName: String,
    prompt: String? = null,
    tools: List<ToolDefinition>? = null
) : Session {

    constructor() : this("", "", "", null, null)

    /**
     * Creates a ChatSession from a [SessionConfig] DSL object.
     */
    constructor(config: SessionConfig) : this(
        baseUrl = config.endpoint,
        apiKey = config.apiKey,
        modelName = config.model,
        prompt = config.systemPrompt,
        tools = config.buildToolDefinitions().ifEmpty { null }
    ) {
        sessionConfig = config
    }

    private var sessionConfig: SessionConfig? = null

    private var userOnEvent: ((SessionEvent) -> Unit)? = null
    private var currentInput: String = ""
    private val textAccumulator = StringBuilder()

    private val toolManager = ToolManager()

    private val client = ChatClient(
        baseUrl,
        apiKey,
        modelName,
        prompt,
        tools
    )

    private val scope = CoroutineScope(SupervisorJob())
    private var currJob: Job? = null
    private val chatMutex = Mutex()

    private val historyKeeper = HistoryKeeper()
    private val toolCallWaiter = ToolCallWaiter(scope) { toolCall ->
        handleToolCall(toolCall)
    }

    // --- Session interface implementation ---

    override suspend fun send(text: String, onEvent: (SessionEvent) -> Unit) {
        userOnEvent = onEvent
        currentInput = text
        textAccumulator.clear()
        applyConfig()
        sendMessage(text)
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
     * Applies the stored SessionConfig to the underlying ChatClient.
     */
    private fun applyConfig() {
        sessionConfig?.let { config ->
            client.updateConfig {
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
    }

    /**
     * Sends a user message and starts a new streaming round.
     */
    private fun sendMessage(userMsg: String) = scope.launch {
        chatMutex.withLock {
            cleanUpCurrWork()
            currJob = launch {
                sendMessage(user(userMsg))
            }
        }
    }

    private suspend fun sendMessage(message: Message.User?) {
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
                    toolCallWaiter.enqueue(chatEvent.toolCall)
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
                        responseToolCalls()
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

    private suspend fun responseToolCalls() {
        val results = toolCallWaiter.awaitAll()
        historyKeeper.addToolResults(results)
        sendMessage(null)
    }

    /**
     * Handles a tool call: builds a [ToolCallRequest], dispatches through hooks,
     * and emits [SessionEvent.ToolRunning]/[SessionEvent.ToolSucceeded]/[SessionEvent.ToolFailed].
     */
    private suspend fun handleToolCall(toolCall: ToolCall): Message.Tool {
        val request = buildToolCallRequest(toolCall)

        userOnEvent?.invoke(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = sessionConfig?.hooksBlock
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

    private fun buildToolCallRequest(toolCall: ToolCall): ToolCallRequest {
        return LocalToolCallRequest(
            toolCall = toolCall,
            toolManager = toolManager,
            appParams = sessionConfig?.buildAppParams() ?: emptyMap()
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
