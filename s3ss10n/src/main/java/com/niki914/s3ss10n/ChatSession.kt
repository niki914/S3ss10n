package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.ext.net.OkHttpEngine
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import java.util.concurrent.atomic.AtomicReference
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

    private val configRef = AtomicReference(initialConfig)
    private val engine: HttpEngine = initialConfig.httpEngine ?: OkHttpEngine()
    private val jsonCodec: JsonCodec = initialConfig.jsonCodec ?: GsonJsonCodec()
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
        scheduleDiscovery(initialConfig, reason = "open")
    }

    private class RoundContext(
        val configSnapshot: SessionSnapshot,
        val onEvent: (SessionEvent) -> Unit,
        val initialInput: String,
        val textAccumulator: StringBuilder = StringBuilder(),
        var hasStarted: Boolean = false
    )

    override suspend fun send(text: String, onEvent: (SessionEvent) -> Unit) {
        val config = configRef.get()
        scheduleDiscovery(config, reason = "send")
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

    override fun update(block: SessionConfig.Builder.() -> Unit) {
        val updatedConfig = configRef.updateAndGet { current ->
            val baseCodec = current.jsonCodec
            val baseEngine = current.httpEngine
            val updated = current.toBuilder().apply(block).build()
            if (updated.jsonCodec !== baseCodec) {
                xLog("X", "update ignored open-only field: jsonCodec")
                updated.jsonCodec = baseCodec
            }
            if (updated.httpEngine !== baseEngine) {
                xLog("X", "update ignored open-only field: httpEngine")
                updated.httpEngine = baseEngine
            }
            updated
        }
        scheduleDiscovery(updatedConfig, reason = "update", refreshCached = true)
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
        if (ctx.configSnapshot.tools.find(toolCall.toolName) == null) {
            val resultJson = """{"error":"Unknown tool '${toolCall.toolName}'"}"""
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
            return resultJson
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
                        resultJson = resultJson
                    )
                )
            }
        }
        return resultJson
    }

    private fun buildToolCallRequest(toolCall: ToolCallSpec, snap: SessionSnapshot): ToolCallRequest {
        val descriptor = snap.tools.find(toolCall.toolName)
        return when (val kind = descriptor?.kind) {
            ToolCallKind.Local -> LocalToolCallRequest(
                toolCall = toolCall,
                appParams = snap.appParams
            )

            is ToolCallKind.Mcp -> McpToolCallRequest(
                toolCall = toolCall,
                serverName = kind.serverName,
                appParams = snap.appParams,
                server = snap.mcpServer(kind.serverName),
                mcpClient = mcpClient
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

    private fun classifyStage(t: Throwable): SessionEvent.Stage {
        return when (t) {
            is ConfigInvalidException -> SessionEvent.Stage.Session
            is IllegalArgumentException -> SessionEvent.Stage.Session
            is IllegalStateException -> SessionEvent.Stage.Parse
            else -> SessionEvent.Stage.Transport
        }
    }

    private fun discoveredToolsSnapshot(config: SessionConfig): Map<String, List<McpDiscoveredTool>> {
        return config.mcpRegistry.servers.mapNotNull { (serverName, server) ->
            if (!server.enabled) {
                android.util.Log.d("qwerqwer", "MCP discovery cache skip server=$serverName reason=disabled")
                return@mapNotNull null
            }
            val fingerprint = server.discoveryFingerprint(serverName)
            val tools = mcpDiscoveryCache.snapshot(serverName, fingerprint)
            android.util.Log.d(
                "qwerqwer",
                "MCP discovery cache ${if (tools == null) "miss" else "hit"} server=$serverName " +
                    "fingerprint=$fingerprint tools=${tools?.map { it.name }.orEmpty()}"
            )
            tools?.let { serverName to it }
        }.toMap()
    }

    private fun scheduleDiscovery(
        config: SessionConfig,
        reason: String,
        refreshCached: Boolean = false
    ) {
        config.mcpRegistry.servers.forEach { (serverName, serverConfig) ->
            if (!serverConfig.enabled) {
                android.util.Log.d("qwerqwer", "MCP discovery skipped server=$serverName reason=disabled")
                return@forEach
            }
            val fingerprint = serverConfig.discoveryFingerprint(serverName)
            if (!refreshCached && mcpDiscoveryCache.snapshot(serverName, fingerprint) != null) {
                android.util.Log.d(
                    "qwerqwer",
                    "MCP discovery skipped server=$serverName fingerprint=$fingerprint reason=cache-hit"
                )
                return@forEach
            }
            if (!mcpDiscoveryCache.markRefreshing(serverName, fingerprint)) {
                android.util.Log.d(
                    "qwerqwer",
                    "MCP discovery skipped server=$serverName fingerprint=$fingerprint reason=already-refreshing"
                )
                return@forEach
            }
            val serverSnapshot = serverConfig.deepCopy()
            android.util.Log.d(
                "qwerqwer",
                "MCP discovery scheduled server=$serverName fingerprint=$fingerprint reason=$reason"
            )
            scope.launch {
                try {
                    val tools = mcpClient.listTools(serverSnapshot)
                    val latestServer = configRef.get().mcpRegistry.servers[serverName]
                    if (latestServer?.enabled == true &&
                        latestServer.discoveryFingerprint(serverName) == fingerprint
                    ) {
                        mcpDiscoveryCache.put(serverName, fingerprint, tools)
                        android.util.Log.d(
                            "qwerqwer",
                            "MCP discovery success server=$serverName tools=${tools.map { it.name }}"
                        )
                    } else {
                        android.util.Log.d(
                            "qwerqwer",
                            "MCP discovery stale ignored server=$serverName fingerprint=$fingerprint"
                        )
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.d(
                        "qwerqwer",
                        "MCP discovery failed server=$serverName error=${t.message}",
                        t
                    )
                } finally {
                    mcpDiscoveryCache.markFinished(serverName, fingerprint)
                }
            }
        }
    }
}
