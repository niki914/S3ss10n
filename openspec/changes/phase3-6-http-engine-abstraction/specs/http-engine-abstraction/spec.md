## ADDED Requirements

### Requirement: HttpEngine interface defines the network seam

The system SHALL define an interface `com.niki914.s3ss10n.net.HttpEngine` with two methods: `fun stream(request: HttpRequest): Flow<String>` (returning a flow of SSE payload lines, one per server-sent event, with the terminator `[DONE]` consumed internally) and `fun close()` (idempotent resource release). The interface SHALL NOT reference any OkHttp / Ktor / java.net types.

#### Scenario: HttpEngine interface is library-agnostic

- **WHEN** `HttpEngine.kt` is read
- **THEN** no method signature mentions `okhttp3.*`, `io.ktor.*`, `java.net.*`, or any other library/JDK net type
- **THEN** all signatures use `HttpRequest`, `Flow<String>`, `Unit`

### Requirement: HttpRequest is a self-contained value object

The system SHALL define `com.niki914.s3ss10n.net.HttpRequest` as an immutable data class containing: `method: String`, `url: String`, `headers: Map<String, String>`, `body: ByteArray?`, `timeoutMs: HttpTimeouts`, `isStreaming: Boolean`. The `HttpEngine` implementation SHALL be able to fulfil the request using ONLY the data inside this object, without consulting any external state.

#### Scenario: HttpRequest carries timeouts

- **WHEN** ChatProtocol builds a request from a SessionConfig snapshot with `connectTimeoutMs = 5000`
- **THEN** the resulting HttpRequest's `timeoutMs.connectMs` equals `5000`
- **THEN** OkHttpEngine applies this timeout to the call (e.g., via `OkHttpClient.newBuilder().connectTimeout(...).build()` per request, or `Call` overrides)

#### Scenario: Engine does not read external config

- **WHEN** OkHttpEngine.stream(request) executes
- **THEN** it does NOT read `ConfigHolder` or any other singleton/global state
- **THEN** all per-request data comes from the `request` argument

### Requirement: OkHttpEngine is the sole OkHttp dependency point

The system SHALL provide `com.niki914.s3ss10n.net.OkHttpEngine` as the default implementation of `HttpEngine`. `OkHttpEngine.kt` SHALL be the only file in the `:s3ss10n` module that contains `import okhttp3.*` (or `import okhttp3.sse.*`). All other OkHttp usages in the module SHALL be removed.

#### Scenario: OkHttp imports outside OkHttpEngine are forbidden

- **WHEN** searching the `:s3ss10n` module for `import okhttp3`
- **THEN** the only file matching is `s3ss10n/net/OkHttpEngine.kt`

#### Scenario: OkHttpEngine is the default

- **GIVEN** a developer calls `Session.open<SessionProtocols.OpenAI> { /* no httpEngine set */ }`
- **WHEN** the session sends a message
- **THEN** the underlying network call is made by an `OkHttpEngine` instance

### Requirement: Dynamic-config interceptors are deleted

The following files SHALL be deleted from the module:
- `s3ss10n/util/interceptors/DynamicURLInterceptor.kt`
- `s3ss10n/util/interceptors/DynamicTimeoutInterceptor.kt`
- `s3ss10n/util/interceptors/ChatApiInterceptor.kt`
- `s3ss10n/net/OkhttpClientManager.kt`
- `s3ss10n/chat/ChatService.kt`

The placeholder URL `https://okhttp/interceptor/will/update/this/` SHALL no longer appear anywhere in the codebase.

#### Scenario: Interceptor files do not exist

- **WHEN** the refactor is complete
- **THEN** `DynamicURLInterceptor.kt`, `DynamicTimeoutInterceptor.kt`, `ChatApiInterceptor.kt` do not exist
- **THEN** `OkhttpClientManager.kt` does not exist
- **THEN** `ChatService.kt` does not exist

#### Scenario: Placeholder URL is gone

- **WHEN** searching the entire codebase for `okhttp/interceptor/will/update/this`
- **THEN** zero matches are found

### Requirement: ChatProtocol.buildRequest returns HttpRequest

The `ChatProtocol` interface SHALL replace `buildRequestBody(...)` (returning `String`) with `buildRequest(snapshot, history, pendingUserInput): HttpRequest`. The protocol implementation SHALL fully populate url, headers (including auth), body, and timeouts from the snapshot.

#### Scenario: OpenAIProtocol builds complete HttpRequest

- **GIVEN** a snapshot with `baseUrl = "https://api.openai.com"`, `apiKey = "sk-x"`, `connectTimeoutMs = 5000`, etc.
- **WHEN** `OpenAIProtocol.buildRequest(snapshot, history, pendingUserInput = "hi")` is called
- **THEN** the returned `HttpRequest` has `url = "https://api.openai.com/v1/chat/completions"` (or the protocol's canonical path)
- **THEN** headers contain `Authorization: Bearer sk-x` and `Content-Type: application/json`
- **THEN** body is the JSON byte array of the messages payload
- **THEN** timeoutMs.connectMs equals `5000`

### Requirement: SessionConfig allows custom HttpEngine injection

`SessionConfig.Builder` SHALL expose an `httpEngine: HttpEngine? = null` property. When non-null, `ChatSession` SHALL use this engine and SHALL NOT instantiate `OkHttpEngine`.

#### Scenario: Custom engine is honoured

- **GIVEN** a developer provides `class FakeEngine : HttpEngine` and calls `Session.open<SessionProtocols.OpenAI> { httpEngine = FakeEngine() }`
- **WHEN** the session sends a message
- **THEN** the request is dispatched to `FakeEngine`, not `OkHttpEngine`
- **THEN** OkHttp is not invoked at all

### Requirement: ChatSession depends only on HttpEngine

`ChatSession` SHALL hold a `private val engine: HttpEngine` and SHALL NOT import any OkHttp types or any of the deleted intermediate classes (`OkhttpClientManager`, `ChatService`).

#### Scenario: ChatSession is HTTP-library-agnostic

- **WHEN** the refactor is complete
- **THEN** `ChatSession.kt` does NOT import `okhttp3.*`
- **THEN** `ChatSession.kt` does NOT reference `OkhttpClientManager` or `ChatService`

### Requirement: SSE [DONE] terminator is consumed by OkHttpEngine

`OkHttpEngine.stream` SHALL detect the `data: [DONE]` SSE event and SHALL complete the returned Flow normally without emitting `[DONE]` to downstream. ChatProtocol.parseStream SHALL never see `[DONE]` strings.

#### Scenario: [DONE] is consumed

- **GIVEN** a server stream that ends with `data: [DONE]\n\n`
- **WHEN** `OkHttpEngine.stream(request).toList()` collects the flow
- **THEN** the resulting list does NOT contain `"[DONE]"`
- **THEN** the flow terminates normally (no exception)

### Requirement: Engine cancellation propagates to underlying call

When the consumer cancels the Flow returned by `engine.stream(request)`, the underlying HTTP call SHALL be cancelled. Likewise, `engine.close()` SHALL cancel any in-flight calls and release the connection pool.

#### Scenario: Flow cancellation cancels OkHttp Call

- **GIVEN** a long-running stream from OkHttpEngine
- **WHEN** the collector job is cancelled
- **THEN** the OkHttp Call.cancel() is invoked
- **THEN** no further bytes are read from the network
