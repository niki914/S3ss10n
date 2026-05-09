## Context

PRD §SessionHooks 明文："`hooks { ... }` 必须返回 `Message.Tool`；如果开发者不处理某个 tool call，应该显式调用 `delegate()`；`Handler` 这类业务封装属于开发者代码，框架不提供"。

这意味着框架对**本地工具**的执行不应有内置实现。当前的 `ToolModel` 抽象类 + `ToolManager` 注册表 + `ToolCallJsonTransformLayer`（一个把 `ToolCall` 包装成可写 JSON 响应的 builder）整套体系，在新 DX 下没有调用方——`LocalToolRegistry` 提供 schema，hooks 提供执行。删除这套老体系是清晰化的关键。

`delegate()` 的实际语义需要重定义：
- `Local` 来源：框架没有内置执行器 → 返回标准化 error tool message
- `Mcp(serverName)` 来源：T4 之后由 MCP client 实现真实执行；在此之前 placeholder

`ChatSession.handleToolCall` 当前用 `if ("error" in result.content.lowercase())` 判定结果——任何内容含 "error" 的合法 JSON 都会被错判为失败。修复方式：让 `ToolCallRequest.ok()` / `error()` 在创建 `Message.Tool` 的同时，把 outcome（Success/Failure）记到一个 internal `ToolCallOutcome` 字段里，ChatSession 读这个字段而不是 content 字符串。

## Goals / Non-Goals

**Goals:**
- 删除 `toolbase/` 下三个老 Tool 类
- `LocalToolRegistry` 成为本地工具 schema 的唯一来源
- `delegate()` 语义重定义为"无内置执行"
- `ToolCallRequest.appParams` 字段曝出
- hooks 显式 outcome 决定 ToolSucceeded vs ToolFailed
- `SessionEvent.Stage.Tool` 在合适场景正确发出

**Non-Goals:**
- MCP 真实实现
- 协议无关化（T4）
- 删除 ChatClient（T3）

## Decisions

### Decision 1: 删除整个 toolbase/ 目录的三个文件，不留任何"内置工具执行" hook

**选择**：物理删除 `ToolManager.kt`、`ToolModel.kt`、`ToolCallJsonTransformLayer.kt`。

**原因**：与 PRD 设计哲学一致；任何"留着以备未来扩展"的犹豫只会让后续重构再次付出删除成本。开发者扩展工具的口子已经由 `localTools { } + hooks { }` 提供。

### Decision 2: ToolCallOutcome 是 internal sealed，不污染公开 API

**选择**：

```kotlin
internal sealed interface ToolCallOutcome {
    val message: Message.Tool
    data class Success(override val message: Message.Tool, val resultJson: String) : ToolCallOutcome
    data class Failure(override val message: Message.Tool, val errorMessage: String, val resultJson: String?) : ToolCallOutcome
}
```

`ToolCallRequest.ok()` 和 `error()` 的返回类型仍是 `Message.Tool`（满足 PRD），但它们内部会把 outcome 记录到 `LocalToolCallRequest` / `McpToolCallRequest` 实例的 `internal var lastOutcome: ToolCallOutcome?` 字段上。`ChatSession.handleToolCall` 调用完 hooks 后读这个字段决定事件类型。

**原因**：
- 公开 API 不变（PRD §SessionHooks 要求 `ok()` / `error()` 返回 `Message.Tool`）
- 框架内部不依赖字符串猜测
- 一个 ToolCallRequest 实例对应一次 tool 调用，状态字段不存在并发问题

**替代方案**：让 `Message.Tool` 自身携带 outcome flag。拒绝原因：`Message.Tool` 是协议层数据类，跨网络传输时 OpenAI/Anthropic 都没这个字段，会污染 wire format。

### Decision 3: delegate() 对 Local 返回固定 error，不做任何 fallback

**选择**：

```kotlin
override suspend fun delegate(): Message.Tool {
    return error(
        message = "Local tool '$name' has no built-in implementation. Handle it in hooks { ... }.",
    )
}
```

`error()` 同时把 `lastOutcome` 设为 `Failure`。

**原因**：行为可预测；hooks 没处理就是没处理，让事件流明确告诉调用者"你漏配了"。

### Decision 4: ToolCallRequest.appParams 是 val，在构造时注入

**选择**：

```kotlin
sealed interface ToolCallRequest {
    val id: String
    val name: String
    val argumentsJson: String
    val kind: ToolCallKind
    val appParams: Map<String, Any?>   // 新增
    suspend fun delegate(): Message.Tool
    fun ok(contentJson: String): Message.Tool
    fun error(message: String, contentJson: String = """{"success":false}"""): Message.Tool
}
```

构造时由 ChatSession 从 round-scoped snapshot 读 `config.appParamsSnapshot()` 注入。

**原因**：immutable，符合 hooks 一次性消费的语义；map 已是 snapshot 的副本，hooks 不会意外修改。

### Decision 5: 无 hooks 配置时 ChatSession 直接发 ToolFailed + Stage.Tool

**选择**：当前实现已经是这样（[ChatSession.kt:292-307](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt#L292-L307)），保留逻辑但把 stage 修正为 `Tool` 而非默认 `Session`。

**原因**：语义对齐 PRD `SessionEvent.Stage` 枚举的真实含义。

### Decision 6: hooks 抛异常时被 ChatSession 捕获并发 ToolFailed + Stage.Tool

**选择**：

```kotlin
val outcome = xTry("hooks-execution") { request.hooks() }
if (outcome == null) {
    emit ToolFailed(stage=Tool, message="hooks threw exception")
    return error fallback Message.Tool
}
```

注意：`xTry` 是 T7 落地的工具；本任务中可先用 `try { ... } catch { ... }`，T7 替换为 `xTry`。这是华容道允许的"实现细节后续替换"。

## Risks / Trade-offs

- **删除老 ToolModel 体系会让任何下游隐式依赖（demo 之外）的代码编译失败**：当前 demo 不用 ToolModel，影响为 0。如果有外部 use case 用了，必须迁到 hooks 模型，这是 PRD 期望的。
- **delegate() 对 Local 永远 error**：开发者忘记写 hooks 时会一直失败，事件流明确，可接受。
- **ToolCallOutcome 字段化的并发**：每个 ToolCallRequest 实例只服务一次 tool call（由 ToolCallWaiter 一对一构造），无并发。

## Open Questions

<!-- 无 -->
