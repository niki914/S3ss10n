package com.niki914.s3ss10n.toolbase


import com.niki914.s3ss10n.chat.protocol.FunctionParameters
import com.niki914.s3ss10n.chat.protocol.FunctionTool
import com.niki914.s3ss10n.chat.protocol.PropertyDefinition
import com.niki914.s3ss10n.chat.protocol.ToolDefinition

/**
 * Base class for defining a tool that can be called by the model.
 *
 * Provide name/description and optionally JSON schema (properties/required).
 * Implement execInternal to write fields into the response JSON.
 */
abstract class ToolModel {
    abstract val name: String

    protected abstract val description: String

    protected open val type: String = "object"
    protected open val properties: Map<String, PropertyDefinition> = emptyMap()
    protected open val required: List<String> = emptyList()

    val toolDefinition: ToolDefinition
        get() = ToolDefinition(
            function = FunctionTool(
                name = name,
                description = description,
                parameters = parameters
            )
        )

    protected val parameters: FunctionParameters
        get() = FunctionParameters(
            type = type,
            properties = properties,
            required = required
        )

    suspend fun exec(
        layer: ToolCallJsonTransformLayer
    ) = layer.execInternal()

    /**
     * Executes the tool and writes fields into the layer's response builder.
     */
    abstract suspend fun ToolCallJsonTransformLayer.execInternal()
}