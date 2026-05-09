## Context

当前协议分层已经做到 `ChatSession` 不感知 OpenAI 字段名，但 reasoning 上下文仍然有一个空洞：

- `OpenAIProtocol` 没有解析 `delta.reasoning_content`
- `ProtocolEvent` 没有 reasoning 事件
- `ChatTurn.Assistant` 没有保存 reasoning 内容
- 下一轮请求无法把 assistant 的 reasoning 上下文带回协议层

这不是 DeepSeek 专属业务逻辑，而是 OpenAI-compatible 协议扩展字段未被承接。修复应落在协议层和中性历史模型之间，而不是在 `ChatSession` 中写 provider 特判。

## Goals / Non-Goals

**Goals:**
- 让 OpenAI-compatible 协议链路可以无损保存并回传 `reasoning_content`
- 保持 `ChatSession` 继续只依赖中性类型
- 去掉 tool 参数分片的误导性 JSON 日志
- 让 HTTP 非 2xx 异常消息可直接看到响应体

**Non-Goals:**
- 为 DeepSeek 单独分叉一套协议实现
- 让 UI 或 `SessionEvent` 必须暴露 reasoning 内容
- 为所有 provider 强制要求 `reasoning_content`

## Decisions

### Decision 1: `reasoning_content` 进入中性历史模型

**选择**：

在 `ChatTurn.Assistant` 增加：

```kotlin
val reasoningContent: String? = null
```

**原因**：
- 该字段描述的是 assistant turn 的附加语义载荷，不是 transport 细节
- 保留在中性历史模型中，协议可以按需回传，其他 provider 可以忽略
- 比保存 provider 原始 JSON 更干净

**替代方案**：
- 仅在 `OpenAIProtocol` 内临时缓存：被否，跨轮历史无法保真
- 在 `ChatSession` 保存 provider 原始 message JSON：被否，污染中性层

### Decision 2: 协议事件新增 reasoning 增量

**选择**：

在 `ProtocolEvent` 增加：

```kotlin
data class ReasoningDelta(val text: String) : ProtocolEvent
```

`ChatSession` 负责累积该字段，但不强制转成 `SessionEvent` 对外发出。

**原因**：
- 保持会话层依然协议无关
- reasoning 与文本 delta 一样，本质上也是流式增量
- 当前需求只是保存和回传，不需要强行暴露给 UI

### Decision 3: OpenAIProtocol 以可选字段方式读写 reasoning

**选择**：

- `OpenAIModels` 的 assistant message / delta 增加可选 `reasoning_content`
- `OpenAIProtocol.parseStream()` 解析 `delta.reasoning_content`
- `OpenAIProtocol.toOpenAIMessage()` 在 `reasoningContent != null` 时原样回传

**原因**：
- `reasoning_content` 属于 wire format 字段，应该停留在协议层
- 对不支持该字段的 provider，值保持 `null` 即可，不影响兼容性
- 这是 OpenAI-compatible 扩展的良性补齐，不是 DeepSeek 特判

### Decision 4: tool 参数完整性探测不再依赖异常控制流

**选择**：

在 `ToolCallAccumulator` 里先做轻量结构判断，只有形态上可能完整的对象 JSON 才调用 `codec.decodeMap(...)`。

**原因**：
- 当前半截分片触发 `decodeMap failed` 只是噪声，不是实际错误
- 保持 `GsonJsonCodec` 的失败日志契约不变，避免把问题错误地下沉到 codec
- 用无异常控制流做完整性探测，更符合该场景

**替代方案**：
- 直接删除 `GsonJsonCodec.decodeMap` 的日志：被否，会降低真实解析错误的可见性
- 保留现状：被否，排障信息噪声过大

### Decision 5: HTTP 4xx/5xx 需要透传响应体

**选择**：

`OkHttpEngine` 在 `response.isSuccessful == false` 时读取 body 文本，错误消息格式改为包含 `HTTP <code>`、message 和 body。

**原因**：
- 当前只有 `HTTP 400:`，诊断价值太低
- 这是 transport 层增强，不增加协议层耦合

## Risks / Trade-offs

- **中性模型字段增加**：`ChatTurn.Assistant` 会更丰富，但仍然保持协议中性，可接受
- **不同 provider 的宽松性不同**：有些 provider 会忽略 `reasoning_content`，有些会消费；可选字段设计能兼容
- **reasoning 不对外透出**：本任务优先保正确性，不处理 UI 展示需求；若后续要展示，再单独立 change

## Open Questions

- 是否需要后续再立一个 change，把 reasoning 通过 `SessionEvent` 暴露给 demo/UI？
- 是否要把“非 2xx 响应体必须透传”上升为 `http-engine-abstraction` 的正式 spec？当前任务先按实现要求处理，避免 scope 扩大
