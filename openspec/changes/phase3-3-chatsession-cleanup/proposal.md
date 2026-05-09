## Why

T1/T2 完成后，`ChatSession` 的字段已经稳定（不再有 `toolManager`，不再有跨层 `Config` 拷贝），可以一次性整理它的内部结构问题。当前 [ChatSession.kt](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt) 有以下遗留问题：

1. **`ChatClient` 是无价值的中间层**：唯一职责是持有 `OkhttpClientManager` + `ChatService` 并在 `sendMessages()` 时拼 systemMessage。已没有公开 API 暴露 `ChatClient`，留着只增加间接性。
2. **per-send 状态字段化**：`userOnEvent` / `currentInput` / `textAccumulator` 是字段（[ChatSession.kt:55-57](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt#L55-L57)）。`send()` 入口立刻覆盖；mutex 只能保证 round 串行执行，但不能阻止后入 send 的 onEvent 被前一个尾部事件错误触发的窗口。
3. **多个无用构造函数**：`ChatSession()` 无参 + `ChatSession(baseUrl, apiKey, ...)` 多参形式都不再被使用，应只保留 `constructor(config: SessionConfig)`。
4. **PRD 外溢能力**：`Session.getHistory()` 不在 PRD 中（PRD §Session 没这个方法）；`ChatSession.preConnect()` 也不在公开 API。
5. **`ChatPair` 作为公开类型**：当前 `getHistory()` 返回 `List<ChatPair>`，且 `ChatPair` 是公开 class。按用户指示，T3 改为 `List<ChatTurn>`（中性 turn 类型），在 T4 才真正引入 `ChatTurn`；本任务先把 `ChatPair` 标记为内部，删除 `getHistory()` 直到 T4 再以中性签名重新加回。

## What Changes

- **BREAKING**: 删除 `ChatClient.kt`，内部逻辑内联到 `ChatSession`
- **BREAKING**: 删除 `Session.getHistory(): List<ChatPair>`（T4 中以 `getHistory(): List<ChatTurn>` 重新加回）
- **BREAKING**: 删除 `ChatSession.preConnect()` 公开方法
- **BREAKING**: `ChatSession` 仅保留单一构造函数 `constructor(config: SessionConfig)`
- per-send 状态去字段化：引入 `private class RoundContext(val configSnapshot: SessionConfig, val onEvent: (SessionEvent) -> Unit, val initialInput: String, val textAccumulator: StringBuilder)`，由 `send()` 创建并在内部协程间传递
- `ChatPair` 标记为 `internal`（不变更内部使用）
- `Session` 接口保留 `send` / `update` / `resetConversation` / `close` 四个方法（与 PRD 严格一致）

## Capabilities

### New Capabilities

- `chatsession-self-contained`: ChatSession 不再依赖 ChatClient，直接组合 OkhttpClientManager + ChatService + HistoryKeeper + ToolCallWaiter
- `chatsession-round-context`: per-send 状态封装为 round-scoped context，不再以字段形式持有

### Modified Capabilities

- `session-interface-refactor`: 移除 getHistory()；接口收敛到 PRD 四方法

## Impact

- 删除：`s3ss10n/ChatClient.kt`
- 修改：`s3ss10n/ChatSession.kt`（内联 ChatClient 逻辑；引入 RoundContext；删除 preConnect、多余构造函数）
- 修改：`s3ss10n/Session.kt`（删除 getHistory；保留 send/update/resetConversation/close）
- 修改：`s3ss10n/ChatPair.kt`（class → internal class，构造函数 internal）
- 修改：`app/DemoChatViewModel.kt`（不能再用 `Session.getHistory()`，改为通过 onEvent 自行累积 UI state；如果 demo 强需要历史，T4 之后再用 ChatTurn 拿）
- 修改：`s3ss10n/util/ToolCallWaiter.kt`（如其依赖 ChatClient，需要解耦；从初始读看是直接持有 lambda 不依赖 ChatClient，预计无改动）

## Non-Goals

- 协议抽象（T4）
- HTTP/JSON 抽象（T5/T6）
- ChatPair 的最终命运（T4 引入 ChatTurn 后再决定是否删除 ChatPair）
- xTry 替换 try/catch（T7）
