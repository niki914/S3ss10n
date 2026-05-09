## Context

T4 完成后，:s3ss10n 模块对 OpenAI 协议字段名的依赖已收口到 OpenAIProtocol。但 OpenAIProtocol 的 JSON 编解码仍然直接调用 `Gson()` —— 这意味着模块仍然耦合具体 JSON 库。本任务把 JSON 编解码抽到 JsonCodec 接口，GsonJsonCodec 是默认实现。

JsonCodec 设计原则：
- **最小化接口**：只定义模块当前真正需要的几个方法，不为未来想象出来的需求加方法
- **失败语义清晰**：decode 失败返回 null（结合 T7 的 xTry 哲学），不抛异常
- **不暴露 Gson 类型**：接口只用 String / Class / Map / Any?

## Goals / Non-Goals

**Goals:**
- 一个最小的 JsonCodec 接口
- GsonJsonCodec 作为默认实现
- :s3ss10n 模块除 GsonJsonCodec 外不再直接 import com.google.gson.*
- 用户可通过 SessionConfig 注入自定义 JsonCodec

**Non-Goals:**
- 拿掉 Gson 依赖（保留实现绑定）
- 引入 kotlinx.serialization / Moshi
- 流式 JSON 解析（不需要）
- 复杂泛型 TypeToken 支持（OpenAIProtocol 的需求是简单 Class<T>）

## Decisions

### Decision 1: JsonCodec 接口最小化

**选择**：

```kotlin
interface JsonCodec {
    /** 把任意对象（含 data class、Map、List）序列化为 JSON 字符串 */
    fun encode(value: Any?): String

    /** 把 JSON 反序列化为指定类型；失败返回 null */
    fun <T : Any> decode(json: String, type: Class<T>): T?

    /** 把 JSON 解析为通用 Map（用于动态字段访问，如 SSE delta 解析） */
    fun decodeMap(json: String): Map<String, Any?>?

    /** 把 JSON 解析为通用 List */
    fun decodeList(json: String): List<Any?>?
}
```

**原因**：
- OpenAIProtocol 需求：encode 请求体（data class）、decode SSE 帧（动态 Map）、decode tool arguments（Map 或具体 class）
- LocalToolRegistry 需求：encode schema（Map）、decode tool params（具体 class 或 Map）
- 这四个方法覆盖现有所有需求；不预留 reified inline 等高级 API

**替代方案**：
- 用 KType / TypeToken：被否，过于复杂，且只有 OpenAIProtocol 一个调用方
- 暴露 InputStream/Reader 流式 API：被否，没有需求

### Decision 2: GsonJsonCodec 是 :s3ss10n 内部唯一 Gson 入口

**选择**：

```kotlin
internal class GsonJsonCodec(private val gson: Gson = Gson()) : JsonCodec {
    override fun encode(value: Any?): String = gson.toJson(value)
    override fun <T : Any> decode(json: String, type: Class<T>): T? = try {
        gson.fromJson(json, type)
    } catch (t: Throwable) {
        android.util.Log.e("qwerqwer", "GsonJsonCodec.decode<${type.simpleName}> failed", t)
        null
    }
    override fun decodeMap(json: String): Map<String, Any?>? = decode(json, Map::class.java) as? Map<String, Any?>
    override fun decodeList(json: String): List<Any?>? = decode(json, List::class.java) as? List<Any?>
}
```

CI/Lint 应禁止其他文件 `import com.google.gson.*`。

**原因**：
- 一个文件控制 Gson 依赖，未来换库只换它
- decode 失败返回 null + 日志，符合 T7 xTry 哲学

**替代方案**：
- 拿掉 Gson 依赖：被否（用户已说"暂时保持依赖"）
- 用 reflection 写一个零依赖 codec：被否，工作量大且不稳定

### Decision 3: JsonCodec 注入路径

**选择**：
- `SessionConfig` 增加可选字段 `internal val jsonCodec: JsonCodec? = null`
- `SessionConfig.Builder` 暴露 `jsonCodec(codec: JsonCodec)` DSL
- `Session.open<P> { ... }` 内部：

```kotlin
val codec = config.jsonCodec ?: GsonJsonCodec()
val protocol = ProtocolRegistry.resolve(P::class).withCodec(codec)
// 或：ChatSession 把 codec 注入给 protocol / LocalToolRegistry
```

`ChatProtocol` 接口本身不持有 codec（保持纯无状态），改为：协议实现允许通过构造或 `withCodec` 拷贝来绑定 codec。`OpenAIProtocol` 提供 `class OpenAIProtocol(private val codec: JsonCodec = GsonJsonCodec()) : ChatProtocol`。

**原因**：
- 配置入口统一在 SessionConfig（与 T1 一致）
- 协议自主决定是否需要 codec（未来可能有协议不需要 JSON）
- 缺省值 GsonJsonCodec 让默认体验零额外配置

**替代方案**：
- 把 codec 加进 ChatProtocol 接口：被否，污染抽象
- 全局单例 `JsonCodecProvider`：被否，不利于测试与多实例

### Decision 4: SessionProtocols.OpenAI 与 codec 注入的耦合

**选择**：
- `SessionProtocols.OpenAI` 在 T4 是预注册的单例 OpenAIProtocol（默认 GsonJsonCodec）
- 用户如果要换 codec，路径是：
  1. 调用 `ProtocolRegistry.register(SessionProtocols.OpenAI::class, OpenAIProtocol(MyCodec()))` 覆盖默认
  2. 或通过 `SessionConfig.jsonCodec(...)` 在 open 时注入（ChatSession 收到非 null codec 时，临时构造一个新的 OpenAIProtocol(codec) 替代注册表的）

倾向方案 2 更自然，由 ChatSession 在构造时按需替换 protocol。tasks 中明确实现路径。

**原因**：
- 让用户在 `Session.open<P> { jsonCodec = MyCodec() }` 一处完成配置
- 不需要在 Application 启动时改全局注册

### Decision 5: LocalToolRegistry 也走 JsonCodec

**选择**：`LocalToolRegistry` 持有 `internal var codec: JsonCodec = GsonJsonCodec()`，由 ChatSession 在构造时注入实际 codec。所有 schema 与参数的 JSON 处理走 codec。

**原因**：
- 否则 LocalToolRegistry 仍然直接依赖 Gson，破坏"单一 binding 文件"目标

### Decision 6: 保留 Gson Gradle 依赖

**选择**：`:s3ss10n/build.gradle.kts` 保持 `implementation("com.google.code.gson:gson:...")`。

**原因**：
- 用户已确认"暂时保持依赖"
- 默认 GsonJsonCodec 还是要用 Gson
- 用户如果想去 Gson，只需要：自己写一个 JsonCodec 实现 + 在 SessionConfig 注入 + 把 dependencies 改为 `compileOnly` 即可，但这是 T5 之后的优化

## Risks / Trade-offs

- **接口可能不完整**：随着第二个协议实现（Anthropic）加入，可能需要 `decode<T>(json, type: KType): T?` 等高级签名。可接受：到时再加，不预测
- **decode 失败静默**：返回 null 可能掩盖 bug。trade-off：协议层失败已经在 ProtocolEvent.Error 报上来；codec 自己再抛只会让上层重复处理。日志 + null 是最实用的折中

## Open Questions

- 是否要把 GsonJsonCodec 暴露为 public（让用户能在自己的 Application 里手动 `OpenAIProtocol(GsonJsonCodec())` 注册自定义协议）？倾向 public，方便扩展
