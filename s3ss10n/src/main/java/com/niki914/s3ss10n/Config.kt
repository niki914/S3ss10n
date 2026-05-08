package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import okhttp3.Interceptor
import java.net.Proxy

internal data class Config(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val prompt: String?,
    val tools: List<ToolDefinition>?,
    val readTimeout: Long,
    val connectTimeout: Long,
    val writeTimeout: Long,
    val callTimeout: Long,
    val temperature: Float? = null,
    val proxy: Proxy?,
    val interceptors: List<Interceptor>
)

/**
 * Thrown when baseUrl or modelName is not set to a valid value.
 */
class ConfigInvalidException() :
    IllegalAccessException("Config is invalid. Set BaseUrl and Model first!")