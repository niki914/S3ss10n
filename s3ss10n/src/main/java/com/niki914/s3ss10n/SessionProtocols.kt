package com.niki914.s3ss10n

import kotlinx.coroutines.runBlocking
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolRegistry
import com.niki914.s3ss10n.ext.protocol.openai.OpenAIProtocol

object SessionProtocols {
    object OpenAI : ChatProtocol by OpenAIProtocol()

    init {
        runBlocking { ProtocolRegistry.register(OpenAI::class, OpenAI) }
    }

    suspend fun ensureInitialized() = Unit
}
