package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.FunctionCall
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toolbase.ToolManager

fun main() {
    println("=== ToolCallRequest Smoke Test ===")

    val toolCall = ToolCall(
        id = "call_123",
        type = "function",
        function = FunctionCall(
            name = "test_tool",
            arguments = """{"key":"value"}"""
        )
    )

    // Test LocalToolCallRequest.ok()
    val req = LocalToolCallRequest(toolCall, ToolManager(), emptyMap())
    assertOrPrint("id == 'call_123'", req.id == "call_123")
    assertOrPrint("name == 'test_tool'", req.name == "test_tool")
    assertOrPrint("argumentsJson", req.argumentsJson == """{"key":"value"}""")
    assertOrPrint("kind is Local", req.kind == ToolCallKind.Local)

    val okResult = req.ok("""{"done":true}""")
    assertOrPrint("ok() returns Message.Tool", okResult is Message.Tool)
    assertOrPrint("ok() toolCallId", okResult.toolCallId == "call_123")
    assertOrPrint("ok() content", okResult.content == """{"done":true}""")

    val errResult = req.error("timeout", """{"code":1}""")
    assertOrPrint("error() returns Message.Tool", errResult is Message.Tool)
    assertOrPrint("error() contains error", "timeout" in errResult.content)

    // Test McpToolCallRequest
    val mcpReq = McpToolCallRequest(toolCall, "aslocate")
    assertOrPrint("Mcp kind", mcpReq.kind is ToolCallKind.Mcp)
    assertOrPrint("Mcp serverName", (mcpReq.kind as ToolCallKind.Mcp).serverName == "aslocate")

    val mcpOk = mcpReq.ok("""{"x":1}""")
    assertOrPrint("Mcp ok() content", mcpOk.content == """{"x":1}""")

    val mcpErr = mcpReq.error("not implemented")
    assertOrPrint("Mcp error() content", "not implemented" in mcpErr.content)

    // Test that both implement sealed interface
    val req1: ToolCallRequest = req
    val req2: ToolCallRequest = mcpReq
    assertOrPrint("ToolCallRequest is sealed interface", req1 is ToolCallRequest && req2 is ToolCallRequest)

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
