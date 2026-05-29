package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodecFactory
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.SseLineParser
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.ext.net.OkHttpEngine
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConfigInvalidException :
    IllegalAccessException("Config is invalid. Set endpoint and model first!")

class ChatSession internal constructor(
    initialConfig: SessionConfig,
    private val protocol: ChatProtocol
) : Session {

    private val configMutex = Mutex()
    private var _config: SessionConfig = initialConfig
    private val engine: HttpEngine = initialConfig.httpEngine ?: OkHttpEngine()
    private val jsonCodec: JsonCodec = initialConfig.jsonCodec ?: JsonCodecFactory.create()

    private suspend fun thisConfig(): SessionConfig = configMutex.withLock { _config }
    private val mcpClient: McpClient = HttpMcpClient(engine = engine, codec = jsonCodec)
    private val toolCallCoordinator = ToolCallCoordinator(mcpClient = mcpClient, codec = jsonCodec)
    private val scope = CoroutineScope(SupervisorJob())
    private val mcpDiscoveryCoordinator = McpDiscoveryCoordinator(
        mcpClient = mcpClient,
        scope = scope,
        codec = jsonCodec,
        latestConfig = ::thisConfig
    )
    private var currJob: Job? = null
    private val chatMutex = Mutex()
    private val historyKeeper = HistoryKeeper()
    private val toolCallWaiter = ToolCallWaiter<RoundContext>(scope) { toolCall, ctx ->
        toolCallCoordinator.handle(toolCall, ctx.configSnapshot) { event ->
            emitCoordinatorEvent(ctx, event)
        }
    }

    init {
        initialConfig.localToolRegistry.codec = jsonCodec
        mcpDiscoveryCoordinator.scheduleDiscovery(initialConfig)
    }

    private class RoundContext(
        val configSnapshot: SessionSnapshot,
        val onEvent: suspend (SessionEvent) -> Unit,
        val initialInput: String,
        val textAccumulator: StringBuilder = StringBuilder(),
        var hasStarted: Boolean = false,
        var hasCompleted: Boolean = false,
        var hasError: Boolean = false
    )

    private class RoundEventCallbackException(
        val original: Throwable
    ) : RuntimeException(original)

    override suspend fun send(text: String, onEvent: suspend (SessionEvent) -> Unit) {
        val config = thisConfig()
        mcpDiscoveryCoordinator.scheduleDiscovery(config)
        val ctx = RoundContext(
            config.toRoundSnapshot(
                codec = jsonCodec,
                discoveredMcpTools = mcpDiscoveryCoordinator.discoveredToolsSnapshot(config)
            ),
            onEvent,
            text
        )
        awaitRound(runRound(ctx, text))
    }

    override suspend fun update(block: SessionConfig.Builder.() -> Unit) {
        val updatedConfig = configMutex.withLock {
            val baseCodec = _config.jsonCodec
            val baseEngine = _config.httpEngine
            val updated = _config.toBuilder().apply(block).build()
            if (updated.jsonCodec !== baseCodec) {
                xLog("X", "update ignored open-only field: jsonCodec")
                updated.jsonCodec = baseCodec
            }
            if (updated.httpEngine !== baseEngine) {
                xLog("X", "update ignored open-only field: httpEngine")
                updated.httpEngine = baseEngine
            }
            _config = updated
            updated
        }
        mcpDiscoveryCoordinator.scheduleDiscovery(updatedConfig, refreshCached = true)
    }

    override suspend fun refreshMcpTools(): McpRefreshResult {
        return mcpDiscoveryCoordinator.refreshEnabledServers(config = thisConfig(), refreshCached = true)
    }

    override suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot {
        return mcpDiscoveryCoordinator.getSnapshot(thisConfig())
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

    private fun runRound(ctx: RoundContext, userInput: String?) = scope.async {
        val roundJob = chatMutex.withLock {
            cleanUpCurrWork()
            scope.async {
                doRound(ctx, userInput)
            }.also { currJob = it }
        }
        roundJob.await()
    }

    private suspend fun awaitRound(round: Deferred<Unit>) {
        try {
            round.await()
        } catch (t: Throwable) {
            throw t.unwrapRecoveredCoroutineException()
        }
    }

    private fun Throwable.unwrapRecoveredCoroutineException(): Throwable {
        val original = cause ?: return this
        return if (this::class == original::class && message == original.message) {
            original
        } else {
            this
        }
    }

    private suspend fun cleanUpCurrWork() {
        currJob?.cancel()
        currJob?.join()
        toolCallWaiter.cancelAndClear(join = true)
    }

    private suspend fun doRound(ctx: RoundContext, userInput: String?) {
        xTrySuspend("ChatSession.doRound", onError = { t ->
            if (t is RoundEventCallbackException) {
                throw t.original
            }
            unwrapRoundEventCallbackException {
                emitRoundError(
                    ctx,
                    SessionEvent.Error(
                        stage = classifyStage(t),
                        message = t.message ?: "Round failed",
                        cause = t
                    )
                )
                finishRoundIfStarted(ctx)
            }
        }) {
            ensureConfigValid(ctx.configSnapshot)

            val fullText = StringBuilder()
            val reasoningContent = StringBuilder()
            var reasoningSignature: String? = null
            val toolCalls = mutableListOf<ToolCallSpec>()
            val req = protocol.buildRequest(
                snapshot = ctx.configSnapshot,
                history = historyKeeper.snapshot(),
                pendingUserInput = userInput
            )

            // Merge auth headers from protocol with custom headers (custom takes precedence)
            val authHeaders = protocol.useApiKey(ctx.configSnapshot.apiKey)
            val mergedAuthAndCustom = mergeHeadersWithCustomOverride(
                authHeaders, ctx.configSnapshot.headers
            )
            val effectiveReq = req.copy(headers = req.headers + mergedAuthAndCustom)

            val rawFlow = engine.stream(effectiveReq)
            val sseData = SseLineParser.parse(rawFlow)
            protocol.parseStream(sseData).collect { event ->
                startRoundIfNeeded(ctx)
                when (event) {
                    is ProtocolEvent.TextDelta -> {
                        fullText.append(event.text)
                        ctx.textAccumulator.append(event.text)
                        emitRoundEvent(
                            ctx,
                            SessionEvent.TextDelta(
                                delta = event.text,
                                fullText = ctx.textAccumulator.toString()
                            )
                        )
                    }

                    is ProtocolEvent.ReasoningDelta -> {
                        reasoningContent.append(event.text)
                    }

                    is ProtocolEvent.ReasoningSignature -> {
                        reasoningSignature = event.signature
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
                        emitRoundError(
                            ctx,
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

            val committed = commitRoundTurnsIfValid(
                ctx = ctx,
                userInput = userInput,
                assistantContent = fullText.toString(),
                toolCalls = toolCalls.toList(),
                reasoningContent = reasoningContent.toString().ifEmpty { null },
                reasoningSignature = reasoningSignature
            )
            if (!committed) {
                finishRoundIfStarted(ctx)
                return@xTrySuspend
            }

            if (!toolCallWaiter.isEmpty()) {
                responseToolCalls(ctx)
            } else {
                finishRoundIfStarted(ctx)
            }
        }
    }

    private suspend fun startRoundIfNeeded(ctx: RoundContext) {
        if (ctx.hasStarted) return
        emitRoundEvent(ctx, SessionEvent.RoundStarted(input = ctx.initialInput))
        ctx.hasStarted = true
    }

    private suspend fun finishRoundIfStarted(ctx: RoundContext) {
        if (!ctx.hasStarted || ctx.hasCompleted) return
        emitRoundEvent(
            ctx,
            SessionEvent.RoundCompleted(
                fullText = ctx.textAccumulator.toString()
            )
        )
        ctx.hasCompleted = true
    }

    private suspend fun emitRoundError(ctx: RoundContext, event: SessionEvent.Error) {
        ctx.hasError = true
        emitRoundEvent(ctx, event)
    }

    private suspend fun emitCoordinatorEvent(ctx: RoundContext, event: SessionEvent) {
        if (event is SessionEvent.Error) {
            emitRoundError(ctx, event)
        } else {
            emitRoundEvent(ctx, event)
        }
    }

    private suspend fun emitRoundEvent(ctx: RoundContext, event: SessionEvent) {
        try {
            ctx.onEvent(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw RoundEventCallbackException(t)
        }
    }

    private suspend fun unwrapRoundEventCallbackException(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: RoundEventCallbackException) {
            throw e.original
        }
    }

    private suspend fun commitRoundTurnsIfValid(
        ctx: RoundContext,
        userInput: String?,
        assistantContent: String,
        toolCalls: List<ToolCallSpec>,
        reasoningContent: String?,
        reasoningSignature: String?
    ): Boolean {
        val hasAssistantPayload = assistantContent.isNotBlank() || toolCalls.isNotEmpty()
        if (!hasAssistantPayload) {
            if (!ctx.hasError) {
                emitRoundError(
                    ctx,
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Parse,
                        message = "Empty assistant response"
                    )
                )
            }
            return false
        }

        if (userInput != null) {
            historyKeeper.add(ChatTurn.User(userInput))
        }
        historyKeeper.add(
            ChatTurn.Assistant(
                content = assistantContent,
                toolCalls = toolCalls,
                reasoningContent = reasoningContent,
                reasoningSignature = reasoningSignature
            )
        )
        return true
    }

    private suspend fun responseToolCalls(ctx: RoundContext) {
        val results = toolCallWaiter.awaitAll()
        results.forEach { (toolCall, msg) ->
            historyKeeper.add(
                protocol.encodeToolResult(
                    callId = toolCall.callId,
                    toolName = toolCall.toolName,
                    resultJson = msg.contentJson
                )
            )
        }
        doRound(ctx, null)
    }

    private fun ensureConfigValid(snapshot: SessionSnapshot) {
        if (!snapshot.endpoint.startsWith("http://") && !snapshot.endpoint.startsWith("https://")) {
            throw ConfigInvalidException()
        }
        if (snapshot.model.isBlank()) {
            throw ConfigInvalidException()
        }
    }

    /**
     * Merge auth headers with custom headers. Custom headers take precedence
     * over auth headers with the same name (case-insensitive key matching).
     */
    private fun mergeHeadersWithCustomOverride(
        authHeaders: Map<String, String>,
        customHeaders: Map<String, String>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val customKeysLower = customHeaders.keys.mapTo(HashSet()) { it.lowercase() }
        for ((key, value) in authHeaders) {
            if (key.lowercase() !in customKeysLower) {
                result[key] = value
            }
        }
        result.putAll(customHeaders)
        return result
    }

    private fun classifyStage(t: Throwable): SessionEvent.Stage {
        return when (t) {
            is ConfigInvalidException -> SessionEvent.Stage.Session
            is IllegalArgumentException -> SessionEvent.Stage.Session
            is IllegalStateException -> SessionEvent.Stage.Parse
            else -> SessionEvent.Stage.Transport
        }
    }

}
