# Session API 重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 ChatSession/ConfigBuilder/ChatEvent/ToolModel 公开 API 改造为 PRD 定义的 Session/SessionConfig/SessionEvent/localTools DSL，内部通过 SessionImpl 包装 ChatSession 实现零风险过渡。

**Architecture:** SessionImpl 持有 ChatSession 并实现 ChatSession.Callback，将内部 ChatEvent 映射为 SessionEvent 回调给用户。hooks{} 块在 Callback.onToolCall 中被调用。localTools DSL 生成 ToolDefinition 直接喂给 ChatClient。MCP DSL 仅占位编译通过。

**Tech Stack:** Kotlin, OkHttp 4.12, Gson, kotlinx.coroutines, 现有 ChatSession/ChatClient/SSE 管道

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `s3ss10n/.../SessionEvent.kt` | 新增 | SessionEvent sealed interface + Stage enum |
| `s3ss10n/.../ToolCallKind.kt` | 新增 | ToolCallKind sealed interface (Local, Mcp) |
| `s3ss10n/.../ToolCallRequest.kt` | 新增 | ToolCallRequest sealed interface + impl |
| `s3ss10n/.../LocalToolRegistry.kt` | 新增 | LocalToolRegistry + LocalToolConfig + LocalToolProperty + ToolValueType |
| `s3ss10n/.../McpTypes.kt` | 新增 | McpRegistry + McpServerConfig + McpTransport 占位 |
| `s3ss10n/.../SessionConfig.kt` | 新增 | SessionConfig class + hooks/localTools/mcp DSL |
| `s3ss10n/.../Session.kt` | 新增 | Session interface |
| `s3ss10n/.../SessionImpl.kt` | 新增 | Session 实现，包装 ChatSession |
| `s3ss10n/.../ChatSession.kt` | 修改 | Callback 接口保留（SessionImpl 使用），其余不变 |
| `app/.../DemoChatViewModel.kt` | 重写 | 使用新 Session API |
| `s3ss10n/.../Config.kt` | 修改 | 增 temperature 字段 |
| `s3ss10n/.../ChatClient.kt` | 修改 | sendMessages 支持 temperature |
| `s3ss10n/.../chat/protocol/ChatApiRequestBody.kt` | 修改 | 增 temperature 字段 |

---

### Task 1: SessionEvent 类型定义

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/SessionEvent.kt`
- Create: `s3ss10n/src/test/java/com/niki914/s3ss10n/SessionEventTest.kt` (main function smoke test)

- [ ] **Step 1: 创建 SessionEvent.kt**

```kotlin
package com.niki914.s3ss10n

/**
 * Events emitted during a send() round.
 */
sealed interface SessionEvent {
    data class RoundStarted(val input: String) : SessionEvent

    data class TextDelta(
        val delta: String,
        val fullText: String
    ) : SessionEvent

    data class ToolRunning(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind
    ) : SessionEvent

    data class ToolSucceeded(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind,
        val resultJson: String
    ) : SessionEvent

    data class ToolFailed(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind,
        val message: String,
        val resultJson: String? = null
    ) : SessionEvent

    data class RoundCompleted(
        val fullText: String
    ) : SessionEvent

    data class Error(
        val stage: Stage,
        val message: String,
        val cause: Throwable? = null
    ) : SessionEvent

    enum class Stage {
        Transport,
        Parse,
        Tool,
        Session
    }
}
```

- [ ] **Step 2: 创建 smoke test**

```kotlin
// s3ss10n/src/test/java/com/niki914/s3ss10n/SessionEventTest.kt
package com.niki914.s3ss10n

fun main() {
    println("=== SessionEvent Smoke Test ===")

    // RoundStarted
    val rs = SessionEvent.RoundStarted("hello")
    assertOrPrint("RoundStarted.input == 'hello'", rs.input == "hello")

    // TextDelta
    val td = SessionEvent.TextDelta("He", "Hello")
    assertOrPrint("TextDelta.delta == 'He'", td.delta == "He")
    assertOrPrint("TextDelta.fullText == 'Hello'", td.fullText == "Hello")

    // ToolRunning (depends on ToolCallKind, use placeholder)
    val tr = SessionEvent.ToolRunning("call_1", "toast", ToolCallKind.Local)
    assertOrPrint("ToolRunning.callId == 'call_1'", tr.callId == "call_1")
    assertOrPrint("ToolRunning.toolName == 'toast'", tr.toolName == "toast")
    assertOrPrint("ToolRunning.kind is Local", tr.kind == ToolCallKind.Local)

    // ToolSucceeded
    val ts = SessionEvent.ToolSucceeded("call_1", "toast", ToolCallKind.Local, """{"ok":true}""")
    assertOrPrint("ToolSucceeded.resultJson", ts.resultJson == """{"ok":true}""")

    // ToolFailed
    val tf = SessionEvent.ToolFailed("call_1", "toast", ToolCallKind.Local, "timeout")
    assertOrPrint("ToolFailed.message == 'timeout'", tf.message == "timeout")
    assertOrPrint("ToolFailed.resultJson == null", tf.resultJson == null)

    // ToolFailed with result
    val tf2 = SessionEvent.ToolFailed("call_2", "foo", ToolCallKind.Local, "err", """{"e":1}""")
    assertOrPrint("ToolFailed with result", tf2.resultJson == """{"e":1}""")

    // RoundCompleted
    val rc = SessionEvent.RoundCompleted("Hello World")
    assertOrPrint("RoundCompleted.fullText", rc.fullText == "Hello World")

    // Error
    val err = SessionEvent.Error(SessionEvent.Stage.Transport, "timeout", null)
    assertOrPrint("Error.stage == Transport", err.stage == SessionEvent.Stage.Transport)

    val err2 = SessionEvent.Error(SessionEvent.Stage.Parse, "bad json", IllegalStateException("boom"))
    assertOrPrint("Error with cause", err2.cause?.message == "boom")

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
```

Note: `ToolCallKind.Local` 引用了 Task 2 中定义的 `ToolCallKind`。由于 Kotlin 编译需要所有依赖先存在，所以 Task 1 的测试需要在 Task 2 完成后才能编译运行。这里先写好文件。

- [ ] **Step 3: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/SessionEvent.kt \
        s3ss10n/src/test/java/com/niki914/s3ss10n/SessionEventTest.kt
git commit -m "feat: add SessionEvent sealed interface with smoke test"
```

---

### Task 2: ToolCallKind 类型定义

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallKind.kt`

- [ ] **Step 1: 创建 ToolCallKind.kt**

```kotlin
package com.niki914.s3ss10n

sealed interface ToolCallKind {
    data object Local : ToolCallKind
    data class Mcp(val serverName: String) : ToolCallKind
}
```

- [ ] **Step 2: 更新 SessionEventTest.kt 使其可独立编译（移除 ToolCallKind 引用，改为纯 SessionEvent 结构测试），然后在 Task 3 中做联合测试**

实际上：Task 1 的测试文件中引用了 `ToolCallKind.Local`，但 Task 2 才定义它。修正方式：Task 1 先创建测试但不引用 ToolCallKind。待 Task 2 完成后，在 Task 2 的测试中覆盖 ToolCallKind。

Task 1 测试改为只测不依赖 ToolCallKind 的 event 类型。

- [ ] **Step 3: 添加 ToolCallKind 测试**

创建 `s3ss10n/src/test/java/com/niki914/s3ss10n/ToolCallKindTest.kt`:

```kotlin
package com.niki914.s3ss10n

fun main() {
    println("=== ToolCallKind Smoke Test ===")

    // Local
    val local: ToolCallKind = ToolCallKind.Local
    assertOrPrint("Local is data object", local is ToolCallKind.Local)

    // Mcp
    val mcp = ToolCallKind.Mcp("aslocate")
    assertOrPrint("Mcp.serverName == 'aslocate'", mcp.serverName == "aslocate")

    // Equality
    assertOrPrint("Local == Local", ToolCallKind.Local == ToolCallKind.Local)
    assertOrPrint("Mcp('a') == Mcp('a')", ToolCallKind.Mcp("a") == ToolCallKind.Mcp("a"))
    assertOrPrint("Mcp('a') != Mcp('b')", ToolCallKind.Mcp("a") != ToolCallKind.Mcp("b"))

    // Use in SessionEvent
    val tr = SessionEvent.ToolRunning("c1", "t1", ToolCallKind.Local)
    assertOrPrint("SessionEvent with ToolCallKind.Local", tr.kind == ToolCallKind.Local)

    val tr2 = SessionEvent.ToolRunning("c2", "t2", ToolCallKind.Mcp("srv"))
    val kind = tr2.kind
    assertOrPrint("SessionEvent with Mcp", (kind as ToolCallKind.Mcp).serverName == "srv")

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
```

- [ ] **Step 4: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallKind.kt \
        s3ss10n/src/test/java/com/niki914/s3ss10n/ToolCallKindTest.kt
git commit -m "feat: add ToolCallKind sealed interface with smoke test"
```

---

### Task 3: ToolCallRequest 类型定义

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallRequest.kt`

- [ ] **Step 1: 创建 ToolCallRequest.kt**

```kotlin
package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toolbase.ToolManager

/**
 * Wraps an incoming tool call with routing metadata.
 */
sealed interface ToolCallRequest {
    val id: String
    val name: String
    val argumentsJson: String
    val kind: ToolCallKind

    suspend fun delegate(): Message.Tool

    fun ok(contentJson: String): Message.Tool

    fun error(
        message: String,
        contentJson: String = """{"success":false}"""
    ): Message.Tool
}

/**
 * Local tool call — dispatched to the built-in ToolManager.
 */
internal class LocalToolCallRequest(
    private val toolCall: ToolCall,
    private val toolManager: ToolManager,
    private val appParams: Map<String, Any?>
) : ToolCallRequest {
    override val id: String get() = toolCall.id ?: "unknown"
    override val name: String get() = toolCall.function?.name ?: "unknown"
    override val argumentsJson: String get() = toolCall.function?.arguments ?: "{}"
    override val kind: ToolCallKind = ToolCallKind.Local

    override suspend fun delegate(): Message.Tool {
        val result = toolManager.exec(toolCall, appParams)
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = result
        )
    }

    override fun ok(contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = contentJson
        )
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = """{"error":"$message","detail":$contentJson}"""
        )
    }
}

/**
 * MCP tool call — placeholder, always returns error for MVP.
 */
internal class McpToolCallRequest(
    private val toolCall: ToolCall,
    private val serverName: String
) : ToolCallRequest {
    override val id: String get() = toolCall.id ?: "unknown"
    override val name: String get() = toolCall.function?.name ?: "unknown"
    override val argumentsJson: String get() = toolCall.function?.arguments ?: "{}"
    override val kind: ToolCallKind = ToolCallKind.Mcp(serverName)

    override suspend fun delegate(): Message.Tool {
        return error("MCP not implemented yet")
    }

    override fun ok(contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = contentJson
        )
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        return Message.Tool(
            toolCallId = id,
            name = name,
            content = """{"error":"$message"}"""
        )
    }
}
```

- [ ] **Step 2: 创建 smoke test**

创建 `s3ss10n/src/test/java/com/niki914/s3ss10n/ToolCallRequestTest.kt`:

```kotlin
package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.FunctionCall
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toolbase.ToolManager

fun main() {
    println("=== ToolCallRequest Smoke Test ===")

    val toolCall = ToolCall(
        id = "call_123",
        type = "function",
        function = FunctionCall(
            name = "test_tool",
            arguments = """{"key":"value"}"""
        )
    )

    // Test LocalToolCallRequest.ok()
    val req = LocalToolCallRequest(toolCall, ToolManager(), emptyMap())
    assertOrPrint("id == 'call_123'", req.id == "call_123")
    assertOrPrint("name == 'test_tool'", req.name == "test_tool")
    assertOrPrint("argumentsJson", req.argumentsJson == """{"key":"value"}""")
    assertOrPrint("kind is Local", req.kind == ToolCallKind.Local)

    val okResult = req.ok("""{"done":true}""")
    assertOrPrint("ok() returns Message.Tool", okResult is Message.Tool)
    assertOrPrint("ok() toolCallId", okResult.toolCallId == "call_123")
    assertOrPrint("ok() content", okResult.content == """{"done":true}""")

    val errResult = req.error("timeout", """{"code":1}""")
    assertOrPrint("error() returns Message.Tool", errResult is Message.Tool)
    assertOrPrint("error() contains error", "timeout" in errResult.content)

    // Test McpToolCallRequest
    val mcpReq = McpToolCallRequest(toolCall, "aslocate")
    assertOrPrint("Mcp kind", mcpReq.kind is ToolCallKind.Mcp)
    assertOrPrint("Mcp serverName", (mcpReq.kind as ToolCallKind.Mcp).serverName == "aslocate")

    val mcpOk = mcpReq.ok("""{"x":1}""")
    assertOrPrint("Mcp ok() content", mcpOk.content == """{"x":1}""")

    val mcpErr = mcpReq.error("not implemented")
    assertOrPrint("Mcp error() content", "not implemented" in mcpErr.content)

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
```

- [ ] **Step 3: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallRequest.kt \
        s3ss10n/src/test/java/com/niki914/s3ss10n/ToolCallRequestTest.kt
git commit -m "feat: add ToolCallRequest sealed interface with Local and Mcp impls"
```

---

### Task 4: LocalToolRegistry + LocalToolConfig 类型定义

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/LocalToolRegistry.kt`

- [ ] **Step 1: 创建 LocalToolRegistry.kt**

```kotlin
package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.FunctionParameters
import com.niki914.s3ss10n.chat.protocol.FunctionTool
import com.niki914.s3ss10n.chat.protocol.PropertyDefinition
import com.niki914.s3ss10n.chat.protocol.ToolDefinition

enum class ToolValueType(val jsonType: String) {
    String("string"),
    Integer("integer"),
    Number("number"),
    Boolean("boolean"),
    Object("object"),
    Array("array")
}

data class LocalToolProperty(
    val name: String,
    var type: ToolValueType = ToolValueType.String,
    var description: String = "",
    var required: Boolean = false,
    var enumValues: List<String> = emptyList()
)

data class LocalToolConfig(
    var description: String = "",
    var rawInputSchemaJson: String? = null
) {
    internal val properties = mutableMapOf<String, LocalToolProperty>()
    internal val requiredNames = mutableListOf<String>()

    fun string(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.String, block)
    }

    fun integer(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Integer, block)
    }

    fun number(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Number, block)
    }

    fun boolean(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Boolean, block)
    }

    fun object_(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Object, block)
    }

    fun array(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Array, block)
    }

    fun rawJsonSchema(json: String) {
        rawInputSchemaJson = json
    }

    private fun addProperty(name: String, type: ToolValueType, block: LocalToolProperty.() -> Unit) {
        val prop = LocalToolProperty(name = name, type = type).apply(block)
        properties[name] = prop
        if (prop.required) requiredNames.add(name)
    }

    internal fun toToolDefinition(toolName: String): ToolDefinition {
        val propDefs = properties.mapValues { (_, prop) ->
            PropertyDefinition(
                type = prop.type.jsonType,
                description = prop.description
            )
        }
        return ToolDefinition(
            function = FunctionTool(
                name = toolName,
                description = description,
                parameters = FunctionParameters(
                    type = "object",
                    properties = propDefs,
                    required = requiredNames.ifEmpty { null }
                )
            )
        )
    }
}

interface LocalToolRegistry {
    fun add(name: String, block: LocalToolConfig.() -> Unit)
    fun replace(name: String, block: LocalToolConfig.() -> Unit)
    fun remove(name: String)
}

internal class LocalToolRegistryImpl : LocalToolRegistry {
    private val _tools = mutableMapOf<String, LocalToolConfig>()

    val tools: Map<String, LocalToolConfig> get() = _tools.toMap()

    override fun add(name: String, block: LocalToolConfig.() -> Unit) {
        _tools[name] = LocalToolConfig().apply(block)
    }

    override fun replace(name: String, block: LocalToolConfig.() -> Unit) {
        _tools[name] = LocalToolConfig().apply(block)
    }

    override fun remove(name: String) {
        _tools.remove(name)
    }

    fun toToolDefinitions(): List<ToolDefinition> =
        _tools.map { (name, config) -> config.toToolDefinition(name) }
}
```

- [ ] **Step 2: 创建 smoke test**

创建 `s3ss10n/src/test/java/com/niki914/s3ss10n/LocalToolRegistryTest.kt`:

```kotlin
package com.niki914.s3ss10n

fun main() {
    println("=== LocalToolRegistry Smoke Test ===")

    // Test DSL
    val registry = LocalToolRegistryImpl()
    registry.add("toast") {
        description = "显示提示"
        string("message") {
            description = "消息内容"
            required = true
        }
        integer("duration") {
            description = "持续时间"
        }
    }

    val tools = registry.tools
    assertOrPrint("added 1 tool", tools.size == 1)
    val toast = tools["toast"]!!
    assertOrPrint("toast.description", toast.description == "显示提示")
    assertOrPrint("toast has 2 properties", toast.properties.size == 2)
    assertOrPrint("message property", toast.properties["message"]?.name == "message")
    assertOrPrint("message required", toast.properties["message"]?.required == true)
    assertOrPrint("message type", toast.properties["message"]?.type == ToolValueType.String)
    assertOrPrint("message desc", toast.properties["message"]?.description == "消息内容")
    assertOrPrint("duration type", toast.properties["duration"]?.type == ToolValueType.Integer)
    assertOrPrint("duration not required", toast.properties["duration"]?.required == false)

    // Test ToolDefinition generation
    val defs = registry.toToolDefinitions()
    assertOrPrint("1 definition", defs.size == 1)
    val def = defs[0]
    assertOrPrint("def name", def.function.name == "toast")
    assertOrPrint("required list", def.function.parameters.required == listOf("message"))
    assertOrPrint("properties count", def.function.parameters.properties.size == 2)

    // Test replace
    registry.replace("toast") {
        description = "新描述"
    }
    assertOrPrint("replaced description", registry.tools["toast"]?.description == "新描述")

    // Test remove
    registry.remove("toast")
    assertOrPrint("removed", registry.tools.isEmpty())

    // Test all property types
    registry.add("alltypes") {
        description = "test"
        string("s") {}
        integer("i") {}
        number("n") {}
        boolean("b") {}
        object_("o") {}
        array("a") {}
    }
    val allTypes = registry.tools["alltypes"]!!
    assertOrPrint("6 properties", allTypes.properties.size == 6)
    assertOrPrint("string jsonType", allTypes.properties["s"]?.type?.jsonType == "string")
    assertOrPrint("integer jsonType", allTypes.properties["i"]?.type?.jsonType == "integer")
    assertOrPrint("number jsonType", allTypes.properties["n"]?.type?.jsonType == "number")
    assertOrPrint("boolean jsonType", allTypes.properties["b"]?.type?.jsonType == "boolean")
    assertOrPrint("object jsonType", allTypes.properties["o"]?.type?.jsonType == "object")
    assertOrPrint("array jsonType", allTypes.properties["a"]?.type?.jsonType == "array")

    // rawJsonSchema
    registry.add("raw") {
        rawJsonSchema("""{"custom":true}""")
    }
    assertOrPrint("rawInputSchemaJson", registry.tools["raw"]?.rawInputSchemaJson == """{"custom":true}""")

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
```

- [ ] **Step 3: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/LocalToolRegistry.kt \
        s3ss10n/src/test/java/com/niki914/s3ss10n/LocalToolRegistryTest.kt
git commit -m "feat: add LocalToolRegistry DSL with LocalToolConfig and ToolValueType"
```

---

### Task 5: MCP 占位类型

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/McpTypes.kt`

- [ ] **Step 1: 创建 McpTypes.kt**

```kotlin
package com.niki914.s3ss10n

sealed interface McpTransport {
    data class Http(var url: String = "") : McpTransport
}

data class McpServerConfig(
    var enabled: Boolean = true,
    var transport: McpTransport = McpTransport.Http(),
    var headers: Map<String, String> = emptyMap()
) {
    fun http(block: McpTransport.Http.() -> Unit) {
        val http = (transport as? McpTransport.Http) ?: McpTransport.Http()
        transport = http.apply(block)
    }
}

interface McpRegistry {
    fun add(name: String, block: McpServerConfig.() -> Unit)
    fun replace(name: String, block: McpServerConfig.() -> Unit)
    fun remove(name: String)
}

internal class McpRegistryImpl : McpRegistry {
    private val _servers = mutableMapOf<String, McpServerConfig>()

    val servers: Map<String, McpServerConfig> get() = _servers.toMap()

    override fun add(name: String, block: McpServerConfig.() -> Unit) {
        _servers[name] = McpServerConfig().apply(block)
    }

    override fun replace(name: String, block: McpServerConfig.() -> Unit) {
        _servers[name] = McpServerConfig().apply(block)
    }

    override fun remove(name: String) {
        _servers.remove(name)
    }
}
```

- [ ] **Step 2: 创建 smoke test**

创建 `s3ss10n/src/test/java/com/niki914/s3ss10n/McpTypesTest.kt`:

```kotlin
package com.niki914.s3ss10n

fun main() {
    println("=== McpTypes Smoke Test ===")

    // McpTransport
    val http = McpTransport.Http(url = "http://127.0.0.1:51338/mcp")
    assertOrPrint("Http.url", http.url == "http://127.0.0.1:51338/mcp")
    assertOrPrint("Http is McpTransport", http is McpTransport)

    // McpServerConfig defaults
    val cfg = McpServerConfig()
    assertOrPrint("default enabled", cfg.enabled)
    assertOrPrint("default transport is Http", cfg.transport is McpTransport.Http)
    assertOrPrint("default headers empty", cfg.headers.isEmpty())

    // McpServerConfig DSL
    val cfg2 = McpServerConfig().apply {
        http {
            url = "http://example.com/mcp"
        }
    }
    val t = cfg2.transport as McpTransport.Http
    assertOrPrint("DSL url set", t.url == "http://example.com/mcp")

    // McpRegistry
    val registry = McpRegistryImpl()
    registry.add("aslocate") {
        http { url = "http://127.0.0.1:51338/mcp" }
    }

    val servers = registry.servers
    assertOrPrint("1 server added", servers.size == 1)
    val aslocate = servers["aslocate"]!!
    assertOrPrint("aslocate http url", (aslocate.transport as McpTransport.Http).url == "http://127.0.0.1:51338/mcp")

    // replace
    registry.replace("aslocate") {
        enabled = false
    }
    assertOrPrint("replaced enabled=false", registry.servers["aslocate"]?.enabled == false)

    // remove
    registry.remove("aslocate")
    assertOrPrint("removed", registry.servers.isEmpty())

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
```

- [ ] **Step 3: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/McpTypes.kt \
        s3ss10n/src/test/java/com/niki914/s3ss10n/McpTypesTest.kt
git commit -m "feat: add McpTypes placeholder (McpRegistry, McpServerConfig, McpTransport)"
```

---

### Task 6: SessionConfig 类型定义

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/SessionConfig.kt`

- [ ] **Step 1: 创建 SessionConfig.kt**

```kotlin
package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.ToolDefinition
import com.niki914.s3ss10n.chat.protocol.beans.Message

class SessionConfig {
    var endpoint: String = ""
    var apiKey: String = ""
    var model: String = ""
    var systemPrompt: String? = null
    var temperature: Float = 0.7f
    var connectTimeoutSeconds: Long = 30
    var readTimeoutSeconds: Long = 60
    var writeTimeoutSeconds: Long = 30

    // Internal — populated by DSL methods
    internal var hooksBlock: (suspend ToolCallRequest.() -> Message.Tool)? = null
    internal val localToolRegistry = LocalToolRegistryImpl()
    internal val mcpRegistry = McpRegistryImpl()

    fun hooks(block: suspend ToolCallRequest.() -> Message.Tool) {
        hooksBlock = block
    }

    fun localTools(block: LocalToolRegistry.() -> Unit) {
        localToolRegistry.apply(block)
    }

    fun mcp(block: McpRegistry.() -> Unit) {
        mcpRegistry.apply(block)
    }

    internal fun buildToolDefinitions(): List<ToolDefinition> =
        localToolRegistry.toToolDefinitions()

    internal fun buildAppParams(): Map<String, Any?> = emptyMap()
}
```

- [ ] **Step 2: 创建 smoke test**

创建 `s3ss10n/src/test/java/com/niki914/s3ss10n/SessionConfigTest.kt`:

```kotlin
package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.protocol.beans.Message

fun main() {
    println("=== SessionConfig Smoke Test ===")

    // Default values
    val cfg = SessionConfig()
    assertOrPrint("default endpoint empty", cfg.endpoint == "")
    assertOrPrint("default temperature 0.7", cfg.temperature == 0.7f)
    assertOrPrint("default connectTimeout 30", cfg.connectTimeoutSeconds == 30L)
    assertOrPrint("default readTimeout 60", cfg.readTimeoutSeconds == 60L)
    assertOrPrint("default writeTimeout 30", cfg.writeTimeoutSeconds == 30L)

    // Property assignment (simulates Session.open {} DSL)
    cfg.apply {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test"
        model = "gpt-4.1-mini"
        systemPrompt = "You are helpful."
        temperature = 0.5f
    }
    assertOrPrint("endpoint set", cfg.endpoint == "https://api.openai.com/v1/chat/completions")
    assertOrPrint("model set", cfg.model == "gpt-4.1-mini")
    assertOrPrint("temperature set", cfg.temperature == 0.5f)

    // localTools DSL
    cfg.localTools {
        add("toast") {
            description = "显示提示"
            string("message") { required = true }
        }
    }
    val defs = cfg.buildToolDefinitions()
    assertOrPrint("localTools -> ToolDefinition", defs.size == 1)
    assertOrPrint("tool name", defs[0].function.name == "toast")

    // hooks DSL
    cfg.hooks { call ->
        ok("""{"handled":true}""")
    }
    assertOrPrint("hooks set", cfg.hooksBlock != null)

    // mcp DSL (placeholder)
    cfg.mcp {
        add("aslocate") {
            http { url = "http://127.0.0.1:51338/mcp" }
        }
    }
    assertOrPrint("mcp servers", cfg.mcpRegistry.servers.size == 1)

    println("=== ALL PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
```

- [ ] **Step 3: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/SessionConfig.kt \
        s3ss10n/src/test/java/com/niki914/s3ss10n/SessionConfigTest.kt
git commit -m "feat: add SessionConfig with hooks/localTools/mcp DSL"
```

---

### Task 7: Session 接口定义

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/Session.kt`

- [ ] **Step 1: 创建 Session.kt**

```kotlin
package com.niki914.s3ss10n

interface Session {
    suspend fun send(
        text: String,
        onEvent: (SessionEvent) -> Unit = {}
    )

    suspend fun resetConversation()

    suspend fun close()

    companion object {
        fun open(block: SessionConfig.() -> Unit): Session {
            return SessionImpl(SessionConfig().apply(block))
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/Session.kt
git commit -m "feat: add Session interface with open/send/resetConversation/close"
```

---

### Task 8: SessionImpl — Session 实现（核心）

**Files:**
- Create: `s3ss10n/src/main/java/com/niki914/s3ss10n/SessionImpl.kt`
- Modify: `s3ss10n/src/main/java/com/niki914/s3ss10n/Config.kt` (增 temperature)
- Modify: `s3ss10n/src/main/java/com/niki914/s3ss10n/ChatClient.kt` (增 temperature)
- Modify: `s3ss10n/src/main/java/com/niki914/s3ss10n/chat/protocol/ChatApiRequestBody.kt` (增 temperature)

- [ ] **Step 1: 在 ChatApiRequestBody 中增加 temperature 字段**

修改 `s3ss10n/src/main/java/com/niki914/s3ss10n/chat/protocol/ChatApiRequestBody.kt`。

当前:
```kotlin
data class ChatApiRequestBody(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<Message>,
    @SerializedName("tools") val tools: List<ToolDefinition>? = null
) {
    @SerializedName("stream")
    val stream: Boolean = true
}
```

改为:
```kotlin
data class ChatApiRequestBody(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<Message>,
    @SerializedName("tools") val tools: List<ToolDefinition>? = null,
    @SerializedName("temperature") val temperature: Float? = null
) {
    @SerializedName("stream")
    val stream: Boolean = true
}
```

- [ ] **Step 2: 在 Config + ConfigBuilder + ChatClient 中传递 temperature**

修改 `s3ss10n/src/main/java/com/niki914/s3ss10n/Config.kt`，在构造参数末尾增加:
```kotlin
val temperature: Float? = null
```

修改 `s3ss10n/src/main/java/com/niki914/s3ss10n/util/ConfigBuilder.kt`，在 `var callTimeout: Long = 30L` 后增加:
```kotlin
var temperature: Float? = null
```

在 `ConfigBuilder.build()` 返回 `Config(...)` 中增加:
```kotlin
temperature = temperature,
```

在 `ConfigBuilder.Companion.fromConfig()` 中增加:
```kotlin
temperature = config.temperature
```

修改 `s3ss10n/src/main/java/com/niki914/s3ss10n/ChatClient.kt`，`performStream` 方法中 `ChatApiRequestBody` 构造增加:
```kotlin
temperature = config.temperature
```

- [ ] **Step 3: 创建完整的 SessionImpl.kt**

```kotlin
package com.niki914.s3ss10n

import com.niki914.s3ss10n.chat.AIContent
import com.niki914.s3ss10n.chat.protocol.ToolCall
import com.niki914.s3ss10n.chat.protocol.beans.Message
import com.niki914.s3ss10n.toolbase.ToolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class SessionImpl(
    private val config: SessionConfig
) : Session, ChatSession.Callback {

    private var userOnEvent: ((SessionEvent) -> Unit)? = null
    private var currentInput: String = ""
    private val textAccumulator = StringBuilder()

    private val scope = CoroutineScope(SupervisorJob())

    private val toolManager = ToolManager()

    private val chatSession: ChatSession = ChatSession(
        baseUrl = config.endpoint,
        apiKey = config.apiKey,
        modelName = config.model,
        prompt = config.systemPrompt,
        tools = config.buildToolDefinitions().ifEmpty { null }
    ).apply {
        callback = this@SessionImpl
    }

    override suspend fun send(text: String, onEvent: (SessionEvent) -> Unit) {
        userOnEvent = onEvent
        currentInput = text
        applyConfig()
        chatSession.sendMessage(text)
    }

    override suspend fun resetConversation() {
        chatSession.reset()
    }

    override suspend fun close() {
        scope.cancel()
    }

    private fun applyConfig() {
        chatSession.updateConfig {
            baseUrl = config.endpoint
            apiKey = config.apiKey
            modelName = config.model
            prompt = config.systemPrompt
            temperature = config.temperature
            readTimeout = config.readTimeoutSeconds
            connectTimeout = config.connectTimeoutSeconds
            writeTimeout = config.writeTimeoutSeconds
            tools = config.buildToolDefinitions().ifEmpty { null }
        }
    }

    // --- ChatSession.Callback implementation ---

    override fun onConfigInvalid() {
        userOnEvent?.invoke(
            SessionEvent.Error(
                stage = SessionEvent.Stage.Session,
                message = "Config is invalid. Set endpoint and model first."
            )
        )
    }

    override fun onStarted() {
        textAccumulator.clear()
        userOnEvent?.invoke(
            SessionEvent.RoundStarted(input = currentInput)
        )
    }

    override fun onUpdated() {
        // No-op for MVP
    }

    override fun onContent(aiContent: AIContent) {
        when (aiContent) {
            is AIContent.Text -> {
                textAccumulator.append(aiContent.content)
                userOnEvent?.invoke(
                    SessionEvent.TextDelta(
                        delta = aiContent.content,
                        fullText = textAccumulator.toString()
                    )
                )
            }
            is AIContent.Else -> { /* ignore */ }
        }
    }

    override fun onError(message: String, cause: Throwable?) {
        userOnEvent?.invoke(
            SessionEvent.Error(
                stage = SessionEvent.Stage.Transport,
                message = message,
                cause = cause
            )
        )
    }

    override suspend fun onToolCall(toolCall: ToolCall): Message.Tool {
        val request = buildToolCallRequest(toolCall)

        userOnEvent?.invoke(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = config.hooksBlock
        return if (hooks != null) {
            val result = request.hooks()
            if ("error" in result.content.lowercase()) {
                userOnEvent?.invoke(
                    SessionEvent.ToolFailed(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        message = result.content,
                        resultJson = result.content
                    )
                )
            } else {
                userOnEvent?.invoke(
                    SessionEvent.ToolSucceeded(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        resultJson = result.content
                    )
                )
            }
            result
        } else {
            userOnEvent?.invoke(
                SessionEvent.ToolFailed(
                    callId = request.id,
                    toolName = request.name,
                    kind = request.kind,
                    message = "No hooks configured"
                )
            )
            Message.Tool(
                toolCallId = request.id,
                name = request.name,
                content = """{"error":"No hooks configured"}"""
            )
        }
    }

    override fun onCompleted(isSuccess: Boolean, cause: Throwable?) {
        if (isSuccess) {
            userOnEvent?.invoke(
                SessionEvent.RoundCompleted(fullText = textAccumulator.toString())
            )
        } else {
            userOnEvent?.invoke(
                SessionEvent.Error(
                    stage = SessionEvent.Stage.Session,
                    message = "Round failed",
                    cause = cause
                )
            )
        }
    }

    private fun buildToolCallRequest(toolCall: ToolCall): ToolCallRequest {
        return LocalToolCallRequest(
            toolCall = toolCall,
            toolManager = toolManager,
            appParams = config.buildAppParams()
        )
    }
}
```

- [ ] **Step 4: 编译验证**

使用 MCP aslocate 工具检查 SessionImpl.kt 和其他修改文件的编译错误，用 Gradle 编译:

```bash
cd /Users/niki/.repo/android/new_S3ss10n && ./gradlew :s3ss10n:compileDebugKotlin 2>&1 | tail -50
```

- [ ] **Step 5: 创建 SessionImpl smoke test**

创建 `s3ss10n/src/test/java/com/niki914/s3ss10n/SessionImplTest.kt`:

```kotlin
package com.niki914.s3ss10n

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== SessionImpl Smoke Test ===")

    // Create Session with full config
    val session = Session.open {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test"
        model = "gpt-4.1-mini"
        systemPrompt = "You are a test assistant."
        temperature = 0.5f

        hooks { call ->
            println("  hooks called: name=${call.name}, id=${call.id}")
            ok("""{"status":"ok"}""")
        }

        localTools {
            add("test_tool") {
                description = "A test tool"
                string("param1") { required = true }
            }
        }

        mcp {
            add("test_mcp") {
                http { url = "http://localhost:9999/mcp" }
            }
        }
    }

    println("Session created: $session")

    // Test send (will fail because no real endpoint, but should emit Error event)
    val events = mutableListOf<SessionEvent>()
    session.send("Hello") { event ->
        events.add(event)
        println("  Event: ${event.javaClass.simpleName}")
    }

    println("Events received: ${events.size}")
    events.forEach { event ->
        when (event) {
            is SessionEvent.RoundStarted -> println("  -> RoundStarted: input=${event.input}")
            is SessionEvent.TextDelta -> println("  -> TextDelta: delta=${event.delta}, full=${event.fullText}")
            is SessionEvent.ToolRunning -> println("  -> ToolRunning: ${event.toolName}")
            is SessionEvent.ToolSucceeded -> println("  -> ToolSucceeded: ${event.toolName}")
            is SessionEvent.ToolFailed -> println("  -> ToolFailed: ${event.toolName}: ${event.message}")
            is SessionEvent.RoundCompleted -> println("  -> RoundCompleted: ${event.fullText}")
            is SessionEvent.Error -> println("  -> Error[${event.stage}]: ${event.message}")
        }
    }

    // Test resetConversation
    session.resetConversation()
    println("resetConversation called")

    // Test close
    session.close()
    println("close called")

    println("=== ALL PASSED ===")
}
```

- [ ] **Step 6: 提交**

```bash
git add s3ss10n/src/main/java/com/niki914/s3ss10n/SessionImpl.kt \
        s3ss10n/src/main/java/com/niki914/s3ss10n/Config.kt \
        s3ss10n/src/main/java/com/niki914/s3ss10n/util/ConfigBuilder.kt \
        s3ss10n/src/main/java/com/niki914/s3ss10n/ChatClient.kt \
        s3ss10n/src/main/java/com/niki914/s3ss10n/chat/protocol/ChatApiRequestBody.kt \
        s3ss10n/src/test/java/com/niki914/s3ss10n/SessionImplTest.kt
git commit -m "feat: add SessionImpl wrapping ChatSession with full event mapping"
```

---

### Task 9: 重写 DemoChatViewModel 使用新 Session API

**Files:**
- Modify: `app/src/main/java/com/niki914/demo/DemoChatViewModel.kt`

- [ ] **Step 1: 重写 DemoChatViewModel**

```kotlin
package com.niki914.demo

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.niki914.composebase.ComposeMVIViewModel
import com.niki914.s3ss10n.ChatPair
import com.niki914.s3ss10n.Session
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.ToolCallKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ChatState(
    val pairs: List<ChatPair>,
    val isGenerating: Boolean,
    val config: SessionConfig
)

sealed interface ChatIntent {
    data class Send(val msg: String) : ChatIntent
    data class SetConfig(
        val block: (SessionConfig.() -> Unit)
    ) : ChatIntent
    data object NewRoom : ChatIntent
}

sealed interface ChatEffect {
    data object ConfigUnset : ChatEffect
    data class ErrorOccurred(val message: String) : ChatEffect
    data object NewRoomCreated : ChatEffect
}

class ChatViewModel
    : ComposeMVIViewModel<ChatIntent, ChatState, ChatEffect>() {

    private var session: Session? = null

    val uiState: ChatState
        @Composable
        get() = uiStateFlow.collectAsStateWithLifecycle().value

    override fun initUiState(): ChatState {
        return ChatState(
            pairs = emptyList(),
            isGenerating = false,
            config = SessionConfig()
        )
    }

    override suspend fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SetConfig -> {
                intent.block(currentState.config)
                session = Session.open {
                    // Copy current config
                    endpoint = currentState.config.endpoint
                    apiKey = currentState.config.apiKey
                    model = currentState.config.model
                    systemPrompt = currentState.config.systemPrompt
                    temperature = currentState.config.temperature

                    hooks { call ->
                        when (call.kind) {
                            ToolCallKind.Local -> {
                                // Handle local tools
                                if (call.name == "send_toast") {
                                    ok("""{"shown":true}""")
                                } else {
                                    error("Unknown tool: ${call.name}")
                                }
                            }
                            is ToolCallKind.Mcp -> {
                                error("MCP not supported yet")
                            }
                        }
                    }

                    localTools {
                        add("send_toast") {
                            description = "Send a Toast notification to the user's device."
                            string("message") {
                                description = "The message you'd like to tell the user."
                                required = true
                            }
                        }
                    }
                }
            }

            is ChatIntent.Send -> {
                val s = session ?: run {
                    sendEffect(ChatEffect.ConfigUnset)
                    return
                }
                updateState { copy(isGenerating = true) }
                s.send(intent.msg) { event ->
                    when (event) {
                        is SessionEvent.RoundStarted -> {
                            updateState { copy(isGenerating = true) }
                        }
                        is SessionEvent.TextDelta -> {
                            // UI updates via history polling
                        }
                        is SessionEvent.ToolRunning -> {
                            println("Tool running: ${event.toolName}")
                        }
                        is SessionEvent.ToolSucceeded -> {
                            println("Tool succeeded: ${event.toolName}")
                        }
                        is SessionEvent.ToolFailed -> {
                            println("Tool failed: ${event.toolName} - ${event.message}")
                        }
                        is SessionEvent.RoundCompleted -> {
                            updateState { copy(isGenerating = false) }
                        }
                        is SessionEvent.Error -> {
                            updateState { copy(isGenerating = false) }
                            sendEffect(ChatEffect.ErrorOccurred(event.message))
                        }
                    }
                }
            }

            is ChatIntent.NewRoom -> {
                session?.resetConversation()
                sendEffect(ChatEffect.NewRoomCreated)
                updateState {
                    copy(pairs = emptyList(), isGenerating = false)
                }
            }
        }
    }
}
```

- [ ] **Step 2: 移除 DemoChatViewModel 对旧 API 的引用**

确认不再 import `ChatSession`, `ConfigBuilder`, `ToolManager`, `DemoToastModel`（如存在旧引用）。

- [ ] **Step 3: 编译验证**

```bash
cd /Users/niki/.repo/android/new_S3ss10n && ./gradlew :app:compileDebugKotlin 2>&1 | tail -50
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/niki914/demo/DemoChatViewModel.kt
git commit -m "refactor: rewrite DemoChatViewModel to use new Session API with localTools DSL"
```

---

### Task 10: 集成烟测 — 完整生命周期测试

**Files:**
- Create: `s3ss10n/src/test/java/com/niki914/s3ss10n/IntegrationTest.kt`

- [ ] **Step 1: 创建集成测试**

```kotlin
package com.niki914.s3ss10n

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Integration Smoke Test ===")
    println()

    // Test 1: Session creation with all DSL features
    println("--- Test 1: Session.open with full DSL ---")
    val session = Session.open {
        endpoint = "https://api.openai.com/v1/chat/completions"
        apiKey = "sk-test-key"
        model = "gpt-4.1-mini"
        systemPrompt = "You are a test assistant."
        temperature = 0.3f
        connectTimeoutSeconds = 10
        readTimeoutSeconds = 20
        writeTimeoutSeconds = 10

        hooks { call ->
            println("    hooks invoked: name=${call.name}, kind=${call.kind}")
            when (call.name) {
                "toast" -> ok("""{"shown":true}""")
                else -> delegate()
            }
        }

        localTools {
            add("toast") {
                description = "Show a toast message"
                string("message") {
                    description = "The message to display"
                    required = true
                }
                integer("duration") {
                    description = "Duration in ms"
                }
            }
            add("setVolume") {
                description = "Set audio volume"
                integer("level") {
                    description = "Volume level 0-100"
                    required = true
                }
                boolean("speakBack") {
                    description = "Whether to speak back"
                }
            }
        }

        mcp {
            add("aslocate") {
                http { url = "http://127.0.0.1:51338/mcp" }
            }
        }
    }
    println("  Session created: OK")

    // Test 2: send with bad config (will get Error event)
    println()
    println("--- Test 2: send() with invalid endpoint ---")
    val events2 = mutableListOf<SessionEvent>()
    try {
        session.send("Hello, world!") { event ->
            events2.add(event)
            val name = event.javaClass.simpleName
            println("  Event: $name")
        }
    } catch (e: Exception) {
        println("  Exception: ${e.message}")
    }

    val hasError = events2.any { it is SessionEvent.Error }
    println("  Has error event: $hasError")
    assertOrPrint("send with bad config emits Error", hasError)

    // Test 3: resetConversation
    println()
    println("--- Test 3: resetConversation ---")
    session.resetConversation()
    println("  resetConversation: OK")

    // Test 4: close
    println()
    println("--- Test 4: close ---")
    session.close()
    println("  close: OK")

    // Test 5: SessionConfig property types
    println()
    println("--- Test 5: Config property validation ---")
    val cfg = SessionConfig().apply {
        endpoint = "https://test.example.com/v1/chat"
        apiKey = "sk-abc123"
        model = "test-model"
        systemPrompt = "Be helpful."
        temperature = 0.8f
        connectTimeoutSeconds = 15
        readTimeoutSeconds = 45
        writeTimeoutSeconds = 15
    }
    assertOrPrint("endpoint", cfg.endpoint == "https://test.example.com/v1/chat")
    assertOrPrint("apiKey", cfg.apiKey == "sk-abc123")
    assertOrPrint("model", cfg.model == "test-model")
    assertOrPrint("systemPrompt", cfg.systemPrompt == "Be helpful.")
    assertOrPrint("temperature", cfg.temperature == 0.8f)
    assertOrPrint("connectTimeout", cfg.connectTimeoutSeconds == 15L)
    assertOrPrint("readTimeout", cfg.readTimeoutSeconds == 45L)
    assertOrPrint("writeTimeout", cfg.writeTimeoutSeconds == 15L)

    println()
    println("=== ALL INTEGRATION TESTS PASSED ===")
}

fun assertOrPrint(name: String, condition: Boolean) {
    if (condition) println("  PASS: $name")
    else println("  FAIL: $name")
}
```

- [ ] **Step 2: 提交**

```bash
git add s3ss10n/src/test/java/com/niki914/s3ss10n/IntegrationTest.kt
git commit -m "test: add integration smoke test for full Session lifecycle"
```

---

### Task 11: 最终编译验证与清理

- [ ] **Step 1: 全量编译**

```bash
cd /Users/niki/.repo/android/new_S3ss10n && ./gradlew :s3ss10n:compileDebugKotlin :app:compileDebugKotlin 2>&1 | tail -60
```

- [ ] **Step 2: 检查编译错误 — 使用 MCP aslocate 工具**

用 aslocate 的 error 工具检查每个修改过的文件有无编译错误。

- [ ] **Step 3: 修复所有编译错误**

逐个修复，每次修复后重新编译验证。

- [ ] **Step 4: 提交最终修复**

```bash
git add -A
git commit -m "fix: resolve compilation errors from Session API refactor"
```

---

## 执行顺序

Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7 → Task 8 → Task 9 → Task 10 → Task 11

依序执行，因为后续 Task 的类型依赖前面 Task 定义的类型。Task 8 是最核心的适配层，完成后即可在 Task 9 中验证集成。
