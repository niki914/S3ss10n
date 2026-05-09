## 1. 内联 ChatClient 到 ChatSession

- [ ] 1.1 把 `ChatClient.isConfigValid()` 逻辑搬到 ChatSession 私有方法 `private fun isConfigValid(snap: SessionConfig): Boolean`
- [ ] 1.2 把 `ChatClient.sendMessages(messages, includeSystemPrompt)` 逻辑搬到 ChatSession 私有方法 `private fun streamRequest(snap: SessionConfig, messages: List<Message>): Flow<ChatEvent>`
- [ ] 1.3 把 `ChatClient.systemMessage` 逻辑内联到 streamRequest
- [ ] 1.4 把 `ChatClient.performStream` 的 ConfigInvalidException 短路逻辑内联
- [ ] 1.5 ChatSession 直接持有 `private val clientManager = OkhttpClientManager(...)` 和 `private val service by lazy { ChatService(clientManager.okHttpClient) }`
- [ ] 1.6 删除 `ChatClient.kt` 文件
- [ ] 1.7 全局搜索确认无 `import com.niki914.s3ss10n.ChatClient` 残留

## 2. 引入 RoundContext

- [ ] 2.1 在 `ChatSession.kt` 同文件内新增 `private class RoundContext(val configSnapshot: SessionConfig, val onEvent: (SessionEvent) -> Unit, val initialInput: String, val textAccumulator: StringBuilder = StringBuilder())`
- [ ] 2.2 删除字段：`var sessionConfig: SessionConfig?`、`var userOnEvent: ((SessionEvent) -> Unit)?`、`var currentInput: String`、`val textAccumulator: StringBuilder`
- [ ] 2.3 改造 `send(text, onEvent)` 入口：`val ctx = RoundContext(configRef.get().snapshot(), onEvent, text)`，然后 `runRound(ctx, userInput = text)`
- [ ] 2.4 改造 `sendMessage(userMsg)` / `sendMessage(message: Message.User?)` 为 `runRound(ctx, userInput: String?)`，所有内部方法签名增加 `ctx: RoundContext`
- [ ] 2.5 `handleToolCall(ctx, toolCall)` 接收 ctx，从 ctx 读 hooks / appParams
- [ ] 2.6 `responseToolCalls(ctx)` 接收 ctx，递归 runRound 复用同一 ctx（保证 fullText 跨轮）
- [ ] 2.7 ToolCallWaiter 的 lambda 持有 ctx 引用：`ToolCallWaiter(scope) { toolCall -> handleToolCall(currentCtx, toolCall) }` —— 这里需要把 ToolCallWaiter 改造为支持 per-round 注入 ctx，或保留单 lambda 但让 ChatSession 保存"当前 round 的 ctx"作为短期 race-free 字段（仅 send mutex 内有效）
- [ ] 2.8 评估方案：ToolCallWaiter 内部维护 `var currentRoundCtx: RoundContext?`，在 send 入口设置、cleanUpCurrWork 清空；或重构 ToolCallWaiter 为按 ctx 创建实例（每个 send 一个 waiter）

## 3. 收敛 ChatSession 构造函数

- [ ] 3.1 删除 `ChatSession()` 无参构造
- [ ] 3.2 删除 `ChatSession(baseUrl, apiKey, modelName, prompt, tools)` 多参构造
- [ ] 3.3 主构造函数改为 `internal constructor(initialConfig: SessionConfig)`
- [ ] 3.4 构造函数内做：`configRef = AtomicReference(initialConfig)`，初始化 `clientManager`（接 T1 的 `() -> SessionConfig` 取值器，从 configRef 取最新）

## 4. 删除 PRD 外溢能力

- [ ] 4.1 `Session.kt` 接口删除 `suspend fun getHistory(): List<ChatPair>`
- [ ] 4.2 `ChatSession` 删除 override `getHistory()`
- [ ] 4.3 `ChatSession` 删除 `fun preConnect()`
- [ ] 4.4 `ChatService` 删除 `fun preConnect()`
- [ ] 4.5 全局搜索确认无 `getHistory()` 调用残留（demo 代码同步修改）

## 5. ChatPair 退化为 internal

- [ ] 5.1 `ChatPair.kt`：`class ChatPair` → `internal class ChatPair`
- [ ] 5.2 `ChatPair.RoundState` enum 同步标记 internal
- [ ] 5.3 `ChatPair.Companion.newPendingPair` 同步 internal
- [ ] 5.4 任何 `:app` 中的 ChatPair 引用替换为基于 SessionEvent 自累积的 UI 模型

## 6. demo 适配

- [ ] 6.1 `DemoChatViewModel` 的 `ChatState.pairs` 改为基于 SessionEvent 自累积（用一个本地的 UI 数据模型，不再读 Session.getHistory）
- [ ] 6.2 删除任何对 `Session.getHistory()` 的调用

## 7. 烟测

- [ ] 7.1 删除或修改 `SessionImplTest.kt` 中调用 `getHistory()` 的部分
- [ ] 7.2 `IntegrationTest.kt` 删除 getHistory 调用
- [ ] 7.3 新增 `RoundContextIsolationTest.kt`：mock 两次串行 send，验证 onEvent 不会跨 round 漏触发（如果烟测难以仿真，可暂用单元测试 placeholder）

## 8. 编译与回归

- [ ] 8.1 `:s3ss10n:compileDebugKotlin` 通过
- [ ] 8.2 `:app:compileDebugKotlin` 通过
- [ ] 8.3 运行所有 smoketest main() 全 PASS
