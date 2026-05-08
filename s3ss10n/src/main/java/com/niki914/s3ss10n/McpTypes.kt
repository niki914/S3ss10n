package com.niki914.s3ss10n

sealed interface McpTransport {
    data class Http(var url: String = "") : McpTransport
}

data class McpServerConfig(
    var enabled: Boolean = true,
    var transport: McpTransport = McpTransport.Http(),
    var headers: Map<String, String> = emptyMap()
) {
    fun http(block: McpTransport.Http.() -> Unit) {
        val http = (transport as? McpTransport.Http) ?: McpTransport.Http()
        transport = http.apply(block)
    }
}

interface McpRegistry {
    fun add(name: String, block: McpServerConfig.() -> Unit)
    fun replace(name: String, block: McpServerConfig.() -> Unit)
    fun remove(name: String)
}

internal class McpRegistryImpl : McpRegistry {
    private val _servers = mutableMapOf<String, McpServerConfig>()

    val servers: Map<String, McpServerConfig> get() = _servers.toMap()

    override fun add(name: String, block: McpServerConfig.() -> Unit) {
        _servers[name] = McpServerConfig().apply(block)
    }

    override fun replace(name: String, block: McpServerConfig.() -> Unit) {
        _servers[name] = McpServerConfig().apply(block)
    }

    override fun remove(name: String) {
        _servers.remove(name)
    }
}
