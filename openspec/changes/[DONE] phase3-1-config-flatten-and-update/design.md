## Context

PRD §SessionConfig 定义了 8 个公开字段（endpoint, apiKey, model, systemPrompt, temperature, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds）和 3 个 DSL 块（hooks, localTools, mcp）。当前代码已经在 `SessionConfig.kt` 用对齐的命名实现了字段，但内部又通过 `ConfigBuilder` 把这些字段重命名拷贝到 `Config`（baseUrl/modelName/prompt/readTimeout 等不一致命名）。这种"DSL 里命名对齐 PRD，内部又改回旧命名"是噪音放大的根源。

PRD §update 还规定：
- `update {}` 立即更新 active config
- 已运行的 round 不受影响
- 下一次 `send(...)` 使用最新配置
- 不会自动清空历史

PRD §SessionHooks 例子里 `MyToolHandler.handle(call)` 的语义里，开发者经常需要从外部注入 Android `Context` / DAO / SharedPreferences 等对象给本地工具。这就是 `appParams` 的真实用途。

## Goals / Non-Goals

**Goals:**
- 删除 `Config` / `ConfigBuilder` / `ConfigHolder` 三个文件
- `SessionConfig` 成为单一公开 + 单一内部真理源
- `Session.update {}` 落地，符合 PRD 快照语义
- `appParams { }` DSL 落地，可被后续任务读取
- 所有内部引用统一到 SessionConfig 字段名

**Non-Goals:**
- 删除 ChatClient（T3）
- 拦截器去除（T6）
- ToolCallRequest 暴露 appParams 字段（T2 提议中实现）
- 协议层使用这些字段构建请求体（T4）

## Decisions

### Decision 1: SessionConfig 既是公开 DSL 也是内部状态对象，不再拆分两套

**选择**：删除 `Config.kt` 和 `ConfigBuilder.kt`，让 `SessionConfig` 直接持有运行所需的所有字段。`OkhttpClientManager` 构造时直接接收 `SessionConfig` 引用并按需读取。

**原因**：当前 `Config` 的存在唯一价值是"不可变"，但实际上它由可变 `ConfigBuilder` 构造，每次配置变更都要 fromConfig→build 来回跳，没有真实不可变收益。`SessionConfig` 提供 `snapshot()` 浅拷贝即可达到同样效果，且只在需要快照时拷贝（每次 `send()` 入口）。

**替代方案**：保留 `Config` 但把命名对齐 PRD。拒绝原因：仍然两套字段两套维护成本，没有解决根本问题。

### Decision 2: snapshot 是浅拷贝且不可变

**选择**：`SessionConfig.snapshot()` 返回新的 `SessionConfig` 实例，所有标量字段值拷贝；`localToolRegistry` / `mcpRegistry` / `appParams` 拷贝当前内容到新容器。后续修改原 SessionConfig 不影响 snapshot。

**原因**：进行中 round 持有 snapshot，外部 `update {}` 替换主 SessionConfig 时不会影响 snapshot。简单、无并发陷阱。

**替代方案**：让 SessionConfig 全字段 `val` 化、用 copy() 实现 update。拒绝原因：DSL 习惯是可变 receiver，强制 val 会让 `endpoint = "..."` 这种写法消失，违反 PRD §SessionConfig 示例。

### Decision 3: Session 内部用原子引用持有当前 SessionConfig

**选择**：`ChatSession` 内 `private val configRef = AtomicReference(initialConfig)`。`update {}` 时 `configRef.updateAndGet { it.applyMutations(block) }`（或先 snapshot 再 apply）。`send()` 入口先 `configRef.get().snapshot()`。

**原因**：与 PRD"已运行 round 不受影响"严格匹配；并发 update 与 send 不会撕裂。

### Decision 4: appParams 用 Map<String, Any?>，不引入泛型 key

**选择**：

```kotlin
class SessionConfig {
    private val _appParams = mutableMapOf<String, Any?>()
    fun appParams(block: MutableMap<String, Any?>.() -> Unit) {
        _appParams.apply(block)
    }
    internal fun appParamsSnapshot(): Map<String, Any?> = _appParams.toMap()
}
```

**原因**：使用方场景是注入 `Context`、DAO 等异构对象，类型安全 key 收益小；hooks 里业务代码 `as? Context` 即可。简单优于过度设计。

### Decision 5: Session.update 是同步方法

**选择**：`fun update(block: SessionConfig.() -> Unit)`（非 suspend）。

**原因**：PRD §Session 签名 `fun update(...)` 不带 suspend；本身只做内存原子替换，无 IO。

### Decision 6: ChatClient 本轮只剥离 ConfigBuilder 用法，不删除

**选择**：`ChatClient.updateConfig(block: ConfigBuilder.() -> Unit)` 入口删除；改为 `ChatClient` 直接持有 `SessionConfig` 引用（构造注入）。完整删除 ChatClient 留给 T3。

**原因**：华容道排序——T1 只冻结配置层 API，不动 ChatSession 协调骨架。T3 完成 ChatSession 内联后再清掉 ChatClient。

## Risks / Trade-offs

- **OkhttpClientManager 暂时直接读 SessionConfig**：当前拦截器需要的 baseUrl/timeout/headers 来源从 `ConfigHolder.config.xxx` 改为 `() -> session.currentConfig.xxx`。这是临时形态，T6 会推翻整个拦截器机制。但这个临时形态不会冻结新 API，所以没违反华容道。
- **删除 socksProxy()/httpProxy()**：当前代码确实存在但未在 PRD 暴露的能力，删除会让任何隐式依赖该能力的用户代码编译失败。可接受（demo 没有用）。
- **appParams 类型不安全**：选择简单优先，后续如有真实需要可加 typed-key 扩展。

## Open Questions

<!-- 无 -->
