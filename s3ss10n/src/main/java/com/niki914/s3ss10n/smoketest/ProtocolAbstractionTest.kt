package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.protocol.ChatProtocol
import com.niki914.s3ss10n.protocol.ProtocolEvent
import com.niki914.s3ss10n.protocol.ProtocolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

private class FakeProtocol : ChatProtocol {
    override fun buildRequestBody(
        snapshot: SessionConfig,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): String = """{"fake":true}"""

    override fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent> = emptyFlow()

    override fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult {
        return ChatTurn.ToolResult(callId, toolName, resultJson)
    }
}

fun main12() = runBlocking {
    println("=== ProtocolAbstractionTest ===")
    ProtocolRegistry.register(FakeProtocol::class, FakeProtocol())

    val session = Session.open<FakeProtocol> {
        endpoint = "https://example.com/v1/chat/completions"
        model = "fake-model"
    }

    assertOrPrint("custom protocol session created", session != null)
    assertOrPrint("initial history empty", session.getHistory().isEmpty())
    session.close()
    println("=== PASSED ===")
}
