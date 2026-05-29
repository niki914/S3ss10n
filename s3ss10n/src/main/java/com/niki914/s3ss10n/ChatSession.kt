package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodecFactory
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.ext.net.OkHttpEngine
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.util.HistoryKeeper
import kotlinx.coroutines.async
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
    private val roundRunner = RoundRunner(
        protocol = protocol,
        engine = engine,
        historyKeeper = historyKeeper,
        toolCallCoordinator = toolCallCoordinator,
        scope = scope
    )

    init {
        initialConfig.localToolRegistry.codec = jsonCodec
        mcpDiscoveryCoordinator.scheduleDiscovery(initialConfig)
    }

    override suspend fun send(text: String, onEvent: suspend (SessionEvent) -> Unit) {
        val config = thisConfig()
        mcpDiscoveryCoordinator.scheduleDiscovery(config)
        val input = RoundInput(
            snapshot = config.toRoundSnapshot(
                codec = jsonCodec,
                discoveredMcpTools = mcpDiscoveryCoordinator.discoveredToolsSnapshot(config)
            ),
            initialInput = text,
            onEvent = onEvent
        )
        awaitRound(runRound(input))
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

    private fun runRound(input: RoundInput): Deferred<Unit> = scope.async {
        val roundJob = chatMutex.withLock {
            cleanUpCurrWork()
            scope.async {
                roundRunner.run(input)
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
        roundRunner.cancelAndClearTools(join = true)
    }

}
