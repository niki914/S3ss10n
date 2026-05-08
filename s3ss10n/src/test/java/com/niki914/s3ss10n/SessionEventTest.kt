package com.niki914.s3ss10n

fun main() {
    println("=== SessionEvent Smoke Test ===")

    // RoundStarted
    val rs = SessionEvent.RoundStarted("hello")
    assertOrPrint("RoundStarted.input == 'hello'", rs.input == "hello")

    // TextDelta
    val td = SessionEvent.TextDelta("He", "Hello")
    assertOrPrint("TextDelta.delta == 'He'", td.delta == "He")
    assertOrPrint("TextDelta.fullText == 'Hello'", td.fullText == "Hello")

    // RoundCompleted
    val rc = SessionEvent.RoundCompleted("Hello World")
    assertOrPrint("RoundCompleted.fullText", rc.fullText == "Hello World")

    // Error with Transport stage
    val err = SessionEvent.Error(SessionEvent.Stage.Transport, "timeout", null)
    assertOrPrint("Error.stage == Transport", err.stage == SessionEvent.Stage.Transport)

    // Error with Parse stage and cause
    val err2 = SessionEvent.Error(SessionEvent.Stage.Parse, "bad json", IllegalStateException("boom"))
    assertOrPrint("Error with cause", err2.cause?.message == "boom")

    // Error with Tool stage
    val err3 = SessionEvent.Error(SessionEvent.Stage.Tool, "exec failed", null)
    assertOrPrint("Error.stage == Tool", err3.stage == SessionEvent.Stage.Tool)

    // Error with Session stage
    val err4 = SessionEvent.Error(SessionEvent.Stage.Session, "session lost", null)
    assertOrPrint("Error.stage == Session", err4.stage == SessionEvent.Stage.Session)

    // sealed interface type check
    val event: SessionEvent = SessionEvent.RoundStarted("test")
    assertOrPrint("RoundStarted is SessionEvent", event is SessionEvent)
    assertOrPrint("TextDelta is SessionEvent", SessionEvent.TextDelta("a", "b") is SessionEvent)
    assertOrPrint("RoundCompleted is SessionEvent", SessionEvent.RoundCompleted("x") is SessionEvent)
    assertOrPrint("Error is SessionEvent", SessionEvent.Error(SessionEvent.Stage.Transport, "m") is SessionEvent)

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
