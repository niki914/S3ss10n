package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * handler for tool-call
 */
internal class ToolCallWaiter<CTX>(
    private val scope: CoroutineScope,
    private val onToolCall: suspend (ToolCall, CTX) -> Message.Tool
) {
    private val currCalls = mutableListOf<Deferred<Message.Tool>>()

    fun isEmpty(): Boolean = currCalls.isEmpty()

    fun enqueue(toolCall: ToolCall, ctx: CTX) {
        currCalls.add(
            scope.async { onToolCall(toolCall, ctx) }
        )
    }

    suspend fun awaitAll(): List<Message.Tool> {
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