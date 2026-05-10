# s3ss10n

一个面向 Android 的、基于 OkHttp + SSE 的流式 Chat Completions 客户端封装。支持 OpenAI、Anthropic、DeepSeek 协议，提供会话级别的历史管理、Tool Calling，以及 MCP 支持。

Demo：本仓库包含一个可运行的 demo app，见 <https://github.com/niki914/s3ss10n/tree/main/app>

## 特性

- 多协议支持：OpenAI、Anthropic、DeepSeek
- 流式输出（SSE）
- 会话封装：自动维护 history（user/assistant/tool），以及回合状态
- Tool Calling：将流式分片的 tool_calls 合并为完整调用，并自动回传 tool 结果继续下一轮
- 本地工具 DSL：带类型化参数 schema 声明
- MCP（Model Context Protocol）HTTP server 支持，自动发现工具
- 动态配置：endpoint、model、超时、system prompt、temperature

## 安装

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
    implementation("com.github.niki914:s3ss10n:1.9.9")
}
```

## 快速开始

核心入口是 `Session`。用 `Session.open` 创建，用 `send` 发送消息，通过事件回调观察结果。

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

`endpoint` 是本库最终发起请求时使用的 URL，并不强制必须以 `/v1/chat/completions` 结尾。
只要你的服务端接受 OpenAI 风格的 Chat Completions 请求体，就可以按服务端要求填写对应的 endpoint。

示例：

- OpenAI：`https://api.openai.com/v1/chat/completions`
- Anthropic：`https://api.anthropic.com/v1/messages`
- DeepSeek：`https://api.deepseek.com/v1/chat/completions`
- Ollama（OpenAI 兼容 endpoint）：`http://localhost:11434/v1/chat/completions`

## Tool Calling

s3ss10n 自动合并流式 `tool_calls` 分片。通过 `hooks { ... }` 拦截工具调用并返回结果。

### 1) 注册本地工具

```kotlin
val session = Session.open<SessionProtocols.OpenAI> {
    endpoint = "https://api.openai.com/v1/chat/completions"
    apiKey = "YOUR_API_KEY"
    model = "gpt-4o-mini"

    localTools {
        add("getCurrentWeather") {
            description = "查询城市天气"
            string("location") {
                description = "城市名，例如：北京"
                required = true
            }
        }
    }
}
```

### 2) 在 hooks 中处理工具调用

```kotlin
val session = Session.open<SessionProtocols.OpenAI> {
    // ... endpoint, apiKey, model ...

    hooks { call ->
        when (call.name) {
            "getCurrentWeather" -> ok("""{"weather":"sunny","location":"北京"}""")
            else -> delegate()
        }
    }

    localTools {
        add("getCurrentWeather") {
            description = "查询城市天气"
            string("location") {
                description = "城市名"
                required = true
            }
        }
    }
}
```

`hooks { ... }` 接收 `ToolCallRequest`，必须返回 `Message.Tool`。可用方法：
- `ok(contentJson)` — 成功
- `error(message)` — 失败
- `delegate()` — 交给默认处理器（例如 MCP 工具）

### 3) MCP 工具

```kotlin
val session = Session.open<SessionProtocols.OpenAI> {
    endpoint = "https://api.openai.com/v1/chat/completions"
    apiKey = "YOUR_API_KEY"
    model = "gpt-4o-mini"

    hooks { call ->
        when (call.kind) {
            ToolCallKind.Local -> {
                // 在这里处理本地工具
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

MCP 工具会自动从服务端发现。可通过 `call.kind`（`ToolCallKind.Local` / `ToolCallKind.Mcp(serverName)`）做分流。

## Session API

```kotlin
interface Session {
    suspend fun send(text: String, onEvent: (SessionEvent) -> Unit = {})
    suspend fun update(block: SessionConfig.Builder.() -> Unit)
    suspend fun getHistory(): List<ChatTurn>
    suspend fun resetConversation()
    suspend fun close()
}
```

- `send`：发起一轮新的用户输入，事件通过 `onEvent` 回调
- `update`：更新后续轮次使用的配置，不影响正在运行的轮次
- `getHistory`：返回对话历史（`ChatTurn.User`、`ChatTurn.Assistant`、`ChatTurn.ToolResult`）
- `resetConversation`：清空历史，通常在切换模型、MCP 或新建对话时调用
- `close`：释放会话资源

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

## 配置项

通过 `Session.open { }` 或 `session.update { }` 设置：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `endpoint` | `String` | `""` | 请求 URL（任意 OpenAI 兼容 endpoint） |
| `apiKey` | `String` | `""` | 以 `Authorization: Bearer <apiKey>` 注入 |
| `model` | `String` | `""` | 请求体 `model` 字段 |
| `systemPrompt` | `String?` | `null` | 可选 system prompt |
| `temperature` | `Float` | `0.7f` | 采样温度 |
| `connectTimeoutSeconds` | `Long` | `30` | 连接超时（秒） |
| `readTimeoutSeconds` | `Long` | `60` | 读取超时（秒） |
| `writeTimeoutSeconds` | `Long` | `30` | 写入超时（秒） |

以下字段仅 `open` 时生效（`update` 中修改被忽略）：
- `jsonCodec: JsonCodec?` — 自定义 JSON 编解码器（默认 Gson）
- `httpEngine: HttpEngine?` — 自定义 HTTP 引擎（默认 OkHttp）

## 常见问题

### 为什么提示 Config 无效？

当 `endpoint` 不是 `http(s)` 或 `model` 为空时，`send()` 会抛出 `ConfigInvalidException` 并发送 `SessionEvent.Error(stage = Stage.Session, ...)`。

### tool_calls 为什么需要等待？

OpenAI 风格的流式 `tool_calls` 可能被拆分成多段 SSE 传输。库内部会将分片合并为完整参数，并在本轮流式结束后汇总 tool 执行结果再继续下一轮补全。
