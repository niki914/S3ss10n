package com.niki914.s3ss10n

internal sealed interface ToolCallOutcome {
    val resultJson: String

    data class Success(
        override val resultJson: String
    ) : ToolCallOutcome

    data class Failure(
        val errorMessage: String,
        override val resultJson: String
    ) : ToolCallOutcome
}

sealed interface ToolCallRequest {
    val id: String
    val name: String
    val argumentsJson: String
    val kind: ToolCallKind
    val appParams: Map<String, Any?>

    suspend fun delegate(): String

    fun ok(contentJson: String): String

    fun error(
        message: String,
        contentJson: String = """{"success":false}"""
    ): String
}

internal class LocalToolCallRequest(
    private val toolCall: ToolCallSpec,
    override val appParams: Map<String, Any?>
) : ToolCallRequest {
    override val id: String get() = toolCall.callId
    override val name: String get() = toolCall.toolName
    override val argumentsJson: String get() = toolCall.argumentsJson
    override val kind: ToolCallKind = ToolCallKind.Local

    internal var lastOutcome: ToolCallOutcome? = null

    override suspend fun delegate(): String {
        return error("Local tool '$name' has no built-in implementation. Handle it in hooks { ... }.")
    }

    override fun ok(contentJson: String): String {
        lastOutcome = ToolCallOutcome.Success(contentJson)
        return contentJson
    }

    override fun error(message: String, contentJson: String): String {
        val resultJson = """{"error":"$message","detail":$contentJson}"""
        lastOutcome = ToolCallOutcome.Failure(message, resultJson)
        return resultJson
    }
}

internal class McpToolCallRequest(
    private val toolCall: ToolCallSpec,
    private val serverName: String,
    override val appParams: Map<String, Any?>
) : ToolCallRequest {
    override val id: String get() = toolCall.callId
    override val name: String get() = toolCall.toolName
    override val argumentsJson: String get() = toolCall.argumentsJson
    override val kind: ToolCallKind = ToolCallKind.Mcp(serverName)

    internal var lastOutcome: ToolCallOutcome? = null

    override suspend fun delegate(): String {
        return error("MCP not implemented yet")
    }

    override fun ok(contentJson: String): String {
        lastOutcome = ToolCallOutcome.Success(contentJson)
        return contentJson
    }

    override fun error(message: String, contentJson: String): String {
        val resultJson = """{"error":"$message"}"""
        lastOutcome = ToolCallOutcome.Failure(message, resultJson)
        return resultJson
    }
}
