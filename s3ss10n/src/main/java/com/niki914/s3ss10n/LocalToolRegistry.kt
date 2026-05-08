package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.FunctionParameters
import com.niki914.s3ss10n.chat.protocol.FunctionTool
import com.niki914.s3ss10n.chat.protocol.PropertyDefinition
import com.niki914.s3ss10n.chat.protocol.ToolDefinition

enum class ToolValueType(val jsonType: String) {
    String("string"),
    Integer("integer"),
    Number("number"),
    Boolean("boolean"),
    Object("object"),
    Array("array")
}

data class LocalToolProperty(
    val name: String,
    var type: ToolValueType = ToolValueType.String,
    var description: String = "",
    var required: Boolean = false,
    var enumValues: List<String> = emptyList()
)

data class LocalToolConfig(
    var description: String = "",
    var rawInputSchemaJson: String? = null
) {
    internal val properties = mutableMapOf<String, LocalToolProperty>()
    internal val requiredNames = mutableListOf<String>()

    fun string(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.String, block)
    }

    fun integer(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Integer, block)
    }

    fun number(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Number, block)
    }

    fun boolean(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Boolean, block)
    }

    fun object_(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Object, block)
    }

    fun array(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Array, block)
    }

    fun rawJsonSchema(json: String) {
        rawInputSchemaJson = json
    }

    private fun addProperty(name: String, type: ToolValueType, block: LocalToolProperty.() -> Unit) {
        val prop = LocalToolProperty(name = name, type = type).apply(block)
        properties[name] = prop
        if (prop.required) requiredNames.add(name)
    }

    internal fun toToolDefinition(toolName: String): ToolDefinition {
        val propDefs = properties.mapValues { (_, prop) ->
            PropertyDefinition(
                type = prop.type.jsonType,
                description = prop.description
            )
        }
        return ToolDefinition(
            function = FunctionTool(
                name = toolName,
                description = description,
                parameters = FunctionParameters(
                    type = "object",
                    properties = propDefs,
                    required = requiredNames.ifEmpty { null }
                )
            )
        )
    }
}

interface LocalToolRegistry {
    fun add(name: String, block: LocalToolConfig.() -> Unit)
    fun replace(name: String, block: LocalToolConfig.() -> Unit)
    fun remove(name: String)
}

internal class LocalToolRegistryImpl : LocalToolRegistry {
    private val _tools = mutableMapOf<String, LocalToolConfig>()

    val tools: Map<String, LocalToolConfig> get() = _tools.toMap()

    override fun add(name: String, block: LocalToolConfig.() -> Unit) {
        _tools[name] = LocalToolConfig().apply(block)
    }

    override fun replace(name: String, block: LocalToolConfig.() -> Unit) {
        _tools[name] = LocalToolConfig().apply(block)
    }

    override fun remove(name: String) {
        _tools.remove(name)
    }

    fun toToolDefinitions(): List<ToolDefinition> =
        _tools.map { (name, config) -> config.toToolDefinition(name) }
}
