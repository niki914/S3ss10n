package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpTimeouts

open class SessionConfig {
    var endpoint: String = ""
    var apiKey: String = ""
    var model: String = ""
    var systemPrompt: String? = null
    var temperature: Float = 0.7f
    var connectTimeoutSeconds: Long = 30
    var readTimeoutSeconds: Long = 60
    var writeTimeoutSeconds: Long = 30

    /**
     * 自定义 JSON 编解码器。
     * null 表示使用默认的 GsonJsonCodec()。
     */
    var jsonCodec: JsonCodec? = null

    /**
     * 自定义 HttpEngine。
     * null 表示使用默认 OkHttpEngine()；自定义 engine 时 OkHttp 不会被实例化
     */
    var httpEngine: com.niki914.s3ss10n.net.HttpEngine? = null

    internal var hooksBlock: (suspend ToolCallRequest.() -> Message.Tool)? = null
    internal val localToolRegistry = LocalToolRegistryImpl()
    internal val mcpRegistry = McpRegistryImpl()
    internal val _appParams = mutableMapOf<String, Any?>()

    fun hooks(block: suspend ToolCallRequest.() -> Message.Tool) {
        hooksBlock = block
    }

    fun localTools(block: LocalToolRegistry.() -> Unit) {
        localToolRegistry.apply(block)
    }

    fun mcp(block: McpRegistry.() -> Unit) {
        mcpRegistry.apply(block)
    }

    fun appParams(block: MutableMap<String, Any?>.() -> Unit) {
        _appParams.apply(block)
    }

    internal fun buildToolCatalog(
        codec: JsonCodec,
        discoveredMcpTools: Map<String, List<McpDiscoveredTool>> = emptyMap()
    ): ToolCatalog {
        val localDescriptors = localToolRegistry.toToolDescriptors(codec)
        val mcpDescriptors = mcpRegistry.toToolDescriptors(codec, discoveredMcpTools)
        return ToolCatalog(
            descriptors = localDescriptors + mcpDescriptors
        )
    }

    internal fun appParamsSnapshot(): Map<String, Any?> = _appParams.toMap()

    internal fun toRoundSnapshot(
        codec: JsonCodec,
        discoveredMcpTools: Map<String, List<McpDiscoveredTool>> = emptyMap()
    ): SessionSnapshot {
        return SessionSnapshot(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            temperature = temperature,
            timeouts = HttpTimeouts(
                connectMs = connectTimeoutSeconds * 1000,
                readMs = readTimeoutSeconds * 1000,
                writeMs = writeTimeoutSeconds * 1000
            ),
            hooksBlock = hooksBlock,
            appParams = appParamsSnapshot(),
            tools = buildToolCatalog(codec, discoveredMcpTools),
            mcpServers = mcpRegistry.servers.mapValues { (_, server) -> server.deepCopy() },
            jsonCodec = codec
        )
    }

    internal fun snapshot(): SessionConfig {
        val newConfig = SessionConfig()
        copyInto(newConfig)
        return newConfig
    }

    internal fun toBuilder(): Builder {
        return Builder().also { copyInto(it) }
    }

    protected fun copyInto(target: SessionConfig) {
        target.endpoint = endpoint
        target.apiKey = apiKey
        target.model = model
        target.systemPrompt = systemPrompt
        target.temperature = temperature
        target.connectTimeoutSeconds = connectTimeoutSeconds
        target.readTimeoutSeconds = readTimeoutSeconds
        target.writeTimeoutSeconds = writeTimeoutSeconds
        target.jsonCodec = jsonCodec
        target.httpEngine = httpEngine
        target.hooksBlock = hooksBlock
        target.localToolRegistry.copyFrom(localToolRegistry)
        target.mcpRegistry.copyFrom(mcpRegistry)
        target._appParams.putAll(_appParams)
    }

    class Builder : SessionConfig() {
        fun build(): SessionConfig {
            return snapshot()
        }
    }
}
