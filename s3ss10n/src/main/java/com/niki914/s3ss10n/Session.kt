package com.niki914.s3ss10n

import com.niki914.s3ss10n.protocol.ChatProtocol
import com.niki914.s3ss10n.protocol.ProtocolRegistry

interface Session {
    suspend fun send(
        text: String,
        onEvent: (SessionEvent) -> Unit = {}
    )

    suspend fun getHistory(): List<ChatTurn>

    suspend fun resetConversation()

    suspend fun close()

    fun update(block: SessionConfig.Builder.() -> Unit)

    companion object {
        fun <P : ChatProtocol> open(
            protocolClass: kotlin.reflect.KClass<P>,
            builder: SessionConfig.Builder.() -> Unit
        ): Session {
            SessionProtocols.ensureInitialized()
            val protocol = ProtocolRegistry.resolve(protocolClass)
            val config = SessionConfig.Builder().apply(builder).build()
            return ChatSession(initialConfig = config, protocol = protocol)
        }

        inline fun <reified P : ChatProtocol> open(
            noinline builder: SessionConfig.Builder.() -> Unit
        ): Session = open(P::class, builder)
    }
}
