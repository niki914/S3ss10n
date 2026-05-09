package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.SessionEvent
import kotlinx.coroutines.runBlocking

fun main8() = runBlocking {
    println("=== Integration Smoke Test ===")
    println()

    // Test 1: Session creation with all DSL features
    println("--- Test 1: Session.open with full DSL ---")
    val session = Session.Companion.open {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test-key"
        model = "gpt-4.1-mini"
        systemPrompt = "You are a test assistant."
        temperature = 0.3f
        connectTimeoutSeconds = 10
        readTimeoutSeconds = 20
        writeTimeoutSeconds = 10

        hooks {
            println("    hooks invoked: name=$name, kind=$kind")
            when (name) {
                "toast" -> ok("""{"shown":true}""")
                else -> delegate()
            }
        }

        localTools {
            add("toast") {
                description = "Show a toast message"
                string("message") {
                    description = "The message to display"
                    required = true
                }
                integer("duration") {
                    description = "Duration in ms"
                }
            }
            add("setVolume") {
                description = "Set audio volume"
                integer("level") {
                    description = "Volume level 0-100"
                    required = true
                }
                boolean("speakBack") {
                    description = "Whether to speak back"
                }
            }
        }

        mcp {
            add("aslocate") {
                http { url = "http://127.0.0.1:51338/mcp" }
            }
        }
    }
    println("  Session created: OK")

    // Test 2: send with bad config (will get Error event)
    println()
    println("--- Test 2: send() with invalid endpoint ---")
    session.update { endpoint = "invalid" }
    val events2 = mutableListOf<SessionEvent>()
    try {
        session.send("Hello, world!") { event ->
            events2.add(event)
            val name = event.javaClass.simpleName
            println("  Event: $name")
        }
        kotlinx.coroutines.delay(100)
    } catch (e: Exception) {
        println("  Exception: ${e.message}")
    }

    val hasError = events2.any { it is SessionEvent.Error }
    println("  Has error event: $hasError")
    assertOrPrint("send with bad config emits Error", hasError)

    // Test 3: update
    println()
    println("--- Test 3: update ---")
    session.update {
        endpoint = "https://api.openai.com/v2/chat/completions"
        model = "gpt-4-turbo"
    }
    println("  update: OK")

    // Test 4: resetConversation
    println()
    println("--- Test 4: resetConversation ---")
    session.resetConversation()
    println("  resetConversation: OK")

    // Test 5: close
    println()
    println("--- Test 5: close ---")
    session.close()
    println("  close: OK")

    // Test 6: SessionConfig property types
    println()
    println("--- Test 6: Config property validation ---")
    val cfg = SessionConfig().apply {
        endpoint = "https://test.example.com/v1/chat"
        apiKey = "sk-abc123"
        model = "test-model"
        systemPrompt = "Be helpful."
        temperature = 0.8f
        connectTimeoutSeconds = 15
        readTimeoutSeconds = 45
        writeTimeoutSeconds = 15
    }
    assertOrPrint("endpoint", cfg.endpoint == "https://test.example.com/v1/chat")
    assertOrPrint("apiKey", cfg.apiKey == "sk-abc123")
    assertOrPrint("model", cfg.model == "test-model")
    assertOrPrint("systemPrompt", cfg.systemPrompt == "Be helpful.")
    assertOrPrint("temperature", cfg.temperature == 0.8f)
    assertOrPrint("connectTimeout", cfg.connectTimeoutSeconds == 15L)
    assertOrPrint("readTimeout", cfg.readTimeoutSeconds == 45L)
    assertOrPrint("writeTimeout", cfg.writeTimeoutSeconds == 15L)

    println()
    println("=== ALL INTEGRATION TESTS PASSED ===")
}
