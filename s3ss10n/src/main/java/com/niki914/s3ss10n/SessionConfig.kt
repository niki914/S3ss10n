package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message

class SessionConfig {
    var endpoint: String = ""
    var apiKey: String = ""
    var model: String = ""
    var systemPrompt: String? = null
    var temperature: Float = 0.7f
    var connectTimeoutSeconds: Long = 30
    var readTimeoutSeconds: Long = 60
    var writeTimeoutSeconds: Long = 30

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

    internal fun buildToolDefinitions(): List<ToolDefinition> =
        localToolRegistry.toToolDefinitions()

    internal fun appParamsSnapshot(): Map<String, Any?> = _appParams.toMap()

    internal fun snapshot(): SessionConfig {
        val newConfig = SessionConfig()
        newConfig.endpoint = this.endpoint
        newConfig.apiKey = this.apiKey
        newConfig.model = this.model
        newConfig.systemPrompt = this.systemPrompt
        newConfig.temperature = this.temperature
        newConfig.connectTimeoutSeconds = this.connectTimeoutSeconds
        newConfig.readTimeoutSeconds = this.readTimeoutSeconds
        newConfig.writeTimeoutSeconds = this.writeTimeoutSeconds
        newConfig.hooksBlock = this.hooksBlock
        newConfig.localToolRegistry.copyFrom(this.localToolRegistry)
        newConfig.mcpRegistry.copyFrom(this.mcpRegistry)
        newConfig._appParams.putAll(this._appParams)
        return newConfig
    }
}
