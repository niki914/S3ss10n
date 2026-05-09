## 1. 新建 reasoning context 变更

- [ ] 1.1 新建 `openspec/changes/openai-compatible-reasoning-context/`
- [ ] 1.2 编写 `proposal.md`，明确这是 OpenAI-compatible 协议补齐，不是 DeepSeek 特判
- [ ] 1.3 编写 `design.md`，记录 `reasoning_content`、噪声日志、HTTP 错误透传三个决策
- [ ] 1.4 编写 `specs/protocol-abstraction/spec.md`，补充对中性历史模型与中性事件模型的修改

## 2. 扩展中性模型

- [ ] 2.1 修改 `s3ss10n/ChatTurn.kt`：`ChatTurn.Assistant` 新增 `reasoningContent: String? = null`
- [ ] 2.2 修改 `s3ss10n/protocol/ProtocolEvent.kt`：新增 `ReasoningDelta(text: String)`
- [ ] 2.3 检查 `getHistory()` 语义：返回结构不变，只是 assistant turn 携带更多可选信息

## 3. 扩展 OpenAI-compatible 协议模型

- [ ] 3.1 修改 `s3ss10n/protocol/openai/OpenAIModels.kt`：assistant message 增加可选 `reasoning_content`
- [ ] 3.2 修改 `s3ss10n/protocol/openai/OpenAIModels.kt`：stream delta 增加可选 `reasoning_content`
- [ ] 3.3 修改 `s3ss10n/protocol/openai/OpenAIProtocol.kt`：`parseStream()` 解析 reasoning 增量并发出 `ProtocolEvent.ReasoningDelta`
- [ ] 3.4 修改 `s3ss10n/protocol/openai/OpenAIProtocol.kt`：历史 assistant turn 编码时原样回传 `reasoning_content`

## 4. 扩展会话层累积逻辑

- [ ] 4.1 修改 `s3ss10n/ChatSession.kt`：为当前 round 增加 reasoning accumulator
- [ ] 4.2 assistant 历史入库时同时写入 `content`、`toolCalls`、`reasoningContent`
- [ ] 4.3 保持 `ChatSession` 不出现 provider 名称、endpoint 判断或 provider 特判

## 5. 去掉 tool 参数分片噪声日志

- [ ] 5.1 修改 `s3ss10n/protocol/openai/OpenAIProtocol.kt` 中 `ToolCallAccumulator`
- [ ] 5.2 增加轻量结构预判：明显不完整的半截 JSON 不调用 `codec.decodeMap(...)`
- [ ] 5.3 保留真正 JSON 解析错误的可见性，不改 `GsonJsonCodec` 的失败日志契约

## 6. 改善 HTTP 诊断

- [ ] 6.1 修改 `s3ss10n/net/OkHttpEngine.kt`：非 2xx 时读取响应体
- [ ] 6.2 异常消息包含状态码、response message 与 body 文本
- [ ] 6.3 空 body 场景仍返回稳定错误消息

## 7. 同步 cleanup 任务

- [ ] 7.1 修改 `openspec/changes/phase3-7-cleanup-zephyr-and-smoketest/tasks.md`
- [ ] 7.2 在 T7 的待办清单里补充：reasoning context 新增路径完成后，相关 `xTry/xLog` 收口仍需覆盖
- [ ] 7.3 在 T7 的待办清单里补充：tool 参数完整性判断应按新实现做 cleanup，不再以 `decodeMap` 噪声日志为既定行为

## 8. 手工验证清单

- [ ] 8.1 用 DeepSeek thinking 模式复现一次 tool call 场景，确认后续轮次不再 400
- [ ] 8.2 观察日志：半截 tool 参数分片不再打印 `GsonJsonCodec.decodeMap failed`
- [ ] 8.3 观察 4xx/5xx 场景，确认错误消息能直接看到服务端 body
- [ ] 8.4 回归普通 OpenAI-compatible provider，确认未携带 `reasoning_content` 的 provider 不受影响
