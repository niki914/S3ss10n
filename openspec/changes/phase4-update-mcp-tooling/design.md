## Context

当前代码已经有 `update(block)`、`mcp {}`、`rawJsonSchema()`、`HttpEngine` 等表面 API，但几个关键路径没有闭合：

- `update{}`：`AtomicReference` 存在，但 open-only 对象和 update 后 snapshot 的关系没有定义清楚。
- `rawJsonSchema()`：只存字符串，不输出到 tool schema。
- MCP：registry 可配置，tool definitions 不包含 MCP，tool dispatch 永远 local。
- `OkHttpEngine.close()`：没有取消 active calls。
- 验证：旧 smoketest 已删除，需要新的 DemoActivity 手工入口。

Phase4 的目标不是继续大拆架构，而是把这些“看起来有、实际没闭环”的能力补齐。

## Goals / Non-Goals

**Goals:**
- 完整定义并修正 update snapshot 语义。
- raw schema 生效，并通过 JsonCodec 校验。
- local/MCP tools 统一成 ToolCatalog。
- MCP tool call 能从模型 tools 暴露到实际 delegate 分流。
- OkHttpEngine.close 取消 active calls。
- 新增 `main1/main2/main3/main` 手工测试入口，并在 DemoActivity 调用。

**Non-Goals:**
- 改 `xLog` 默认 tag。
- 完整 MCP discovery。
- 自动运行测试。
- 动态切 protocol/jsonCodec/httpEngine。

## Decisions

### Decision 1: update 支持字段分层

**选择**：把字段分成 dynamic 与 open-only。

Dynamic，可通过 `update{}` 生效到下一次 send：
- `endpoint`
- `apiKey`
- `model`
- `systemPrompt`
- `temperature`
- `connectTimeoutSeconds/readTimeoutSeconds/writeTimeoutSeconds`
- `hooks`
- `localTools`
- `mcp`
- `appParams`

Open-only，不允许通过 `update{}` 生效：
- `jsonCodec`
- `httpEngine`
- `protocol`

实现上有两个可选路径：
1. `SessionConfig.Builder` 仍保留 open-only 属性，但 `ChatSession.update()` 在 merge 时忽略这些字段，并 `xLog("X", "update ignored open-only field: ...")`。
2. 从 update builder 中移除 open-only 字段。

倾向路径 1，改动小，兼容当前 API；但必须明确日志提示，避免“看似生效”。

### Decision 2: 每次 send 冻结 SessionSnapshot

**选择**：新增内部不可变快照：

```kotlin
internal data class SessionSnapshot(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String?,
    val temperature: Float,
    val timeouts: HttpTimeouts,
    val hooksBlock: (suspend ToolCallRequest.() -> String)?,
    val appParams: Map<String, Any?>,
    val tools: ToolCatalog,
    val jsonCodec: JsonCodec,
)
```

`send()` 开始时从 `configRef.get()` 构建 `SessionSnapshot`。`RoundContext.configSnapshot` 改为 `SessionSnapshot`。

**原因**：
- snapshot 显式包含 tool catalog 与 appParams，不再到处读 mutable registry。
- update 后的新 config 只影响下一次 send。
- protocol/buildRequest/dispatch 都使用同一个 snapshot。

### Decision 3: ToolCatalog 是 local/MCP 的唯一工具目录

**选择**：新增：

```kotlin
internal data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val kind: ToolCallKind,
)

internal data class ToolCatalog(
    val descriptors: List<ToolDescriptor>
) {
    private val byName = descriptors.associateBy { it.name }
    fun find(name: String): ToolDescriptor? = byName[name]
}
```

`SessionConfig.buildToolCatalog(codec)` 同时汇总 local 和 MCP：
- local tool → `ToolCallKind.Local`
- mcp server/tool → `ToolCallKind.Mcp(serverName)`

**原因**：
- `OpenAIProtocol` 不应该知道 local/MCP registry 的细节。
- `ChatSession.buildToolCallRequest()` 只查 catalog 分流。

### Decision 4: rawJsonSchema 优先级最高

**选择**：`LocalToolConfig.toDescriptor(codec)` 规则：
- 如果 `rawInputSchemaJson != null`：用 `codec.decodeMap(rawInputSchemaJson)` 校验并作为 `inputSchema`。
- 如果 decode 失败：抛 `IllegalArgumentException("Invalid rawJsonSchema for tool ...")`，由 `doRound` 转 `SessionEvent.Error(Stage.Session)` 或 `Stage.Parse`。
- 如果没有 raw schema：用 DSL property 构建标准 JSON schema。

**原因**：
- raw schema 是用户显式给的完整 schema，应优先于 property DSL。
- 不能保存但不使用。

### Decision 5: MCP DSL 必须声明 tools

**选择**：扩展 `McpServerConfig`：

```kotlin
data class McpServerConfig(
    var enabled: Boolean = true,
    var transport: McpTransport = McpTransport.Http(),
    var headers: Map<String, String> = emptyMap()
) {
    internal val tools = mutableMapOf<String, McpToolConfig>()
    fun tool(name: String, block: McpToolConfig.() -> Unit)
}
```

`McpToolConfig` 与 local tool schema 配置保持类似：`description` + `rawJsonSchema` + property DSL。

**原因**：
- 不做 MCP discovery 时，必须让用户显式声明模型可见的 MCP tools。
- 这样 update 可以动态 add/replace/remove MCP tools。

### Decision 6: MCP 最小执行接口

**选择**：新增：

```kotlin
internal interface McpClient {
    suspend fun call(server: McpServerConfig, toolName: String, argumentsJson: String): String
}
```

`HttpMcpClient` MVP：
- 只支持 `McpTransport.Http(url)`。
- 发送最小 JSON-RPC 请求到 `url`。
- headers 使用 server.headers。
- 返回 response body 原始 JSON string。

`McpToolCallRequest.delegate()` 调用 `McpClient.call(...)`。

**原因**：
- 如果 MCP tool 暴露给模型，就必须有 delegate 执行路径。
- 完整 MCP protocol 后续再做，不阻塞最小闭环。

### Decision 7: buildToolCallRequest 按 ToolCatalog 分流

**选择**：

```kotlin
private fun buildToolCallRequest(toolCall: ToolCallSpec, snap: SessionSnapshot): ToolCallRequest {
    val descriptor = snap.tools.find(toolCall.toolName)
    return when (val kind = descriptor?.kind) {
        ToolCallKind.Local -> LocalToolCallRequest(toolCall, snap.appParams)
        is ToolCallKind.Mcp -> McpToolCallRequest(toolCall, kind.serverName, snap.appParams, snap.mcpServer(kind.serverName), mcpClient)
        null -> UnknownToolCallRequest(...)
    }
}
```

若 unknown，不要默认 local；发 `ToolFailed` 和 `SessionEvent.Error(Stage.Tool)`。

### Decision 8: OpenAIProtocol 只编码 ToolDescriptor

**选择**：`OpenAIProtocol.buildRequest(snapshot, history, pendingUserInput)` 从 `snapshot.tools.descriptors` 获取 tools，然后映射为 OpenAI `ToolDefinition`。

OpenAI model 类型需要支持 raw schema：

```kotlin
data class FunctionTool(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
)
```

不再用强类型 `FunctionParameters(properties: Map<String, PropertyDefinition>)` 限制 schema。

**原因**：raw JSON schema 可能包含 enum/items/oneOf 等字段，强类型模型会丢字段。

### Decision 9: OkHttpEngine active call 管理

**选择**：
- `private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())`
- newCall 后 add。
- onFailure/onResponse/awaitClose finally remove。
- close：复制 activeCalls，逐个 `cancel()`，再 shutdown/evict。

**原因**：`HttpEngine.close()` 必须是完整资源释放入口。

### Decision 10: 手工测试入口按用户要求放 main

**选择**：新增 `Phase4Smoke.kt`：

```kotlin
package com.niki914.s3ss10n.smoketest

fun main1() { android.util.Log.e("X", "main1 update snapshot start") }
fun main2() { android.util.Log.e("X", "main2 raw schema/tool catalog start") }
fun main3() { android.util.Log.e("X", "main3 mcp/engine close start") }
fun main() {
    main1()
    main2()
    main3()
}
```

`DemoActivity` 中调用 `com.niki914.s3ss10n.smoketest.main()`。

**原因**：用户明确要求用 `Log.e` 打印、每个写一个 `main1/2/3`、再统一 `main` 调用、最终 DemoActivity 调 main。

注意：这与之前“删除 smoketest”口径不同，但这是新的 phase4 手工 smoke，不恢复旧烟测。

## Risks / Trade-offs

- **MCP MVP 不完整**：没有 discovery，用户必须手写 MCP tool schema；但比当前“配置了也不可用”强。
- **OpenAI FunctionTool.parameters 改成 Map**：牺牲部分类型安全，换取 raw schema 不丢字段。
- **DemoActivity 调 smoke main**：这是开发态入口，不应长期留在线上 release。实现时可用 debug-only guard 或明确注释。

## Open Questions

- `HttpMcpClient` 是否复用 `HttpEngine`，还是独立 OkHttp？倾向复用 `HttpEngine`，但当前 HttpEngine 只有 stream；MCP JSON-RPC 更适合 unary。若不想扩 HttpEngine，就临时在 `HttpMcpClient` 用 OkHttp 会破坏 OkHttp 收口。建议本 phase 同步给 HttpEngine 增加 `suspend fun unary(request): HttpResponse`。
