package com.niki914.s3ss10n

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SessionState(
    initialConfig: SessionConfig
) {

    private val configMutex: Mutex = Mutex()
    private var config: SessionConfig = initialConfig
    private val roundMutex: Mutex = Mutex()
    private var currentRoundJob: Job? = null

    internal suspend fun currentConfig(): SessionConfig {
        return configMutex.withLock { config }
    }

    internal suspend fun updateConfig(
        block: SessionConfig.Builder.() -> Unit
    ): SessionConfig {
        return configMutex.withLock {
            val baseCodec = config.jsonCodec
            val baseEngine = config.httpEngine
            val updated = config.toBuilder().apply(block).build()
            if (updated.jsonCodec !== baseCodec) {
                xLog("X", "update ignored open-only field: jsonCodec")
                updated.jsonCodec = baseCodec
            }
            if (updated.httpEngine !== baseEngine) {
                xLog("X", "update ignored open-only field: httpEngine")
                updated.httpEngine = baseEngine
            }
            config = updated
            updated
        }
    }

    internal fun runReplacingCurrent(
        scope: CoroutineScope,
        cleanupTools: suspend () -> Unit,
        block: suspend () -> Unit
    ): Deferred<Unit> = scope.async {
        val roundJob = roundMutex.withLock {
            cancelCurrentRoundLocked(cleanupTools)
            scope.async {
                block()
            }.also { currentRoundJob = it }
        }
        roundJob.await()
    }

    internal suspend fun cancelCurrentRound(
        cleanupTools: suspend () -> Unit
    ) {
        roundMutex.withLock {
            cancelCurrentRoundLocked(cleanupTools)
        }
    }

    private suspend fun cancelCurrentRoundLocked(
        cleanupTools: (suspend () -> Unit)?
    ) {
        currentRoundJob?.cancel()
        currentRoundJob?.join()
        cleanupTools?.invoke()
        currentRoundJob = null
    }
}
