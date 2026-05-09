## Why

当前 Tool 系统有**两套并存的注册体系**：
- 新 DSL：`SessionConfig.localTools { add("toast") { ... } }` → `LocalToolRegistry`，**只产出 schema**，没有可执行体
- 老抽象：`abstract class ToolModel` + `ToolManager` 注册表 + `ToolCallJsonTransformLayer`，**有可执行体**但 ChatSession 持有的 `toolManager` **从未被注册任何工具**

后果：[ToolCallRequest.kt](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallRequest.kt) 的 `LocalToolCallRequest.delegate()` 调用 `toolManager.exec(toolCall, ...)` 必然返回 `ToolNotFound`。这是隐藏的功能性 Bug。

PRD §SessionHooks 的设计哲学是：**框架不内置工具执行器**，开发者在 hooks 里通过 `ok()`/`error()` 自己处理。`delegate()` 的语义应该是"**框架没有内置实现**，本地工具默认返回 standardized error；MCP 走 MCP client（未实现）"。

同时 [ChatSession.kt:271](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt#L271) `if ("error" in result.content.lowercase())` 用字符串包含判定 hooks 返回的 Tool 消息成功/失败——这是典型的 band-aid，违反"显式优于猜测"。应该让 hooks 自己用 `ok()` 还是 `error()` 显式表态，由 `ToolCallRequest` 端记录结果状态。

`SessionEvent.Stage.Tool` / `Parse` 当前永远不会被发出，借本任务一并修正。

## What Changes

- **BREAKING**: 删除 `toolbase/ToolManager.kt` / `toolbase/ToolModel.kt` / `toolbase/ToolCallJsonTransformLayer.kt`（整个 `toolbase/` 目录可清空或仅留少量内部辅助）
- **BREAKING**: `ChatSession` 不再持有 `toolManager: ToolManager` 字段
- `LocalToolCallRequest.delegate()` 语义重定义：返回标准化的 `Message.Tool`（content = `{"error":"local tool requires hooks implementation","name":"<name>"}`），不再尝试执行
- `McpToolCallRequest.delegate()` 维持当前"返回 not implemented error"行为（T4 之后由 MCP 实现替换）
- 新增 `ToolCallRequest.appParams: Map<String, Any?>`（接 T1 的 SessionConfig.appParamsSnapshot()）
- `ToolCallRequest.ok()` / `error()` 返回的 `Message.Tool` 携带"用户显式 success/failure"标记（通过新增 internal sealed result 类型 `ToolCallOutcome` 在内部传递；公开 API 仍是 `Message.Tool`）
- `ChatSession.handleToolCall` 删除 `"error" in result.content.lowercase()` 判定，改为读 `ToolCallOutcome` 决定发 `ToolSucceeded` 还是 `ToolFailed`
- `SessionEvent.Stage.Tool` 在 hooks 抛异常 / 无 hooks 配置时正确发出
- `SessionEvent.Stage.Parse` 在 ToolCallHandler 解析失败时正确发出（T4 之前先在当前 SseToChatTransformLayer 用占位 stage 即可，这里不强求）

## Capabilities

### New Capabilities

- `tool-system-single-source`: LocalToolRegistry 是本地工具 schema 的唯一来源，不再有 ToolModel/ToolManager 旧体系
- `tool-call-explicit-outcome`: hooks 通过 ok()/error() 显式表态，框架不再用字符串包含判定成功失败
- `tool-call-app-params`: ToolCallRequest.appParams 字段曝出，hooks 可读取 SessionConfig.appParams DSL 中注入的对象

### Modified Capabilities

<!-- 无现有 spec 需要修改 -->

## Impact

- 删除：`s3ss10n/toolbase/ToolManager.kt`、`s3ss10n/toolbase/ToolModel.kt`、`s3ss10n/toolbase/ToolCallJsonTransformLayer.kt`
- 修改：`s3ss10n/ToolCallRequest.kt`（新增 appParams 字段；delegate 语义重定义；引入 ToolCallOutcome 内部跟踪）
- 修改：`s3ss10n/ChatSession.kt`（删除 toolManager 字段；handleToolCall 读 outcome 决定事件分支；Stage 正确发出）
- 烟测：`ToolCallRequestTest.kt` 增加 appParams 验证、显式 outcome 验证

## Non-Goals

- MCP 真实实现（仍 placeholder）
- ChatSession 完整内联到 Session（T3）
- 协议层重构（T4）
- 任何 JSON / HTTP 抽象（T5/T6）
