package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.SessionProtocols
import com.niki914.s3ss10n.json.JsonCodec
import kotlinx.coroutines.runBlocking

class FakeCodec : JsonCodec {
    var encodeCalls = 0
    var decodeCalls = 0
    var decodeMapCalls = 0

    override fun encode(value: Any?): String {
        encodeCalls++
        return "{}"
    }

    override fun <T : Any> decode(json: String, type: Class<T>): T? {
        decodeCalls++
        return null
    }

    override fun decodeMap(json: String): Map<String, Any?>? {
        decodeMapCalls++
        return if (json == "complete_json") mapOf("a" to 1) else null
    }

    override fun decodeList(json: String): List<Any?>? {
        return null
    }
}

fun main13() = runBlocking {
    println("=== JsonCodec Abstraction Smoke Test ===")
    
    val fakeCodec = FakeCodec()
    val session = Session.open<SessionProtocols.OpenAI> {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test"
        model = "gpt-4.1-mini"
        jsonCodec = fakeCodec
    }

    println("Sending test message...")
    session.send("Hello") {
        if (it is SessionEvent.Error) {
            println("Expected error due to fake codec/network: ${it.message}")
        }
    }

    println("encode calls: ${fakeCodec.encodeCalls}")
    assertOrPrint("encode called", fakeCodec.encodeCalls > 0)
}
