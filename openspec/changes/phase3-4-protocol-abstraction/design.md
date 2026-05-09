## Context

T3 完成后 ChatSession 形态固定（单一构造函数、RoundContext 化、无 ChatClient）。现在动协议层是安全的——所有 OpenAI 形态都内聚在 ChatSession 调用的几个点（构建请求体、解析 SSE 流、拼接 toolCall delta、维护历史）。

PRD §7 的形态：

```kotlin
val session = Session.open<SessionProtocols.OpenAI> { /* SessionConfig DSL */ }
session.send("hi") { event -> ... }
```

协议泛型绑定在 `open` 上的语义是：一个 session 一旦 open 就锁定协议，期间 update 只改配置不改协议。这与 PRD `update {}` 的"动态拔插 tool / hooks / appParams"语义不冲突。

本任务的边界要小心：
- **不抽 JSON / HTTP**：T5/T6 才做。OpenAIProtocol 现在可以直接 `import com.google.gson.Gson` —— 这是合法过渡态。
- **不动 update 语义**：T1 已经定义；本任务只是把 update 之外的"协议字段"移出 SessionConfig（如果有的话——目前没有，所以零额外改动）。
- **保留 toolCall 拼接逻辑**：用户已确认拼接频次低，整体迁移到 OpenAIProtocol 内部即可。

## Goals / Non-Goals

**Goals:**
- 抽 ChatProtocol 接口
- OpenAIProtocol 作为内置实现，承接现有所有 OpenAI 硬编码
- Session.open 泛型化
- ChatTurn 取代 OpenAI 形态 Message，作为 ChatSession ↔ ChatProtocol 的中性数据契约
- HistoryKeeper 改用 ChatTurn
- getHistory(): List<ChatTurn> 重新加回

**Non-Goals:**
- 第二个协议实现（Anthropic / Google）
- JSON / HTTP 抽象
- xTry / xLog 落地（T7）
- 协议级别的 update（不在 PRD）

## Decisions

### Decision 1: ChatProtocol 接口最小职责

**选择**：

```kotlin
interface ChatProtocol {
    /** 构建 HTTP 请求体（JSON 字节）+ headers/path 增量。当前阶段返回 String JSON 即可，T6 抽 HttpEngine 时再改值对象 */
    fun buildRequestBody(snapshot: SessionConfig, history: List<ChatTurn>, pendingUserInput: String?): String

    /** 把原始 SSE 数据流（每行 String）转成中性的 ProtocolEvent 流；toolCall delta 拼接在此实现内部完成 */
    fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent>

    /** 把工具结果编码为下一轮请求时要追加的 ChatTurn.ToolResult；具体编码细节是协议私有的 */
    fun encodeToolResult(callId: String, toolName: String, resultJson: String): ChatTurn.ToolResult
}
```

`ProtocolEvent` 是中性事件流：

```kotlin
sealed interface ProtocolEvent {
    data class TextDelta(val text: String) : ProtocolEvent
    data class ToolCallReady(val callId: String, val toolName: String, val argumentsJson: String) : ProtocolEvent
    data object Completed : ProtocolEvent
    data class Error(val cause: Throwable, val stage: SessionEvent.Stage) : ProtocolEvent
}
```

**原因**：
- ChatSession 不再关心 OpenAI vs Anthropic 的字段差异，只看 ProtocolEvent
- toolCall delta 拼接是协议私有问题（OpenAI 用 index + arguments 增量，Anthropic 用 input_json_delta 等），不能再硬编码在 ChatSession
- `buildRequestBody` 当前返回 String 是对未来 HttpEngine 的让步：T6 引入 HttpRequest 值对象后，签名改为返回 HttpRequest 即可，对外协议形态稳定

**替代方案**：
- 让协议返回完整 OkHttp `Request`：被否，会污染抽象层
- 让协议返回流式 `Flow<SessionEvent>` 而非 `Flow<ProtocolEvent>`：被否，SessionEvent 包含 RoundStarted/RoundCompleted/ToolRunning 这些会话级事件，不该由协议层关心

### Decision 2: ChatTurn 是中性 sealed interface

**选择**：

```kotlin
sealed interface ChatTurn {
    data class System(val content: String) : ChatTurn       // 由 SessionConfig.systemPrompt 生成；HistoryKeeper 不持久化
    data class User(val content: String) : ChatTurn
    data class Assistant(val content: String, val toolCalls: List<ToolCallSpec> = emptyList()) : ChatTurn
    data class ToolResult(val callId: String, val toolName: String, val resultJson: String) : ChatTurn
}

data class ToolCallSpec(val callId: String, val toolName: String, val argumentsJson: String)
```

**原因**：
- 字段只表达"对话语义"，不表达"协议字段名"
- `Assistant.toolCalls` 是中性结构，OpenAIProtocol 编码时映射到 `tool_calls`，Anthropic 协议映射到 `content` 内的 tool_use block
- `argumentsJson` 与 `resultJson` 都用 String 传递——协议自己负责再次解析/再次序列化（T5 抽 JsonCodec 后协议可以拿注入的 codec 再处理）

**替代方案**：
- 直接复用 OpenAI 的 Message：被否，等于没抽
- 用 `Map<String, Any>` 完全无类型：被否，在 IDE 与重构时太脆弱
- 加 `Tool` turn 区分 Assistant（带 toolCalls）vs Tool（结果）：拒绝原因——OpenAI 的 `tool` role 是 ToolResult 的一种表达，Anthropic 是 user role 包 tool_result，统一用 `ToolResult` 更中性

### Decision 3: 协议泛型绑定在 open，运行时持有 ChatProtocol 实例

**选择**：

```kotlin
interface Session {
    suspend fun send(text: String, onEvent: (SessionEvent) -> Unit)
    fun update(builder: SessionConfig.Builder.() -> Unit)
    fun resetConversation()
    fun close()

    companion object {
        inline fun <reified P : ChatProtocol> open(noinline builder: SessionConfig.Builder.() -> Unit): Session {
            val protocol = ProtocolRegistry.resolve(P::class)  // 内部维护 KClass -> ChatProtocol 单例
            val config = SessionConfig.Builder().apply(builder).build()
            return ChatSession(initialConfig = config, protocol = protocol)
        }
    }
}
```

`SessionProtocols.kt`：

```kotlin
object SessionProtocols {
    object OpenAI : ChatProtocol by OpenAIProtocol()  // 或使用 OpenAIProtocol 自身作为 type token
    // 未来 object Anthropic : ChatProtocol by AnthropicProtocol()
}
```

**原因**：
- PRD §7 形态精确支持
- 运行时通过 KClass 查表拿到协议实例；内置协议在 `init` 时注册
- 用户自定义协议可以提供：`ProtocolRegistry.register(MyProtocol::class, MyProtocol())`，再 `Session.open<MyProtocol> {}`
- `reified` 让调用点零成本

**替代方案**：
- `Session.open(protocol: ChatProtocol, builder)`：被否，违反 PRD 形态
- 协议绑在 send 而不是 open：被否，每个 send 切协议会让历史变得不可解释（一段对话用不同协议来读，谁负责转换历史？）

### Decision 4: HistoryKeeper 内部存储 ChatTurn，删除 ChatPair

**选择**：
- `HistoryKeeper` 改成 `private val history: MutableList<ChatTurn>`
- `add(turn: ChatTurn)` / `snapshot(): List<ChatTurn>` / `clear()` / `markErrorOnLastUserTurn()` 等
- 删除 `ChatPair.kt`（T3 已经 internal 化，本任务彻底删除）
- 删除 `ChatPair.RoundState`（其语义由 SessionEvent 承担）

**原因**：
- ChatPair 是"成对存储 user + assistant"的过度结构，对中性历史而言只是噪音
- ChatTurn 是平铺序列，更贴合 OpenAI/Anthropic/Google 等所有协议的 messages 数组形态
- 错误回滚的语义改为：当一轮失败时移除最后一条尚未配对的 User turn

**替代方案**：
- 保留 ChatPair 与 ChatTurn 并存：被否，两份历史会立刻分裂
- 在 HistoryKeeper 内部用 ChatPair 存、对外暴露 ChatTurn 投影：被否，徒增映射成本

### Decision 5: SystemPrompt 的位置

**选择**：
- `SessionConfig.systemPrompt: String?` 不进 HistoryKeeper（不持久化为 ChatTurn）
- `ChatSession.runRound()` 在调用 `protocol.buildRequestBody(snapshot, history, pendingUserInput)` 时把 systemPrompt 通过 `snapshot.systemPrompt` 传入；协议自己决定是否要把它体现为 System turn
- 对外的 `getHistory(): List<ChatTurn>` 不包含 system turn（与 OpenAI 客户端常见行为一致）

**原因**：
- system prompt 是"配置"而非"历史事件"
- update 改 systemPrompt 后，下一轮立即生效（与 PRD update 语义吻合）

**替代方案**：
- 把 systemPrompt 第一次进入 history 时插入：被否，update 改 systemPrompt 后历史会出现两份 system

### Decision 6: ToolResult 何时入 history

**选择**：
- 工具执行完成（无论成功失败）→ ChatSession 调用 `protocol.encodeToolResult(callId, toolName, resultJson)` → 拿到 `ChatTurn.ToolResult` → `historyKeeper.add(turn)`
- 失败时 `resultJson` 是 ToolCallOutcome.Failure 序列化的中性结构（在 T2 中已定义）

**原因**：
- 入 history 的时机点是 ChatSession 控制；编码细节是协议控制——职责清晰
- 协议未来可以选择"只入 message-style 结果"或"入 tool-block-style 结果"，对外行为一致

### Decision 7: OpenAIProtocol 内部仍可直接 import Gson

**选择**：本任务允许 OpenAIProtocol 直接 `import com.google.gson.Gson`，T5 引入 JsonCodec 后再注入。

**原因**：
- 一次只动一层，T4 形态稳定后 T5 替换 codec 是机械操作
- 否则本任务会被 T5 阻塞

### Decision 8: 错误日志暂用 try/catch + android.util.Log

**选择**：本任务新加的 OpenAIProtocol / ChatProtocol 调用路径上的异常处理，暂时写为：

```kotlin
try {
    // ...
} catch (t: Throwable) {
    android.util.Log.e("qwerqwer", "OpenAIProtocol.parseStream failed", t)
    emit(ProtocolEvent.Error(t, SessionEvent.Stage.Parse))
}
```

并在 tasks.md 中记录所有"待 T7 替换为 xTry"的位置。

**原因**：
- X.kt 文件本身在 T7 才落地
- 提前写 `xTry(...)` 会编译失败
- 用户已说明：禁止 try/catch/runCatching 是最终目标，过渡态可接受但必须登记

## Risks / Trade-offs

- **接口可能在 T5/T6 微调**：当 HttpEngine/JsonCodec 引入后，`buildRequestBody` 的返回类型可能由 String 变成 HttpRequest 值对象。这是预期内的小调整，签名不会大改。
- **协议注册表是新概念**：`ProtocolRegistry` 引入了"全局可变"。可接受：内置协议在 SessionProtocols 类加载时注册一次；自定义协议由用户在 Application 启动时注册。同时记得做线程安全（ConcurrentHashMap）。
- **ChatPair 删除可能影响烟测**：smoketest 如果断言 `ChatPair`，需同步迁移；T3 已经把它 internal，影响面有限。

## Open Questions

- ProtocolRegistry 是放 `s3ss10n/protocol/ProtocolRegistry.kt` 还是放在 SessionProtocols 同文件？倾向独立文件，因为它是用户扩展点。
