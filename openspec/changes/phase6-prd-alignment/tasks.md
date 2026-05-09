## 1. OpenSpec 文档

- [x] 1.1 新增 Phase6 proposal/design/tasks/spec 文档
- [x] 1.2 明确 PRD 未对齐项：`Message.Tool`、MCP initialize、MCP result normalization、Session API 形态
- [x] 1.3 明确不自动编译、不启动 app

## 2. 公开 Message 契约

- [x] 2.1 新增公开 `Message` 类型，至少包含 `Tool`
- [x] 2.2 `Message.Tool` 字段覆盖 tool call id、tool name、result content JSON
- [x] 2.3 明确 error tool result 的表达方式，避免只靠异常或裸字符串
- [x] 2.4 保持 `Message.Tool` 不携带 MCP transport/server-specific 字段

## 3. hooks 返回类型迁移

- [x] 3.1 修改 `SessionConfig.hooks` block 返回类型为 `Message.Tool`
- [x] 3.2 修改 `SessionSnapshot.hooksBlock` 类型
- [x] 3.3 修改 `ToolCallRequest.delegate()` 返回 `Message.Tool`
- [x] 3.4 修改 `ToolCallRequest.ok()` 返回 `Message.Tool`
- [x] 3.5 修改 `ToolCallRequest.error()` 返回 `Message.Tool`
- [x] 3.6 保留内部 success/failure outcome，用于发射 `ToolSucceeded` / `ToolFailed`
- [x] 3.7 删除工具结果主链路中的裸 String 返回依赖

## 4. ChatSession 工具结果链路

- [x] 4.1 修改 `ChatSession.handleToolCall()` 消费 `Message.Tool`
- [x] 4.2 修改 `ChatSession.responseToolCalls()` 回填 tool result
- [x] 4.3 确认 unknown tool 仍发射 `ToolFailed` 和 `SessionEvent.Error(Stage.Tool)`
- [x] 4.4 确认 hook 抛异常时仍构造可回填模型的 `Message.Tool`
- [x] 4.5 确认 active round 继续使用创建时的 `SessionSnapshot`

## 5. 协议桥接

- [x] 5.1 调整 `ChatTurn.ToolResult` 或新增桥接函数接收 `Message.Tool`
- [x] 5.2 调整 `OpenAIProtocol.encodeToolResult()` 或等价路径
- [x] 5.3 确认 OpenAI request 中 tool message 的 role、tool_call_id、name、content 不退化
- [x] 5.4 保持 local tool 与 MCP tool 对 OpenAI 编码路径一致

## 6. MCP initialize lifecycle

- [x] 6.1 为 HTTP MCP server 增加 session-scoped initialize 状态缓存
- [x] 6.2 initialize 状态 key 包含 server name、transport、headers 等 fingerprint
- [x] 6.3 `tools/list` 前确保 initialize 完成
- [x] 6.4 `tools/call` 前确保 initialize 完成
- [x] 6.5 initialize 成功后发送 `notifications/initialized`
- [x] 6.6 initialize 失败时 discovery 只打日志并保持旧 cache 策略
- [x] 6.7 initialize 失败时 tool call 返回 error `Message.Tool`
- [x] 6.8 update MCP server 后不复用旧 fingerprint 的 initialize 状态

## 7. MCP result normalization

- [x] 7.1 新增内部 result normalizer，靠近 `McpClient.kt`
- [x] 7.2 优先解析 `result.structuredContent`
- [x] 7.3 其次解析 `result.content[]`
- [x] 7.4 兼容 `content[].type == text` 且 `text` 内部为 JSON 字符串
- [x] 7.5 处理 `isError == true` 并映射为 failure outcome
- [x] 7.6 正规化失败时返回明确 error JSON，避免 round 卡死
- [x] 7.7 用 `android.util.Log.d("qwerqwer", ...)` 记录解析路径和失败摘要

## 8. Session API 形态决策

- [x] 8.1 决策是否新增默认 OpenAI `Session.open {}` 入口
- [x] 8.2 如果新增默认入口，确保不破坏现有 protocol-first API
- [x] 8.3 决策 `SessionConfig` 是否保持 class + Builder，还是补 data class 兼容层
- [x] 8.4 将最终决策同步到 `PRD.md` 或 `CLAUDE.md`

## 9. Demo 与 smoke

- [x] 9.1 更新 `DemoChatViewModel` 的 hooks 返回 `Message.Tool`
- [ ] 9.2 新增或扩展 smoke 覆盖 local `ok()` / `error()` 返回 `Message.Tool`
- [ ] 9.3 新增或扩展 smoke 覆盖 MCP initialize + initialized 通知
- [ ] 9.4 新增或扩展 smoke 覆盖 structuredContent result
- [ ] 9.5 新增或扩展 smoke 覆盖 content text 内嵌 JSON result

## 10. 手工验证

- [ ] 10.1 用户运行 demo app
- [ ] 10.2 观察 Logcat tag `qwerqwer`
- [ ] 10.3 确认 MCP initialize 成功后才执行 tools/list 或 tools/call
- [ ] 10.4 确认 MCP result 被正规化后回填模型
- [ ] 10.5 确认 local tool hook 与 MCP delegate 都能完成 round

## 11. 禁止自动验证

- [x] 11.1 不自动运行 Gradle 编译
- [x] 11.2 不自动启动 app
- [x] 11.3 仅使用静态检查和 IDE diagnostics
