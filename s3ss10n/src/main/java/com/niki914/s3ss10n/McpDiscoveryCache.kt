package com.niki914.s3ss10n

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>
)

internal class McpDiscoveryCache {
    private val cache = mutableMapOf<CacheKey, List<McpDiscoveredTool>>()
    private val refreshing = mutableSetOf<CacheKey>()
    private val mutex = Mutex()

    suspend fun snapshot(serverName: String, fingerprint: String): List<McpDiscoveredTool>? = mutex.withLock {
        cache[CacheKey(serverName, fingerprint)]
    }

    suspend fun put(serverName: String, fingerprint: String, tools: List<McpDiscoveredTool>) = mutex.withLock {
        cache[CacheKey(serverName, fingerprint)] = tools
    }

    suspend fun markRefreshing(serverName: String, fingerprint: String): Boolean = mutex.withLock {
        refreshing.add(CacheKey(serverName, fingerprint))
    }

    suspend fun markFinished(serverName: String, fingerprint: String) = mutex.withLock {
        refreshing.remove(CacheKey(serverName, fingerprint))
    }

    private data class CacheKey(
        val serverName: String,
        val fingerprint: String
    )
}
