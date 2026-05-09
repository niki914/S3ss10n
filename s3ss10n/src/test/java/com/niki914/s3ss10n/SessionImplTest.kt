package com.niki914.s3ss10n

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Session Smoke Test (via ChatSession) ===")

    // Create Session with full config
    val session = Session.open {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test"
        model = "gpt-4.1-mini"
        systemPrompt = "You are a test assistant."
        temperature = 0.5f

        hooks { call ->
            println("  hooks called: name=${call.name}, id=${call.id}")
            ok("""{"status":"ok"}""")
        }

        localTools {
            add("test_tool") {
                description = "A test tool"
                string("param1") { required = true }
            }
        }

        mcp {
            add("test_mcp") {
                http { url = "http://localhost:9999/mcp" }
            }
        }
    }

    println("Session created: $session")

    // Test send (will fail because no real endpoint, but should emit Error event)
    val events = mutableListOf<SessionEvent>()
    session.send("Hello") { event ->
        events.add(event)
        println("  Event: ${event.javaClass.simpleName}")
    }

    println("Events received: ${events.size}")
    events.forEach { event ->
        when (event) {
            is SessionEvent.RoundStarted -> println("  -> RoundStarted: input=${event.input}")
            is SessionEvent.TextDelta -> println("  -> TextDelta: delta=${event.delta}, full=${event.fullText}")
            is SessionEvent.ToolRunning -> println("  -> ToolRunning: ${event.toolName}")
            is SessionEvent.ToolSucceeded -> println("  -> ToolSucceeded: ${event.toolName}")
            is SessionEvent.ToolFailed -> println("  -> ToolFailed: ${event.toolName}: ${event.message}")
            is SessionEvent.RoundCompleted -> println("  -> RoundCompleted: ${event.fullText}")
            is SessionEvent.Error -> println("  -> Error[${event.stage}]: ${event.message}")
        }
    }

    // Test resetConversation
    session.resetConversation()
    println("resetConversation called")

    // Test close
    session.close()
    println("close called")

    println("=== ALL PASSED ===")
}
