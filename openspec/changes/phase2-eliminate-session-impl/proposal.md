## Why

Phase 1 用适配器模式（SessionImpl 持有 ChatSession 并实现 Callback）完成了公开 API 的过渡，但引入了不必要的间接层——每个事件经过 ChatEvent → Callback → SessionEvent 两次跳转。Phase 2 消除这个薄适配层，将逻辑直接内联到 ChatSession，减少一个事件跳转和一个文件。同时修复 TextDelta.fullText 在 tool-call 触发的递归 send 中被清空的已知问题。

## What Changes

- ChatSession 直接实现 Session 接口，移除 ChatSession.Callback 接口
- Session.open{} 直接创建重构后的 ChatSession，删除 SessionImpl.kt
- SseToChatTransformLayer 的直接产出从 ChatEvent 改为 SessionEvent，消除中间映射
- **BREAKING**: ChatSession.Callback 接口被删除（已有 SessionEvent 替代）
- TextDelta.fullText 改为在一次 send() 的完整生命周期（含 tool-call 递归）中持续累积
- 添加 fullText 跨轮累积的烟测验证

## Capabilities

### New Capabilities

- `session-interface-refactor`: ChatSession 直接实现 Session 接口，内部事件流直接产出 SessionEvent，消除 Callback 中间层
- `fulltext-cross-round-accumulation`: TextDelta.fullText 在一次 send() 的所有递归轮次中持续累积，RoundCompleted.fullText 反映完整内容

### Modified Capabilities

<!-- 无现有 spec 需要修改 -->

## Impact

- 重构: `ChatSession.kt`（实现 Session，移除 Callback）
- 修改: `SseToChatTransformLayer.kt`（ChatEvent → SessionEvent）
- 修改: `Session.kt`（companion.open 指向新实现）
- 删除: `SessionImpl.kt`
- 修改: `ChatService.kt`、`ChatClient.kt`（简化）
- 烟测: 新增 fullText 跨轮累积场景测试
