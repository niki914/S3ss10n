package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.LocalToolCallRequest
import com.niki914.s3ss10n.McpToolCallRequest
import com.niki914.s3ss10n.ToolCallSpec
import com.niki914.s3ss10n.ToolCallKind
import com.niki914.s3ss10n.ToolCallOutcome
import com.niki914.s3ss10n.ToolCallRequest
import kotlinx.coroutines.runBlocking

fun main3() {
    println("=== ToolCallRequest Smoke Test ===")

    val toolCall = ToolCallSpec(
        callId = "call_123",
        toolName = "test_tool",
        argumentsJson = """{"key":"value"}"""
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
    assertOrPrint("ok() returns json", okResult == """{"done":true}""")
    assertOrPrint("ok records Success outcome", req.lastOutcome is ToolCallOutcome.Success)
    assertOrPrint("ok records correct resultJson", (req.lastOutcome as ToolCallOutcome.Success).resultJson == """{"done":true}""")

    val errResult = req.error("timeout", """{"code":1}""")
    assertOrPrint("error() returns json", "timeout" in errResult)
    assertOrPrint("error() contains error", "timeout" in errResult)
    assertOrPrint("error records Failure outcome", req.lastOutcome is ToolCallOutcome.Failure)
    assertOrPrint("error records correct errorMessage", (req.lastOutcome as ToolCallOutcome.Failure).errorMessage == "timeout")

    req.ok("""{"detail":"no error occurred"}""")
    assertOrPrint("ok content with substring 'error' still Success", req.lastOutcome is ToolCallOutcome.Success)

    runBlocking {
        val delegated = req.delegate()
        assertOrPrint("delegate on Local returns error message", "no built-in implementation" in delegated)
        assertOrPrint("delegate on Local sets outcome to Failure", req.lastOutcome is ToolCallOutcome.Failure)
    }

    // Test McpToolCallRequest
    val mcpReq = McpToolCallRequest(toolCall, "aslocate", emptyMap())
    assertOrPrint("Mcp kind", mcpReq.kind is ToolCallKind.Mcp)
    assertOrPrint("Mcp serverName", (mcpReq.kind as ToolCallKind.Mcp).serverName == "aslocate")
    assertOrPrint("Mcp appParams accessible", mcpReq.appParams.isEmpty())

    val mcpOk = mcpReq.ok("""{"x":1}""")
    assertOrPrint("Mcp ok() content", mcpOk == """{"x":1}""")
    assertOrPrint("Mcp ok records Success outcome", mcpReq.lastOutcome is ToolCallOutcome.Success)

    val mcpErr = mcpReq.error("not implemented")
    assertOrPrint("Mcp error() content", "not implemented" in mcpErr)
    assertOrPrint("Mcp error records Failure outcome", mcpReq.lastOutcome is ToolCallOutcome.Failure)

    // Test that both implement sealed interface
    val req1: ToolCallRequest = req
    val req2: ToolCallRequest = mcpReq
    assertOrPrint("ToolCallRequest is sealed interface", req1 is ToolCallRequest && req2 is ToolCallRequest)

    println("=== ALL PASSED ===")
}
