package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.ToolCallSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * handler for tool-call
 */
internal class ToolCallWaiter<CTX>(
    private val scope: CoroutineScope,
    private val onToolCall: suspend (ToolCallSpec, CTX) -> String
) {
    private val currCalls = mutableListOf<Deferred<Pair<ToolCallSpec, String>>>()

    fun isEmpty(): Boolean = currCalls.isEmpty()

    fun enqueue(toolCall: ToolCallSpec, ctx: CTX) {
        currCalls.add(
            scope.async { toolCall to onToolCall(toolCall, ctx) }
        )
    }

    suspend fun awaitAll(): List<Pair<ToolCallSpec, String>> {
        return currCalls.awaitAll().also {
            currCalls.clear()
        }
    }

    suspend fun cancelAndClear(join: Boolean = false) {
        currCalls.forEach {
            it.cancel()
            if (join)
                it.join()
        }
        currCalls.clear()
    }
}
