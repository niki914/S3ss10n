package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.net.HttpTimeouts

internal interface McpClient {
    suspend fun call(server: McpServerConfig, toolName: String, argumentsJson: String): String
    suspend fun listTools(server: McpServerConfig): List<McpDiscoveredTool>
}

internal class HttpMcpClient(
    private val engine: HttpEngine,
    private val codec: JsonCodec
) : McpClient {
    override suspend fun call(server: McpServerConfig, toolName: String, argumentsJson: String): String {
        val transport = server.transport
        if (transport !is McpTransport.Http || transport.url.isBlank()) {
            throw IllegalArgumentException("Unsupported MCP transport")
        }
        val body = codec.encode(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to System.currentTimeMillis().toString(),
                "method" to "tools/call",
                "params" to mapOf(
                    "name" to toolName,
                    "arguments" to (codec.decodeMap(argumentsJson) ?: emptyMap<String, Any?>())
                )
            )
        )
        return engine.unary(
            HttpRequest(
                method = "POST",
                url = transport.url,
                headers = server.headers + mapOf("Content-Type" to "application/json"),
                body = body.toByteArray(Charsets.UTF_8),
                timeoutMs = HttpTimeouts(
                    connectMs = 30_000,
                    readMs = 60_000,
                    writeMs = 30_000
                ),
                isStreaming = false
            )
        )
    }

    override suspend fun listTools(server: McpServerConfig): List<McpDiscoveredTool> {
        val transport = server.transport
        if (transport !is McpTransport.Http || transport.url.isBlank()) {
            android.util.Log.d("qwerqwer", "MCP discovery unsupported transport transport=${server.transport}")
            throw IllegalArgumentException("Unsupported MCP transport")
        }
        val body = codec.encode(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to System.currentTimeMillis().toString(),
                "method" to "tools/list",
                "params" to emptyMap<String, Any?>()
            )
        )
        val response = engine.unary(
            HttpRequest(
                method = "POST",
                url = transport.url,
                headers = server.headers + mapOf("Content-Type" to "application/json"),
                body = body.toByteArray(Charsets.UTF_8),
                timeoutMs = HttpTimeouts(
                    connectMs = 30_000,
                    readMs = 60_000,
                    writeMs = 30_000
                ),
                isStreaming = false
            )
        )
        val root = codec.decodeMap(response)
            ?: throw IllegalStateException("Invalid MCP tools/list response")
        val result = root["result"].asStringMap()
            ?: throw IllegalStateException("Missing MCP tools/list result")
        val tools = result["tools"] as? List<*>
            ?: throw IllegalStateException("Missing MCP tools/list tools")
        return tools.mapNotNull { item ->
            val tool = item.asStringMap() ?: return@mapNotNull null
            val name = tool["name"] as? String ?: return@mapNotNull null
            val schema = tool["inputSchema"].asStringMap() ?: emptyMap()
            McpDiscoveredTool(
                name = name,
                description = tool["description"] as? String ?: "",
                inputSchema = schema
            )
        }
    }

    private fun Any?.asStringMap(): Map<String, Any?>? {
        val raw = this as? Map<*, *> ?: return null
        return raw.entries.associate { (key, value) ->
            (key as? String ?: return null) to value
        }
    }
}
