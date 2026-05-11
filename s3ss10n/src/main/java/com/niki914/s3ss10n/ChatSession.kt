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
import kotlinx.coroutines.CancellationException
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

    private val configMutex = Mutex()
    private var _config: SessionConfig = initialConfig
    private val engine: HttpEngine = initialConfig.httpEngine ?: OkHttpEngine()
    private val jsonCodec: JsonCodec = initialConfig.jsonCodec ?: JsonCodecFactory.create()

    private suspend fun thisConfig(): SessionConfig = configMutex.withLock { _config }
    private val mcpClient: McpClient = HttpMcpClient(engine = engine, codec = jsonCodec)
    private val mcpDiscoveryCache = McpDiscoveryCache()
    private val scope = CoroutineScope(SupervisorJob())
    private var currJob: Job? = null
    private val chatMutex = Mutex()
    private val historyKeeper = HistoryKeeper()
    private val toolCallWaiter = ToolCallWaiter<RoundContext>(scope) { toolCall, ctx ->
        handleToolCall(toolCall, ctx)
    }

    init {
        initialConfig.localToolRegistry.codec = jsonCodec
        scheduleDiscovery(initialConfig)
    }

    private class RoundContext(
        val configSnapshot: SessionSnapshot,
        val onEvent: suspend (SessionEvent) -> Unit,
        val initialInput: String,
        val textAccumulator: StringBuilder = StringBuilder(),
        var hasStarted: Boolean = false
    )

    override suspend fun send(text: String, onEvent: suspend (SessionEvent) -> Unit) {
        val config = thisConfig()
        scheduleDiscovery(config)
        val ctx = RoundContext(
            config.toRoundSnapshot(
                codec = jsonCodec,
                discoveredMcpTools = discoveredToolsSnapshot(config)
            ),
            onEvent,
            text
        )
        runRound(ctx, text).join()
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
        scheduleDiscovery(updatedConfig, refreshCached = true)
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
                if (!ctx.hasStarted) {
                    ctx.hasStarted = true
                    ctx.onEvent(SessionEvent.RoundStarted(input = ctx.initialInput))
                }
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
                    reasoningContent = reasoningContent.toString().ifEmpty { null },
                    reasoningSignature = reasoningSignature
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

    private suspend fun handleToolCall(toolCall: ToolCallSpec, ctx: RoundContext): Message.Tool {
        if (ctx.configSnapshot.tools.find(toolCall.toolName) == null) {
            val resultJson = jsonCodec.encode(mapOf("error" to "Unknown tool '${toolCall.toolName}'"))
            ctx.onEvent(
                SessionEvent.ToolFailed(
                    callId = toolCall.callId,
                    toolName = toolCall.toolName,
                    kind = ToolCallKind.Local,
                    message = "Unknown tool '${toolCall.toolName}'",
                    resultJson = resultJson
                )
            )
            ctx.onEvent(
                SessionEvent.Error(
                    stage = SessionEvent.Stage.Tool,
                    message = "Unknown tool '${toolCall.toolName}'"
                )
            )
            return Message.Tool(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                contentJson = resultJson
            )
        }
        val request = buildToolCallRequest(toolCall, ctx.configSnapshot)
        ctx.onEvent(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = ctx.configSnapshot.hooksBlock
        if (hooks == null && request is LocalToolCallRequest) {
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

        val toolMsg = xTrySuspend("ChatSession.handleToolCall", onError = { t ->
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
            if (hooks != null) {
                request.hooks()
            } else {
                request.delegate()
            }
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
                ctx.onEvent(
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Tool,
                        message = outcome.errorMessage
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
                        resultJson = toolMsg.contentJson
                    )
                )
            }
        }
        return toolMsg
    }

    private fun buildToolCallRequest(toolCall: ToolCallSpec, snap: SessionSnapshot): ToolCallRequest {
        val descriptor = snap.tools.find(toolCall.toolName)
        return when (val kind = descriptor?.kind) {
            ToolCallKind.Local -> LocalToolCallRequest(
                toolCall = toolCall,
                appParams = snap.appParams,
                codec = jsonCodec
            )

            is ToolCallKind.Mcp -> McpToolCallRequest(
                toolCall = toolCall,
                serverName = kind.serverName,
                appParams = snap.appParams,
                server = snap.mcpServer(kind.serverName),
                mcpClient = mcpClient,
                codec = jsonCodec
            )

            null -> error("Unknown tool '${toolCall.toolName}'")
        }
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

    private suspend fun discoveredToolsSnapshot(config: SessionConfig): Map<String, List<McpDiscoveredTool>> {
        return config.mcpRegistry.servers.mapNotNull { (serverName, server) ->
            if (!server.enabled) return@mapNotNull null
            val fingerprint = server.discoveryFingerprint(serverName)
            val tools = mcpDiscoveryCache.snapshot(serverName, fingerprint)
            tools?.let { serverName to it }
        }.toMap()
    }

    private fun scheduleDiscovery(
        config: SessionConfig,
        refreshCached: Boolean = false
    ) {
        config.mcpRegistry.servers.forEach { (serverName, serverConfig) ->
            if (!serverConfig.enabled) return@forEach
            val fingerprint = serverConfig.discoveryFingerprint(serverName)
            scope.launch {
                if (!refreshCached && mcpDiscoveryCache.snapshot(serverName, fingerprint) != null) return@launch
                if (!mcpDiscoveryCache.markRefreshing(serverName, fingerprint)) return@launch
                val serverSnapshot = serverConfig.deepCopy()
                val tools = xTrySuspend("ChatSession.scheduleDiscovery") {
                    mcpClient.listTools(serverSnapshot)
                }
                if (tools != null) {
                    val latestServer = thisConfig().mcpRegistry.servers[serverName]
                    if (latestServer?.enabled == true &&
                        latestServer.discoveryFingerprint(serverName) == fingerprint
                    ) {
                        mcpDiscoveryCache.put(serverName, fingerprint, tools)
                    }
                }
                mcpDiscoveryCache.markFinished(serverName, fingerprint)
            }
        }
    }
}
