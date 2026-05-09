package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.GsonJsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.OkHttpEngine
import com.niki914.s3ss10n.protocol.ChatProtocol
import com.niki914.s3ss10n.protocol.ProtocolEvent
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConfigInvalidException :
    IllegalAccessException("Config is invalid. Set endpoint and model first!")

class ChatSession internal constructor(
    initialConfig: SessionConfig,
    private val protocol: ChatProtocol
) : Session {

    private val configRef = AtomicReference(initialConfig)
    private val engine: HttpEngine = initialConfig.httpEngine ?: OkHttpEngine()
    private val scope = CoroutineScope(SupervisorJob())
    private var currJob: Job? = null
    private val chatMutex = Mutex()
    private val historyKeeper = HistoryKeeper()
    private val toolCallWaiter = ToolCallWaiter<RoundContext>(scope) { toolCall, ctx ->
        handleToolCall(toolCall, ctx)
    }

    init {
        val codec = initialConfig.jsonCodec ?: GsonJsonCodec()
        initialConfig.localToolRegistry.codec = codec
    }

    private class RoundContext(
        val configSnapshot: SessionConfig,
        val onEvent: (SessionEvent) -> Unit,
        val initialInput: String,
        val textAccumulator: StringBuilder = StringBuilder(),
        var hasStarted: Boolean = false
    )

    override suspend fun send(text: String, onEvent: (SessionEvent) -> Unit) {
        val ctx = RoundContext(configRef.get().snapshot(), onEvent, text)
        runRound(ctx, text).join()
    }

    override fun update(block: SessionConfig.Builder.() -> Unit) {
        configRef.updateAndGet { current ->
            current.toBuilder().apply(block).build()
        }
    }

    override suspend fun getHistory(): List<ChatTurn> {
        return historyKeeper.snapshot().filterNot { it is ChatTurn.System }
    }

    override suspend fun resetConversation() {
        cleanUpCurrWork()
        historyKeeper.clear()
    }

    override suspend fun close() {
        scope.cancel()
        engine.close()
    }

    private fun runRound(ctx: RoundContext, userInput: String?) = scope.launch {
        chatMutex.withLock {
            cleanUpCurrWork()
            currJob = launch {
                doRound(ctx, userInput)
            }
        }
    }

    private suspend fun cleanUpCurrWork() {
        currJob?.cancel()
        currJob?.join()
        toolCallWaiter.cancelAndClear(join = true)
    }

    private suspend fun doRound(ctx: RoundContext, userInput: String?) {
        xTrySuspend("ChatSession.doRound", onError = { t ->
            ctx.onEvent(
                SessionEvent.Error(
                    stage = classifyStage(t),
                    message = t.message ?: "Round failed",
                    cause = t
                )
            )
        }) {
            ensureConfigValid(ctx.configSnapshot)
            if (!ctx.hasStarted) {
                ctx.hasStarted = true
                ctx.onEvent(SessionEvent.RoundStarted(input = ctx.initialInput))
            }

            val fullText = StringBuilder()
            val reasoningContent = StringBuilder()
            val toolCalls = mutableListOf<ToolCallSpec>()
            val req = protocol.buildRequest(
                snapshot = ctx.configSnapshot,
                history = historyKeeper.snapshot(),
                pendingUserInput = userInput
            )
            val rawFlow = engine.stream(req)
            protocol.parseStream(rawFlow).collect { event ->
                when (event) {
                    is ProtocolEvent.TextDelta -> {
                        fullText.append(event.text)
                        ctx.textAccumulator.append(event.text)
                        ctx.onEvent(
                            SessionEvent.TextDelta(
                                delta = event.text,
                                fullText = ctx.textAccumulator.toString()
                            )
                        )
                    }

                    is ProtocolEvent.ReasoningDelta -> {
                        reasoningContent.append(event.text)
                    }

                    is ProtocolEvent.ToolCallReady -> {
                        val toolCall = ToolCallSpec(
                            callId = event.callId,
                            toolName = event.toolName,
                            argumentsJson = event.argumentsJson
                        )
                        toolCalls += toolCall
                        toolCallWaiter.enqueue(toolCall, ctx)
                    }

                    is ProtocolEvent.Error -> {
                        ctx.onEvent(
                            SessionEvent.Error(
                                stage = event.stage,
                                message = event.cause.message ?: "Protocol error",
                                cause = event.cause
                            )
                        )
                    }

                    ProtocolEvent.Completed -> Unit
                }
            }

            if (userInput != null) {
                historyKeeper.add(ChatTurn.User(userInput))
            }
            historyKeeper.add(
                ChatTurn.Assistant(
                    content = fullText.toString(),
                    toolCalls = toolCalls.toList(),
                    reasoningContent = reasoningContent.toString().ifEmpty { null }
                )
            )

            if (!toolCallWaiter.isEmpty()) {
                responseToolCalls(ctx)
            } else {
                ctx.onEvent(
                    SessionEvent.RoundCompleted(
                        fullText = ctx.textAccumulator.toString()
                    )
                )
            }
        }
    }

    private suspend fun responseToolCalls(ctx: RoundContext) {
        val results = toolCallWaiter.awaitAll()
        results.forEach { (toolCall, resultJson) ->
            historyKeeper.add(
                protocol.encodeToolResult(
                    callId = toolCall.callId,
                    toolName = toolCall.toolName,
                    resultJson = resultJson
                )
            )
        }
        doRound(ctx, null)
    }

    private suspend fun handleToolCall(toolCall: ToolCallSpec, ctx: RoundContext): String {
        val request = buildToolCallRequest(toolCall, ctx.configSnapshot)
        ctx.onEvent(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = ctx.configSnapshot.hooksBlock
        if (hooks == null) {
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
            return request.error("No hooks configured")
        }

        val resultJson = xTrySuspend("ChatSession.handleToolCall", onError = { t ->
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
            request.error(t.message ?: "hooks threw exception")
        }) {
            request.hooks()
        }

        when (val outcome = when (request) {
            is LocalToolCallRequest -> request.lastOutcome
            is McpToolCallRequest -> request.lastOutcome
        }) {
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
                        resultJson = resultJson
                    )
                )
            }
        }
        return resultJson
    }

    private fun buildToolCallRequest(toolCall: ToolCallSpec, snap: SessionConfig): ToolCallRequest {
        return LocalToolCallRequest(
            toolCall = toolCall,
            appParams = snap.appParamsSnapshot()
        )
    }

    private fun ensureConfigValid(snapshot: SessionConfig) {
        if (!snapshot.endpoint.startsWith("http://") && !snapshot.endpoint.startsWith("https://")) {
            throw ConfigInvalidException()
        }
        if (snapshot.model.isBlank()) {
            throw ConfigInvalidException()
        }
    }

    private fun classifyStage(t: Throwable): SessionEvent.Stage {
        return when (t) {
            is ConfigInvalidException -> SessionEvent.Stage.Session
            is IllegalStateException -> SessionEvent.Stage.Parse
            else -> SessionEvent.Stage.Transport
        }
    }
}
