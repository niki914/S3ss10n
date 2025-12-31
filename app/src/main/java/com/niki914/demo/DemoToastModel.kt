package com.niki914.demo

import com.niki914.s3ss10n.chat.protocol.PropertyDefinition
import com.niki914.s3ss10n.toolbase.ToolCallJsonTransformLayer
import com.niki914.s3ss10n.toolbase.ToolModel
import com.zephyr.tools.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DemoToastModel : ToolModel() {
    override val name: String = "send_toast"
    override val description: String = "Send a Toast notification to the user's device."
    override val properties: Map<String, PropertyDefinition>
        get() = mapOf(
            "message" to PropertyDefinition(
                type = "string",
                description = "The message you'd like to tell the user."
            )
        )
    override val required: List<String>
        get() = listOf("message")

    override suspend fun ToolCallJsonTransformLayer.execInternal() {
        val message = getFromToolCall<String>("message")

        if (message == null) {
            state = ToolCallJsonTransformLayer.ResponseState.IllegalArgs
        } else {
            withContext(Dispatchers.Main) {
                Toaster().toast(message)
            }
            this["msg"] = "success"
        }
    }
}