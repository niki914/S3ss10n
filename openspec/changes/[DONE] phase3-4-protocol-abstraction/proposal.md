## Why

PRD §7 已经写明 `session.send<SessionProtocols.OpenAI>("") {}` 这种泛型形态，目标是让同一套 `Session` 同时支持 OpenAI / Anthropic / Google 等多家对话协议，并允许开发者自带协议实现。但现有代码中：

1. **请求体硬编码 OpenAI 形态**：[ChatApiRequestBody.kt](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/chat/protocol/ChatApiRequestBody.kt) 直接绑死 OpenAI 字段（`messages` / `tools` / `tool_choice` 等）。
2. **响应解析硬编码 OpenAI 帧**：[SseToChatTransformLayer.kt](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/chat/SseToChatTransformLayer.kt) 直接读 `choices[0].delta.content` 等 OpenAI 字段。
3. **ToolCall 流式拼接硬编码 OpenAI 形态**：[ToolCallHandler.kt](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/util/ToolCallHandler.kt) 拼接的是 OpenAI 的 `tool_calls[i].function.arguments` 增量。
4. **历史模型硬编码 OpenAI 形态**：`Message.System/User/Assistant/Tool` 是 OpenAI 协议本身的术语，跨协议复用会让 Anthropic/Google 等协议被迫做适配。
5. **`Session.open` 没有协议泛型**：当前签名 `open { ... }: Session` 没有 `<P : ChatProtocol>`，PRD 形态无法表达。

按用户决策：
- 协议泛型绑定在 `Session.open<P>` 而不是 `send`（每个 session 一个协议，open 时确定）
- 暂时保留 Gson/OkHttp 依赖（T5/T6 再抽 JsonCodec/HttpEngine）
- 历史模型迁移到中性的 `ChatTurn`
- 不依赖 Zephyr，统一使用 X.kt（未实现）的 `xLog(tag, str)` 与 `xTry(name, block) -> T?`（落地在 T7）；本任务新加的代码先以 `try { ... } catch { android.util.Log.e(...) }` 临时过渡，并在 tasks 中登记，等 T7 替换

## What Changes

- **新增**：`s3ss10n/protocol/ChatProtocol.kt` 接口，定义协议三件事：构建请求体 / 解析流式响应 / 编码工具结果
- **新增**：`s3ss10n/protocol/OpenAIProtocol.kt` 内置实现（迁移当前所有 OpenAI 形态代码）
- **新增**：`s3ss10n/SessionProtocols.kt` 顶层入口（PRD 命名）暴露内置协议引用
- **新增**：`s3ss10n/ChatTurn.kt` 中性历史模型（sealed interface，覆盖 user / assistant / toolResult / system 四种 turn）
- **修改**：`Session.kt`：`fun open(...)` → `inline fun <reified P : ChatProtocol> open(builder: SessionConfig.Builder.() -> Unit): Session`；同时重新加回 `suspend fun getHistory(): List<ChatTurn>`
- **修改**：`ChatSession`：构造函数额外接收 `protocol: ChatProtocol`；删除直接构建 `ChatApiRequestBody` 的代码，改为调用 `protocol.buildRequestBody(snapshot, history)`；删除直接解析 SSE 帧的代码，改为调用 `protocol.parseStream(rawSseFlow)` 得到中性 `Flow<ProtocolEvent>`
- **修改**：`HistoryKeeper`：内部存储改用 `ChatTurn`（删除或下移 `ChatPair`，详见 design）
- **删除**（迁移到 OpenAIProtocol 内部）：`s3ss10n/chat/protocol/ChatApiRequestBody.kt`、`s3ss10n/chat/SseToChatTransformLayer.kt`、`s3ss10n/util/ToolCallHandler.kt` 中的 OpenAI 形态拼接逻辑

## Capabilities

### New Capabilities

- `protocol-abstraction`: ChatProtocol 接口 + OpenAIProtocol 默认实现 + Session.open 泛型绑定
- `chat-turn-history`: 中性历史模型 ChatTurn 取代 OpenAI 形态 Message

### Modified Capabilities

- `chatsession-self-contained`: ChatSession 通过 ChatProtocol 解耦协议细节
- `session-interface-refactor`: open 加泛型；getHistory 以 List<ChatTurn> 形态重新加回

## Impact

- 新增：`s3ss10n/protocol/ChatProtocol.kt`、`s3ss10n/protocol/OpenAIProtocol.kt`、`s3ss10n/protocol/ProtocolEvent.kt`、`s3ss10n/SessionProtocols.kt`、`s3ss10n/ChatTurn.kt`
- 修改：`s3ss10n/Session.kt`（open 泛型；getHistory 加回）
- 修改：`s3ss10n/ChatSession.kt`（注入 protocol；用 protocol.buildRequestBody / parseStream 替代硬编码）
- 修改：`s3ss10n/util/HistoryKeeper.kt`（内部数据结构换为 ChatTurn）
- 迁移并删除：`s3ss10n/chat/protocol/ChatApiRequestBody.kt`、`s3ss10n/chat/SseToChatTransformLayer.kt`、`s3ss10n/chat/protocol/Message.kt`、`s3ss10n/chat/protocol/ChatBeans.kt`（这些 OpenAI 形态实体下移到 OpenAIProtocol 的私有/internal 层）
- 修改：`s3ss10n/util/ToolCallHandler.kt`（拼接逻辑下沉到 OpenAIProtocol；如需保留也仅作为 OpenAI 协议私有 helper）
- 修改：`s3ss10n/ChatPair.kt`（T3 已 internal；本任务可考虑彻底删除，由 ChatTurn 承担）
- 修改：`app/DemoChatViewModel.kt`（如果之前去掉的历史显示能力要恢复，改用 `Session.getHistory(): List<ChatTurn>` + ChatTurn → UI 模型映射）

## Non-Goals

- JSON 序列化抽象（T5）：本任务 OpenAIProtocol 内部仍可直接用 Gson
- HTTP 引擎抽象（T6）：本任务 ChatService 仍走 OkHttp
- xTry/xLog 落地（T7）：本任务新代码沿用 try/catch + android.util.Log，留 TODO
- MCP 协议（PRD 中是独立 DSL，不属于 ChatProtocol 范畴）
- 第三方协议实现（Anthropic / Google）：本任务只抽接口 + 提供 OpenAIProtocol 一个内置实现；其他协议作为后续增量
