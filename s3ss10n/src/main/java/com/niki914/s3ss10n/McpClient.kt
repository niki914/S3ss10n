package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.net.HttpTimeouts

internal interface McpClient {
    suspend fun call(server: McpServerConfig, toolName: String, argumentsJson: String): String
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
}
