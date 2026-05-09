package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.Message
import com.niki914.s3ss10n.ToolCallSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

internal class ToolCallWaiter<CTX>(
    private val scope: CoroutineScope,
    private val onToolCall: suspend (ToolCallSpec, CTX) -> Message.Tool
) {
    private val currCalls = mutableListOf<Deferred<Pair<ToolCallSpec, Message.Tool>>>()

    fun isEmpty(): Boolean = currCalls.isEmpty()

    fun enqueue(toolCall: ToolCallSpec, ctx: CTX) {
        currCalls.add(
            scope.async { toolCall to onToolCall(toolCall, ctx) }
        )
    }

    suspend fun awaitAll(): List<Pair<ToolCallSpec, Message.Tool>> {
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
