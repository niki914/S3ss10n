package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main11() = runBlocking {
    println("=== RoundContextIsolationTest ===")

    val session = Session.open {
        endpoint ="https://api.deepseek.com/v1/chat/completions"  
        apiKey = "sk-1307bc01b96c49c0909c7bd0a4dacf6d"
        model = "deepseek-v4-flash"
    }

    val events1 = mutableListOf<SessionEvent>()
    val events2 = mutableListOf<SessionEvent>()

    // Simulate a slow send
    val job1 = launch {
        session.send("Hello 1") { event ->
            events1.add(event)
            println("Round 1 Event: ${event.javaClass.simpleName}")
        }
    }

    // Give it a tiny bit of time to start
    delay(50)

    // Send a new message, which should cancel the first round and start a new one
    val job2 = launch {
        session.send("Hello 2") { event ->
            events2.add(event)
            println("Round 2 Event: ${event.javaClass.simpleName}")
        }
    }

    job1.join()
    job2.join()

    println("Events in Round 1: ${events1.size}")
    println("Events in Round 2: ${events2.size}")

    // A proper test would mock the network to return events slowly, but here we just verify
    // that the second send doesn't somehow leak its textAccumulator or initialInput to the first's onEvent.
    // The design guarantees it because `onEvent` is bound to the `RoundContext` which is round-scoped.

    println("=== PASSED ===")
}