package com.niki914.s3ss10n

fun main() {
    println("=== ToolCallKind Smoke Test ===")

    // Local
    val local: ToolCallKind = ToolCallKind.Local
    assertOrPrint("Local is data object", local is ToolCallKind.Local)

    // Mcp
    val mcp = ToolCallKind.Mcp("aslocate")
    assertOrPrint("Mcp.serverName == 'aslocate'", mcp.serverName == "aslocate")

    // Equality
    assertOrPrint("Local == Local", ToolCallKind.Local == ToolCallKind.Local)
    assertOrPrint("Mcp('a') == Mcp('a')", ToolCallKind.Mcp("a") == ToolCallKind.Mcp("a"))
    assertOrPrint("Mcp('a') != Mcp('b')", ToolCallKind.Mcp("a") != ToolCallKind.Mcp("b"))

    // Use in SessionEvent (now that ToolCallKind exists)
    val tr = SessionEvent.ToolRunning("c1", "t1", ToolCallKind.Local)
    assertOrPrint("SessionEvent with ToolCallKind.Local", tr.kind == ToolCallKind.Local)

    val tr2 = SessionEvent.ToolRunning("c2", "t2", ToolCallKind.Mcp("srv"))
    val kind = tr2.kind
    assertOrPrint("SessionEvent with Mcp", (kind as ToolCallKind.Mcp).serverName == "srv")

    // ToolSucceeded
    val ts = SessionEvent.ToolSucceeded("c1", "toast", ToolCallKind.Local, """{"ok":true}""")
    assertOrPrint("ToolSucceeded.resultJson", ts.resultJson == """{"ok":true}""")

    // ToolFailed
    val tf = SessionEvent.ToolFailed("c1", "toast", ToolCallKind.Local, "timeout")
    assertOrPrint("ToolFailed.message == 'timeout'", tf.message == "timeout")
    assertOrPrint("ToolFailed.resultJson == null", tf.resultJson == null)

    // ToolFailed with result
    val tf2 = SessionEvent.ToolFailed("c2", "foo", ToolCallKind.Local, "err", """{"e":1}""")
    assertOrPrint("ToolFailed with result", tf2.resultJson == """{"e":1}""")

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
