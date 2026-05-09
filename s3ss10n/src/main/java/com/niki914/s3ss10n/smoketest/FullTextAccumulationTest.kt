package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.SessionProtocols
import kotlinx.coroutines.runBlocking

fun main9() = runBlocking {
    println("=== FullText Cross-Round Accumulation Test ===")
    println()

    // Test 1: Accumulator starts empty on send()
    println("--- Test 1: Accumulator starts empty on each send() ---")
    val session = Session.open<SessionProtocols.OpenAI> {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test"
        model = "gpt-4.1-mini"
    }

    // First send
    println("  Send 1...")
    val events1 = mutableListOf<SessionEvent>()
    session.send("first message") { event ->
        events1.add(event)
    }

    val textDeltas1 = events1.filterIsInstance<SessionEvent.TextDelta>()
    println("  Send 1 TextDeltas: ${textDeltas1.size}")
    if (textDeltas1.isNotEmpty()) {
        val lastFullText1 = textDeltas1.last().fullText
        println("  Send 1 final fullText: '$lastFullText1'")
    }

    // Second send — accumulator should be fresh
    println("  Send 2...")
    val events2 = mutableListOf<SessionEvent>()
    session.send("second message") { event ->
        events2.add(event)
    }

    val textDeltas2 = events2.filterIsInstance<SessionEvent.TextDelta>()
    println("  Send 2 TextDeltas: ${textDeltas2.size}")
    if (textDeltas2.isNotEmpty()) {
        val firstFullText2 = textDeltas2.first().fullText
        println("  Send 2 first fullText: '$firstFullText2'")
        // Accumulator should be fresh — first delta's fullText should NOT contain text from send 1
        if (textDeltas1.isNotEmpty()) {
            val send1Text = textDeltas1.last().fullText
            val containsOld = firstFullText2.contains(send1Text) && send1Text.isNotEmpty()
            if (containsOld) {
                println("  FAIL: Send 2 fullText contains Send 1 text — accumulator not reset!")
            } else {
                println("  PASS: Accumulator properly reset between send() calls")
            }
        }
    }

    // Test 2: RoundStarted carries correct input
    println()
    println("--- Test 2: RoundStarted carries input ---")
    val roundStarts = events1.filterIsInstance<SessionEvent.RoundStarted>()
    if (roundStarts.isNotEmpty()) {
        val input = roundStarts.first().input
        assertOrPrint("RoundStarted.input == 'first message'", input == "first message")
    } else {
        println("  (no RoundStarted — expected with real endpoint)")
    }

    // Test 3: RoundCompleted carries fullText
    println()
    println("--- Test 3: RoundCompleted carries fullText ---")
    val completed = events1.filterIsInstance<SessionEvent.RoundCompleted>()
    if (completed.isNotEmpty()) {
        val fullText = completed.first().fullText
        println("  RoundCompleted.fullText: '$fullText'")
        // If we got text deltas, fullText should match
        if (textDeltas1.isNotEmpty()) {
            assertOrPrint(
                "RoundCompleted.fullText matches last TextDelta.fullText",
                fullText == textDeltas1.last().fullText
            )
        }
    }

    // Test 4: Error events carry stage info
    println()
    println("--- Test 4: Error stage classification ---")
    val errors = events1.filterIsInstance<SessionEvent.Error>()
    if (errors.isNotEmpty()) {
        println("  Error count: ${errors.size}")
        errors.forEach { err ->
            println("  Error[${err.stage}]: ${err.message}")
        }
        // At minimum, errors should have a stage and message
        assertOrPrint("Error has stage", errors.all { it.stage.name.isNotEmpty() })
        assertOrPrint("Error has message", errors.all { it.message.isNotEmpty() })
    }

    println()
    println("=== FULLTEXT ACCUMULATION TEST COMPLETE ===")
}
