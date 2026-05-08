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

    fun hooks(block: suspend ToolCallRequest.() -> Message.Tool) {
        hooksBlock = block
    }

    fun localTools(block: LocalToolRegistry.() -> Unit) {
        localToolRegistry.apply(block)
    }

    fun mcp(block: McpRegistry.() -> Unit) {
        mcpRegistry.apply(block)
    }

    internal fun buildToolDefinitions(): List<ToolDefinition> =
        localToolRegistry.toToolDefinitions()

    internal fun buildAppParams(): Map<String, Any?> = emptyMap()
}
