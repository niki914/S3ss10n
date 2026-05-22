# s3ss10n

[中文文档](https://github.com/niki914/s3ss10n/blob/main/README_zh_CN.md)

An Android streaming Chat Completions client based on OkHttp + SSE.
Supports OpenAI, Anthropic, and DeepSeek protocols. Provides session-level history management, tool calling, and MCP support.

## Demo

This repository contains a runnable demo app:
<https://github.com/niki914/s3ss10n/tree/main/app>

## Features

- Multi-protocol support: OpenAI, Anthropic, DeepSeek
- Streaming output via SSE
- Session wrapper with automatic history (user/assistant/tool) and round state
- Tool calling: merges streaming tool_call fragments and resumes the next round automatically
- Local tool DSL with typed parameter schema
- MCP (Model Context Protocol) HTTP server support with automatic tool discovery
- Dynamic config: endpoint, model, timeouts, system prompt, temperature

## Installation

### Gradle (JitPack)

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
        mavenCentral()
        google()
    }
}
```

```kotlin
dependencies {
    implementation("com.github.niki914:s3ss10n:2.0.2")
}
```

## Quick Start

The main entry is `Session`. Create one with `Session.open`, send messages with `send`, and observe events via either the callback overload or the `Flow` overload.

```kotlin
val session = Session.open<SessionProtocols.OpenAI> {
    endpoint = "https://api.openai.com/v1/chat/completions"
    apiKey = "YOUR_API_KEY"
    model = "gpt-4o-mini"
    systemPrompt = "You are a helpful assistant."
}

session.send("Hello") { event ->
    when (event) {
        is SessionEvent.TextDelta -> print(event.delta)
        is SessionEvent.RoundCompleted -> println("\nDone: ${event.fullText}")
        is SessionEvent.Error -> println("Error: ${event.message}")
        else -> Unit
    }
}
```

```kotlin
session.send("Hello").collect { event ->
    when (event) {
        is SessionEvent.TextDelta -> print(event.delta)
        is SessionEvent.RoundCompleted -> println("\nDone: ${event.fullText}")
        is SessionEvent.Error -> println("Error: ${event.message}")
        else -> Unit
    }
}
```

`endpoint` is the request URL used by this library.
It is not required to end with `/v1/chat/completions`.
Use whatever endpoint your server expects, as long as it accepts an OpenAI-compatible Chat Completions payload.

Examples:

- OpenAI: `https://api.openai.com/v1/chat/completions`
- Anthropic: `https://api.anthropic.com/v1/messages`
- DeepSeek: `https://api.deepseek.com/v1/chat/completions`
- Ollama (OpenAI-compatible endpoint): `http://localhost:11434/v1/chat/completions`

## Tool Calling

s3ss10n merges streaming `tool_calls` fragments automatically. Use `hooks { ... }` to intercept tool calls and return results.

### 1) Register local tools

```kotlin
val session = Session.open<SessionProtocols.OpenAI> {
    endpoint = "https://api.openai.com/v1/chat/completions"
    apiKey = "YOUR_API_KEY"
    model = "gpt-4o-mini"

    localTools {
        add("getCurrentWeather") {
            description = "Get weather for a city"
            string("location") {
                description = "City name, e.g. Beijing"
                required = true
            }
        }
    }
}
```

### 2) Handle tool calls in hooks

```kotlin
val session = Session.open<SessionProtocols.OpenAI> {
    // ... endpoint, apiKey, model ...

    hooks { call ->
        when (call.name) {
            "getCurrentWeather" -> ok("""{"weather":"sunny","location":"Beijing"}""")
            else -> delegate()
        }
    }

    localTools {
        add("getCurrentWeather") {
            description = "Get weather for a city"
            string("location") {
                description = "City name"
                required = true
            }
        }
    }
}
```

`hooks { ... }` receives a `ToolCallRequest` and must return `Message.Tool`. Use:
- `ok(contentJson)` for success
- `error(message)` for failure
- `delegate()` to let the default handler take over (e.g. for MCP tools)

### 3) MCP tools

```kotlin
val session = Session.open<SessionProtocols.OpenAI> {
    endpoint = "https://api.openai.com/v1/chat/completions"
    apiKey = "YOUR_API_KEY"
    model = "gpt-4o-mini"

    hooks { call ->
        when (call.kind) {
            ToolCallKind.Local -> {
                // handle local tools here
                delegate()
            }
            is ToolCallKind.Mcp -> delegate()
        }
    }

    mcp {
        add("aslocate") {
            http { url = "http://127.0.0.1:51338/mcp" }
        }
    }
}
```

MCP tools are auto-discovered from the server. Use `call.kind` (`ToolCallKind.Local` / `ToolCallKind.Mcp(serverName)`) to route differently.

Discovery remains asynchronous by default. `open()`, `update()`, and `send()` may trigger background discovery, but they do not block the current round.
If you need MCP tools to be available on the first request right after changing MCP config, call `refreshMcpTools()` explicitly:

```kotlin
session.update {
    mcp {
        add("aslocate") {
            http { url = "http://127.0.0.1:51338/mcp" }
        }
    }
}

val result = session.refreshMcpTools()
session.send("Find the definition of this class")
```

`refreshMcpTools()` waits for `initialize + tools/list` to finish for the currently enabled MCP servers, keeps the old cache on failure, and returns `McpRefreshResult` so you can inspect partial success.

## Session API

```kotlin
interface Session {
    suspend fun send(text: String, onEvent: suspend (SessionEvent) -> Unit)
    fun send(text: String): Flow<SessionEvent>
    suspend fun update(block: SessionConfig.Builder.() -> Unit)
    suspend fun refreshMcpTools(): McpRefreshResult
    suspend fun getHistory(): List<ChatTurn>
    suspend fun resetConversation()
    suspend fun close()
}
```

- `send(text, onEvent)`: start a new user round. Events arrive via `onEvent`.
- `send(text)`: returns a cold `Flow<SessionEvent>`. The round starts when collected.
- `update`: change config for future rounds. Running rounds are not interrupted.
- `refreshMcpTools`: synchronously refresh MCP discovery for the currently enabled servers. Use this when you need the latest MCP tools to be visible to the next `send()`.
- `getHistory`: returns the conversation history (`ChatTurn.User`, `ChatTurn.Assistant`, `ChatTurn.ToolResult`).
- `resetConversation`: clear history. Use when switching model, MCP servers, or starting a new conversation.
- `close`: release session resources.

## SessionEvent

```kotlin
sealed interface SessionEvent {
    data class RoundStarted(val input: String)
    data class TextDelta(val delta: String, val fullText: String)
    data class ToolRunning(val callId: String, val toolName: String, val kind: ToolCallKind)
    data class ToolSucceeded(val callId: String, val toolName: String, val kind: ToolCallKind, val resultJson: String)
    data class ToolFailed(val callId: String, val toolName: String, val kind: ToolCallKind, val message: String, val resultJson: String?)
    data class RoundCompleted(val fullText: String)
    data class Error(val stage: Stage, val message: String, val cause: Throwable? = null)
}
```

## Config reference

Set via `Session.open { }` or `session.update { }`:

| Field | Type | Default | Description |
|---|---|---|---|
| `endpoint` | `String` | `""` | Request URL (any OpenAI-compatible endpoint) |
| `apiKey` | `String` | `""` | Injected as `Authorization: Bearer <apiKey>` |
| `model` | `String` | `""` | Model name sent in the request body |
| `systemPrompt` | `String?` | `null` | Optional system prompt |
| `temperature` | `Float` | `0.7f` | Sampling temperature |
| `connectTimeoutSeconds` | `Long` | `30` | Connection timeout in seconds |
| `readTimeoutSeconds` | `Long` | `60` | Read timeout in seconds |
| `writeTimeoutSeconds` | `Long` | `30` | Write timeout in seconds |

The following are open-only (changes in `update` are ignored):
- `jsonCodec: JsonCodec?` — custom JSON codec (default: Gson)
- `httpEngine: HttpEngine?` — custom HTTP engine (default: OkHttp)

## FAQ

### Why does ConfigInvalidException occur?

When `endpoint` is not `http(s)` or `model` is blank, `send()` throws `ConfigInvalidException` and emits `SessionEvent.Error(stage = Stage.Session, ...)`.

### Why do tool_calls need waiting?

OpenAI-style streaming `tool_calls` may be split across multiple SSE chunks. The library merges fragments internally and only starts tool execution after the stream completes. Once all tool results are collected, it automatically continues the next round.
