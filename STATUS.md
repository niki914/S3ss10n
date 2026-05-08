# Phase 1 完成状态 & Phase 2 任务规划

## Phase 1 成果（已完成）

PRD 定义的公开 API 全部就位，内部通过 `SessionImpl` 适配层包装旧 `ChatSession`。

### 新增文件（s3ss10n 模块）

| 文件 | 职责 |
|---|---|
| `Session.kt` | 公开 interface：`send()`, `getHistory()`, `resetConversation()`, `close()`, `companion.open{}` |
| `SessionImpl.kt` | Session 实现，持有 ChatSession 并实现 ChatSession.Callback 做事件映射 |
| `SessionConfig.kt` | 公开配置类：`endpoint/apiKey/model/systemPrompt/temperature` + `hooks{}` / `localTools{}` / `mcp{}` DSL |
| `SessionEvent.kt` | 公开事件 sealed interface：RoundStarted/TextDelta/ToolRunning/ToolSucceeded/ToolFailed/RoundCompleted/Error(Stage) |
| `ToolCallRequest.kt` | hooks{} 中接收的工具调用对象：`ok()`/`error()`/`delegate()` |
| `ToolCallKind.kt` | 工具来源区分：Local / Mcp(serverName) |
| `LocalToolRegistry.kt` | localTools{} DSL：LocalToolConfig, LocalToolProperty, ToolValueType |
| `McpTypes.kt` | MCP 占位类型：McpRegistry, McpServerConfig, McpTransport（编译通过，无行为） |

### 修改的现有文件

| 文件 | 改动 |
|---|---|
| `ChatApiRequestBody.kt` | 增加 `temperature: Float?` 字段 |
| `Config.kt` | 增加 `temperature: Float?` 字段 |
| `ConfigBuilder.kt` | 增加 `temperature` 属性，贯通 build()/fromConfig() |
| `ChatClient.kt` | performStream() 传递 temperature 到 request body |

### Demo 应用改动

| 文件 | 改动 |
|---|---|
| `DemoChatViewModel.kt` | 重写：使用 Session.open{} + send() onEvent，ChatState.pairs 通过 getHistory() 刷新 |
| `DemoChatScreen.kt` | 配置字段重命名：baseUrl→endpoint, modelName→model, prompt→systemPrompt |
| `DemoToastModel.kt` | 已删除（工具改为 localTools DSL 内联定义） |

### 测试文件（s3ss10n/src/test/）

| 文件 | 覆盖范围 |
|---|---|
| `SessionEventTest.kt` | SessionEvent 基础类型（不含 ToolCallKind 引用） |
| `ToolCallKindTest.kt` | ToolCallKind + 与 SessionEvent 结合 |
| `ToolCallRequestTest.kt` | LocalToolCallRequest / McpToolCallRequest 的 ok/error 方法 |
| `LocalToolRegistryTest.kt` | DSL 注册、属性类型、ToolDefinition 生成、replace/remove |
| `McpTypesTest.kt` | McpTransport, McpServerConfig, McpRegistry 占位 |
| `SessionConfigTest.kt` | SessionConfig 默认值、属性赋值、三个 DSL 块 |
| `SessionImplTest.kt` | Session.open + send + resetConversation + close 烟测 |
| `IntegrationTest.kt` | 全生命周期集成烟测 |

### 已知问题

1. **TextDelta.fullText 跨轮不累计**：`SessionImpl.textAccumulator` 在每次 `onStarted()` 时清空。当 tool-call 触发递归 send 时，第二轮文本会丢失第一轮的累积。见 `SessionImpl.kt:75`。
2. **SessionImpl 是薄适配层**：ChatSession.Callback 仍在中间，每个事件要经过 ChatEvent → Callback → SessionEvent 两次跳转。
3. **update{} 未实现**：Session 接口无 update 方法，SessionConfig 构造后不可变。
4. **MCP 仅占位**：McpTypes.kt 编译通过但 `delegate()` 始终返回 "not implemented"。

---

## Phase 2 任务规划

### Task 2.1: 底层重构 — 消除 SessionImpl 适配层

**目标**：将 `SessionImpl` 的逻辑直接内联到 `ChatSession`，或重写 `ChatSession` 使其直接实现 `Session` 接口。

**具体步骤**：
1. 将 `ChatSession` 重命名/重构为直接 `implement Session`
2. `ChatSession.Callback` 接口移除，回调逻辑内联为 `send()` 的 onEvent 参数
3. `ChatEvent` → `SessionEvent` 映射直接在 ChatSession 内部完成（不再经过 Callback）
4. `SseToChatTransformLayer` 直接产出 `SessionEvent`（或新增映射层）
5. 删除 `SessionImpl.kt`，`Session.open {}` 直接创建重构后的 ChatSession

**涉及文件**：
- 重构：`ChatSession.kt`（→ 实现 Session）
- 修改：`SseToChatTransformLayer.kt`（可选：直接产出 SessionEvent）
- 删除：`SessionImpl.kt`
- 修改：`Session.kt`（companion.open 指向新实现）
- 可能修改：`ChatService.kt`, `ChatClient.kt`（简化）

### Task 2.2: fullText 跨轮累积修复

**目标**：`TextDelta.fullText` 和 `RoundCompleted.fullText` 在一次 `send()` 的完整对话轮次（含 tool-call 递归）中持续累积。

**当前行为**：
```
send("hello") → tool call → 递归 send(null)
  Round 1: TextDelta("He", "He"), TextDelta("llo", "Hello"), ToolRunning...
  Round 2: TextDelta("Ok", "Ok")  ← fullText 从 "Hello" 重置为 "Ok"
```

**期望行为**：
```
send("hello") → tool call → 递归 send(null)
  Round 1: TextDelta("He", "He"), TextDelta("llo", "Hello"), ToolRunning...
  Round 2: TextDelta("Ok", "HelloOk")  ← fullText 跨轮累积
  RoundCompleted("HelloOk")
```

**具体步骤**：
1. 在 `SessionImpl`（或 Phase 2.1 重构后的 ChatSession）中，将 `textAccumulator` 的清空时机从 `onStarted()` 移到 `send()` 入口
2. 递归 `sendMessage(null)` 时不触发清空
3. 添加烟测验证 tool-call 场景下的 fullText 累积

**涉及文件**：
- `SessionImpl.kt`（或重构后的 ChatSession.kt）
- 新增测试：tool-call fullText 累积场景
