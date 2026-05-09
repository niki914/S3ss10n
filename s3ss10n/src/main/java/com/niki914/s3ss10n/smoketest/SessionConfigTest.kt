package com.niki914.s3ss10n.smoketest

import com.niki914.s3ss10n.SessionConfig

fun main6() {
    println("=== SessionConfig Smoke Test ===")

    // Default values
    val cfg = SessionConfig()
    assertOrPrint("default endpoint empty", cfg.endpoint == "")
    assertOrPrint("default temperature 0.7", cfg.temperature == 0.7f)
    assertOrPrint("default connectTimeout 30", cfg.connectTimeoutSeconds == 30L)
    assertOrPrint("default readTimeout 60", cfg.readTimeoutSeconds == 60L)
    assertOrPrint("default writeTimeout 30", cfg.writeTimeoutSeconds == 30L)

    // Property assignment (simulates Session.open {} DSL)
    cfg.apply {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test"
        model = "gpt-4.1-mini"
        systemPrompt = "You are helpful."
        temperature = 0.5f
    }
    assertOrPrint("endpoint set", cfg.endpoint == "https://api.openai.com/v1/chat/completions")
    assertOrPrint("model set", cfg.model == "gpt-4.1-mini")
    assertOrPrint("temperature set", cfg.temperature == 0.5f)

    // localTools DSL
    cfg.localTools {
        add("toast") {
            description = "显示提示"
            string("message") { required = true }
        }
    }
    val defs = cfg.buildToolDefinitions()
    assertOrPrint("localTools -> ToolDefinition", defs.size == 1)
    assertOrPrint("tool name", defs[0].function.name == "toast")

    // hooks DSL
    cfg.hooks {
        ok("""{"handled":true}""")
    }
    assertOrPrint("hooks set", cfg.hooksBlock != null)

    // mcp DSL (placeholder)
    cfg.mcp {
        add("aslocate") {
            http { url = "http://127.0.0.1:51338/mcp" }
        }
    }
    assertOrPrint("mcp servers", cfg.mcpRegistry.servers.size == 1)

    // appParams DSL
    cfg.appParams {
        put("test_key", "test_value")
    }
    assertOrPrint("appParams put", cfg.appParamsSnapshot()["test_key"] == "test_value")

    // snapshot isolation
    val snap = cfg.snapshot()
    cfg.endpoint = "https://new.endpoint"
    cfg.appParams {
        put("test_key", "new_value")
    }
    cfg.localTools {
        add("new_tool") {
            description = "New tool"
        }
    }
    assertOrPrint("snapshot endpoint isolated", snap.endpoint == "https://api.openai.com/v1/chat/completions")
    assertOrPrint("snapshot appParams isolated", snap.appParamsSnapshot()["test_key"] == "test_value")
    assertOrPrint("snapshot localTools isolated", snap.buildToolDefinitions().size == 1)

    println("=== ALL PASSED ===")
}

