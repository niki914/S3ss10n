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

    @Test
    fun `refreshMcpTools 成功后 discovery snapshot 为 available`() = runBlocking {
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
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()
            val server = session.getMcpDiscoverySnapshot().servers.getValue("docs")

            assertTrue(result.isSuccess)
            assertEquals(McpDiscoveryState.Available, server.state)
            assertEquals(1, server.discoveredToolCount)
            assertNotNull(server.lastSuccessAtMillis)
            assertEquals(null, server.errorMessage)
            assertFalse(server.stale)
        } finally {
            session.close()
        }
    }

    @Test
    fun `refreshMcpTools 失败时沿用 stale cache 中的已发现工具`() = runBlocking {
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
            val firstResult = session.refreshMcpTools()
            val firstServer = session.getMcpDiscoverySnapshot().servers.getValue("docs")

            assertTrue(firstResult.isSuccess)
            assertEquals(McpDiscoveryState.Available, firstServer.state)
            assertEquals(1, firstServer.discoveredToolCount)

            engine.failuresByUrl["https://mcp.ok"] = IllegalStateException("boom")

            val secondResult = session.refreshMcpTools()
            val staleServer = session.getMcpDiscoverySnapshot().servers.getValue("docs")

            assertFalse(secondResult.isSuccess)
            assertEquals("docs", secondResult.failedServers.single().serverName)
            assertEquals(McpDiscoveryState.UsingStaleCache, staleServer.state)
            assertTrue(staleServer.stale)
            assertEquals(1, staleServer.discoveredToolCount)

            session.send("hello") { }

            val tool = protocol.lastSnapshot?.tools?.find("remote_search")
            assertNotNull(tool)
            assertEquals(ToolCallKind.Mcp("docs"), tool?.kind)
        } finally {
            session.close()
        }
    }

    @Test
    fun `refreshMcpTools 失败后 discovery snapshot 记录 error`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = emptyMap(),
            failuresByUrl = mapOf(
                "https://mcp.bad" to IllegalStateException("boom")
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("bad") {
                    http { url = "https://mcp.bad" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()
            val server = session.getMcpDiscoverySnapshot().servers.getValue("bad")

            assertFalse(result.isSuccess)
            assertFalse(result.isPartialSuccess)
            assertEquals(McpDiscoveryState.Failed, server.state)
            assertEquals("boom", server.errorMessage)
            assertEquals(0, server.discoveredToolCount)
            assertFalse(server.stale)
        } finally {
            session.close()
        }
    }

    @Test
    fun `mcpHooks onToolsDiscovered 收到 server 和 tools`() = runBlocking {
        val tools = listOf(
            McpDiscoveredTool(
                name = "remote_search",
                description = "search docs",
                inputSchema = mapOf("type" to "object")
            )
        )
        var discoveredServer: String? = null
        var discoveredTools: List<McpDiscoveredTool> = emptyList()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf("https://mcp.ok" to tools)
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
            mcpHooks {
                onToolsDiscovered = { server, receivedTools ->
                    discoveredServer = server
                    discoveredTools = receivedTools
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertTrue(result.isSuccess)
            assertEquals("docs", discoveredServer)
            assertEquals(tools, discoveredTools)
        } finally {
            session.close()
        }
    }

    @Test
    fun `mcpHooks 抛异常不影响 refresh result`() = runBlocking {
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
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
            mcpHooks {
                onToolsDiscovered = { _, _ -> error("hook boom") }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertTrue(result.isSuccess)
            assertFalse(result.isPartialSuccess)
            assertEquals(listOf("docs"), result.refreshedServers)
            assertEquals(1, result.discoveredToolCount)
        } finally {
            session.close()
        }
    }
}
