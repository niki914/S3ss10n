# S3ss10n

一个面向 Android 的、基于 OkHttp + SSE 的流式 Chat Completions 客户端封装，提供会话级别的历史管理与 OpenAI 兼容的 Tool Calling 支持。

Demo：本仓库包含一个可运行的 demo app，见 <https://github.com/niki914/s3ss10n/tree/main/app>

Demo.apk：[Demo.apk](https://github.com/niki914/s3ss10n/releases/latest)

## 特性

- 面向 OpenAI 兼容接口的流式输出（SSE）
- 会话封装：自动维护 history（user/assistant/tool），以及回合状态
- Tool Calling：支持将流式分片的 tool_calls 合并为完整调用，并串行回传 tool 结果
- 动态网络配置：BaseUrl、超时、Proxy、额外 Interceptor

## 安装

### Gradle

如果你通过 JitPack 分发：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
        mavenCentral()
        google()
    }
}
```

然后添加依赖（将坐标替换为你发布的 group/artifact/version）：

```kotlin
dependencies {
    implementation("com.github.niki914:s3ss10n:<version>")
}
```

如果你发布到 MavenCentral / 私服，请按对应仓库的方式添加 repository 与坐标。

## 快速开始

核心类是 `ChatSession`（见 [ChatSession.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt)）。它负责：

- 维护对话历史（见 [ChatPair.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/ChatPair.kt)）
- 驱动流式请求与回调分发
- 遇到 tool call 时等待业务侧返回 tool 结果，并自动发起下一轮补全

### 1) 创建会话并配置

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

    readTimeout = 60
    connectTimeout = 30
    writeTimeout = 30

    // 可选：代理
    // httpProxy("127.0.0.1", 7890)
}

session.preConnect()
```

`baseUrl` 是本库最终发起请求时使用的 URL，并不强制必须以 `/v1/chat/completions` 结尾。
只要你的服务端接受 OpenAI 风格的 Chat Completions 请求体，就可以按服务端要求填写对应的 endpoint。

示例：

- OpenAI：`https://api.openai.com/v1/chat/completions`
- Ollama（OpenAI 兼容 endpoint）：`http://localhost:11434/v1/chat/completions`

### 2) 发送消息

```kotlin
session.sendMessage("Hello")
```

### 3) 获取历史

```kotlin
val history: List<ChatPair> = session.getHistory()
```

`ChatPair` 的 `state` 表示当前回合状态（Pending / Generating / Succeeded / Failed）。

## Tool Calling

本库对 OpenAI 风格的 `tool_calls` 做了两层封装：

- 协议层：`ToolDefinition`（见 [RequestModel.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/chat/protocol/RequestModel.kt)）、`ToolCall`（见 [ResponseModel.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/chat/protocol/ResponseModel.kt)）、`Message.Tool`（见 [Message.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/chat/protocol/beans/Message.kt)）
- 业务层：在 `onToolCall` 中返回 `Message.Tool`，会话会在所有 tool call 结果就绪后自动继续下一轮补全

### 使用 ToolManager（推荐）

`s3ss10n` 提供了一个轻量的工具注册与执行器：

- `ToolManager`（见 [ToolManager.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/toolbase/ToolManager.kt)）
- `ToolModel`（见 [ToolModel.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/toolbase/ToolModel.kt)）
- `ToolCallJsonTransformLayer`（见 [ToolCallJsonTransformLayer.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/toolbase/ToolCallJsonTransformLayer.kt)）

#### 1) 定义一个 Tool

```kotlin
class GetCurrentTimeTool : ToolModel() {
    override val name: String = "getCurrentTime"
    override val description: String = "获取当前时间"

    override suspend fun ToolCallJsonTransformLayer.execInternal() {
        val timezone = getFromToolCall<String>("timezone") ?: "UTC"
        this["timezone"] = timezone
        this["time"] = System.currentTimeMillis().toString()
    }
}
```

如果你希望让模型更可靠地产生参数，可以定义 `properties/required`：

```kotlin
class WeatherTool : ToolModel() {
    override val name: String = "getCurrentWeather"
    override val description: String = "查询城市天气"

    override val properties = mapOf(
        "location" to PropertyDefinition(
            type = "string",
            description = "城市名，例如：北京"
        )
    )
    override val required = listOf("location")

    override suspend fun ToolCallJsonTransformLayer.execInternal() {
        val location = getFromToolCall<String>("location") ?: run {
            state = ToolCallJsonTransformLayer.ResponseState.IllegalArgs
            return
        }

        this["location"] = location
        this["weather"] = "sunny"
    }
}
```

#### 2) 注册工具并注入 tools 描述

```kotlin
val toolManager = ToolManager().apply {
    registerTool(GetCurrentTimeTool())
    registerTool(WeatherTool())
}

session.updateConfig {
    tools = toolManager.descriptions
}
```

#### 3) 在 onToolCall 中执行并回传结果

```kotlin
override suspend fun onToolCall(toolCall: ToolCall): Message.Tool {
    val json = toolManager.exec(
        toolCall = toolCall,
        appParams = mapOf(
            "context" to appContext
        )
    )

    return Message.Tool(
        toolCallId = toolCall.id!!,
        name = toolCall.function!!.name!!,
        content = json
    )
}
```

## 配置项

通过 `ConfigBuilder` 配置（见 [ConfigBuilder.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/util/ConfigBuilder.kt)）：

- `baseUrl`: 实际请求 URL（可按服务端要求填写，不强制 `/v1/chat/completions`）
- `apiKey`: 会作为 `Authorization: Bearer <apiKey>` 注入
- `modelName`: 请求体 `model`
- `prompt`: 可选 system prompt
- `tools`: 可选 tool definitions
- `readTimeout/connectTimeout/writeTimeout`: 秒
- `proxy`: `httpProxy(...)` / `socksProxy(...)`
- `interceptors`: 额外 OkHttp Interceptor

## 常见问题

### 1) 为什么提示 Config 无效？

当 `baseUrl` 不是 `http(s)` 或 `modelName` 为空时，会触发 `ConfigInvalidException`（见 [Config.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/Config.kt)）并回调 `onConfigInvalid()`。

### 2) tool_calls 为什么需要等待？

OpenAI 风格的流式 `tool_calls` 可能被拆分成多段传输。库内部会将分片合并为完整 JSON 参数（见 [ToolCallHandler.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/util/ToolCallHandler.kt)），并在本轮流式结束后汇总 tool 执行结果再继续下一轮补全（见 [ChatSession.kt](./s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt)）。

## License

TBD
