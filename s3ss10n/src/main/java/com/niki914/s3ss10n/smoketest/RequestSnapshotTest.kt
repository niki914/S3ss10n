package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionProtocols
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private class SlowEngine : HttpEngine {
    var lastReq: HttpRequest? = null

    override fun stream(request: HttpRequest): Flow<String> = flow {
        lastReq = request
        delay(100)
        emit("""{"choices":[{"delta":{"content":"hello"}}]}""")
    }

    override fun close() {}
}

fun main15() = runBlocking {
    println("=== RequestSnapshotTest ===")
    val engine = SlowEngine()
    val session = Session.open<SessionProtocols.OpenAI> {
        endpoint = "https://old.com/v1/chat/completions"
        apiKey = "test"
        model = "fake-model"
        httpEngine = engine
    }

    val job = launch {
        session.send("hi") {}
    }
    
    delay(50) // wait for stream to start
    session.update {
        endpoint = "https://new.com/v1/chat/completions"
    }
    
    job.join()
    
    assertOrPrint("url is old", engine.lastReq?.url == "https://old.com/v1/chat/completions")
    
    session.send("hi again") {}
    assertOrPrint("url is new", engine.lastReq?.url == "https://new.com/v1/chat/completions")
    
    session.close()
    println("=== PASSED ===")
}
