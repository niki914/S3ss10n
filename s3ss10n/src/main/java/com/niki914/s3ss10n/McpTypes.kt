package com.niki914.s3ss10n

sealed interface McpTransport {
    data class Http(var url: String = "") : McpTransport
}

typealias McpToolConfig = LocalToolConfig

data class McpServerConfig(
    var enabled: Boolean = true,
    var transport: McpTransport = McpTransport.Http(),
    var headers: Map<String, String> = emptyMap()
) {
    internal val tools = mutableMapOf<String, McpToolConfig>()

    fun http(block: McpTransport.Http.() -> Unit) {
        val http = (transport as? McpTransport.Http) ?: McpTransport.Http()
        transport = http.apply(block)
    }

    fun tool(name: String, block: McpToolConfig.() -> Unit) {
        tools[name] = McpToolConfig().apply(block)
    }

    internal fun deepCopy(): McpServerConfig {
        val newConfig = copy(
            headers = headers.toMap(),
            transport = when (val current = transport) {
                is McpTransport.Http -> current.copy()
            }
        )
        tools.forEach { (name, config) ->
            newConfig.tools[name] = config.deepCopy()
        }
        return newConfig
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

    internal fun copyFrom(other: McpRegistryImpl) {
        _servers.clear()
        other._servers.forEach { (k, v) ->
            _servers[k] = v.deepCopy()
        }
    }

    internal fun toToolDescriptors(codec: com.niki914.s3ss10n.json.JsonCodec): List<ToolDescriptor> =
        _servers.flatMap { (serverName, server) ->
            if (!server.enabled) {
                emptyList()
            } else {
                server.tools.map { (toolName, toolConfig) ->
                    toolConfig.toToolDescriptor(
                        toolName = toolName,
                        kind = ToolCallKind.Mcp(serverName),
                        codec = codec
                    )
                }
            }
        }
}
