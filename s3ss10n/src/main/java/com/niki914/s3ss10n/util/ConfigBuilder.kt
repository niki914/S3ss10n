package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.Config
import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import okhttp3.Interceptor
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * A DSL builder for chat client configuration.
 */
class ConfigBuilder {
    var baseUrl: String = ""
    var apiKey: String = ""

    var modelName: String = ""
    var prompt: String? = null
    var tools: List<ToolDefinition>? = null

    var readTimeout: Long = 30L
    var connectTimeout: Long = 30L
    var writeTimeout: Long = 30L
    var callTimeout: Long = 30L

    var proxy: Proxy? = null

    val interceptors = mutableListOf<Interceptor>()

    companion object {

        /**
         * Builds a Config instance using the DSL.
         */
        internal fun buildConfig(block: ConfigBuilder.() -> Unit = {}): Config {
            return ConfigBuilder().apply(block).build()
        }

        internal fun fromConfig(config: Config): ConfigBuilder {
            return ConfigBuilder().apply {
                baseUrl = config.baseUrl
                apiKey = config.apiKey
                modelName = config.modelName
                prompt = config.prompt
                tools = config.tools
                readTimeout = config.readTimeout
                connectTimeout = config.connectTimeout
                writeTimeout = config.writeTimeout
                callTimeout = config.callTimeout
                proxy = config.proxy
                interceptors.addAll(config.interceptors.toList())
            }
        }
    }

    fun socksProxy(host: String, port: Int) {
        this.proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

    fun httpProxy(host: String, port: Int) {
        this.proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    internal fun build(): Config {
        return Config(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName,
            prompt = prompt,
            tools = tools,
            readTimeout = readTimeout,
            connectTimeout = connectTimeout,
            writeTimeout = writeTimeout,
            callTimeout = callTimeout,
            proxy = proxy,
            interceptors = interceptors.toList()
        )
    }
}