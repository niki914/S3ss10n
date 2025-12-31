package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.Config
import java.util.concurrent.atomic.AtomicReference

internal class ConfigHolder(
    initialConfig: Config
) {
    constructor(block: ConfigBuilder.() -> Unit) : this(
        ConfigBuilder()
            .apply(block)
            .build()
    )

    private val configRef = AtomicReference(initialConfig)

    val config: Config
        get() = configRef.get()

    fun update(newConfig: Config) {
        configRef.set(newConfig)
    }

    fun update(block: ConfigBuilder.() -> Unit) {
        val newConfig = ConfigBuilder.fromConfig(config).apply(block).build()
        update(newConfig)
    }
}