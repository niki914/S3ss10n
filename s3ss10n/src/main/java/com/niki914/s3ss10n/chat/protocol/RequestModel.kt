package com.niki914.s3ss10n.chat.protocol

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/*
    val requestBody = ChatApiRequestBody(
        model = modelName,
        messages = messages,
        tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionTool(
                    name = "getCurrentWeather",
                    description = "天气查询",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "location" to PropertyDefinition(
                                type = "string",
                                description = "城市名，例如：北京"
                            )
                        ),
                        required = listOf("location") // 明确指定 required 参数
                    )
                )
            )
        )
    )
 */

// 工具的定义，用于请求中的 "tools" 字段
@Keep
data class ToolDefinition(
    @SerializedName("function") val function: FunctionTool
) {
    @SerializedName("type")
    val type: String = "function"
}

/**
 * Function tool metadata.
 */
@Keep
data class FunctionTool(
    @SerializedName("name") val name: String,        // 函数名称，例如 "getCurrentWeather"
    @SerializedName("description") val description: String, // 函数描述，例如 "天气查询"
    @SerializedName("parameters") val parameters: FunctionParameters // 函数参数的 JSON Schema
)

/**
 * JSON schema of function parameters.
 */
@Keep
data class FunctionParameters(
    @SerializedName("type") val type: String, // 参数类型，例如 "object"
    @SerializedName("properties") val properties: Map<String, PropertyDefinition>, // 参数属性的映射
    @SerializedName("required") val required: List<String>? = null // 必须参数列表，可为空
)

// 单个参数属性的定义，例如 "location"
@Keep
data class PropertyDefinition(
    @SerializedName("type") val type: String,        // 属性类型，例如 "string"
    @SerializedName("description") val description: String // 属性描述
)