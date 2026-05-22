package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRefreshTest {
    @Test
    fun `refreshMcpTools 成功后 下一轮可以看到发现的工具`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        val protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) }
        val session = newChatSession(protocol = protocol, engine = engine) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertTrue(result.isSuccess)
            assertFalse(result.isPartialSuccess)
            assertEquals(listOf("docs"), result.refreshedServers)
            assertEquals(emptyList<McpServerRefreshFailure>(), result.failedServers)
            assertEquals(1, result.discoveredToolCount)

            session.send("hello") { }

            val tool = protocol.lastSnapshot?.tools?.find("remote_search")
            assertNotNull(tool)
            assertEquals(ToolCallKind.Mcp("docs"), tool?.kind)
        } finally {
            session.close()
        }
    }

    @Test
    fun `refreshMcpTools 在部分 server 失败时返回 partial success`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            ),
            failuresByUrl = mapOf(
                "https://mcp.bad" to IllegalStateException("boom")
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("ok") {
                    http { url = "https://mcp.ok" }
                }
                add("bad") {
                    http { url = "https://mcp.bad" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertFalse(result.isSuccess)
            assertTrue(result.isPartialSuccess)
            assertEquals(listOf("ok"), result.refreshedServers)
            assertEquals(1, result.discoveredToolCount)
            assertEquals(1, result.failedServers.size)
            assertEquals("bad", result.failedServers.single().serverName)
            assertEquals("boom", result.failedServers.single().message)
        } finally {
            session.close()
        }
    }
}
