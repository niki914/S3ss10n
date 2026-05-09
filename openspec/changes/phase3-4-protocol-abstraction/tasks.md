## 1. 新增 ChatTurn 中性历史模型

- [ ] 1.1 新建 `s3ss10n/ChatTurn.kt`，定义 `sealed interface ChatTurn` + 4 个 variant：`User` / `Assistant` / `ToolResult` / `System`
- [ ] 1.2 同文件定义 `data class ToolCallSpec(callId: String, toolName: String, argumentsJson: String)`
- [ ] 1.3 字段命名严格中性，禁止出现 OpenAI 字段名（如 `tool_calls`、`tool_call_id`）
- [ ] 1.4 KDoc 标注：`Assistant.toolCalls` 为空表示纯文本回复

## 2. 新增 ProtocolEvent 中性事件流

- [ ] 2.1 新建 `s3ss10n/protocol/ProtocolEvent.kt`，定义 `sealed interface ProtocolEvent` + 4 个 variant：`TextDelta(text)` / `ToolCallReady(callId, toolName, argumentsJson)` / `Completed` / `Error(cause, stage)`
- [ ] 2.2 `Error.stage` 复用 `SessionEvent.Stage`（Parse / Transport / Tool / Session）

## 3. 新增 ChatProtocol 接口

- [ ] 3.1 新建 `s3ss10n/protocol/ChatProtocol.kt`：

```kotlin
interface ChatProtocol {
    fun buildRequestBody(snapshot: SessionConfig, history: List<ChatTurn>, pendingUserInput: String?): String
    fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent>
    fun encodeToolResult(callId: String, toolName: String, resultJson: String): ChatTurn.ToolResult
}
```

- [ ] 3.2 KDoc 标注：toolCall delta 拼接是协议私有职责
- [ ] 3.3 KDoc 标注：T6 引入 HttpEngine 后，`buildRequestBody` 返回类型可能由 String 改为 HttpRequest 值对象（提示性 TODO）

## 4. 新增 ProtocolRegistry

- [ ] 4.1 新建 `s3ss10n/protocol/ProtocolRegistry.kt`：

```kotlin
object ProtocolRegistry {
    private val map = java.util.concurrent.ConcurrentHashMap<KClass<out ChatProtocol>, ChatProtocol>()
    fun <P : ChatProtocol> register(klass: KClass<P>, instance: P)
    fun resolve(klass: KClass<out ChatProtocol>): ChatProtocol  // 查不到抛 IllegalStateException
}
```

- [ ] 4.2 错误消息要清晰，提示用户调用 `ProtocolRegistry.register(P::class, P())`

## 5. 新增 SessionProtocols 顶层入口

- [ ] 5.1 新建 `s3ss10n/SessionProtocols.kt`：

```kotlin
object SessionProtocols {
    object OpenAI : ChatProtocol by OpenAIProtocol()
    init { ProtocolRegistry.register(OpenAI::class, OpenAI) }
}
```

- [ ] 5.2 评估方案：用 `class OpenAI` 作为类型 token + `init` 注册 `OpenAIProtocol()` 单例；或者用 `object OpenAI : ChatProtocol by OpenAIProtocol()` 让 OpenAI 既是类型 token 也是协议实现。倾向后者，签名更直观

## 6. 新增 OpenAIProtocol 实现

- [ ] 6.1 新建目录 `s3ss10n/protocol/openai/`
- [ ] 6.2 把 `chat/protocol/Message.kt` / `ChatApiRequestBody.kt` / `ChatBeans.kt` 移入 `protocol/openai/` 并改为 internal
- [ ] 6.3 把 `chat/SseToChatTransformLayer.kt` 的核心解析逻辑迁入 `OpenAIProtocol.parseStream(...)`，保留 toolCall 拼接（沿用 `util/ToolCallHandler.kt` 逻辑或直接合并到 OpenAIProtocol）
- [ ] 6.4 `OpenAIProtocol.buildRequestBody(snapshot, history, pendingUserInput)`：
  - 把 `snapshot.systemPrompt` 转成第一条 `{role:"system",content:...}`
  - 把 `history: List<ChatTurn>` 映射为 OpenAI messages 数组（User → user / Assistant → assistant + tool_calls / ToolResult → tool）
  - 拼上 `pendingUserInput`（如果非空）作为最后一条 user
  - 序列化 LocalToolRegistry 的 schema 到 `tools` 字段（沿用现有 schema）
  - 用 Gson 序列化整体（T5 替换为 JsonCodec）
- [ ] 6.5 `OpenAIProtocol.encodeToolResult(callId, toolName, resultJson)` 返回 `ChatTurn.ToolResult(callId, toolName, resultJson)`（neutral 直存即可）
- [ ] 6.6 `OpenAIProtocol.parseStream(...)` 内部异常处理：暂用 `try/catch + android.util.Log.e("qwerqwer", "...", t)` + `emit(ProtocolEvent.Error(t, Stage.Parse))`，同时在文件头加 `// TODO(T7): replace try/catch with xTry`

## 7. 改造 HistoryKeeper

- [ ] 7.1 内部存储 `private val history: MutableList<ChatTurn> = mutableListOf()`
- [ ] 7.2 API 改造：`add(turn: ChatTurn)` / `snapshot(): List<ChatTurn>` / `clear()` / `dropLastIfUserOrphan()`（处理失败回滚）
- [ ] 7.3 删除所有 ChatPair 相关方法
- [ ] 7.4 删除 `ChatPair.kt` 文件
- [ ] 7.5 全局搜索 `ChatPair` 残留并清理

## 8. 改造 ChatSession

- [ ] 8.1 构造函数：`internal constructor(initialConfig: SessionConfig, protocol: ChatProtocol)`，新增 `private val protocol: ChatProtocol`
- [ ] 8.2 `runRound(ctx, userInput)`：
  - 把 userInput 转成 `ChatTurn.User`（或先存到 ctx，由 protocol.buildRequestBody 的 `pendingUserInput` 参数传入；选后者，避免失败时还要回滚 history）
  - 调 `protocol.buildRequestBody(ctx.configSnapshot, historyKeeper.snapshot(), pendingUserInput = userInput)` 拿 String body
  - 把 body 通过 `ChatService` 发出去，得到 `Flow<String>` SSE 行
  - 调 `protocol.parseStream(rawFlow)` 拿 `Flow<ProtocolEvent>`
  - 收集 ProtocolEvent → 翻译为 SessionEvent → 触发 ctx.onEvent
  - 全部成功后：history.add(User(userInput)) + history.add(Assistant(fullText, accumulatedToolCalls))
- [ ] 8.3 ToolCallReady → 触发 hooks → 拿到 ToolCallOutcome → `protocol.encodeToolResult(...)` → history.add(ToolResult) → 递归 runRound(ctx, userInput=null)
- [ ] 8.4 删除所有 `ChatApiRequestBody` / `Message` / `ChatBeans` 直接 import
- [ ] 8.5 异常处理临时沿用 `try/catch + android.util.Log.e("qwerqwer", ...)` —— 在文件头加 `// TODO(T7): replace try/catch with xTry`

## 9. 改造 Session 接口

- [ ] 9.1 `Session.kt` 接口加回 `suspend fun getHistory(): List<ChatTurn>`
- [ ] 9.2 companion object：

```kotlin
companion object {
    inline fun <reified P : ChatProtocol> open(noinline builder: SessionConfig.Builder.() -> Unit): Session {
        val protocol = ProtocolRegistry.resolve(P::class)
        val config = SessionConfig.Builder().apply(builder).build()
        return ChatSession(initialConfig = config, protocol = protocol)
    }
}
```

- [ ] 9.3 `ChatSession.getHistory(): List<ChatTurn>` 实现：从 historyKeeper.snapshot() 过滤掉 System turn 返回

## 10. 删除迁移后的文件

- [ ] 10.1 删除 `s3ss10n/chat/SseToChatTransformLayer.kt`（逻辑已迁移）
- [ ] 10.2 删除 `s3ss10n/chat/protocol/ChatApiRequestBody.kt`（迁到 protocol/openai/internal）
- [ ] 10.3 删除 `s3ss10n/chat/protocol/Message.kt`
- [ ] 10.4 删除 `s3ss10n/chat/protocol/ChatBeans.kt`
- [ ] 10.5 删除 `s3ss10n/util/ToolCallHandler.kt`（拼接逻辑迁入 OpenAIProtocol）
- [ ] 10.6 删除 `s3ss10n/ChatPair.kt`
- [ ] 10.7 全局编译确认无残余 import

## 11. demo 适配

- [ ] 11.1 `DemoActivity` 中如果有 `Session.open { ... }` 调用，改为 `Session.open<SessionProtocols.OpenAI> { ... }`
- [ ] 11.2 `DemoChatViewModel`：恢复 `Session.getHistory()` 调用（如需），结果类型由 `List<ChatPair>` 变 `List<ChatTurn>`，UI 投影方法相应调整

## 12. 烟测

- [ ] 12.1 `SessionImplTest.kt` 全量过：open<SessionProtocols.OpenAI> + send + getHistory 返回 List<ChatTurn>
- [ ] 12.2 新增 `ProtocolAbstractionTest.kt`：mock 一个 minimal `class FakeProtocol : ChatProtocol`，注册到 ProtocolRegistry，`Session.open<FakeProtocol> {}` 跑通
- [ ] 12.3 验证 ToolCall delta 拼接行为没有回归（在 OpenAIProtocol 内部）

## 13. 编译与回归

- [ ] 13.1 `:s3ss10n:compileDebugKotlin` 通过
- [ ] 13.2 `:app:compileDebugKotlin` 通过
- [ ] 13.3 所有 smoketest main() 全 PASS

## 14. 登记 T7 待替换的 try/catch 位置

- [ ] 14.1 在本任务结束后整理一份"待 xTry 化"的清单（文件 + 行号），写入 `phase3-7-cleanup-zephyr-and-smoketest/tasks.md`（T7 写作时引用）
- [ ] 14.2 至少包含：`OpenAIProtocol.parseStream` 异常处理、`ChatSession.runRound` 异常处理、其他 T4 新增代码中的异常处理点
