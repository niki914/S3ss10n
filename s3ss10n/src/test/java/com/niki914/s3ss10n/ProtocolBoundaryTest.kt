package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.ext.protocol.anthropic.AnthropicProtocol
import com.niki914.s3ss10n.ext.protocol.openai.OpenAIProtocol
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolBoundaryTest {
    @Test
    fun `OpenAI 普通文本 delta 解析为 TextDelta`() = runBlocking {
        val events = collectOpenAIEvents(
            """{"choices":[{"delta":{"content":"hello"}}]}"""
        )

        assertTrue(events.contains(ProtocolEvent.TextDelta("hello")))
        assertCompleted(events)
    }

    @Test
    fun `OpenAI reasoning content 解析为 ReasoningDelta`() = runBlocking {
        val events = collectOpenAIEvents(
            """{"choices":[{"delta":{"reasoning_content":"why"}}]}"""
        )

        assertTrue(events.contains(ProtocolEvent.ReasoningDelta("why")))
        assertCompleted(events)
    }

    @Test
    fun `OpenAI tool call arguments 分片累积到完整 JSON 后发出 ToolCallReady`() = runBlocking {
        val events = collectOpenAIEvents(
            """
            {
              "choices": [
                {
                  "delta": {
                    "tool_calls": [
                      {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "lookup",
                          "arguments": "{\"query\":"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
            """
            {
              "choices": [
                {
                  "delta": {
                    "tool_calls": [
                      {
                        "type": "function",
                        "function": {
                          "arguments": "\"hi\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ProtocolEvent.ToolCallReady(
                    callId = "call-1",
                    toolName = "lookup",
                    argumentsJson = """{"query":"hi"}"""
                )
            ),
            events.filterIsInstance<ProtocolEvent.ToolCallReady>()
        )
        assertCompleted(events)
    }

    @Test
    fun `OpenAI invalid frame 解析为 Parse error`() = runBlocking {
        val events = collectOpenAIEvents("not-json")
        val error = events.filterIsInstance<ProtocolEvent.Error>().single()

        assertEquals(SessionEvent.Stage.Parse, error.stage)
        assertCompleted(events)
    }

    @Test
    fun `Anthropic text delta 解析为 TextDelta`() = runBlocking {
        val events = collectAnthropicEvents(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}"""
        )

        assertTrue(events.contains(ProtocolEvent.TextDelta("hello")))
        assertCompleted(events)
    }

    @Test
    fun `Anthropic thinking 和 signature delta 解析为 reasoning events`() = runBlocking {
        val events = collectAnthropicEvents(
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"think"}}""",
            """{"type":"content_block_delta","delta":{"type":"signature_delta","signature":"sig"}}"""
        )

        assertTrue(events.contains(ProtocolEvent.ReasoningDelta("think")))
        assertTrue(events.contains(ProtocolEvent.ReasoningSignature("sig")))
        assertCompleted(events)
    }

    @Test
    fun `Anthropic tool_use input_json_delta 累积到 content_block_stop 后发出 ToolCallReady`() = runBlocking {
        val events = collectAnthropicEvents(
            """
            {
              "type": "content_block_start",
              "content_block": {
                "type": "tool_use",
                "id": "toolu-1",
                "name": "lookup"
              }
            }
            """.trimIndent(),
            """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}""",
            """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"\"hi\"}"}}""",
            """{"type":"content_block_stop"}"""
        )

        assertEquals(
            listOf(
                ProtocolEvent.ToolCallReady(
                    callId = "toolu-1",
                    toolName = "lookup",
                    argumentsJson = """{"query":"hi"}"""
                )
            ),
            events.filterIsInstance<ProtocolEvent.ToolCallReady>()
        )
        assertCompleted(events)
    }

    @Test
    fun `Anthropic error frame 解析为 Transport error`() = runBlocking {
        val events = collectAnthropicEvents(
            """{"type":"error","error":{"message":"bad request"}}"""
        )
        val error = events.filterIsInstance<ProtocolEvent.Error>().single()

        assertEquals(SessionEvent.Stage.Transport, error.stage)
        assertEquals("bad request", error.cause.message)
        assertCompleted(events)
    }

    @Test
    fun `OpenAI 和 Anthropic encodeToolResult 保持透传行为`() {
        val expected = ChatTurn.ToolResult(
            callId = "call-1",
            toolName = "lookup",
            resultJson = """{"ok":true}"""
        )

        assertEquals(
            expected,
            OpenAIProtocol(GsonJsonCodec()).encodeToolResult("call-1", "lookup", """{"ok":true}""")
        )
        assertEquals(
            expected,
            AnthropicProtocol(GsonJsonCodec()).encodeToolResult("call-1", "lookup", """{"ok":true}""")
        )
    }

    private suspend fun collectOpenAIEvents(vararg frames: String): List<ProtocolEvent> {
        return OpenAIProtocol(GsonJsonCodec()).parseStream(flowOf(*frames)).toList()
    }

    private suspend fun collectAnthropicEvents(vararg frames: String): List<ProtocolEvent> {
        return AnthropicProtocol(GsonJsonCodec()).parseStream(flowOf(*frames)).toList()
    }

    private fun assertCompleted(events: List<ProtocolEvent>) {
        assertEquals(ProtocolEvent.Completed, events.last())
    }
}
