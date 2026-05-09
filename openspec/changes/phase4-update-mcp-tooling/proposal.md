## Why

最新代码已经完成大部分 Phase3 重构，但仍有几个会阻碍 PRD 完整落地的真实缺口：

1. **`update{}` 只有入口，不是完整动态语义**：`ChatSession.update()` 已用 `AtomicReference<SessionConfig>` 更新配置，`send()` 也会取 snapshot；但 `jsonCodec`、`httpEngine`、`protocol.withCodec()`、`localToolRegistry.codec` 等基础设施绑定发生在 `open` / 构造阶段，update 后不一致。
2. **`rawJsonSchema()` 是死配置**：`LocalToolConfig.rawInputSchemaJson` 被保存，但 `toToolDefinition()` 完全忽略它，raw schema 没有参与 tool schema 输出，也没有走 `JsonCodec` 校验。
3. **MCP 只有 DSL 壳子**：`mcp { add(...) }` 能写配置，但 `SessionConfig.buildToolDefinitions()` 只返回 local tools；`ChatSession.buildToolCallRequest()` 永远创建 `LocalToolCallRequest`，所以模型看不到 MCP tools，也不可能正确分流执行。
4. **`OkHttpEngine.close()` 生命周期不完整**：Flow 取消时会 `call.cancel()`，但 `engine.close()` 本身不记录/取消 active calls，只 shutdown dispatcher 和 evict connection pool。
5. **缺少新的手工烟测入口**：用户确认旧 smoketest 已删除，新的验证方式应改成 `main1/main2/main3` 分别覆盖 update、tool/raw schema、MCP/engine，再由 `main()` 统一调用，最终由 `DemoActivity` 调用 `main()`。

用户新决策：`xLog` 默认 tag 使用 `"X"` 是有意决策，不再作为问题处理。

## What Changes

- 明确 `update{}` 支持边界：动态字段支持 update；open-only 基础设施字段不再假装支持动态 update。
- 引入 round 级 `SessionSnapshot` / `ToolCatalog`，每次 send 开始冻结完整配置与 tool 信息。
- 修复 `rawJsonSchema(json)`：raw schema 优先生效，必须通过 `JsonCodec.decodeMap()` 校验，再参与 OpenAI tools 输出。
- 将 local tools 与 MCP tools 合并为中性的 `ToolDescriptor` 列表，由协议层编码。
- MCP 接入最小闭环：MCP registry 输出 tool descriptors；tool call 根据 descriptor kind 分流到 Local / MCP；MCP delegate 调用内部 `McpClient`。
- 修复 `OkHttpEngine.close()`：记录 active calls，close 时 cancel 所有 in-flight calls。
- 新增手工测试入口：`main1()` / `main2()` / `main3()` / `main()`，全部使用 `Log.e` 打印；`DemoActivity` 调用统一 `main()`。

## Capabilities

### New Capabilities

- `update-snapshot-contract`: update 字段边界、round snapshot 语义、open-only 字段处理。
- `tool-catalog-and-raw-schema`: local/MCP tool 统一 descriptor，raw JSON schema 生效并走 JsonCodec。
- `mcp-dispatch-and-engine-lifecycle`: MCP 分流/最小 client/engine close 取消 active calls。
- `manual-smoke-entrypoints`: `main1/main2/main3/main` + DemoActivity 调用，使用 `Log.e` 打印。

### Modified Capabilities

- `session-config`: 区分 dynamic update 字段与 open-only 字段。
- `protocol-abstraction`: `ChatProtocol.buildRequest()` 从 snapshot/tool catalog 获取 tools，而不是直接读取 local registry。
- `http-engine`: close 契约补齐。

## Impact

- 修改：`SessionConfig.kt`、`ChatSession.kt`、`LocalToolRegistry.kt`、`McpTypes.kt`、`ToolCallRequest.kt`
- 修改：`OpenAIProtocol.kt`、`OpenAIModels.kt`
- 修改：`OkHttpEngine.kt`
- 新增：`SessionSnapshot.kt` / `ToolDescriptor.kt` / `ToolCatalog.kt`（具体文件名实现时可合并）
- 新增：`McpClient.kt` / `HttpMcpClient.kt`（最小 MVP）
- 新增：手工测试入口文件，例如 `s3ss10n/src/main/java/com/niki914/s3ss10n/smoketest/Phase4Smoke.kt`
- 修改：`DemoActivity.kt` 调用 `com.niki914.s3ss10n.smoketest.main()`

## Non-Goals

- 不改 `xLog` 默认 tag：`"X"` 是用户决策。
- 不恢复旧 smoketest 文件；本 change 新增 phase4 专用手工测试入口。
- 不做完整 MCP capability discovery / tools/list 自动发现；MVP 使用用户在 DSL 中声明的 tool schema。
- 不让 `protocol` 在 update 中动态切换。
- 不让 `jsonCodec` / `httpEngine` 在 update 中动态替换；这些应在 `open` 时绑定。
- 不自动编译、运行程序；测试入口由 DemoActivity 手工触发。
