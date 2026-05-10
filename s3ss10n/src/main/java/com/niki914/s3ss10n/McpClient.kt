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
    private val lifecycleCache = McpLifecycleCache()

    private fun jsonRpcRequest(method: String, params: Map<String, Any?> = emptyMap()): String {
        return codec.encode(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to System.currentTimeMillis().toString(),
                "method" to method,
                "params" to params
            )
        )
    }

    override suspend fun call(server: McpServerConfig, toolName: String, argumentsJson: String): String {
        val transport = server.transport
        if (transport !is McpTransport.Http || transport.url.isBlank()) {
            throw IllegalArgumentException("Unsupported MCP transport")
        }
        val fingerprint = server.discoveryFingerprint("call")
        xTrySuspend("McpClient.call", onError = { t ->
            throw IllegalStateException("MCP initialize failed: ${t.message}", t)
        }) {
            ensureInitialized(server, fingerprint)
        }
        val arguments = codec.decodeMap(argumentsJson) ?: emptyMap<String, Any?>()
        val body = jsonRpcRequest("tools/call", mapOf("name" to toolName, "arguments" to arguments))
        val rawResponse = engine.unary(
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
        return normalizeResult(rawResponse)
    }

    private fun normalizeResult(rawResponse: String): String {
        val root = codec.decodeMap(rawResponse) ?: return codec.encode(mapOf("error" to "Invalid MCP response"))
        val error = root["error"]
        if (error != null) {
            val errorMap = error as? Map<*, *>
            val errMsg = errorMap?.get("message") as? String ?: "MCP tool call error"
            return codec.encode(mapOf("error" to errMsg))
        }
        val result = root["result"] ?: return codec.encode(mapOf("error" to "Missing result"))
        val resultMap = result as? Map<*, *> ?: return codec.encode(result)
        val isError = resultMap["isError"] as? Boolean == true
        val normalized: String? = normalizeStructuredContent(resultMap)
            ?: normalizeContentArray(resultMap)
        if (normalized != null) {
            if (isError) {
                return codec.encode(
                    mapOf("error" to "MCP tool error", "detail" to codec.decodeMap(normalized))
                )
            }
            return normalized
        }
        return codec.encode(resultMap)
    }

    private fun normalizeStructuredContent(result: Map<*, *>): String? {
        val sc = result["structuredContent"] ?: return null
        return sc as? String ?: codec.encode(sc)
    }

    private fun normalizeContentArray(result: Map<*, *>): String? {
        val content = result["content"] as? List<*> ?: return null
        if (content.isEmpty()) return null
        val first = content.first()
        if (first is Map<*, *>) {
            val type = first["type"] as? String
            if (type == "text") {
                val text = first["text"] as? String ?: return null
                val parsed = codec.decodeMap(text)
                if (parsed != null) {
                    return codec.encode(parsed)
                }
                return text
            }
        }
        return codec.encode(content)
    }

    override suspend fun listTools(server: McpServerConfig): List<McpDiscoveredTool> {
        val transport = server.transport
        if (transport !is McpTransport.Http || transport.url.isBlank()) {
            throw IllegalArgumentException("Unsupported MCP transport")
        }
        val fingerprint = server.discoveryFingerprint("discovery")
        xTrySuspend("McpClient.listTools") {
            ensureInitialized(server, fingerprint)
        }
        val body = jsonRpcRequest("tools/list")
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

    private suspend fun ensureInitialized(server: McpServerConfig, fingerprint: String) {
        if (lifecycleCache.isInitialized(fingerprint)) return
        val transport = server.transport as? McpTransport.Http ?: return
        val initBody = jsonRpcRequest("initialize", mapOf(
            "protocolVersion" to "2025-06-18",
            "capabilities" to emptyMap<String, Any?>(),
            "clientInfo" to mapOf("name" to "com.niki914.s3ss10n", "version" to "1.9.9a")
        ))
        val initResponse = engine.unary(
            HttpRequest(
                method = "POST",
                url = transport.url,
                headers = server.headers + mapOf("Content-Type" to "application/json"),
                body = initBody.toByteArray(Charsets.UTF_8),
                timeoutMs = HttpTimeouts(
                    connectMs = 30_000,
                    readMs = 60_000,
                    writeMs = 30_000
                ),
                isStreaming = false
            )
        )
        val initRoot = codec.decodeMap(initResponse)
        if (initRoot == null || initRoot["error"] != null) {
            val errMsg = (initRoot?.get("error") as? Map<*, *>)?.get("message") ?: "initialize failed"
            throw IllegalStateException("MCP initialize error: $errMsg")
        }
        val notifBody = jsonRpcRequest("notifications/initialized")
        engine.unary(
            HttpRequest(
                method = "POST",
                url = transport.url,
                headers = server.headers + mapOf("Content-Type" to "application/json"),
                body = notifBody.toByteArray(Charsets.UTF_8),
                timeoutMs = HttpTimeouts(
                    connectMs = 30_000,
                    readMs = 60_000,
                    writeMs = 30_000
                ),
                isStreaming = false
            )
        )
        lifecycleCache.markInitialized(fingerprint)
    }

    private fun Any?.asStringMap(): Map<String, Any?>? {
        val raw = this as? Map<*, *> ?: return null
        return raw.entries.associate { (key, value) ->
            (key as? String ?: return null) to value
        }
    }
}
