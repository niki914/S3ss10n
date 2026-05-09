package com.niki914.s3ss10n.protocol

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object ProtocolRegistry {
    private val map = ConcurrentHashMap<KClass<out ChatProtocol>, ChatProtocol>()

    fun <P : ChatProtocol> register(klass: KClass<P>, instance: P) {
        map[klass] = instance
    }

    fun resolve(klass: KClass<out ChatProtocol>): ChatProtocol {
        return map[klass]
            ?: throw IllegalStateException(
                "Protocol `${klass.qualifiedName}` is not registered. " +
                    "Call ProtocolRegistry.register(${klass.simpleName}::class, ${klass.simpleName}()) first."
            )
    }
}
