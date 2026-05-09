## Context

到 T6 之前，:s3ss10n 的网络层还有"拦截器读全局可变 ConfigHolder"这种 hack 在生效。占位 URL `https://okhttp/interceptor/will/update/this/` 是显著的代码异味，也直接破坏 PRD `update {}` 的 snapshot 语义：拦截器在请求发出前总是读最新 ConfigHolder，意味着进行中的 round 也会被 update 影响。

本任务把网络层切成"显式 HttpRequest 值对象 + HttpEngine 抽象"两部分：
- 配置→请求的转换在 SessionConfig snapshot 拿到的瞬间就完成（在 ChatProtocol.buildRequestBody 里）
- HttpEngine 只负责"把这个 HttpRequest 变成响应"，对配置一无所知
- OkHttp 拦截器全部删除

这同时也是把 ChatProtocol.buildRequestBody 从"返回 String body"升级到"返回完整 HttpRequest"的时机——T4 design 已经预告过。

## Goals / Non-Goals

**Goals:**
- HttpEngine 接口 + HttpRequest/HttpResponse 值对象
- OkHttpEngine 是 :s3ss10n 内部唯一直接依赖 OkHttp 的文件
- 删除 DynamicURLInterceptor / DynamicTimeoutInterceptor / ChatApiInterceptor
- 删除 OkhttpClientManager / ChatService
- 每次 send 显式构造完整 HttpRequest

**Non-Goals:**
- 拿掉 OkHttp Gradle 依赖
- 自定义 SSE 协议解析（沿用 OkHttp 的 ResponseBody）
- 引入 Ktor 实现示例
- 第三方 HttpEngine 实现（用户自定义留扩展点即可）

## Decisions

### Decision 1: HttpRequest 值对象只表达"一次请求所需的全部信息"

**选择**：

```kotlin
data class HttpRequest(
    val method: String,                       // "POST"
    val url: String,                          // 已拼好 baseUrl + path
    val headers: Map<String, String>,         // 已包含 Authorization、Content-Type
    val body: ByteArray?,                     // JSON bytes
    val timeoutMs: HttpTimeouts,              // 连接/读/写
    val isStreaming: Boolean = true,          // SSE 走 stream，普通 JSON 走非 stream
)

data class HttpTimeouts(
    val connectMs: Long,
    val readMs: Long,
    val writeMs: Long,
)
```

**原因**：
- 完全自包含——HttpEngine 不需要回查任何外部状态
- 每个字段都是当前 send 这一刻的"快照"
- timeoutMs 也跟请求走，update 改 timeout 不影响进行中 round

**替代方案**：
- 复用 OkHttp 的 `Request`：被否，等于没抽
- 用 InputStream/Reader 作 body：被否，过早抽象，内存里的 JSON byte 数组够了

### Decision 2: HttpEngine 接口只暴露 stream 与 close

**选择**：

```kotlin
interface HttpEngine {
    /** 发起一次流式请求，返回 SSE 行的 Flow（每个元素是去掉 "data: " 前缀的 payload；终止标记 "[DONE]" 由 engine 内部识别后正常 close 流） */
    fun stream(request: HttpRequest): Flow<String>

    /** 释放底层资源（连接池、线程池等）；幂等 */
    fun close()
}
```

**原因**：
- 当前 :s3ss10n 只发流式请求，不需要 unary
- close 让 ChatSession.close() 能传下去
- 返回 `Flow<String>` 是与 ChatProtocol.parseStream 的契约——SSE 数据行（payload）逐行送出

**替代方案**：
- 返回 `Flow<ByteArray>`：被否，所有协议第一步都 utf8.toString，统一在 engine 做减少边界泄露
- 加 `unary(request): HttpResponse`：被否，YAGNI

### Decision 3: ChatProtocol.buildRequestBody 升级返回 HttpRequest

**选择**：

```kotlin
interface ChatProtocol {
    fun buildRequest(snapshot: SessionConfig, history: List<ChatTurn>, pendingUserInput: String?): HttpRequest
    fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent>
    fun encodeToolResult(callId: String, toolName: String, resultJson: String): ChatTurn.ToolResult
}
```

方法名同时由 `buildRequestBody` 改为 `buildRequest`（更准确）。

**原因**：
- 协议负责把 url 路径、headers（含鉴权）、body 全部装好
- ChatSession 不知道 OpenAI 走 POST /v1/chat/completions，Anthropic 走 POST /v1/messages
- 鉴权 header 也是协议职责（OpenAI 是 `Authorization: Bearer ...`，Anthropic 是 `x-api-key: ...`）

**替代方案**：
- 让 ChatSession 拼 url、headers，协议只管 body：被否，等于把协议差异半泄半藏，未来加 Anthropic 时还要回头改 ChatSession

### Decision 4: 拦截器全部删除，鉴权移到 OpenAIProtocol.buildRequest

**选择**：
- 删除 `DynamicURLInterceptor` / `DynamicTimeoutInterceptor` / `ChatApiInterceptor`
- 鉴权 header（`Authorization: Bearer ${apiKey}`）由 OpenAIProtocol.buildRequest 在构造 HttpRequest 时塞进 headers
- timeout 由 ChatSession 在调 engine.stream 前从 snapshot 取出，作为 HttpRequest.timeoutMs 字段
- url 由 OpenAIProtocol.buildRequest 直接 `"${snapshot.baseUrl}/v1/chat/completions"`

**原因**：
- snapshot 进入 buildRequest 后所有"动态"字段就固化了
- OkHttpEngine 内部按 HttpRequest 直接构造 OkHttp Request；不再有"全局 ConfigHolder 被拦截器读取"

**替代方案**：
- 保留拦截器但让其读 HttpRequest 自身字段：被否，毫无意义

### Decision 5: OkHttpEngine 内部仍可用 OkHttp 拦截器（自身私有）

**选择**：OkHttpEngine 的 OkHttpClient 实例可以内部用 OkHttp 自带的 logging interceptor 等"OkHttp 实现细节内部使用"的拦截器。但这些拦截器不读外部状态，只对 HttpRequest 这一次请求做处理。

**原因**：
- 我们禁止的是"用拦截器实现配置动态化"，不是"用拦截器"本身
- OkHttp logging interceptor 是内部细节，无外部状态依赖

### Decision 6: ChatService 与 OkhttpClientManager 整体删除

**选择**：
- ChatService 的"建 SSE EventSource"逻辑搬到 OkHttpEngine.stream 内部
- OkhttpClientManager 整个删除（构造 OkHttpClient 在 OkHttpEngine 构造里完成）
- ChatSession 直接持有 `private val engine: HttpEngine`

**原因**：
- 三层（ChatSession → ChatService → OkhttpClientManager → OkHttp）压成两层（ChatSession → HttpEngine → OkHttp）
- 与 T3 删除 ChatClient 的精神一致

### Decision 7: HttpEngine 注入路径

**选择**：
- `SessionConfig.Builder.httpEngine: HttpEngine? = null`
- `ChatSession` 构造时：`this.engine = initialConfig.httpEngine ?: OkHttpEngine()`
- 用户自定义 engine 完全替换默认，OkHttp 都不会被实例化

**原因**：
- 与 T5 JsonCodec 的注入风格一致
- 用户定制能力到位

### Decision 8: SSE 终止符识别在 OkHttpEngine 内部

**选择**：OkHttpEngine 在收到 `data: [DONE]` 时停止 emit，正常 complete Flow。ChatProtocol.parseStream 不需要见到 `[DONE]`。

**原因**：
- `[DONE]` 是 SSE 协议本身（Server-Sent Events 风格）的常见约定，不是 OpenAI 独有
- 让 engine 处理这层格式，protocol 只关心 payload

**替代方案**：
- 让 protocol 自己识别 [DONE]：被否，不同协议的"流终止符"形态不同（Anthropic 用 `event: message_stop`），但 SSE 帧本身的 [DONE] 多半是底层惯例。退一步：如果未来某个协议的终止符不是 `[DONE]`，HttpEngine.stream 应该 emit 完所有 raw 数据后让 protocol 决定 —— 这种情况再加 `terminator: String?` 参数。当前简化处理。

## Risks / Trade-offs

- **OkHttpEngine 接管 SSE 行解析**：原 `okhttp-sse` 库里的 EventSource 自带行解析。OkHttpEngine 用 EventSource 时，把 onEvent 回调 emit 到 Flow 中。要做好背压（可以用 `callbackFlow` + `trySend`）。
- **取消传播**：`engine.close()` 与 `Flow.cancel()` 必须能终止 OkHttp Call。OkHttpEngine.stream 内部用 `awaitClose { call.cancel() }`。
- **内存敏感**：HttpRequest.body 是 ByteArray，对大 body 会复制一份；可接受（chat 请求体一般几十 KB）。

## Open Questions

- HttpEngine 是否要支持非流式请求？当前 ChatSession 不用，但未来 MCP 工具 metadata 拉取可能要。倾向：到时再加 `unary(request): HttpResponse`，YAGNI 原则。
