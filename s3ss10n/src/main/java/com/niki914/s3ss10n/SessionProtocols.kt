package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolRegistry
import com.niki914.s3ss10n.ext.protocol.openai.OpenAIProtocol

object SessionProtocols {
    object OpenAI : ChatProtocol by OpenAIProtocol()

    init {
        ProtocolRegistry.register(OpenAI::class, OpenAI)
    }

    fun ensureInitialized() = Unit
}
