## Context

Phase 1 引入 `SessionImpl` 作为 `ChatSession` 的适配器实现 `Session` 接口，同时通过 `ChatSession.Callback` 桥接内部 `ChatEvent` 到公开 `SessionEvent`。当前架构有两层事件跳转（ChatEvent → Callback → SessionEvent），且 `SessionImpl.kt` (~187 行) 的大部分逻辑（textAccumulator、hooks 调度、事件发射）本就属于会话核心。

Phase 2 将这些逻辑内联到 `ChatSession`，消除中间层，让 `ChatSession` 直接实现 `Session`。

当前架构:
```
Session.open{} → SessionImpl → ChatSession(Callback)
                   ↓ callback
              ChatEvent → SessionEvent 映射
```

目标架构:
```
Session.open{} → ChatSession implements Session
                   ↓ 内部处理
              ChatEvent → 更新 HistoryKeeper + 直接发射 SessionEvent
```

## Goals / Non-Goals

**Goals:**
- ChatSession 直接实现 Session 接口，删除 SessionImpl.kt
- 移除 ChatSession.Callback 接口，回调逻辑内联
- TextDelta.fullText 在 send() 完整生命周期（含 tool-call 递归）中持续累积
- SseToChatTransformLayer 保持产出 ChatEvent（内部类型不变）
- 公开 API 行为不变

**Non-Goals:**
- MCP 真实实现（仍占位）
- update{} 方法暴露
- SSE/OkHttp 管道改动
- ToolManager/ToolModel 重构
- ChatEvent 类型删除（保留为内部类型，供 SseToChatTransformLayer 和 ChatClient 使用）

## Decisions

### Decision 1: ChatSession 直接实现 Session，不重命名

**选择**: `ChatSession` 改为 `class ChatSession : Session`，保留类名不变。

**原因**: ChatSession 是内部类型（internal），对外不可见。重命名会增加 diff 噪音，且当前无技术债迫使改名。后续可直接将 ChatSession 改名或内联到 SessionImpl。

**替代方案**: 删除 ChatSession，将全部逻辑迁入 SessionImpl。拒绝原因：ChatSession 有 193 行，包含 HistoryKeeper + ToolCallWaiter 协调 + sendMessage 递归逻辑，强行拆分反而降低可读性。

### Decision 2: 保留 ChatEvent 为内部类型

**选择**: SseToChatTransformLayer 继续产出 ChatEvent，ChatSession 内部 consume ChatEvent 后直接发射 SessionEvent。

**原因**: ChatEvent (ToolCallIntent) 携带原始 ToolCall 对象供 HistoryKeeper 和 ToolCallWaiter 使用，SessionEvent (ToolRunning) 只有 callId/toolName/kind。改成 SessionEvent 会导致 ToolCall 数据丢失。保留 ChatEvent 最小化改动范围。

**替代方案**: SseToChatTransformLayer 直接产出 SessionEvent + 侧通道传递 ToolCall。过于复杂，收益不大。

### Decision 3: textAccumulator 清空时机

**选择**: 在 `send()` 入口处清空 `textAccumulator`，而非在每轮 `onStarted()` 时清空。

**原因**: tool-call 递归调用 `sendMessage(null)` 会触发 `onStarted()`，但不应重置全文本累计器。只在用户调用 `send(text)` 时重置是正确语义。

### Decision 4: Companion.open 指向 ChatSession

**选择**: `Session.open{}` 直接构造 ChatSession，删除 SessionImpl 引用。

```
companion object {
    fun open(block: SessionConfig.() -> Unit): Session {
        val config = SessionConfig().apply(block)
        return ChatSession(config)
    }
}
```

## Risks / Trade-offs

- **ChatSession 行数膨胀**: 内联后 ChatSession 从 ~193 行增至 ~350 行。→ 可接受，逻辑内聚在一个文件内反而更易理解。
- **测试破坏**: 现有 SessionImplTest 引用了 SessionImpl 的集成行为。→ 更新测试指向 ChatSession。
- **ToolCallWaiter 依赖 Callback**: 当前 ToolCallWaiter 通过 `callback?.onToolCall()` 获取工具结果。→ 改为直接调用 ChatSession 的内部方法或 lambda。

## Open Questions

<!-- 无 — 设计已足够明确 -->
