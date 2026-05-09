package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolRegistry
import kotlin.reflect.KClass

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
            protocolClass: KClass<P>,
            builder: SessionConfig.Builder.() -> Unit
        ): Session {
            SessionProtocols.ensureInitialized()
            val config = SessionConfig.Builder().apply(builder).build()

            var protocol = ProtocolRegistry.resolve(protocolClass)
            if (config.jsonCodec != null) {
                protocol = protocol.withCodec(config.jsonCodec!!)
            }

            return ChatSession(initialConfig = config, protocol = protocol)
        }

        inline fun <reified P : ChatProtocol> open(
            noinline builder: SessionConfig.Builder.() -> Unit
        ): Session = open(P::class, builder)
    }
}
