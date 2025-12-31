# s3ss10n

[中文文档](https://github.com/niki914/s3ss10n/blob/main/README_zh_CN.md)

An Android streaming Chat Completions client based on OkHttp + SSE.
It provides session-level history management and OpenAI-compatible tool calling.

## Demo

This repository contains a runnable demo app:
<https://github.com/niki914/s3ss10n/tree/main/app>

[Demo.apk](https://github.com/niki914/s3ss10n/releases/latest)

## Features

- Streaming output for OpenAI-compatible endpoints (SSE)
- Session wrapper with automatic history (user/assistant/tool) and round state
- Tool calling: merges streaming tool_call fragments and resumes the next round automatically
- Dynamic networking config: baseUrl, timeouts, proxy, extra interceptors

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
    implementation("com.github.niki914:s3ss10n:1.0")
}
```

## Quick Start

The main entry is `ChatSession`.

```kotlin
val session = ChatSession().apply {
    callback = object : ChatSession.Callback {
        override fun onConfigInvalid() {
        }

        override fun onStarted() {
        }

        override fun onUpdated() {
        }

        override fun onContent(aiContent: AIContent) {
        }

        override fun onError(message: String, cause: Throwable?) {
        }

        override suspend fun onToolCall(toolCall: ToolCall): Message.Tool {
            return Message.Tool(
                toolCallId = toolCall.id ?: "tool_call_id",
                name = toolCall.function?.name ?: "tool_name",
                content = "{}"
            )
        }

        override fun onCompleted(isSuccess: Boolean, cause: Throwable?) {
        }
    }
}

session.updateConfig {
    baseUrl = "https://api.openai.com/v1/chat/completions"
    apiKey = "YOUR_API_KEY"
    modelName = "gpt-4o-mini"
    prompt = "You are a helpful assistant."
}

session.preConnect()
session.sendMessage("Hello")
```

`baseUrl` is the request URL used by this library.
It is not required to end with `/v1/chat/completions`.
Use whatever endpoint your server expects, as long as it accepts an OpenAI-compatible Chat Completions payload.

Examples:

- OpenAI: `https://api.openai.com/v1/chat/completions`
- Ollama (OpenAI-compatible endpoint): `http://localhost:11434/v1/chat/completions`

## Tool Calling

If you use the built-in tool runner, see the demo app or the following classes:

- [ToolManager.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/toolbase/ToolManager.kt)
- [ToolModel.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/toolbase/ToolModel.kt)
- [ToolCallJsonTransformLayer.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/toolbase/ToolCallJsonTransformLayer.kt)s