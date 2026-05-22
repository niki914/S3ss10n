package com.niki914.s3ss10n

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>
)

internal class McpDiscoveryCache {
    private val cache = mutableMapOf<CacheKey, List<McpDiscoveredTool>>()
    private val inFlight = mutableMapOf<CacheKey, Deferred<*>>()
    private val mutex = Mutex()

    suspend fun snapshot(serverName: String, fingerprint: String): List<McpDiscoveredTool>? = mutex.withLock {
        cache[CacheKey(serverName, fingerprint)]
    }

    suspend fun put(serverName: String, fingerprint: String, tools: List<McpDiscoveredTool>) = mutex.withLock {
        cache[CacheKey(serverName, fingerprint)] = tools
    }

    suspend fun <T> acquireRefresh(
        serverName: String,
        fingerprint: String,
        scope: CoroutineScope,
        block: suspend () -> T
    ): Pair<Deferred<T>, Boolean> = mutex.withLock {
        val key = CacheKey(serverName, fingerprint)
        @Suppress("UNCHECKED_CAST")
        val existing = inFlight[key] as? Deferred<T>
        if (existing != null && existing.isActive) {
            existing to false
        } else {
            if (existing != null) {
                inFlight.remove(key)
            }
            val deferred = scope.async { block() }
            inFlight[key] = deferred
            deferred to true
        }
    }

    suspend fun clearRefresh(serverName: String, fingerprint: String, deferred: Deferred<*>) = mutex.withLock {
        val key = CacheKey(serverName, fingerprint)
        if (inFlight[key] === deferred) {
            inFlight.remove(key)
        }
    }

    private data class CacheKey(
        val serverName: String,
        val fingerprint: String
    )
}
