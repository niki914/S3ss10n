# Session API 重构设计

## 目标

将现有 `ChatSession`/`ConfigBuilder`/`ChatEvent`/`ToolModel` 公开 API 改造为 PRD 定义的 `Session`/`SessionConfig`/`SessionEvent`/`localTools` DSL 形态。

MVP 范围：
- 全部新公开类型就位
- 内部通过适配层复用现有 SSE/OkHttp/HistoryKeeper 管道
- MCP 仅占位 DSL，编译通过即可
- `update{}` 暂不暴露（tools 不可变，实现复杂度高）

## 新公开类型

### 核心入口

```
Session (interface)
  suspend fun send(text: String, onEvent: (SessionEvent) -> Unit = {})
  suspend fun resetConversation()
  suspend fun close()
  companion object { fun open(block: SessionConfig.() -> Unit): Session }
```

实现类 `SessionImpl` 内部持有 `ChatSession`，负责事件映射和 hooks 调度。

### 配置

```
SessionConfig (class, NOT data class for MVP)
  var endpoint, apiKey, model, systemPrompt, temperature
  var connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds
  fun hooks(block: suspend ToolCallRequest.() -> Message.Tool)
  fun localTools(block: LocalToolRegistry.() -> Unit)
  fun mcp(block: McpRegistry.() -> Unit)
```

### 事件

```
SessionEvent (sealed interface)
  RoundStarted(input: String)
  TextDelta(delta: String, fullText: String)
  ToolRunning(callId, toolName, kind)
  ToolSucceeded(callId, toolName, kind, resultJson)
  ToolFailed(callId, toolName, kind, message, resultJson?)
  RoundCompleted(fullText: String)
  Error(stage: Stage, message: String, cause: Throwable?)
    Stage enum: Transport, Parse, Tool, Session
```

### 工具系统

```
ToolCallRequest (sealed interface)
  id, name, argumentsJson, kind
  suspend fun delegate(): Message.Tool
  fun ok(contentJson: String): Message.Tool
  fun error(message: String, contentJson: String = ...): Message.Tool

ToolCallKind (sealed interface)
  Local (data object)
  Mcp(serverName: String) (data class)

LocalToolRegistry (interface)
  add(name, block), replace(name, block), remove(name)

LocalToolConfig (data class)
  description, rawInputSchemaJson?
  string/integer/number/boolean/object_/array DSL property helpers

LocalToolProperty (data class)
  name, type: ToolValueType, description, required, enumValues

ToolValueType (enum)
  String, Integer, Number, Boolean, Object, Array
```

### MCP 占位

```
McpRegistry (interface)
  add, replace, remove

McpServerConfig (data class)
  enabled, transport, headers

McpTransport (sealed interface)
  Http(url: String)
```

## 内部映射

### SessionImpl 持有 ChatSession

```
Session.open { config }
  → SessionConfig → ConfigBuilder.build() → Config
  → ChatSession(baseUrl, apiKey, modelName, prompt, tools)

send(text, onEvent)
  → chatSession.sendMessage(text)
  → 内部 ChatEvent 流映射为 SessionEvent 回调:
      ChatEvent.Start           → RoundStarted
      ChatEvent.AI(Text)        → TextDelta (累积 fullText)
      ChatEvent.ToolCallIntent  → ToolRunning → hooks{} → ToolSucceeded/ToolFailed
      ChatEvent.Complete        → RoundCompleted
      ChatEvent.Error           → Error (映射 stage)

resetConversation() → chatSession.reset()
close() → scope.cancel()
```

### hooks 桥接

hooks 块直接替代旧 `Callback.onToolCall`：
- ToolCallIntent 到达 → 构造 `ToolCallRequest` 实现 → 调用 hooks block
- hooks 返回 `Message.Tool` → 回填到 HistoryKeeper + ToolCallWaiter
- `delegate()` 内部走 `ToolManager.exec()` 路径

### localTools DSL → ToolDefinition

DSL 属性声明 → 内部生成 `FunctionParameters` + `PropertyDefinition` → 包装为 `ToolDefinition`，与旧 `ToolModel.toolDefinition` 产出同类型。

## 现有文件改动

| 文件 | 动作 |
|---|---|
| `ChatSession.kt` | 重写为 `SessionImpl.kt`，实现 Session 接口 |
| `ConfigBuilder.kt` | 删除，由 `SessionConfig.kt` 取代 |
| `Config.kt` | 保留 internal，增 `SessionConfig → Config` 映射 |
| `ChatBeans.kt` | 保留 internal |
| `SessionEvent.kt` | 新增 |
| `ToolCallRequest.kt` | 新增 |
| `ToolCallKind.kt` | 新增 |
| `LocalToolRegistry.kt` / `LocalToolConfig.kt` | 新增 |
| `McpRegistry.kt` / `McpServerConfig.kt` / `McpTransport.kt` | 新增（占位） |
| `ToolModel.kt` / `ToolManager.kt` | 保留 internal，供 delegate() 使用 |
| `:app` DemoChatViewModel.kt | 重写，使用新 Session API |

## 不做的

- MCP 真实实现（仅占位 DSL）
- `update{}` 方法
- `ToolModel` 删除（保留为内部实现）
- SSE/OkHttp 管道改动
- `preConnect()` 暴露

## 测试

每个公开类型配一个 `*Test.kt`，含 `fun main()`，通过构造 → 调用 → println 烟测验证。

## 后续重构路径

1. 当前：上层 API 适配 + 底层不变
2. 下一步：底层重构（ChatClient/ChatSession 内部逻辑直接融入 SessionImpl）
3. 最后：实现 `update{}`、MCP 真实客户端
