package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toolbase.ToolManager

sealed interface ToolCallRequest {
    val id: String
    val name: String
    val argumentsJson: String
    val kind: ToolCallKind

    suspend fun delegate(): Message.Tool

    fun ok(contentJson: String): Message.Tool

    fun error(
        message: String,
        contentJson: String = """{"success":false}"""
    ): Message.Tool
}

internal class LocalToolCallRequest(
    private val toolCall: ToolCall,
    private val toolManager: ToolManager,
    private val appParams: Map<String, Any?>
) : ToolCallRequest {
    override val id: String get() = toolCall.id ?: "unknown"
    override val name: String get() = toolCall.function?.name ?: "unknown"
    override val argumentsJson: String get() = toolCall.function?.arguments ?: "{}"
    override val kind: ToolCallKind = ToolCallKind.Local

    override suspend fun delegate(): Message.Tool {
        val result = toolManager.exec(toolCall, appParams)
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = result
        )
    }

    override fun ok(contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = contentJson
        )
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = """{"error":"$message","detail":$contentJson}"""
        )
    }
}

internal class McpToolCallRequest(
    private val toolCall: ToolCall,
    private val serverName: String
) : ToolCallRequest {
    override val id: String get() = toolCall.id ?: "unknown"
    override val name: String get() = toolCall.function?.name ?: "unknown"
    override val argumentsJson: String get() = toolCall.function?.arguments ?: "{}"
    override val kind: ToolCallKind = ToolCallKind.Mcp(serverName)

    override suspend fun delegate(): Message.Tool {
        return error("MCP not implemented yet")
    }

    override fun ok(contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = contentJson
        )
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = """{"error":"$message"}"""
        )
    }
}
