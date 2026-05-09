## Context

T1 把配置层拍平到 SessionConfig。T2 删除老 Tool 体系。此时 `ChatSession` 内部还有几个历史包袱：
- 持有 `ChatClient`（一个只增加间接性的中间层）
- per-send 状态字段化（`userOnEvent` / `currentInput` / `textAccumulator`）
- 残留多个无用构造函数
- 暴露 PRD 外溢能力（`getHistory`、`preConnect`）

T3 一次性收敛 ChatSession 的内部结构，让它在进入 T4 协议抽象之前形态固定：唯一公开实现 `Session`、单一构造函数、round-scoped 状态、不依赖 ChatClient。

PRD §Session 严格要求只有 4 个方法：`send`、`update`、`resetConversation`、`close`。`getHistory` 当前为了 demo `ChatState.pairs` 加上去；用户已确认 T4 用 `List<ChatTurn>` 重新加回。本任务先删除，避免 T4 时签名再次破坏。

## Goals / Non-Goals

**Goals:**
- 删除 ChatClient
- ChatSession 唯一构造函数
- per-send 状态全部 round-scoped
- 删除 PRD 外溢能力（getHistory、preConnect）
- ChatPair 退化为 internal

**Non-Goals:**
- 协议抽象（T4）
- HTTP/JSON 抽象
- 引入 ChatTurn（T4）

## Decisions

### Decision 1: ChatClient 整体内联到 ChatSession

**选择**：删除 `ChatClient.kt`。`ChatSession` 直接持有 `OkhttpClientManager` 和 `ChatService`。`isConfigValid()`、`sendMessages(messages)` 的逻辑搬入 ChatSession 的 private 方法。

**原因**：
- ChatClient 没有公开 API 暴露
- 它的所有方法都只服务 ChatSession 一个调用方
- 内联后 ChatSession 行数会增加约 50 行，但消除一个文件 + 一个间接调用层是净收益
- T6 重构 HTTP 层时只需要改 ChatSession 一处

**替代方案**：让 ChatClient 演化为 T6 的 `HttpEngine` 适配器。拒绝原因：HttpEngine 是新接口，ChatClient 是历史包袱，混在一起会让 T6 难以下手。

### Decision 2: RoundContext 是 internal data class

**选择**：

```kotlin
private class RoundContext(
    val configSnapshot: SessionConfig,
    val onEvent: (SessionEvent) -> Unit,
    val initialInput: String,
    val textAccumulator: StringBuilder = StringBuilder(),
)
```

`send(text, onEvent)` 创建 RoundContext，传入 `runRound(ctx)`。所有内部方法（`emitEvent`、`handleToolCall`、`responseToolCalls`）的签名带 `ctx: RoundContext` 参数。

**原因**：
- 消除字段共享与 race window
- 同一 send 内的递归 round 共享同一 ctx（保证 fullText 跨轮累积，与 phase2 决策一致）
- ToolCallWaiter 当前持有 lambda 回调，可将 ctx 闭包进去

**替代方案**：用 ThreadLocal/CoroutineContext。拒绝原因：复杂度爆炸，没必要。

### Decision 3: ChatPair 标记为 internal，但不删除

**选择**：`class ChatPair` → `internal class ChatPair`；`HistoryKeeper` 内部继续用。

**原因**：
- T4 引入 `ChatTurn` 后才有合适的中性替代
- 本任务目标是收敛公开表面，不重构内部数据结构
- T4 完成后再考虑是否物理删除 ChatPair

### Decision 4: getHistory 暂时删除，T4 重新加回

**选择**：本任务删除 `Session.getHistory()`。`DemoChatViewModel` 改为通过 onEvent 累积 UI state（实际上 demo 已经是这样做的，getHistory 只是辅助刷新）。

**原因**：避免本任务返回 `List<ChatPair>`、T4 又改为 `List<ChatTurn>` 的二次破坏。一次到位。

**替代方案**：本任务保留 getHistory 但返回 `List<ChatPair>`，T4 替换签名。拒绝原因：T4 将动 PRD 之外的公开签名两次。

### Decision 5: ChatSession 单一构造函数

**选择**：

```kotlin
class ChatSession internal constructor(initialConfig: SessionConfig) : Session {
    // ...
}
```

删除：`ChatSession()` / `ChatSession(baseUrl, apiKey, modelName, prompt, tools)`。

**原因**：唯一调用方是 `Session.open{}`，其他都是死代码。

### Decision 6: preConnect 删除

**选择**：删除 `ChatSession.preConnect()` 与 `ChatService.preConnect()`。

**原因**：未在 PRD 暴露，未被 demo 使用。如果将来需要，应作为 PRD 增量讨论。

### Decision 7: chatMutex 保留，但收紧职责

**选择**：保留 `private val chatMutex = Mutex()`，仅用于 `send` 入口的"前一个 round 必须先 cleanUpCurrWork 才能开新 round"互斥。RoundContext 化之后不再保护 per-send 状态。

**原因**：当前 mutex 已是合理的；RoundContext 只是消除了"字段被新 send 立即覆盖"的问题，并发 send 的串行化仍然需要。

## Risks / Trade-offs

- **demo 失去 getHistory()**：UI 现在必须自己累积 ChatPair 一样的结构。T4 之后会有 `getHistory(): List<ChatTurn>` 重新提供。短暂期内 demo 可能丢失显示历史的能力——可接受，demo 不影响发布。
- **ChatSession 行数膨胀**：内联 ChatClient 后约 +50 行，达到 ~400 行。可接受，逻辑内聚。
- **ChatPair internal 化导致 ChatPair 测试访问问题**：当前 smoketest 在 `:app` 模块，跨模块访问 internal 受限。这是 T7 收尾问题；T3 完成后如果烟测因此爆掉，临时把测试访问的字段做 internal 可见性扩大或先把 ChatPair 留 public，T7 再收尾。

## Open Questions

<!-- 无 -->
