## Why

当前 `:s3ss10n` 的 OpenAI-compatible 协议路径在普通多轮对话下工作正常，但在 DeepSeek thinking 模式触发 tool call 后会暴露一个结构性缺口：

1. assistant 历史消息没有保存 `reasoning_content`
2. 协议事件层没有承载 reasoning 增量的中性事件
3. 后续请求无法把该字段原样回传给服务端

这会导致 DeepSeek 在发生 tool call 的轮次后返回 `HTTP 400`。同时，tool 参数流式分片的完整性判断当前会对半截 JSON 做反序列化试探，产生日志噪声，掩盖真正的根因。

## What Changes

- 扩展中性历史模型：`ChatTurn.Assistant` 新增可选 `reasoningContent`
- 扩展中性协议事件：新增 `ProtocolEvent.ReasoningDelta`
- 扩展 OpenAI-compatible 协议模型：支持解析和回传 `reasoning_content`
- 调整 tool 参数完整性探测：避免半截 JSON 触发误导性日志
- 改进 HTTP 4xx/5xx 透传：错误信息包含响应体

## Capabilities

### Modified Capabilities

- `protocol-abstraction`: 中性事件与中性历史模型补齐 reasoning 上下文
- `http-engine-abstraction`: 非 2xx 响应的错误信息更可诊断

## Impact

- 修改：`s3ss10n/src/main/java/com/niki914/s3ss10n/ChatTurn.kt`
- 修改：`s3ss10n/src/main/java/com/niki914/s3ss10n/protocol/ProtocolEvent.kt`
- 修改：`s3ss10n/src/main/java/com/niki914/s3ss10n/protocol/openai/OpenAIModels.kt`
- 修改：`s3ss10n/src/main/java/com/niki914/s3ss10n/protocol/openai/OpenAIProtocol.kt`
- 修改：`s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt`
- 修改：`s3ss10n/src/main/java/com/niki914/s3ss10n/net/OkHttpEngine.kt`
- 同步：`openspec/changes/phase3-7-cleanup-zephyr-and-smoketest/tasks.md`

## Non-Goals

- 不在 `ChatSession` 中添加 DeepSeek 或 endpoint 字符串特判
- 不把 `reasoningContent` 暴露为 UI 必须消费的字段
- 不改动 tool hook 的业务语义
- 不在本任务引入新的日志框架、异常层级或 provider 专属分支
