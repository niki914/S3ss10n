## 1. 引入 ToolCallOutcome 内部类型

- [ ] 1.1 在 `ToolCallRequest.kt` 同文件内新增 `internal sealed interface ToolCallOutcome { val message: Message.Tool }`
  - `data class Success(override val message: Message.Tool, val resultJson: String) : ToolCallOutcome`
  - `data class Failure(override val message: Message.Tool, val errorMessage: String, val resultJson: String?) : ToolCallOutcome`
- [ ] 1.2 在 `LocalToolCallRequest` / `McpToolCallRequest` 各添加 `internal var lastOutcome: ToolCallOutcome? = null`

## 2. 改造 ToolCallRequest 接口与实现

- [ ] 2.1 在 `sealed interface ToolCallRequest` 新增 `val appParams: Map<String, Any?>`
- [ ] 2.2 `LocalToolCallRequest` 构造增加 `override val appParams: Map<String, Any?>` 参数
- [ ] 2.3 `McpToolCallRequest` 构造增加 `override val appParams: Map<String, Any?>` 参数
- [ ] 2.4 `LocalToolCallRequest.delegate()` 重写为：调用 `error("Local tool '$name' has no built-in implementation. Handle it in hooks { ... }.")`
- [ ] 2.5 `LocalToolCallRequest.ok(json)` / `error(msg, json)` 在返回 `Message.Tool` 之前先把 `lastOutcome` 设为对应 Success / Failure
- [ ] 2.6 `McpToolCallRequest.ok` / `error` 同理
- [ ] 2.7 删除 `LocalToolCallRequest` 构造参数 `toolManager: ToolManager`（已没有用）

## 3. 删除 toolbase/ 老体系

- [ ] 3.1 删除 `s3ss10n/toolbase/ToolManager.kt`
- [ ] 3.2 删除 `s3ss10n/toolbase/ToolModel.kt`
- [ ] 3.3 删除 `s3ss10n/toolbase/ToolCallJsonTransformLayer.kt`
- [ ] 3.4 如 `toolbase/` 目录已空，保留空目录或一并删除
- [ ] 3.5 全局搜索确认无 `import com.niki914.s3ss10n.toolbase.*` 残留

## 4. 改造 ChatSession.handleToolCall

- [ ] 4.1 删除 `ChatSession` 字段 `private val toolManager = ToolManager()`
- [ ] 4.2 `buildToolCallRequest` 改为：根据 ChatEvent.ToolCallIntent 来源（当前都是 Local，T4 之后 MCP）构造对应 request；从 round-scoped snapshot 取 `appParamsSnapshot()` 注入
- [ ] 4.3 `handleToolCall(toolCall: ToolCall): Message.Tool` 改造：
  - 发 `ToolRunning`
  - 若 `hooks == null` → 发 `ToolFailed(stage 隐含 Tool)` + 同时发一个 `SessionEvent.Error(stage = Tool, message = "no hooks configured")`；返回 fallback `Message.Tool`
  - 否则 `try { request.hooks() } catch (t: Throwable) { /* T7 后改 xTry */ ... }`
  - 抛异常分支：发 `ToolFailed` + `Error(stage=Tool, cause=t)`；返回 `request.error(t.message ?: "hooks threw")`
  - 正常返回分支：读 `request.lastOutcome`：Success → `ToolSucceeded`；Failure → `ToolFailed`；null → 视为 Failure（hooks 没用 ok/error 直接 new 一个 Message.Tool）
- [ ] 4.4 删除 `if ("error" in result.content.lowercase())` 这行字符串判定

## 5. 修复 SessionEvent.Stage.Tool / Parse 缺失

- [ ] 5.1 hooks 异常路径发 `Error(stage = Tool, ...)`
- [ ] 5.2 无 hooks 路径发 `Error(stage = Tool, ...)`
- [ ] 5.3 `SseToChatTransformLayer` 的 frame parse 失败路径中，`ChatEvent.Error` 对应映射到 `Stage.Parse`（在 ChatSession 的 Error 分支增加按 cause 类型分流：`is JsonSyntaxException || ... → Parse`，否则 Transport）

## 6. 烟测

- [ ] 6.1 `ToolCallRequestTest.kt` 新增 "appParams accessible" 测试
- [ ] 6.2 `ToolCallRequestTest.kt` 新增 "ok records Success outcome" 测试
- [ ] 6.3 `ToolCallRequestTest.kt` 新增 "error records Failure outcome" 测试
- [ ] 6.4 `ToolCallRequestTest.kt` 新增 "ok content with substring 'error' still Success" 测试（覆盖旧 Bug 回归）
- [ ] 6.5 `LocalToolRegistryTest.kt` 维持不变（schema 来源不变）
- [ ] 6.6 删除任何 `ToolManagerTest` / `ToolModelTest`（如果存在）

## 7. 编译与回归

- [ ] 7.1 `:s3ss10n:compileDebugKotlin` 通过
- [ ] 7.2 `:app:compileDebugKotlin` 通过
- [ ] 7.3 运行所有 smoketest main() 全 PASS
