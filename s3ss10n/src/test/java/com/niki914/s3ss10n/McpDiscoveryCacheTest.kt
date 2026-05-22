package com.niki914.s3ss10n

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpDiscoveryCacheTest {
    @Test
    fun `相同 key 的刷新任务会复用同一个 deferred`() = runTest {
        val cache = McpDiscoveryCache()
        val gate = CompletableDeferred<Unit>()
        var runCount = 0

        val (first, firstCreated) = cache.acquireRefresh("docs", "fp", this) {
            runCount++
            gate.await()
            "ok"
        }
        val (second, secondCreated) = cache.acquireRefresh("docs", "fp", this) {
            runCount++
            "unexpected"
        }

        runCurrent()

        assertTrue(firstCreated)
        assertFalse(secondCreated)
        assertSame(first, second)
        assertEquals(1, runCount)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("ok", first.await())
        assertEquals("ok", second.await())
    }

    @Test
    fun `缓存按 fingerprint 隔离`() = runTest {
        val cache = McpDiscoveryCache()
        val v1Tools = listOf(
            McpDiscoveredTool(
                name = "search_v1",
                description = "first",
                inputSchema = mapOf("type" to "object")
            )
        )
        val v2Tools = listOf(
            McpDiscoveredTool(
                name = "search_v2",
                description = "second",
                inputSchema = mapOf("type" to "object")
            )
        )

        cache.put("docs", "fp1", v1Tools)
        cache.put("docs", "fp2", v2Tools)

        assertEquals(v1Tools, cache.snapshot("docs", "fp1"))
        assertEquals(v2Tools, cache.snapshot("docs", "fp2"))
    }
}
