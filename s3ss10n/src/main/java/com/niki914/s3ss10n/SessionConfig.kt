package com.niki914.s3ss10n

import com.niki914.s3ss10n.protocol.openai.ToolDefinition

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
    var jsonCodec: com.niki914.s3ss10n.json.JsonCodec? = null

    internal var hooksBlock: (suspend ToolCallRequest.() -> String)? = null
    internal val localToolRegistry = LocalToolRegistryImpl()
    internal val mcpRegistry = McpRegistryImpl()
    internal val _appParams = mutableMapOf<String, Any?>()

    fun hooks(block: suspend ToolCallRequest.() -> String) {
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

    internal fun buildToolDefinitions(): List<ToolDefinition> =
        localToolRegistry.toToolDefinitions()

    internal fun appParamsSnapshot(): Map<String, Any?> = _appParams.toMap()

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
