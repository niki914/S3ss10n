## Why

T5 完成后，:s3ss10n 模块对 JSON 的依赖已收口到 GsonJsonCodec。剩下的最大耦合是 OkHttp + 一系列拦截器实现的"动态配置"hack：

1. **占位 URL hack**：[ChatService.kt:57](file:///Users/bytedance/repo/android/personal/5_8_session/s3ss10n/src/main/java/com/niki914/s3ss10n/chat/ChatService.kt#L57) 写死 `https://okhttp/interceptor/will/update/this/`，由 `DynamicURLInterceptor` 在拦截器里读最新 ConfigHolder 替换。
2. **`DynamicURLInterceptor` / `DynamicTimeoutInterceptor` / `ChatApiInterceptor`** 三个拦截器都通过读"全局可变 ConfigHolder"在请求经过时塞 url / timeouts / headers，违反"配置 = 显式数据流"原则，且与 PRD `update {}` 的"snapshot 语义"直接冲突——拦截器读到的总是最新值，但 PRD 要求"进行中 round 不受 update 影响"。
3. **OkhttpClientManager**：依赖整个 OkHttp 客户端生命周期 + 拦截器编排，把模块绑死在 OkHttp 上。

T6 引入 HttpEngine 抽象：每次 send 用 SessionConfig 快照构造一个完整 HttpRequest 值对象（含 url / headers / timeouts / body），交给 HttpEngine 发出去。OkHttpEngine 是默认实现，也是 :s3ss10n 内部唯一直接 import OkHttp 的文件。

同时把 T4 中 `ChatProtocol.buildRequestBody` 的返回类型从 String 升级为 `HttpRequest`（这是 T4 design 已经预告的小调整）。

## What Changes

- **新增**：`s3ss10n/net/HttpEngine.kt` 接口
- **新增**：`s3ss10n/net/HttpRequest.kt`、`s3ss10n/net/HttpResponse.kt` 值对象
- **新增**：`s3ss10n/net/OkHttpEngine.kt` 默认实现，:s3ss10n 唯一 OkHttp 入口
- **修改**：`ChatProtocol.buildRequestBody(...)` 返回类型 `String` → `HttpRequest`（OpenAIProtocol 跟进）
- **修改**：`ChatSession`：删除 OkhttpClientManager / ChatService 持有；改为 `private val engine: HttpEngine = OkHttpEngine(...)`；`runRound` 用 `protocol.buildRequestBody(...)` → `engine.stream(request): Flow<String>` → `protocol.parseStream(...)`
- **删除**：`s3ss10n/util/interceptors/DynamicURLInterceptor.kt`
- **删除**：`s3ss10n/util/interceptors/DynamicTimeoutInterceptor.kt`
- **删除**：`s3ss10n/util/interceptors/ChatApiInterceptor.kt`
- **删除**：`s3ss10n/net/OkhttpClientManager.kt`（功能并入 OkHttpEngine）
- **删除**：`s3ss10n/chat/ChatService.kt`（功能并入 OkHttpEngine + ChatSession）
- **新增**：`SessionConfig` 中可选 `httpEngine: HttpEngine? = null` 注入入口

## Capabilities

### New Capabilities

- `http-engine-abstraction`: HttpEngine 接口 + OkHttpEngine 默认绑定，模块对 OkHttp 的依赖收口到一个文件
- `request-snapshot-construction`: 每次 send 用 SessionConfig snapshot 显式构造完整 HttpRequest，不再依赖拦截器读全局状态

### Modified Capabilities

- `protocol-abstraction`: ChatProtocol.buildRequestBody 返回 HttpRequest 值对象
- `chatsession-self-contained`: ChatSession 通过 HttpEngine 发起请求，不再持有 OkhttpClientManager
- `session-config-single-source`: snapshot 真正发挥作用——拦截器不再绕过 snapshot 读最新值

## Impact

- 新增：`s3ss10n/net/HttpEngine.kt`、`s3ss10n/net/HttpRequest.kt`、`s3ss10n/net/HttpResponse.kt`、`s3ss10n/net/OkHttpEngine.kt`
- 修改：`s3ss10n/protocol/ChatProtocol.kt`（buildRequestBody 返回 HttpRequest）
- 修改：`s3ss10n/protocol/openai/OpenAIProtocol.kt`（buildRequestBody 实现）
- 修改：`s3ss10n/ChatSession.kt`（持有 HttpEngine；runRound 走 engine.stream）
- 修改：`s3ss10n/SessionConfig.kt`（可选 httpEngine 字段）
- 删除：`s3ss10n/util/interceptors/` 整个目录（如内还有别的拦截器，逐个评估）
- 删除：`s3ss10n/net/OkhttpClientManager.kt`
- 删除：`s3ss10n/chat/ChatService.kt`
- 删除：`s3ss10n/chat/` 目录（如已无文件）

## Non-Goals

- 拿掉 OkHttp Gradle 依赖（仍 implementation）
- 自己实现一个 SSE 解析器（OkHttpEngine 内部沿用 okhttp-sse 或自己用 OkHttp 的 ResponseBody.source().readUtf8Line()）
- 引入 Ktor / Retrofit
- xTry / xLog 落地（T7）
