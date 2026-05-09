## 1. 收敛 update 字段语义

- [x] 1.1 明确 `jsonCodec`、`httpEngine`、`protocol` 为 open-only 字段
- [x] 1.2 修改 `ChatSession.update()`：merge 新配置时忽略 open-only 字段变更
- [x] 1.3 当 update 尝试改 `jsonCodec` / `httpEngine` 时，用 `xLog("X", "update ignored open-only field: ...")` 打日志
- [x] 1.4 确认 dynamic 字段能在下一次 send 生效：endpoint/apiKey/model/systemPrompt/temperature/timeouts/hooks/localTools/mcp/appParams

## 2. 引入 SessionSnapshot

- [x] 2.1 新增 internal `SessionSnapshot` 数据结构
- [x] 2.2 `send()` 开始时从 `configRef.get()` 构建 `SessionSnapshot`
- [x] 2.3 `RoundContext.configSnapshot: SessionConfig` 改为 `SessionSnapshot`
- [x] 2.4 `OpenAIProtocol.buildRequest()` 改为读取 `SessionSnapshot` 或等价不可变视图
- [x] 2.5 `ChatSession.handleToolCall()`、`buildToolCallRequest()`、`appParams` 全部读取同一个 snapshot

## 3. 修复 rawJsonSchema

- [x] 3.1 `LocalToolConfig.toToolDescriptor(codec, toolName)`：如果 `rawInputSchemaJson != null`，调用 `codec.decodeMap(rawInputSchemaJson)`
- [x] 3.2 raw schema decode 成功时直接作为 `ToolDescriptor.inputSchema`
- [x] 3.3 raw schema decode 失败时抛明确异常，不再静默忽略
- [x] 3.4 无 raw schema 时继续用 property DSL 构建 JSON schema
- [x] 3.5 required/enum/items 等字段若使用 raw schema，必须完整保留，不通过强类型 `PropertyDefinition` 丢字段

## 4. 引入 ToolDescriptor / ToolCatalog

- [x] 4.1 新增 internal `ToolDescriptor(name, description, inputSchema, kind)`
- [x] 4.2 新增 internal `ToolCatalog(descriptors)`，支持 `find(name)`
- [x] 4.3 `LocalToolRegistryImpl` 输出 `List<ToolDescriptor>`，kind = `ToolCallKind.Local`
- [x] 4.4 `SessionConfig.buildToolDefinitions()` 改为 `buildToolCatalog(codec)` 或等价方法
- [x] 4.5 删除 OpenAIProtocol 直接依赖 `localToolRegistry.toToolDefinitions()` 的路径

## 5. 扩展 MCP DSL

- [x] 5.1 新增 `McpToolConfig`，支持 `description`、`rawJsonSchema(json)`、property DSL
- [x] 5.2 `McpServerConfig` 增加 `tool(name, block)`
- [x] 5.3 `McpRegistryImpl` 输出 enabled servers 的 tool descriptors，kind = `ToolCallKind.Mcp(serverName)`
- [x] 5.4 `McpRegistryImpl.copyFrom()` 深拷贝 server/tool 配置
- [x] 5.5 disabled server 不输出任何 descriptor

## 6. 调整 OpenAI tools 编码

- [x] 6.1 `FunctionTool.parameters` 改为 `Map<String, Any?>` 或等价可保留任意 JSON schema 的结构
- [x] 6.2 `OpenAIProtocol.buildRequest()` 从 `snapshot.tools.descriptors` 编码 tools
- [x] 6.3 删除或降级 `FunctionParameters` / `PropertyDefinition` 强类型模型，避免 raw schema 字段丢失
- [x] 6.4 保留现有 local property DSL 输出：`type=object/properties/required`

## 7. MCP tool call 分流

- [x] 7.1 修改 `ChatSession.buildToolCallRequest()`：按 `snapshot.tools.find(toolCall.toolName)` 判断 kind
- [x] 7.2 local → `LocalToolCallRequest`
- [x] 7.3 mcp → `McpToolCallRequest`
- [x] 7.4 unknown → 返回明确失败请求或直接 emit `ToolFailed` + `SessionEvent.Error(Stage.Tool)`，禁止默认 local
- [x] 7.5 `ToolCallOutcome` 对 MCP success/failure 保持一致

## 8. MCP 最小客户端

- [x] 8.1 新增 internal `McpClient`
- [x] 8.2 新增 `HttpMcpClient` 支持 `McpTransport.Http(url)`
- [x] 8.3 为避免破坏 OkHttp 收口，给 `HttpEngine` 增加 `suspend fun unary(request: HttpRequest): HttpResponse`，或明确复用现有 engine 的非流式路径
- [x] 8.4 `OkHttpEngine` 实现 unary
- [x] 8.5 `McpToolCallRequest.delegate()` 调用 `McpClient.call(...)`
- [x] 8.6 unsupported transport 返回 `request.error("Unsupported MCP transport")`

## 9. 修复 OkHttpEngine.close

- [x] 9.1 `OkHttpEngine` 增加 thread-safe `activeCalls` 集合
- [x] 9.2 `stream()` newCall 后加入 activeCalls
- [x] 9.3 onFailure/onResponse/awaitClose 时移除 activeCalls
- [x] 9.4 `close()` 先复制并 cancel 所有 active calls
- [x] 9.5 cancel 后再 shutdown dispatcher / evict connectionPool
- [x] 9.6 如果新增 unary，也要纳入 activeCalls 管理

## 10. Phase4 手工测试入口

- [x] 10.1 新增 `s3ss10n/src/main/java/com/niki914/s3ss10n/smoketest/Phase4Smoke.kt`
- [x] 10.2 `main1()`：测试 update snapshot；用 fake engine 捕获 old/new endpoint；使用 `Log.e("X", ...)` 打印 PASS/FAIL
- [x] 10.3 `main2()`：测试 rawJsonSchema + ToolCatalog；确认 raw schema 字段保留；使用 `Log.e("X", ...)` 打印 PASS/FAIL
- [x] 10.4 `main3()`：测试 MCP descriptor 分流 + OkHttpEngine/ fake engine close active call 语义；使用 `Log.e("X", ...)` 打印 PASS/FAIL
- [x] 10.5 `main()` 依次调用 `main1()`、`main2()`、`main3()`
- [x] 10.6 所有 smoke 入口允许直接调用，不依赖 JUnit

## 11. DemoActivity 接入

- [x] 11.1 在 `DemoActivity.kt` debug 路径调用 `com.niki914.s3ss10n.smoketest.main()`
- [x] 11.2 避免重复调用：可用 `remember` / `LaunchedEffect(Unit)` / Activity `onCreate` 单次触发
- [x] 11.3 注释说明这是 phase4 手工 smoke 入口，非长期生产逻辑

## 12. 手工验证

- [ ] 12.1 用户运行 demo app
- [ ] 12.2 观察 Logcat tag `X`
- [ ] 12.3 确认 main1/main2/main3 均打印 PASS
- [ ] 12.4 确认真实 demo chat 流程仍正常

## 13. 禁止自动验证

- [x] 13.1 不自动运行 Gradle 编译
- [x] 13.2 不自动启动 app
- [x] 13.3 仅提供手工验证步骤
