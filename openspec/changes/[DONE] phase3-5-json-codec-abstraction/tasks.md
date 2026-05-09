## 1. 新增 JsonCodec 接口

- [ ] 1.1 新建 `s3ss10n/json/JsonCodec.kt`：

```kotlin
interface JsonCodec {
    fun encode(value: Any?): String
    fun <T : Any> decode(json: String, type: Class<T>): T?
    fun decodeMap(json: String): Map<String, Any?>?
    fun decodeList(json: String): List<Any?>?
}
```

- [ ] 1.2 KDoc：明确"decode 失败返回 null 并打 Log.e"，禁止抛异常
- [ ] 1.3 KDoc：明确接口不绑定具体 JSON 库

## 2. 新增 GsonJsonCodec 默认实现

- [ ] 2.1 新建 `s3ss10n/json/GsonJsonCodec.kt`，public 类（允许用户在自定义协议时复用）
- [ ] 2.2 实现 4 个方法，decode 系列内部 try/catch + `android.util.Log.e("qwerqwer", ...)` —— 文件头加 `// TODO(T7): replace try/catch with xTry`
- [ ] 2.3 构造允许传入自定义 Gson 实例（`class GsonJsonCodec(private val gson: Gson = Gson())`）

## 3. 在 SessionConfig 中加 jsonCodec 入口

- [ ] 3.1 `SessionConfig` 新增 `internal val jsonCodec: JsonCodec? = null` 字段
- [ ] 3.2 `SessionConfig.Builder` 暴露 `var jsonCodec: JsonCodec? = null`（DSL 属性形式与 T1 风格一致）
- [ ] 3.3 KDoc：null 表示使用默认 `GsonJsonCodec()`

## 4. 改造 OpenAIProtocol 接受 JsonCodec

- [ ] 4.1 `class OpenAIProtocol(private val codec: JsonCodec = GsonJsonCodec()) : ChatProtocol`
- [ ] 4.2 删除 OpenAIProtocol 内部所有 `Gson()` / `gson.toJson` / `gson.fromJson`，全部改为 `codec.encode(...)` / `codec.decode(...)` / `codec.decodeMap(...)`
- [ ] 4.3 SSE 帧解析：原 `gson.fromJson(line, ChatStreamFrame::class.java)` → `codec.decode(line, ChatStreamFrame::class.java)`
- [ ] 4.4 toolCall 拼接判完整：尝试 `codec.decodeMap(arguments)`，非 null 即视为完整 JSON
- [ ] 4.5 全文搜索 `s3ss10n/protocol/openai/` 下所有 `import com.google.gson`，全部清除

## 5. 改造 SessionProtocols.OpenAI 与 ChatSession 的注入路径

- [ ] 5.1 `SessionProtocols.OpenAI` 仍预注册一个默认 `OpenAIProtocol(GsonJsonCodec())`
- [ ] 5.2 `ChatSession` 构造函数接收的 `protocol: ChatProtocol` 在内部检查：如果 `initialConfig.jsonCodec != null` 且 protocol 是已知可重新绑定 codec 的 OpenAIProtocol，则替换为 `OpenAIProtocol(initialConfig.jsonCodec!!)`；否则保持
- [ ] 5.3 评估方案：在 `ChatProtocol` 上加可选 `fun withCodec(codec: JsonCodec): ChatProtocol = this`（默认实现 noop），让协议自行决定怎么用 codec —— 倾向加这个口子，避免 ChatSession 用 instanceof 判断
- [ ] 5.4 `Session.open<P>` 的 inline 函数中：拿到 protocol 后，根据 config.jsonCodec 决定是否调 `withCodec`

## 6. 改造 LocalToolRegistry

- [ ] 6.1 `LocalToolRegistry` 加 `internal var codec: JsonCodec = GsonJsonCodec()`
- [ ] 6.2 ChatSession 构造时：`config.tools.codec = config.jsonCodec ?: GsonJsonCodec()`（或更优雅的：构造 LocalToolRegistry 时传入 codec）
- [ ] 6.3 把 LocalToolRegistry 内所有 Gson 直接调用改为 codec 调用

## 7. 删除其他 Gson 散点

- [ ] 7.1 删除 `s3ss10n/util/JsonUtil.kt`（如存在），调用方改为 codec
- [ ] 7.2 全局搜索 `import com.google.gson`，确认仅 `s3ss10n/json/GsonJsonCodec.kt` 一处
- [ ] 7.3 全局搜索 `Gson()` 构造，确认同上

## 8. demo 适配（如需）

- [ ] 8.1 demo 默认不需要改；如果 demo 自定义了 codec，验证注入路径

## 9. 烟测

- [ ] 9.1 新增 `JsonCodecAbstractionTest.kt`：定义 `class FakeCodec : JsonCodec`（计数 encode/decode 调用次数），通过 `Session.open<SessionProtocols.OpenAI> { jsonCodec = FakeCodec() }` 跑一次 send，断言 FakeCodec 被调用
- [ ] 9.2 验证 ToolCall delta 拼接判完整逻辑（用 FakeCodec 注入返回值控制行为）
- [ ] 9.3 跑 `SessionImplTest.kt` 全量验证 GsonJsonCodec 默认行为不回归

## 10. 编译与回归

- [ ] 10.1 `:s3ss10n:compileDebugKotlin` 通过
- [ ] 10.2 `:app:compileDebugKotlin` 通过
- [ ] 10.3 全 smoketest main() PASS

## 11. 登记 T7 待替换的 try/catch 位置

- [ ] 11.1 `GsonJsonCodec.decode*` 系列的 try/catch 写入 T7 待办清单
