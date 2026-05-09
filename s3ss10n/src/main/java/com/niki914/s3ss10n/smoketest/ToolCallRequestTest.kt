package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.LocalToolCallRequest
import com.niki914.s3ss10n.McpToolCallRequest
import com.niki914.s3ss10n.ToolCallKind
import com.niki914.s3ss10n.ToolCallOutcome
import com.niki914.s3ss10n.ToolCallRequest
import com.niki914.s3ss10n.chat.protocol.FunctionCall
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import kotlinx.coroutines.runBlocking

fun main3() {
    println("=== ToolCallRequest Smoke Test ===")

    val toolCall = ToolCall(
        id = "call_123",
        type = "function",
        function = FunctionCall(
            name = "test_tool",
            arguments = """{"key":"value"}"""
        )
    )

    val params = mapOf("ctx" to "contextInstance")

    // Test LocalToolCallRequest.ok()
    val req = LocalToolCallRequest(toolCall, params)
    assertOrPrint("id == 'call_123'", req.id == "call_123")
    assertOrPrint("name == 'test_tool'", req.name == "test_tool")
    assertOrPrint("argumentsJson", req.argumentsJson == """{"key":"value"}""")
    assertOrPrint("kind is Local", req.kind == ToolCallKind.Local)
    assertOrPrint("appParams accessible", req.appParams["ctx"] == "contextInstance")

    val okResult = req.ok("""{"done":true}""")
    assertOrPrint("ok() returns Message.Tool", okResult is Message.Tool)
    assertOrPrint("ok() toolCallId", okResult.toolCallId == "call_123")
    assertOrPrint("ok() content", okResult.content == """{"done":true}""")
    assertOrPrint("ok records Success outcome", req.lastOutcome is ToolCallOutcome.Success)
    assertOrPrint("ok records correct resultJson", (req.lastOutcome as ToolCallOutcome.Success).resultJson == """{"done":true}""")

    val errResult = req.error("timeout", """{"code":1}""")
    assertOrPrint("error() returns Message.Tool", errResult is Message.Tool)
    assertOrPrint("error() contains error", "timeout" in errResult.content)
    assertOrPrint("error records Failure outcome", req.lastOutcome is ToolCallOutcome.Failure)
    assertOrPrint("error records correct errorMessage", (req.lastOutcome as ToolCallOutcome.Failure).errorMessage == "timeout")

    val trickyOkResult = req.ok("""{"detail":"no error occurred"}""")
    assertOrPrint("ok content with substring 'error' still Success", req.lastOutcome is ToolCallOutcome.Success)

    runBlocking {
        val delegated = req.delegate()
        assertOrPrint("delegate on Local returns error message", "no built-in implementation" in delegated.content)
        // Note: currently delegate in LocalToolCallRequest returns error() which sets outcome to Failure
        assertOrPrint("delegate on Local sets outcome to Failure", req.lastOutcome is ToolCallOutcome.Failure)
    }

    // Test McpToolCallRequest
    val mcpReq = McpToolCallRequest(toolCall, "aslocate", emptyMap())
    assertOrPrint("Mcp kind", mcpReq.kind is ToolCallKind.Mcp)
    assertOrPrint("Mcp serverName", (mcpReq.kind as ToolCallKind.Mcp).serverName == "aslocate")
    assertOrPrint("Mcp appParams accessible", mcpReq.appParams.isEmpty())

    val mcpOk = mcpReq.ok("""{"x":1}""")
    assertOrPrint("Mcp ok() content", mcpOk.content == """{"x":1}""")
    assertOrPrint("Mcp ok records Success outcome", mcpReq.lastOutcome is ToolCallOutcome.Success)

    val mcpErr = mcpReq.error("not implemented")
    assertOrPrint("Mcp error() content", "not implemented" in mcpErr.content)
    assertOrPrint("Mcp error records Failure outcome", mcpReq.lastOutcome is ToolCallOutcome.Failure)

    // Test that both implement sealed interface
    val req1: ToolCallRequest = req
    val req2: ToolCallRequest = mcpReq
    assertOrPrint("ToolCallRequest is sealed interface", req1 is ToolCallRequest && req2 is ToolCallRequest)

    println("=== ALL PASSED ===")
}
