package com.niki914.s3ss10n.toolbase

import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.ToolDefinition

/**
 * A registry and executor for ToolModel.
 *
 * Register tools, inject their definitions into request config, and execute tool calls.
 */
class ToolManager {

    private val map = mutableMapOf<String, ToolModel>()

    /**
     * Tool definitions that can be put into the Chat Completions request.
     */
    val descriptions: List<ToolDefinition>
        get() = map.values.map { it.toolDefinition }

    fun registerTool(
        model: ToolModel
    ) = synchronized(this) {
        map[model.name] = model
    }

    inline fun <reified T : ToolModel> registerTool() =
        registerTool(
            T::class.java
                .getDeclaredConstructor()
                .newInstance()
        )

    /**
     * Executes a tool call and returns the tool result JSON.
     */
    suspend fun exec(
        toolCall: ToolCall,
        appParams: Map<String, Any?>
    ): String {
        val layer = ToolCallJsonTransformLayer.attach(toolCall, appParams)
        val model = findModel(toolCall)

        if (model == null) {
            layer.state = ToolCallJsonTransformLayer.ResponseState.ToolNotFound
        } else {
            try {
                model.exec(layer)
            } catch (t: Throwable) {
                layer.state = ToolCallJsonTransformLayer.ResponseState.ErrorOccurred(t)
            }
        }

        return layer.responseJson
    }

    private fun findModel(toolCall: ToolCall): ToolModel? = try {
        map[toolCall.function?.name]
    } catch (_: Throwable) {
        null
    }
}