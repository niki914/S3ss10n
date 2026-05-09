package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionProtocols
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private class FakeEngine : HttpEngine {
    var lastReq: HttpRequest? = null
    var closed = false

    override fun stream(request: HttpRequest): Flow<String> {
        lastReq = request
        return flowOf("""{"choices":[{"delta":{"content":"hello"}}]}""")
    }

    override fun close() {
        closed = true
    }
}

fun main14() = runBlocking {
    println("=== HttpEngineAbstractionTest ===")
    val engine = FakeEngine()
    val session = Session.open<SessionProtocols.OpenAI> {
        endpoint = "https://example.com/v1/chat/completions"
        apiKey = "test"
        model = "fake-model"
        httpEngine = engine
    }

    session.send("hi") {}
    
    assertOrPrint("engine received request", engine.lastReq != null)
    assertOrPrint("url correct", engine.lastReq?.url == "https://example.com/v1/chat/completions")
    
    session.close()
    assertOrPrint("engine closed", engine.closed)
    
    println("=== PASSED ===")
}
