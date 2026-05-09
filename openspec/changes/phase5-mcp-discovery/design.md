## Context

Phase4 builds `ToolCatalog` from local tools and explicitly declared MCP tools. Runtime logs show the current failure mode:

- `McpRegistry.add name=local_ide enabled=true tools=[]`
- `McpRegistry.toToolDescriptors server=local_ide enabled=true explicitTools=[]`
- `SessionConfig.buildToolCatalog local=[send_toast] mcp=[]`
- `OpenAIProtocol.buildRequest tools=[send_toast:Local]`

The MCP server is reachable and `tools/list` returns tools, but the app never calls discovery. The user requirement for Phase5 is strict: discovery must be cached and asynchronous. `send()` must not block on `tools/list`; completed discovery updates the cache and affects only later rounds.

## Goals / Non-Goals

**Goals:**
- Automatically discover MCP tools from HTTP MCP servers using `tools/list`.
- Cache discovery results per `ChatSession`.
- Keep `send()` synchronous with respect to local state only; no network discovery wait on the request path.
- Let cache refresh complete asynchronously and affect the next `send()` snapshot.
- Preserve explicit `tool(...)` declarations and make them override discovered tools with the same name.
- Keep stale successful cache on discovery failure.
- Provide enough `qwerqwer` logs to distinguish registration, cache hit/miss, discovery completion, merge result, and OpenAI request tools.

**Non-Goals:**
- No blocking discovery in `send()`.
- No complete MCP lifecycle protocol.
- No resources/prompts discovery.
- No persistent disk cache.
- No automatic build or app launch.

## Decisions

### Decision 1: Discovery Cache Lives In ChatSession

**选择**：新增 session-scoped cache owned by `ChatSession`, not by `SessionConfig`.

```kotlin
internal class McpDiscoveryCache {
    fun snapshot(serverName: String, fingerprint: String): List<McpDiscoveredTool>?
    fun put(serverName: String, fingerprint: String, tools: List<McpDiscoveredTool>)
    fun markRefreshing(serverName: String, fingerprint: String): Boolean
    fun markFinished(serverName: String, fingerprint: String)
}
```

**原因**：
- `SessionConfig` should remain a config object, not a coroutine/network owner.
- Cache lifecycle should close with `ChatSession`.
- `update { mcp { ... } }` changes config but should not discard a usable old cache until a matching new discovery succeeds.

### Decision 2: Server Fingerprint Guards Stale Writes

**选择**：derive a fingerprint from discovery-relevant fields:

```kotlin
serverName + enabled + transport + headers
```

Discovery jobs write results only if their fingerprint still matches the latest config at completion time.

**原因**：
- Prevent late discovery from an old URL/header config from overwriting the cache for a newer config.
- Keep concurrency simple without blocking update/send.

### Decision 3: Discovery Is Scheduled, Not Awaited

**选择**：
- `Session.open` schedules discovery for initial MCP servers.
- `ChatSession.update()` schedules discovery for changed or newly enabled MCP servers after config swap.
- `send()` calls `scheduleDiscovery(configRef.get())` opportunistically but does not await it.
- `send()` builds `SessionSnapshot` from the current config plus the latest completed cache.

**原因**：
- If discovery has not completed, the round proceeds without those discovered tools.
- Once discovery completes, the next round includes the tools.
- Opportunistic scheduling recovers if the initial discovery failed or was not scheduled due to lifecycle timing.

### Decision 4: Explicit Tools Override Discovered Tools

**选择**：merge order:

1. discovered tools from cache
2. explicit `tool(...)` entries

When names collide, explicit tools win.

**原因**：
- Explicit DSL is a local override for schema fixes, descriptions, or temporary hiding of bad remote schema fields.
- Discovery still removes the need to manually copy every tool.

### Decision 5: Discovery Failure Keeps Last Successful Cache

**选择**：on discovery failure:
- log `android.util.Log.d("qwerqwer", "MCP discovery failed ...", throwable)` or equivalent direct `Log.d`;
- keep previous successful cache for the same server fingerprint if it exists;
- do not clear tools;
- do not block or fail `send()`.

**原因**：
- User explicitly requires non-blocking discovery.
- A transient MCP server failure should not remove tools that were already known.

### Decision 6: First Round May Not See Tools

**选择**：If `send()` happens before discovery completes, the first request may not include discovered tools. This is expected and logged.

**原因**：
- The no-blocking requirement is stronger than first-round discovery completeness.
- Smoke and demo should log cache state clearly.

## Data Model

```kotlin
internal data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>
)
```

`HttpMcpClient.listTools(server)` sends:

```json
{
  "jsonrpc": "2.0",
  "id": "...",
  "method": "tools/list",
  "params": {}
}
```

It parses:

```json
{
  "result": {
    "tools": [
      {
        "name": "...",
        "description": "...",
        "inputSchema": {}
      }
    ]
  }
}
```

Parsing must preserve arbitrary JSON schema fields in `inputSchema`.

## Flow

### Open

1. `Session.open` builds config.
2. `ChatSession` creates `McpDiscoveryCache`.
3. `ChatSession` schedules discovery for enabled HTTP MCP servers.
4. Constructor returns immediately.

### Send

1. `send()` reads `configRef.get()`.
2. `send()` schedules discovery if no fresh job/cache exists.
3. `send()` builds `SessionSnapshot` using latest completed discovery cache.
4. `OpenAIProtocol.buildRequest()` encodes `snapshot.tools.descriptors`.
5. Active round never observes later discovery completion.

### Update

1. `update()` merges dynamic config.
2. Open-only fields remain ignored as Phase4 defines.
3. After config swap, `ChatSession` schedules discovery for enabled changed MCP servers.
4. Completed discovery affects future snapshots only.

## Logging

Use direct fully qualified logging in implementation to avoid import churn:

```kotlin
android.util.Log.d("qwerqwer", "...")
```

Required log points:
- MCP server registered/updated: server name, enabled, transport, explicit tool names.
- Discovery scheduled/skipped: server name, fingerprint, reason.
- Discovery result: server name, discovered tool names.
- Discovery failure: server name, error message.
- Catalog merge: local tools, explicit MCP tools, discovered MCP tools, final MCP tools.
- OpenAI request tools: final tool names and kinds.

## Risks / Trade-offs

- First send after app open may not include discovered tools.
- Discovery refresh after update may race with sends; fingerprint check prevents stale writes.
- Per-send opportunistic scheduling can spam logs if not guarded by `markRefreshing`.
- Tool names across MCP servers can collide. Existing `ToolCatalog.find(name)` is name-based, so duplicate names remain ambiguous. Phase5 should log duplicates and keep current behavior unless a separate namespacing change is approved.
