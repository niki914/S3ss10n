## 1. OpenSpec 文档

- [ ] 1.1 新增 Phase5 proposal/design/tasks/spec 文档
- [ ] 1.2 明确 discovery 必须缓存，且不得阻塞 `send()`
- [ ] 1.3 明确 discovery 完成后只影响后续 round，不影响 active round

## 2. Discovery 数据结构

- [ ] 2.1 新增 `McpDiscoveredTool(name, description, inputSchema)`
- [ ] 2.2 新增 `McpDiscoveryCache`，按 serverName + fingerprint 存储 discovered tools
- [ ] 2.3 cache 支持 `snapshot()` / `put()` / `markRefreshing()` / `markFinished()`
- [ ] 2.4 fingerprint 包含 serverName、enabled、transport、headers
- [ ] 2.5 discovery 失败不得清空上一份成功缓存

## 3. MCP Client Discovery

- [ ] 3.1 给 `McpClient` 增加 `suspend fun listTools(server: McpServerConfig): List<McpDiscoveredTool>`
- [ ] 3.2 `HttpMcpClient.listTools()` 发送 JSON-RPC `tools/list`
- [ ] 3.3 解析 `result.tools[].name`
- [ ] 3.4 解析 `result.tools[].description`
- [ ] 3.5 解析并保留 `result.tools[].inputSchema` 任意 JSON schema 字段
- [ ] 3.6 unsupported transport discovery 返回明确异常并打日志

## 4. ChatSession 调度

- [ ] 4.1 `ChatSession` 持有 session-scoped `McpDiscoveryCache`
- [ ] 4.2 `ChatSession` 持有 discovery coroutine job 管理，生命周期跟随 `scope`
- [ ] 4.3 open 后异步 schedule enabled MCP servers discovery
- [ ] 4.4 `update()` 完成 config swap 后异步 schedule changed/enabled MCP servers discovery
- [ ] 4.5 `send()` 可 opportunistic schedule discovery，但禁止 await discovery job
- [ ] 4.6 discovery job 完成前，当前 `send()` 使用旧 cache 或空 cache
- [ ] 4.7 discovery job 完成后，下一次 `send()` 的 snapshot 使用新 cache
- [ ] 4.8 fingerprint 不匹配时丢弃 stale discovery result

## 5. ToolCatalog 合并

- [ ] 5.1 `SessionConfig.buildToolCatalog()` 接收 discovery cache snapshot 或等价不可变视图
- [ ] 5.2 local tools 始终同步进入 catalog
- [ ] 5.3 explicit MCP tools 同步进入 catalog
- [ ] 5.4 discovered MCP tools 从 cache 进入 catalog
- [ ] 5.5 explicit MCP tool 与 discovered MCP tool 同名时 explicit 优先
- [ ] 5.6 disabled MCP server 不 schedule discovery，也不输出 cached tools
- [ ] 5.7 MCP server 配置变更后只使用 matching fingerprint 的 cache

## 6. 日志

- [ ] 6.1 server add/update 打印 `qwerqwer`：name/enabled/transport/explicit tools
- [ ] 6.2 discovery schedule/skip 打印 `qwerqwer`：server/fingerprint/reason
- [ ] 6.3 discovery success 打印 `qwerqwer`：server/discovered tool names
- [ ] 6.4 discovery failure 打印 `qwerqwer`：server/error，并保留旧 cache
- [ ] 6.5 catalog merge 打印 `qwerqwer`：local/explicit/discovered/final tools
- [ ] 6.6 OpenAI request 打印 `qwerqwer`：最终 tools names/kinds

## 7. Demo 接入

- [ ] 7.1 `DemoChatViewModel` 保留 `mcp { add("local_ide") { http { url = "http://127.0.0.1:51337/mcp" } } }`
- [ ] 7.2 删除 demo 中手写的 MCP `tool("search_file_names")` / `tool("search_file_contents")` schema
- [ ] 7.3 `ToolCallKind.Mcp` hook 继续调用 `delegate()`
- [ ] 7.4 保留 adb reverse 使用方式：`adb reverse tcp:51337 tcp:51337`

## 8. Smoke

- [ ] 8.1 新增或扩展 smoke，验证无 explicit MCP tools 时会异步发现 remote tools
- [ ] 8.2 smoke 首轮允许 tools 缺失，但必须打印 cache miss/schedule 日志
- [ ] 8.3 smoke 等待 discovery 完成后发起下一轮，确认 OpenAI request tools 包含 `search_file_names`
- [ ] 8.4 smoke 验证 discovery failure 不清空上一份成功 cache
- [ ] 8.5 smoke 验证 explicit tool override discovered tool

## 9. 手工验证

- [ ] 9.1 用户运行 `adb reverse tcp:51337 tcp:51337`
- [ ] 9.2 用户运行 demo app
- [ ] 9.3 观察 Logcat tag `qwerqwer`
- [ ] 9.4 确认 discovery success 打印 MCP tool names
- [ ] 9.5 确认后续 round 的 OpenAI request body 包含 discovered MCP tools
- [ ] 9.6 确认模型能发起 MCP tool call，并由 `delegate()` 调用 MCP server

## 10. 禁止自动验证

- [ ] 10.1 不自动运行 Gradle 编译
- [ ] 10.2 不自动启动 app
- [ ] 10.3 仅使用 IDE diagnostics 做静态检查
