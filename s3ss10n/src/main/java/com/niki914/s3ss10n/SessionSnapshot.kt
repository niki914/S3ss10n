package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpTimeouts

data class SessionSnapshot(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String?,
    val temperature: Float,
    val timeouts: HttpTimeouts,
    val hooksBlock: (suspend ToolCallRequest.() -> String)?,
    val appParams: Map<String, Any?>,
    val tools: ToolCatalog,
    val mcpServers: Map<String, McpServerConfig>,
    val jsonCodec: JsonCodec
) {
    fun mcpServer(name: String): McpServerConfig? = mcpServers[name]
}

data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val kind: ToolCallKind
)

data class ToolCatalog(
    val descriptors: List<ToolDescriptor>
) {
    private val byName = descriptors.associateBy { it.name }

    fun find(name: String): ToolDescriptor? = byName[name]
}
