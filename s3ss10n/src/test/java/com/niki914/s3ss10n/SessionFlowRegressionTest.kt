package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.json.JsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

            assertEquals(
                listOf(
                    ChatTurn.User(content = "hi"),
                    ChatTurn.Assistant(
                        content = "",
                        toolCalls = listOf(
                            ToolCallSpec(
                                callId = "call-1",
                                toolName = "lookup",
                                argumentsJson = """{"query":"hi"}"""
                            )
                        )
                    ),
                    ChatTurn.ToolResult(
                        callId = "call-1",
                        toolName = "lookup",
                        resultJson = """{"answer":"ok"}"""
                    ),
                    ChatTurn.Assistant(content = "done")
                ),
                history
            )
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

    @Test
    fun `工具调用后第二轮请求写入 tool result 并完成文本响应`() = runBlocking {
        val protocol = singleToolThenTextProtocol(
            toolName = "lookup",
            argumentsJson = """{"query":"hi"}"""
        )
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                ok("""{"answer":"ok"}""")
            }
        }

        try {
            val events = session.send("hi").toList()

            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.ToolRunning(
                        callId = "call-1",
                        toolName = "lookup",
                        kind = ToolCallKind.Local
                    ),
                    SessionEvent.ToolSucceeded(
                        callId = "call-1",
                        toolName = "lookup",
                        kind = ToolCallKind.Local,
                        resultJson = """{"answer":"ok"}"""
                    ),
                    SessionEvent.TextDelta(delta = "done", fullText = "done"),
                    SessionEvent.RoundCompleted(fullText = "done")
                ),
                events
            )
            assertEquals(
                ChatTurn.ToolResult(
                    callId = "call-1",
                    toolName = "lookup",
                    resultJson = """{"answer":"ok"}"""
                ),
                protocol.lastHistory.last()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `未知工具发出 ToolFailed 和 Error 且返回 tool error JSON`() = runBlocking {
        val protocol = singleToolThenTextProtocol(toolName = "missing")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            val failed = events.filterIsInstance<SessionEvent.ToolFailed>().single()
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val toolResult = protocol.lastHistory.last() as ChatTurn.ToolResult

            assertEquals("missing", failed.toolName)
            assertEquals(ToolCallKind.Local, failed.kind)
            assertEquals("Unknown tool 'missing'", failed.message)
            assertEquals(SessionEvent.Stage.Tool, error.stage)
            assertEquals("Unknown tool 'missing'", error.message)
            assertEquals(failed.resultJson, toolResult.resultJson)
            assertTrue(toolResult.resultJson.contains("Unknown tool"))
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), events.last())
        } finally {
            session.close()
        }
    }

    @Test
    fun `本地工具无 hooks 发出 ToolFailed 和 Error`() = runBlocking {
        val protocol = singleToolThenTextProtocol(toolName = "lookup")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
        }

        try {
            val events = session.send("hi").toList()
            val failed = events.filterIsInstance<SessionEvent.ToolFailed>().single()
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val toolResult = protocol.lastHistory.last() as ChatTurn.ToolResult

            assertEquals(
                SessionEvent.ToolRunning(
                    callId = "call-1",
                    toolName = "lookup",
                    kind = ToolCallKind.Local
                ),
                events.filterIsInstance<SessionEvent.ToolRunning>().single()
            )
            assertEquals("No hooks configured", failed.message)
            assertEquals(SessionEvent.Stage.Tool, error.stage)
            assertEquals("no hooks configured", error.message)
            assertTrue(toolResult.resultJson.contains("No hooks configured"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `本地 hook 成功发出 ToolRunning 和 ToolSucceeded 且结果进入下一轮`() = runBlocking {
        val protocol = singleToolThenTextProtocol(toolName = "lookup")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                ok("""{"answer":"hook-ok"}""")
            }
        }

        try {
            val events = session.send("hi").toList()

            assertEquals(
                SessionEvent.ToolRunning(
                    callId = "call-1",
                    toolName = "lookup",
                    kind = ToolCallKind.Local
                ),
                events.filterIsInstance<SessionEvent.ToolRunning>().single()
            )
            assertEquals(
                SessionEvent.ToolSucceeded(
                    callId = "call-1",
                    toolName = "lookup",
                    kind = ToolCallKind.Local,
                    resultJson = """{"answer":"hook-ok"}"""
                ),
                events.filterIsInstance<SessionEvent.ToolSucceeded>().single()
            )
            assertEquals(
                ChatTurn.ToolResult(
                    callId = "call-1",
                    toolName = "lookup",
                    resultJson = """{"answer":"hook-ok"}"""
                ),
                protocol.lastHistory.last()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `本地 hook 抛异常发出 ToolFailed 和 Error 且 round 收尾`() = runBlocking {
        val boom = IllegalStateException("hook boom")
        val protocol = singleToolThenTextProtocol(toolName = "lookup")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                throw boom
            }
        }

        try {
            val events = session.send("hi").toList()
            val errors = events.filterIsInstance<SessionEvent.Error>()

            assertTrue(
                events.filterIsInstance<SessionEvent.ToolFailed>()
                    .any { it.message == "hook boom" && it.toolName == "lookup" }
            )
            assertTrue(
                errors.any {
                    it.stage == SessionEvent.Stage.Tool &&
                        it.message == "hook boom" &&
                        it.cause === boom
                }
            )
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), events.last())
        } finally {
            session.close()
        }
    }

    @Test
    fun `多个工具调用结果按调用顺序写入 history 后进入下一轮`() = runBlocking {
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"one"}"""
                    ),
                    ProtocolEvent.ToolCallReady(
                        callId = "call-2",
                        toolName = "lookup",
                        argumentsJson = """{"query":"two"}"""
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
            registerLookupTool()
            hooks {
                when (id) {
                    "call-1" -> ok("""{"answer":"one"}""")
                    "call-2" -> ok("""{"answer":"two"}""")
                    else -> error("unexpected call")
                }
            }
        }

        try {
            val events = session.send("hi").toList()

            assertEquals(
                listOf(
                    ChatTurn.ToolResult(
                        callId = "call-1",
                        toolName = "lookup",
                        resultJson = """{"answer":"one"}"""
                    ),
                    ChatTurn.ToolResult(
                        callId = "call-2",
                        toolName = "lookup",
                        resultJson = """{"answer":"two"}"""
                    )
                ),
                protocol.lastHistory.filterIsInstance<ChatTurn.ToolResult>()
            )
            assertEquals(2, events.filterIsInstance<SessionEvent.ToolSucceeded>().size)
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), events.last())
        } finally {
            session.close()
        }
    }

    @Test
    fun `onEvent 抛普通异常时 send 透传原始异常`() = runBlocking {
        val expected = IllegalStateException("callback boom")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("hello"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            try {
                session.send("hi") {
                    throw expected
                }
                fail("send should throw callback exception")
            } catch (actual: Throwable) {
                assertSame(expected, actual)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `onEvent 抛 CancellationException 时取消语义不被包装`() = runBlocking {
        val expected = CancellationException("callback cancelled")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("hello"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            try {
                session.send("hi") {
                    throw expected
                }
                fail("send should throw cancellation")
            } catch (actual: CancellationException) {
                assertSame(expected, actual)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `resetConversation 清空 history 并取消等待中的工具调用`() = runBlocking {
        val hookStarted = CompletableDeferred<Unit>()
        val hookRelease = CompletableDeferred<Unit>()
        val protocol = hangingToolThenTextProtocol()
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                hookStarted.complete(Unit)
                hookRelease.await()
                ok("""{"answer":"late"}""")
            }
        }

        try {
            val first = async { session.send("first").toList() }
            hookStarted.await()

            session.resetConversation()
            assertEquals(emptyList<ChatTurn>(), session.getHistory())

            val secondEvents = session.send("second").toList()
            assertEquals(emptyList<ChatTurn>(), protocol.lastHistory)
            assertEquals("second", protocol.lastPendingUserInput)
            assertEquals(
                listOf(
                    ChatTurn.User(content = "second"),
                    ChatTurn.Assistant(content = "done")
                ),
                session.getHistory()
            )
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), secondEvents.last())
            try {
                first.await()
                fail("first send should be cancelled by resetConversation")
            } catch (_: CancellationException) {
                // 当前任务被 resetConversation 取消是既有收尾语义的一部分。
            }
            Unit
        } finally {
            hookRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `连续 send 取消前一轮且不留下孤儿 tool call`() = runBlocking {
        val hookStarted = CompletableDeferred<Unit>()
        val hookRelease = CompletableDeferred<Unit>()
        val protocol = hangingToolThenTextProtocol()
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                hookStarted.complete(Unit)
                hookRelease.await()
                ok("""{"answer":"late"}""")
            }
        }

        try {
            val first = async { session.send("first").toList() }
            hookStarted.await()

            val secondEvents = session.send("second").toList()

            assertFalse(protocol.lastHistory.any { turn ->
                turn is ChatTurn.ToolResult && turn.callId == "call-1"
            })
            assertEquals("second", protocol.lastPendingUserInput)
            assertFalse(session.getHistory().any { turn ->
                turn is ChatTurn.ToolResult && turn.callId == "call-1"
            })
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), secondEvents.last())
            try {
                first.await()
                fail("first send should be cancelled by the next send")
            } catch (_: CancellationException) {
                // 后一轮 send 会取消前一轮挂起工作。
            }
            Unit
        } finally {
            hookRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `close 会关闭 engine`() = runBlocking {
        val engine = FakeHttpEngine()
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("hello"), ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = engine)

        session.close()

        assertTrue(engine.closed)
    }

    @Test
    fun `custom header 按大小写不敏感规则覆盖 auth header`() = runBlocking {
        val engine = FakeHttpEngine()
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("hello"), ProtocolEvent.Completed)
        }
        protocol.apiKeyHeaders = mapOf("Authorization" to "Bearer auth", "X-Trace" to "auth-trace")
        val session = newChatSession(protocol = protocol, engine = engine) {
            apiKey = "test-key"
            header("authorization", "Bearer custom")
            header("X-Custom", "custom")
        }

        try {
            session.send("hi").toList()
            val headers = engine.streamRequests.single().headers

            assertFalse(headers.containsKey("Authorization"))
            assertEquals("Bearer custom", headers["authorization"])
            assertEquals("auth-trace", headers["X-Trace"])
            assertEquals("custom", headers["X-Custom"])
        } finally {
            session.close()
        }
    }

    @Test
    fun `update 忽略 jsonCodec 和 httpEngine open only 字段`() = runBlocking {
        val initialEngine = FakeHttpEngine()
        val updatedEngine = FakeHttpEngine()
        val initialCodec = GsonJsonCodec()
        val protocol = singleToolThenTextProtocol(toolName = "missing")
        val session = newChatSession(protocol = protocol, engine = initialEngine) {
            jsonCodec = initialCodec
        }

        try {
            session.update {
                jsonCodec = throwingJsonCodec()
                httpEngine = updatedEngine
            }
            session.send("hi").toList()

            assertEquals(2, initialEngine.streamRequests.size)
            assertEquals(0, updatedEngine.streamRequests.size)
            assertSame(initialCodec, protocol.lastSnapshot?.jsonCodec)
            assertTrue(
                protocol.lastHistory
                    .filterIsInstance<ChatTurn.ToolResult>()
                    .single()
                    .resultJson
                    .contains("Unknown tool")
            )
        } finally {
            session.close()
        }
    }

    private fun singleToolThenTextProtocol(
        toolName: String,
        argumentsJson: String = """{}"""
    ): RecordingChatProtocol {
        var parseCount = 0
        return RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = toolName,
                        argumentsJson = argumentsJson
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
    }

    private fun hangingToolThenTextProtocol(): RecordingChatProtocol {
        var parseCount = 0
        return RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"first"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
    }

    private fun SessionConfig.registerLookupTool() {
        localTools {
            add("lookup") {
                description = "测试工具"
                string("query") { required = false }
            }
        }
    }

    private fun throwingJsonCodec(): JsonCodec {
        return object : JsonCodec {
            override fun encode(value: Any?): String = error("updated codec should be ignored")

            override fun <T : Any> decode(json: String, type: Class<T>): T? {
                error("updated codec should be ignored")
            }

            override fun decodeMap(json: String): Map<String, Any?>? {
                error("updated codec should be ignored")
            }

            override fun decodeList(json: String): List<Any?>? {
                error("updated codec should be ignored")
            }
        }
    }
}
