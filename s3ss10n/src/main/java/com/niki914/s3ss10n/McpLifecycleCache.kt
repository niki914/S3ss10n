package com.niki914.s3ss10n

internal class McpLifecycleCache {
    private val states = mutableMapOf<String, Boolean>()

    @Synchronized
    fun isInitialized(fingerprint: String): Boolean = states[fingerprint] == true

    @Synchronized
    fun markInitialized(fingerprint: String) {
        states[fingerprint] = true
    }

    @Synchronized
    fun invalidate(fingerprint: String) {
        states.remove(fingerprint)
    }
}
