## Why

`PRD.md` 的新版 Session DX 大部分已经落到代码里，但仍有几处关键偏差会影响对外 API 稳定性和 MCP 兼容性：

1. **hooks 返回类型没有对齐 PRD**：PRD 明确要求 `onToolCall` / `hooks` 必须返回 `Message.Tool`，但当前 `ToolCallRequest`、`SessionConfig.hooks`、`ChatSession` 都以 JSON 字符串作为工具结果传递。
2. **公开 `Message` 类型缺失**：PRD 的公开类型列表包含 `Message`，当前项目只有内部 `ChatTurn`，没有对开发者暴露稳定的 `Message.Tool`。
3. **MCP lifecycle 不完整**：当前 HTTP MCP client 直接调用 `tools/list` 和 `tools/call`，没有 `initialize`、`notifications/initialized`、协议版本和 capabilities 协商。
4. **MCP tool result 未正规化**：当前 `tools/call` 返回原始响应体，尚未按 PRD 要求优先解析 `structuredContent`，其次 `content[]`，最后兼容 `content[].text` 内嵌 JSON。
5. **Session API 形态存在取舍**：当前 `Session.open` 需要 protocol 类型参数，`SessionConfig` 是 class + Builder；PRD 示例展示的是无 protocol 参数的 `open {}` 和 data class 风格配置。

这些问题应作为一次明确的 API 对齐变更处理，避免在后续 MCP 或 Demo 修改中零散修补。

## What Changes

- 引入公开 `Message` 模型，至少稳定暴露工具结果所需的 `Message.Tool`。
- 将 `hooks`、`delegate()`、`ok()`、`error()` 的返回链路从字符串 JSON 调整为 `Message.Tool`。
- 保持协议层最终仍能把工具结果编码成 OpenAI tool message，但不把 OpenAI 细节暴露给 hook API。
- 扩展 HTTP MCP client lifecycle：按 server/fingerprint 做初始化状态缓存，先 initialize，再发送 initialized 通知，再允许 discovery/call。
- 新增 MCP result 正规化层，把不同 MCP server 返回形态统一成工具结果 JSON。
- 明确 `Session.open` 与 `SessionConfig` 的 PRD 对齐策略：要么补默认 OpenAI 入口和配置 data class 兼容层，要么在 PRD/CLAUDE 中记录当前 protocol-first API 是有意偏离。
- 更新 Demo 和 smoke，使它们覆盖 `Message.Tool` hook、MCP initialize、result 解码兼容。

## Capabilities

### New Capabilities

- `message-tool-hook-contract`: hooks 和 ToolCallRequest helper 返回稳定的 `Message.Tool`。
- `mcp-initialize-lifecycle`: HTTP MCP server 在 tools/list 或 tools/call 前完成 initialize + initialized 通知。
- `mcp-result-normalization`: MCP tool result 被统一解析成上层工具结果 JSON。

### Modified Capabilities

- `session-public-api`: 对齐或显式记录 `Session.open`、`SessionConfig` 与 PRD 的 API 形态差异。
- `tool-call-routing`: tool routing 继续由 `ToolCallKind` 分流，但结果类型从 String 迁移到 `Message.Tool`。
- `openai-tool-encoding`: OpenAI 协议从 `Message.Tool` 或等价内部结构编码 tool result。

## Impact

- 修改：`SessionConfig.kt`
- 修改：`ToolCallRequest.kt`
- 修改：`ChatSession.kt`
- 修改：`SessionSnapshot.kt`
- 修改：`ChatTurn.kt`
- 修改：`OpenAIProtocol.kt`
- 修改：`McpClient.kt`
- 修改：`McpTypes.kt`
- 修改：`DemoChatViewModel.kt`
- 可能新增：`Message.kt`
- 可能新增：`McpLifecycleCache.kt`
- 可能新增：`McpResultNormalizer.kt`
- 可能新增或扩展：`Phase4Smoke.kt` / `Phase6Smoke.kt`

## Non-Goals

- 不支持 stdio MCP transport。
- 不支持 MCP resources/prompts。
- 不改 `send()` 等待 discovery 的既有决策；discovery 仍不阻塞 round。
- 不引入新的 JSON 框架；继续通过 `JsonCodec` 收口 JSON 编解码。
- 不自动运行 Gradle、不启动 app；验证入口保持手工触发。
