package com.niki914.s3ss10n

internal data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>
)

internal class McpDiscoveryCache {
    private val cache = mutableMapOf<CacheKey, List<McpDiscoveredTool>>()
    private val refreshing = mutableSetOf<CacheKey>()

    @Synchronized
    fun snapshot(serverName: String, fingerprint: String): List<McpDiscoveredTool>? {
        return cache[CacheKey(serverName, fingerprint)]
    }

    @Synchronized
    fun put(serverName: String, fingerprint: String, tools: List<McpDiscoveredTool>) {
        cache[CacheKey(serverName, fingerprint)] = tools
    }

    @Synchronized
    fun markRefreshing(serverName: String, fingerprint: String): Boolean {
        return refreshing.add(CacheKey(serverName, fingerprint))
    }

    @Synchronized
    fun markFinished(serverName: String, fingerprint: String) {
        refreshing.remove(CacheKey(serverName, fingerprint))
    }

    private data class CacheKey(
        val serverName: String,
        val fingerprint: String
    )
}
