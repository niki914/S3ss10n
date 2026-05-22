package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFlowRegressionTest {
    @Test
    fun `Flow 重载可以桥接跨协程事件`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("hello"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.TextDelta(delta = "hello", fullText = "hello"),
                    SessionEvent.RoundCompleted(fullText = "hello")
                ),
                events
            )
        } finally {
            session.close()
        }
    }
}
