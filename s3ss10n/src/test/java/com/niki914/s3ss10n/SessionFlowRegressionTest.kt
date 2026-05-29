package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFlowRegressionTest {
    @Test
    fun `空 assistant 响应不写入 history 且发出 error 和 complete`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Parse,
                        message = "Empty assistant response"
                    ),
                    SessionEvent.RoundCompleted(fullText = "")
                ),
                events
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            session.close()
        }
    }

    @Test
    fun `正常文本响应提交 user 和 assistant history`() = runBlocking {
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
            assertEquals(
                listOf(
                    ChatTurn.User(content = "hi"),
                    ChatTurn.Assistant(content = "hello")
                ),
                session.getHistory()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `已开始 round 发生异常时仍发出 complete`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flow {
                emit(ProtocolEvent.TextDelta("hello"))
                throw IllegalStateException("boom")
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(4, events.size)
            assertEquals(SessionEvent.RoundStarted(input = "hi"), events[0])
            assertEquals(
                SessionEvent.TextDelta(delta = "hello", fullText = "hello"),
                events[1]
            )
            val error = events[2] as SessionEvent.Error
            assertEquals(SessionEvent.Stage.Parse, error.stage)
            assertEquals("boom", error.message)
            assertTrue(error.cause is IllegalStateException)
            assertEquals(SessionEvent.RoundCompleted(fullText = "hello"), events[3])
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            session.close()
        }
    }

    @Test
    fun `协议 error 后无内容不追加空 assistant error`() = runBlocking {
        val protocolError = IllegalStateException("protocol boom")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.Error(
                    cause = protocolError,
                    stage = SessionEvent.Stage.Parse
                ),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Parse,
                        message = "protocol boom",
                        cause = protocolError
                    ),
                    SessionEvent.RoundCompleted(fullText = "")
                ),
                events
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            session.close()
        }
    }

    @Test
    fun `仅工具调用 assistant 是合法 history`() = runBlocking {
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"hi"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            localTools {
                add("lookup") {
                    description = "测试工具"
                    string("query") { required = true }
                }
            }
            hooks {
                ok("""{"answer":"ok"}""")
            }
        }

        try {
            val events = session.send("hi").toList()
            val history = session.getHistory()
            val assistantWithTool = history[1] as ChatTurn.Assistant

            assertEquals("", assistantWithTool.content)
            assertEquals(
                listOf(
                    ToolCallSpec(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"hi"}"""
                    )
                ),
                assistantWithTool.toolCalls
            )
            assertEquals(1, events.filterIsInstance<SessionEvent.RoundCompleted>().size)
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), events.last())
        } finally {
            session.close()
        }
    }
}
