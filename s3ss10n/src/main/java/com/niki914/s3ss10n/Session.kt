package com.niki914.s3ss10n

interface Session {
    suspend fun send(
        text: String,
        onEvent: (SessionEvent) -> Unit = {}
    )

    suspend fun getHistory(): List<ChatPair>

    suspend fun resetConversation()

    suspend fun close()

    fun update(block: SessionConfig.() -> Unit)

    companion object {
        fun open(block: SessionConfig.() -> Unit): Session {
            val config = SessionConfig().apply(block)
            return ChatSession(config)
        }
    }
}
