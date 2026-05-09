package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.McpToolCallRequest
import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.SessionProtocols
import com.niki914.s3ss10n.ToolCallKind
import com.niki914.s3ss10n.ToolCallSpec
import com.niki914.s3ss10n.json.GsonJsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.net.OkHttpEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val LAN_MCP_URL = "http://127.0.0.1:51337/mcp"
private const val PROJECT_PATH = "/Users/bytedance/repo/android/personal/5_8_session"

fun main1() = runBlocking {
    val engine = SmokeEngine(blockFirstStream = true)
    val session = Session.open<SessionProtocols.OpenAI> {
        endpoint = "https://old.example/chat"
        model = "demo"
        httpEngine = engine
    }
    val first = launch { session.send("a") }
    engine.firstStreamStarted.await()
    session.update { endpoint = "https://new.example/chat" }
    engine.releaseFirstStream.complete(Unit)
    first.join()
    session.send("b")
    val pass = engine.streamUrls == listOf(
        "https://old.example/chat",
        "https://new.example/chat"
    )
    android.util.Log.e("X", "main1 update snapshot ${if (pass) "PASS" else "FAIL"} ${engine.streamUrls}")
    session.close()
}

fun main2() {
    val raw = """{"type":"object","properties":{"mode":{"type":"string","enum":["a","b"]}},"required":["mode"]}"""
    val config = SessionConfig.Builder().apply {
        endpoint = "https://example.com/chat"
        model = "demo"
        localTools {
            add("localRaw") {
                description = "local raw"
                rawJsonSchema(raw)
                string("ignored") { required = true }
            }
        }
        mcp {
            add("remote") {
                http { url = LAN_MCP_URL }
                tool("remoteRaw") {
                    description = "remote raw"
                    rawJsonSchema(raw)
                }
            }
        }
    }.build()
    val snap = config.toRoundSnapshot(GsonJsonCodec())
    val local = snap.tools.find("localRaw")
    val remote = snap.tools.find("remoteRaw")
    val enumKept = local?.inputSchema?.toString()?.contains("enum") == true
    val localKind = local?.kind == ToolCallKind.Local
    val remoteKind = remote?.kind == ToolCallKind.Mcp("remote")
    val pass = enumKept && localKind && remoteKind
    android.util.Log.e("X", "main2 raw schema/tool catalog ${if (pass) "PASS" else "FAIL"}")
}

fun main3() = runBlocking {
    val engine = OkHttpEngine()
    val server = com.niki914.s3ss10n.McpServerConfig().apply {
        http { url = LAN_MCP_URL }
    }
    try {
        val request = McpToolCallRequest(
            toolCall = ToolCallSpec(
                callId = "call-1",
                toolName = "search_file_names",
                argumentsJson = """{"nameSubstring":"OpenAIProtocol","projectPath":"$PROJECT_PATH","limit":5}"""
            ),
            serverName = "remote",
            appParams = emptyMap(),
            server = server,
            mcpClient = com.niki914.s3ss10n.HttpMcpClient(engine, GsonJsonCodec())
        )
        val result = request.delegate()
        val pass = result.contains("OpenAIProtocol.kt")
        android.util.Log.e("X", "main3 real mcp search_file_names ${if (pass) "PASS" else "FAIL"} $result")
    } finally {
        engine.close()
    }
}

fun main() {
    main1()
    main2()
    main3()
}

private class SmokeEngine(
    private val blockFirstStream: Boolean = false
) : HttpEngine {
    val firstStreamStarted = CompletableDeferred<Unit>()
    val releaseFirstStream = CompletableDeferred<Unit>()
    val streamUrls = mutableListOf<String>()
    val unaryUrls = mutableListOf<String>()

    override fun stream(request: HttpRequest): Flow<String> = flow {
        streamUrls += request.url
        if (!firstStreamStarted.isCompleted) {
            firstStreamStarted.complete(Unit)
            if (blockFirstStream) {
                releaseFirstStream.await()
            }
        }
        emit("""{"choices":[{"delta":{"content":"ok","reasoning_content":null,"tool_calls":null}}]}""")
    }

    override suspend fun unary(request: HttpRequest): String {
        unaryUrls += request.url
        return """{"ok":true}"""
    }

    override fun close() = Unit
}
