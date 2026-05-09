package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message

internal sealed interface ToolCallOutcome {
    val message: Message.Tool
    data class Success(override val message: Message.Tool, val resultJson: String) : ToolCallOutcome
    data class Failure(override val message: Message.Tool, val errorMessage: String, val resultJson: String?) : ToolCallOutcome
}

sealed interface ToolCallRequest {
    val id: String
    val name: String
    val argumentsJson: String
    val kind: ToolCallKind
    val appParams: Map<String, Any?>

    suspend fun delegate(): Message.Tool

    fun ok(contentJson: String): Message.Tool

    fun error(
        message: String,
        contentJson: String = """{"success":false}"""
    ): Message.Tool
}

internal class LocalToolCallRequest(
    private val toolCall: ToolCall,
    override val appParams: Map<String, Any?>
) : ToolCallRequest {
    override val id: String get() = toolCall.id ?: "unknown"
    override val name: String get() = toolCall.function?.name ?: "unknown"
    override val argumentsJson: String get() = toolCall.function?.arguments ?: "{}"
    override val kind: ToolCallKind = ToolCallKind.Local

    internal var lastOutcome: ToolCallOutcome? = null

    override suspend fun delegate(): Message.Tool {
        return error("Local tool '$name' has no built-in implementation. Handle it in hooks { ... }.")
    }

    override fun ok(contentJson: String): Message.Tool {
        val msg = Message.Tool(
            toolCallId = id,
            name = name,
            content = contentJson
        )
        lastOutcome = ToolCallOutcome.Success(msg, contentJson)
        return msg
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        val msg = Message.Tool(
            toolCallId = id,
            name = name,
            content = """{"error":"$message","detail":$contentJson}"""
        )
        lastOutcome = ToolCallOutcome.Failure(msg, message, contentJson)
        return msg
    }
}

internal class McpToolCallRequest(
    private val toolCall: ToolCall,
    private val serverName: String,
    override val appParams: Map<String, Any?>
) : ToolCallRequest {
    override val id: String get() = toolCall.id ?: "unknown"
    override val name: String get() = toolCall.function?.name ?: "unknown"
    override val argumentsJson: String get() = toolCall.function?.arguments ?: "{}"
    override val kind: ToolCallKind = ToolCallKind.Mcp(serverName)

    internal var lastOutcome: ToolCallOutcome? = null

    override suspend fun delegate(): Message.Tool {
        return error("MCP not implemented yet")
    }

    override fun ok(contentJson: String): Message.Tool {
        val msg = Message.Tool(
            toolCallId = id,
            name = name,
            content = contentJson
        )
        lastOutcome = ToolCallOutcome.Success(msg, contentJson)
        return msg
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        val msg = Message.Tool(
            toolCallId = id,
            name = name,
            content = """{"error":"$message"}"""
        )
        lastOutcome = ToolCallOutcome.Failure(msg, message, contentJson)
        return msg
    }
}
