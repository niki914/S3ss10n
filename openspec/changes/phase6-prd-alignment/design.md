## Context

当前代码已经具备 Session snapshot、tool catalog、local/MCP routing、MCP discovery 和 HTTP `tools/call` 能力。剩余问题不是简单缺少函数，而是公开 API 契约和 MCP 协议兼容层没有完全收口。

本 change 处理三个核心方向：

- hook 结果类型从字符串迁移到 `Message.Tool`。
- MCP HTTP client 补齐 initialize lifecycle。
- MCP result 解码从原始响应体升级为正规化结果。

## Goals / Non-Goals

**Goals:**
- 让开发者在 hooks 中返回 `Message.Tool`，符合 PRD 的强约束。
- 让 `delegate()`、`ok()`、`error()` 都返回同一结果类型，避免 hook 内混用 String 和 tool message。
- 让 MCP server 在 discovery/call 前完成 initialize 和 initialized 通知。
- 让 MCP result 解码兼容 `structuredContent`、`content[]`、`content[].text` 内嵌 JSON。
- 保持 `ToolCallKind` 对 local/MCP 的来源分流能力。
- 保持 server-specific 兼容在 MCP adapter 内部，不污染 Session 主 API。

**Non-Goals:**
- 不把 MCP transport 细节暴露给模型或 hooks。
- 不支持 stdio、SSE、Streamable HTTP。
- 不让 discovery 阻塞 `send()`。
- 不重构整个 protocol abstraction。
- 不自动执行编译或启动 Demo。

## Decisions

### Decision 1: 新增公开 Message 类型

**选择**：新增公开 `Message` sealed interface，优先稳定 `Message.Tool`，并让工具结果链路统一使用它。

**原因**：
- PRD 已将 `Message` 列为开发者公开类型。
- hooks 返回 `Message.Tool` 后，框架能明确知道 tool result 的 callId、toolName、content，而不是靠字符串和 side effect 推断成功失败。
- 未来如需公开 user/assistant/system message，也能在同一类型边界内扩展。

### Decision 2: ToolCallRequest helper 返回 Message.Tool

**选择**：`delegate()`、`ok()`、`error()` 返回 `Message.Tool`，并在内部保留 outcome 状态用于事件发射。

**原因**：
- PRD 要求开发者不处理时显式 `delegate()`。
- `ok()` / `error()` 是开发者最常用路径，返回类型必须和 hooks 一致。
- `ToolSucceeded` / `ToolFailed` 仍需要区分，内部 outcome 可以继续存在，但不作为公开 API。

### Decision 3: ChatTurn 与协议层做最小桥接

**选择**：历史仍可继续用 `ChatTurn` 表示，但 tool result turn 应从 `Message.Tool` 转换而来。

**原因**：
- 避免一次性替换全部历史模型，降低变更范围。
- OpenAI 编码逻辑继续集中在 `OpenAIProtocol.kt`。
- 若后续要把 `ChatTurn` 替换为公开 `Message`，可作为单独 change。

### Decision 4: MCP lifecycle cache 位于 ChatSession/McpClient 边界

**选择**：为 HTTP MCP server 增加按 server fingerprint 管理的初始化状态。`tools/list` 和 `tools/call` 前必须确保初始化完成。

**原因**：
- initialize 状态跟 server endpoint/header 绑定，不应放入全局单例。
- server config update 后，旧初始化状态不能复用到新 URL/header。
- lifecycle 是 MCP client concern，不应进入 `SessionConfig` 或 hooks。

### Decision 5: initialize 失败不伪造工具

**选择**：
- discovery 路径 initialize 失败时记录日志，并保持既有 discovery failure 策略。
- call 路径 initialize 失败时返回 tool error，通过 `Message.Tool` 回填模型。

**原因**：
- discovery 已是异步非阻塞，不应导致 `send()` 失败。
- tool call 已经发生时必须给模型一个可消费结果，避免 round 卡死。

### Decision 6: MCP result normalization 独立成内部适配层

**选择**：在 MCP client 附近新增 result normalizer，输出字符串 JSON 给 `Message.Tool.contentJson` 或等价字段。

**解析优先级**：
- 优先 `result.structuredContent`。
- 其次 `result.content[]`。
- 最后兼容 `content[].type == text` 且 `text` 内部是 JSON 字符串。
- 如果 `isError == true`，结果应标记为失败 outcome，并保留可回填模型的 JSON。

**原因**：
- `as-locate-plugin-personal` 的 text 内嵌 JSON 是 server-specific 兼容。
- `miot-mcp` 的 structuredContent 更接近标准形态。
- 上层 Session API 不应感知这些差异。

### Decision 7: Session.open API 先给出兼容策略

**选择**：本 change 先明确两种可选策略，实施前必须选一：

- 策略 A：保留 protocol-first API，同时新增默认 OpenAI `Session.open {}` 便利入口。
- 策略 B：将 PRD 改写为 protocol-first API，承认当前实现是有意设计。

**建议**：优先策略 A。它能兼容当前实现，并让 PRD 示例可用。

## Migration Plan

1. 新增 `Message` 类型，并只在 tool result 链路接入。
2. 修改 `SessionConfig.hooks`、`SessionSnapshot.hooksBlock`、`ToolCallRequest` 返回类型。
3. 修改 `ChatSession.handleToolCall()` 和 `responseToolCalls()`，用 `Message.Tool` 回填历史。
4. 修改 `OpenAIProtocol.encodeToolResult()` 或相邻桥接逻辑，确保协议输出不变。
5. 给 MCP client 增加 initialize/initialized lifecycle。
6. 给 MCP client 增加 result normalizer，并让 `McpToolCallRequest.delegate()` 使用正规化结果。
7. 更新 Demo hook 写法和 smoke 覆盖。

## Risks / Trade-offs

- 这是公开 API 变更，Demo 和所有 hook 调用点都要同步。
- 如果 `Message.Tool` 字段设计过窄，后续协议扩展会受限；但字段过宽又会污染 DX，需要只保留 callId、toolName、contentJson、error metadata 等必要信息。
- initialize 是否每次 discovery 都发送会影响远端 server 兼容性；应按 fingerprint 只初始化一次，并允许失败后重试。
- MCP result 的 JSON 正规化若过度猜测，可能掩盖 server 错误；必须通过日志暴露 raw result 摘要和解析路径。
