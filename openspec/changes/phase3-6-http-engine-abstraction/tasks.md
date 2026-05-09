## 1. 新增 HttpRequest / HttpResponse 值对象

- [ ] 1.1 新建 `s3ss10n/net/HttpRequest.kt`：

```kotlin
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMs: HttpTimeouts,
    val isStreaming: Boolean = true,
)

data class HttpTimeouts(
    val connectMs: Long,
    val readMs: Long,
    val writeMs: Long,
)
```

- [ ] 1.2 注意 `data class` 含 ByteArray 的 equals/hashCode 自动生成会用 referential equality，按需 override（或改 `class` + 自定义 equals；当前不需要做 equals 比较，跳过）
- [ ] 1.3 新建 `s3ss10n/net/HttpResponse.kt`（占位，本任务用不到，但为未来 unary 留口子）—— 评估：可不创建，待真正需要时再加

## 2. 新增 HttpEngine 接口

- [ ] 2.1 新建 `s3ss10n/net/HttpEngine.kt`：

```kotlin
interface HttpEngine {
    fun stream(request: HttpRequest): Flow<String>
    fun close()
}
```

- [ ] 2.2 KDoc：`stream` emits SSE payload lines (sans `data: ` prefix); `[DONE]` is consumed internally; cancellation must propagate

## 3. 新增 OkHttpEngine 默认实现

- [ ] 3.1 新建 `s3ss10n/net/OkHttpEngine.kt`，public class
- [ ] 3.2 持有一个 OkHttpClient 实例（构造时一次性建好，不再有"动态配置拦截器"）
- [ ] 3.3 `stream(request)` 实现：
  - 用 `request.timeoutMs` 通过 `okhttp.newBuilder().connectTimeout(...).readTimeout(...).writeTimeout(...).build()` 拷贝出一个临时 client（OkHttp 推荐做法）
  - 构造 `Request.Builder().url(request.url).method(request.method, body).headers(toHeaders(request.headers)).build()`
  - 用 `EventSources.createFactory(client).newEventSource(req, listener)` 启动 SSE
  - listener.onEvent 把 data emit 到 `callbackFlow`
  - listener.onClosed → flow.close()
  - listener.onFailure → flow.close(error)
  - 识别 `data == "[DONE]"` → close 不 emit
  - `awaitClose { eventSource.cancel() }` 处理取消
- [ ] 3.4 `close()`：dispatcher.executorService.shutdown() + connectionPool.evictAll()，幂等
- [ ] 3.5 内部异常处理：`try/catch + android.util.Log.e("qwerqwer", ...)`，文件头加 `// TODO(T7): replace try/catch with xTry`

## 4. 修改 ChatProtocol 接口

- [ ] 4.1 把 `buildRequestBody(snapshot, history, pendingUserInput): String` → `buildRequest(snapshot, history, pendingUserInput): HttpRequest`
- [ ] 4.2 KDoc 更新：协议负责装载 url / headers（含鉴权）/ body / timeout
- [ ] 4.3 spec/protocol-abstraction 同步更新（在 T6 完成后回头修 T4 的 spec.md，加 MODIFIED Requirements 段）—— 在 tasks 中提示

## 5. 修改 OpenAIProtocol.buildRequest 实现

- [ ] 5.1 实现 url 拼接：`"${snapshot.baseUrl.trimEnd('/')}/v1/chat/completions"`
- [ ] 5.2 headers：`mapOf("Authorization" to "Bearer ${snapshot.apiKey}", "Content-Type" to "application/json")`
- [ ] 5.3 body：用 codec.encode(...) 序列化为 JSON String，再 `.toByteArray(Charsets.UTF_8)`
- [ ] 5.4 timeoutMs：`HttpTimeouts(snapshot.connectTimeoutMs, snapshot.readTimeoutMs, snapshot.writeTimeoutMs)`
- [ ] 5.5 isStreaming = true（OpenAI chat completions 走 SSE）

## 6. 修改 ChatSession 走 HttpEngine

- [ ] 6.1 删除 `private val clientManager = OkhttpClientManager(...)` 与 `private val service = ChatService(...)`
- [ ] 6.2 新增 `private val engine: HttpEngine = initialConfig.httpEngine ?: OkHttpEngine()`
- [ ] 6.3 `runRound(ctx, userInput)`：
  - `val req = protocol.buildRequest(ctx.configSnapshot, historyKeeper.snapshot(), userInput)`
  - `val rawFlow: Flow<String> = engine.stream(req)`
  - `val protocolEvents: Flow<ProtocolEvent> = protocol.parseStream(rawFlow)`
  - 收集 protocolEvents 转 SessionEvent → ctx.onEvent
- [ ] 6.4 `close()` 调用 `engine.close()`
- [ ] 6.5 删除所有 OkHttp 直接 import

## 7. 在 SessionConfig 加 httpEngine 入口

- [ ] 7.1 `SessionConfig` 新增 `internal val httpEngine: HttpEngine? = null`
- [ ] 7.2 `SessionConfig.Builder` 暴露 `var httpEngine: HttpEngine? = null`
- [ ] 7.3 KDoc 标注："null 表示使用默认 OkHttpEngine()；自定义 engine 时 OkHttp 不会被实例化"

## 8. 删除拦截器与中间层

- [ ] 8.1 删除 `s3ss10n/util/interceptors/DynamicURLInterceptor.kt`
- [ ] 8.2 删除 `s3ss10n/util/interceptors/DynamicTimeoutInterceptor.kt`
- [ ] 8.3 删除 `s3ss10n/util/interceptors/ChatApiInterceptor.kt`
- [ ] 8.4 删除整个 `s3ss10n/util/interceptors/` 目录（如已无其他文件）
- [ ] 8.5 删除 `s3ss10n/net/OkhttpClientManager.kt`
- [ ] 8.6 删除 `s3ss10n/chat/ChatService.kt`
- [ ] 8.7 删除整个 `s3ss10n/chat/` 目录（如 protocol/openai 已迁出且无残留）
- [ ] 8.8 全文搜索 `okhttp/interceptor/will/update/this`，确保零匹配

## 9. 全局 OkHttp 收口检查

- [ ] 9.1 全文搜索 `import okhttp3`，确认仅 `s3ss10n/net/OkHttpEngine.kt` 一处
- [ ] 9.2 全文搜索 `OkHttpClient(`、`Request.Builder(`、`Response`，同上

## 10. demo 适配

- [ ] 10.1 demo 默认无需改；如有自定义网络注入，迁移到 `httpEngine = MyEngine()`

## 11. 烟测

- [ ] 11.1 新增 `HttpEngineAbstractionTest.kt`：用 `class FakeEngine : HttpEngine { override fun stream(req) = flowOf("...") }` 断言不走 OkHttp
- [ ] 11.2 新增 `RequestSnapshotTest.kt`：在 send 进行中调 `update { baseUrl = "newUrl" }`；断言进行中 round 的 HttpRequest.url 仍是 oldUrl，新 send 才用 newUrl —— 验证 PRD snapshot 语义
- [ ] 11.3 跑 `SessionImplTest.kt` 全量验证 OkHttpEngine 默认行为不回归

## 12. 编译与回归

- [ ] 12.1 `:s3ss10n:compileDebugKotlin` 通过
- [ ] 12.2 `:app:compileDebugKotlin` 通过
- [ ] 12.3 全 smoketest main() PASS

## 13. 登记 T7 待替换的 try/catch 位置

- [ ] 13.1 `OkHttpEngine.stream` 异常处理写入 T7 待办清单
- [ ] 13.2 `ChatSession.runRound` 中网络层异常翻译为 SessionEvent.Error 的 try/catch 写入清单
