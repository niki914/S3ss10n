package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.ChatPair
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toMessages
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wrapper for chat history
 */
internal class HistoryKeeper {
    private val _history = mutableListOf<ChatPair>()

    suspend fun getHistory(): List<ChatPair> = mutex.withLock {
        _history.toList()
    }

    suspend fun getMessages(): List<Message> =
        getHistory().toMessages()

    private val mutex = Mutex()

    suspend fun clear() = mutex.withLock {
        _history.clear()
    }

    suspend fun addUserMsg(user: Message.User) = mutex.withLock {
        _history.add(
            ChatPair.Companion.newPendingPair(user.content)
        )
    }

    suspend fun addToolResults(tools: List<Message.Tool>) = mutex.withLock {
        updateLatestPair {
            copy(aiAndTools = aiAndTools + tools)
        }
    }

    suspend fun appendTextToLastAIMsg(chunk: String) = mutex.withLock {
        updateLatestAssistantMsg {
            Message.Assistant(
                content = (content ?: "") + chunk,
                toolCalls = toolCalls
            )
        }
    }

    suspend fun appendToolCallToLastAIMsg(toolCall: ToolCall) = mutex.withLock {
        updateLatestAssistantMsg {
            Message.Assistant(
                content = content,
                toolCalls = (toolCalls ?: emptyList()) + toolCall
            )
        }
    }

    suspend fun setLatestPairState(
        newState: ChatPair.RoundState
    ) = mutex.withLock {
        updateLatestPair {
            copy(state = newState)
        }
    }

    // --- --- --- ---

    private fun updateLatestAssistantMsg(
        block: Message.Assistant.() -> Message.Assistant
    ) = updateLatestPair {
        val list = aiAndTools.toMutableList()

        val latest = aiAndTools
            .lastOrNull() as? Message.Assistant

        if (latest != null) {
            list.replaceLast {
                latest.block()
            }
        } else {
            val newMsg = Message.Assistant(null, null).block()
            list.add(newMsg)
        }

        copy(
            aiAndTools = list.toList()
        )
    }

    private fun updateLatestPair(
        block: ChatPair.() -> ChatPair
    ) = _history.replaceLast {
        this!! // 这个理论上不可能。如果真的空指针的话就直接让他抛出了
        block()
    }

    private fun <T> MutableList<T>.replaceLast(
        block: T?.() -> T
    ) {
        try {
            val last = last()
            remove(last)
            add(last.block())
        } catch (_: Throwable) {
            add(null.block())
        }
    }
}