## Why

Phase4 closes the explicit MCP tool path, but `mcp { add("local_ide") { http { url = ... } } }` still exposes no tools to the model because the current catalog only reads explicitly declared `tool(...)` entries.

The real MCP server already supports `tools/list`. Requiring users to duplicate every remote schema in Kotlin is brittle, noisy, and easy to desynchronize from the server. However, discovery must not block `send()`: a chat round should use a stable cached tool catalog, while background discovery refreshes the cache for the next round.

## What Changes

- Add cached MCP tool discovery through `tools/list`.
- Start discovery asynchronously when MCP servers are configured or updated.
- Keep `send()` non-blocking with respect to discovery; it reads the latest completed cache only.
- Make newly discovered tools effective on subsequent `send()` calls, not the active round.
- Merge explicit MCP tools with discovered MCP tools, with explicit declarations taking precedence.
- Add visible logs for discovery start/success/failure/cache usage using `android.util.Log.d("qwerqwer", ...)`.
- Update the demo so `mcp { add("local_ide") { http { url = "http://127.0.0.1:51337/mcp" } } }` can expose MCP tools without manually declaring schemas.
- Extend smoke coverage for cached asynchronous discovery.

## Capabilities

### New Capabilities

- `mcp-async-discovery-cache`: MCP `tools/list` discovery runs asynchronously and populates a per-session cache.
- `mcp-cached-tool-catalog`: `ToolCatalog` merges local tools, explicit MCP tools, and cached discovered MCP tools.

### Modified Capabilities

- `tool-catalog-and-raw-schema`: MCP tool descriptors can come from explicit DSL declarations or completed discovery cache entries.
- `update-snapshot-contract`: MCP config updates schedule a discovery refresh; discovered tools take effect only after refresh completion and a later `send()`.
- `manual-smoke-entrypoints`: Phase smoke verifies real MCP discovery through adb reverse without blocking request construction.

## Impact

- 修改：`ChatSession.kt`
- 修改：`SessionConfig.kt`
- 修改：`McpTypes.kt`
- 修改：`McpClient.kt`
- 修改：`ToolCallRequest.kt`（仅在需要携带 discovered metadata 时）
- 修改：`DemoChatViewModel.kt`
- 修改：`Phase4Smoke.kt` 或新增 `Phase5Smoke.kt`
- 可能新增：`McpDiscoveryCache.kt` / `McpDiscoveryModels.kt`

## Non-Goals

- 不让 `send()` 等待 MCP discovery 网络请求。
- 不实现 MCP resources/prompts discovery。
- 不支持非 HTTP MCP transport discovery。
- 不做跨 app 进程持久化缓存。
- 不在 discovery 失败时清空上一份成功缓存。
- 不自动运行 Gradle、不启动 app；验证由用户手工运行 demo 并查看 Logcat。
