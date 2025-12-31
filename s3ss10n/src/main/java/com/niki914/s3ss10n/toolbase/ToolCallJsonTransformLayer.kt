package com.niki914.s3ss10n.toolbase

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.util.gson

/**
 * A helper that binds a ToolCall to JSON parsing and JSON response building.
 *
 * Use getFromToolCall to read arguments and use operator set to build a JSON response.
 */
class ToolCallJsonTransformLayer private constructor(
    private val toolCall: ToolCall,
    val appParams: Map<String, Any?>
) {
    companion object {
        const val NOT_EXIST = "not_exist"

        /**
         * Creates a layer for a single tool call.
         */
        fun attach(
            toolCall: ToolCall,
            appParams: Map<String, Any?>
        ): ToolCallJsonTransformLayer =
            ToolCallJsonTransformLayer(toolCall, appParams)
    }

    sealed interface ResponseState {
        data class ErrorOccurred(val cause: Throwable?) : ResponseState
        data object IllegalArgs : ResponseState
        data class Timeout(val timeout: Long) : ResponseState
        data object ToolNotFound : ResponseState
        data object SimpleOk : ResponseState
        data object Normal : ResponseState
    }

    val toolName: String
        get() = toolCall.function?.name ?: NOT_EXIST

    var state: ResponseState = ResponseState.Normal

    private val toolCallParams: Map<String, Any?> by lazy {
        val json = toolCall.function?.arguments ?: "{}"
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        com.niki914.s3ss10n.util.gson.fromJson(json, type)
    }

    val toolCallArgsJson: JsonObject by lazy {
        val raw = toolCall.function?.arguments
        if (raw.isNullOrBlank()) {
            JsonObject()
        } else {
            runCatching {
                val element = JsonParser.parseString(raw)
                if (element.isJsonObject) element.asJsonObject else JsonObject()
            }.getOrDefault(JsonObject())
        }
    }

    private val responseBuilder = mutableMapOf<String, Any?>()
    val responseJson: String
        get() {
            return when (state) {
                is ResponseState.ErrorOccurred -> smallErrorJson(
                    (state as? ResponseState.ErrorOccurred)?.cause?.message ?: "Unknown error"
                )

                ResponseState.IllegalArgs ->
                    smallErrorJson("Illegal params!")

                ResponseState.Normal ->
                    com.niki914.s3ss10n.util.gson.toJson(responseBuilder)

                is ResponseState.Timeout ->
                    smallErrorJson("Timeout after ${(state as ResponseState.Timeout).timeout}ms.")

                ResponseState.SimpleOk ->
                    smallJson("msg", "Success.")

                ResponseState.ToolNotFound ->
                    smallErrorJson("Tool not found")
            }
        }

    private fun smallErrorJson(message: String?): String =
        smallJson("error", message ?: "Unknown error!")

    private fun smallJson(key: String, value: String) =
        com.niki914.s3ss10n.util.gson.toJson(mapOf(key to value))

    inline fun <reified T> getFromToolCall(key: String): T? {
        val element = toolCallArgsJson.get(key) ?: return null
        if (element.isJsonNull) return null
        return element.toTypedOrNull()
    }

    inline fun <reified T> getFromAppParams(key: String): T? = appParams[key] as? T

    operator fun set(key: String, value: String) = synchronized(this) {
        responseBuilder[key] = value
    }

    operator fun set(key: String, value: Any?) = synchronized(this) {
        responseBuilder[key] = value
    }

    inline fun <reified T> JsonElement.toTypedOrNull(): T? {
        return runCatching {
            if (T::class == String::class) {
                @Suppress("UNCHECKED_CAST")
                return@runCatching (
                        if (isJsonPrimitive && asJsonPrimitive.isString) {
                            asJsonPrimitive.asString
                        } else {
                            toString()
                        }
                        ) as? T
            }

            val type = object : TypeToken<T>() {}.type
            gson.fromJson<T>(this, type)
        }.getOrNull()
    }
}