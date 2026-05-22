package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.net.HttpTimeouts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal class RecordingChatProtocol(
    private val parseHandler: (Flow<String>) -> Flow<ProtocolEvent>
) : ChatProtocol {
    var lastSnapshot: SessionSnapshot? = null

    override fun useApiKey(apiKey: String): Map<String, String> = emptyMap()

    override fun buildRequest(
        snapshot: SessionSnapshot,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): HttpRequest {
        lastSnapshot = snapshot
        return HttpRequest(
            method = "POST",
            url = snapshot.endpoint,
            headers = emptyMap(),
            body = null,
            timeoutMs = HttpTimeouts(
                connectMs = 1_000,
                readMs = 1_000,
                writeMs = 1_000
            )
        )
    }

    override fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent> = parseHandler(rawSseLines)

    override fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult {
        return ChatTurn.ToolResult(
            callId = callId,
            toolName = toolName,
            resultJson = resultJson
        )
    }
}

internal class FakeHttpEngine(
    private val streamHandler: (HttpRequest) -> Flow<String> = { emptyFlow() },
    private val unaryHandler: suspend (HttpRequest) -> String = {
        error("Unexpected unary request: ${it.url}")
    }
) : HttpEngine {
    val streamRequests = mutableListOf<HttpRequest>()
    val unaryRequests = mutableListOf<HttpRequest>()

    override fun stream(request: HttpRequest): Flow<String> {
        streamRequests += request
        return streamHandler(request)
    }

    override suspend fun unary(request: HttpRequest): String {
        unaryRequests += request
        return unaryHandler(request)
    }

    override fun close() = Unit
}

internal class FakeMcpHttpEngine(
    private val toolsByUrl: Map<String, List<McpDiscoveredTool>>,
    private val failuresByUrl: Map<String, Throwable> = emptyMap(),
    private val codec: JsonCodec = GsonJsonCodec()
) : HttpEngine {
    val unaryCalls = mutableListOf<Pair<String, String>>()

    override fun stream(request: HttpRequest): Flow<String> = emptyFlow()

    override suspend fun unary(request: HttpRequest): String {
        val method = rpcMethod(request)
        unaryCalls += request.url to method
        if (method == "tools/list") {
            failuresByUrl[request.url]?.let { throw it }
        }
        return when (method) {
            "initialize" -> codec.encode(
                mapOf(
                    "result" to mapOf(
                        "protocolVersion" to "2025-06-18"
                    )
                )
            )

            "notifications/initialized" -> codec.encode(
                mapOf("result" to emptyMap<String, Any?>())
            )

            "tools/list" -> {
                val tools = toolsByUrl[request.url]
                    ?: error("Unexpected tools/list request: ${request.url}")
                codec.encode(
                    mapOf(
                        "result" to mapOf(
                            "tools" to tools.map { tool ->
                                mapOf(
                                    "name" to tool.name,
                                    "description" to tool.description,
                                    "inputSchema" to tool.inputSchema
                                )
                            }
                        )
                    )
                )
            }

            else -> error("Unexpected RPC method: $method")
        }
    }

    override fun close() = Unit

    private fun rpcMethod(request: HttpRequest): String {
        val body = request.body?.toString(Charsets.UTF_8)
            ?: error("Missing request body")
        val payload = codec.decodeMap(body)
            ?: error("Invalid JSON body: $body")
        return payload["method"] as? String
            ?: error("Missing RPC method: $body")
    }
}

internal fun newChatSession(
    protocol: ChatProtocol,
    engine: HttpEngine,
    configure: SessionConfig.() -> Unit = {}
): ChatSession {
    val config = SessionConfig().apply {
        endpoint = "https://example.com/chat"
        model = "test-model"
        httpEngine = engine
        configure()
    }
    return ChatSession(initialConfig = config, protocol = protocol)
}
