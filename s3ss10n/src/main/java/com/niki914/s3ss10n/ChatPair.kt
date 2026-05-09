package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.chat.protocol.beans.user

/**
 * Converts a chat history to the request message list.
 */
internal fun List<ChatPair>.toMessages(): List<Message> {
    val list = mutableListOf<Message>()
    forEach { pair ->
        list.add(pair.user)
        list.addAll(pair.aiAndTools)
    }
    return list
}

/**
 * A single conversation round.
 *
 * It contains one user message, followed by assistant output and optional tool messages.
 */
internal data class ChatPair(
    val user: Message.User,
    val aiAndTools: List<Message>,
    val state: RoundState
) {
    companion object {
        /**
         * Creates a new round that is ready to start streaming.
         */
        internal fun newPendingPair(userContent: String): ChatPair {
            return ChatPair(
                user = user(userContent),
                aiAndTools = mutableListOf(),
                state = RoundState.Pending
            )
        }
    }

    internal enum class RoundState {
        Pending, Generating, Succeeded, Failed
    }

    override fun toString(): String =
        "ROUND STATE: ${state}\n    user:\n        ${user.content}\n" +
                aiAndTools.joinToString(separator = "\n") {
                    when (it) {
                        is Message.Assistant -> "    ai:\n        ${it.content}"
                        is Message.Tool -> "    ${it.name}:\n        ${it.content}"
                        else -> ""
                    }
                }
}