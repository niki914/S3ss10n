package com.niki914.s3ss10n

import kotlinx.coroutines.runBlocking
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolRegistry
import com.niki914.s3ss10n.ext.protocol.anthropic.AnthropicProtocol
import com.niki914.s3ss10n.ext.protocol.openai.OpenAIProtocol

object SessionProtocols {
    object OpenAI : ChatProtocol by OpenAIProtocol()

    object Anthropic : ChatProtocol by AnthropicProtocol()

    init {
        runBlocking { ProtocolRegistry.register(OpenAI::class, OpenAI) }
        runBlocking { ProtocolRegistry.register(Anthropic::class, Anthropic) }
    }

    suspend fun ensureInitialized() = Unit
}
