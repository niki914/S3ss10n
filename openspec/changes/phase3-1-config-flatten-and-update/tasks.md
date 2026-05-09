## 1. SessionConfig 自身能力扩展

- [ ] 1.1 在 `SessionConfig` 中新增 `internal val _appParams = mutableMapOf<String, Any?>()`
- [ ] 1.2 新增公开方法 `fun appParams(block: MutableMap<String, Any?>.() -> Unit) { _appParams.apply(block) }`
- [ ] 1.3 新增 `internal fun appParamsSnapshot(): Map<String, Any?> = _appParams.toMap()`
- [ ] 1.4 新增 `internal fun snapshot(): SessionConfig`：构造新实例，拷贝所有标量字段、`hooksBlock`、`localToolRegistry` 内容、`mcpRegistry` 内容、`_appParams` 内容
- [ ] 1.5 删除 `SessionConfig.buildAppParams()`（旧的永远返回 emptyMap 的死方法），用 `appParamsSnapshot()` 替代

## 2. Session 接口新增 update

- [ ] 2.1 在 `Session.kt` 接口中新增 `fun update(block: SessionConfig.() -> Unit)`（非 suspend）
- [ ] 2.2 在 `ChatSession` 中实现 `update`：`configRef.updateAndGet { current -> current.snapshot().apply(block) }`

## 3. ChatSession 改造为快照驱动

- [ ] 3.1 `ChatSession` 增加字段 `private val configRef: AtomicReference<SessionConfig>`，由构造函数初始化
- [ ] 3.2 删除 `var sessionConfig: SessionConfig?` 字段
- [ ] 3.3 `send(text, onEvent)` 入口改为：`val snap = configRef.get().snapshot()`，整个 round 从 snap 读字段
- [ ] 3.4 `handleToolCall` / `buildToolCallRequest` 改为从 round-scoped snap 读取 `hooksBlock` 和 `appParamsSnapshot()`
- [ ] 3.5 删除 `applyConfig()` 方法
- [ ] 3.6 删除 `ChatSession` 多余的构造函数（无参 + 多参形式），只保留 `constructor(config: SessionConfig)`

## 4. ChatClient 剥离 ConfigBuilder 用法（不删除 ChatClient 本体）

- [ ] 4.1 删除 `ChatClient.updateConfig(block: ConfigBuilder.() -> Unit)` 公开方法
- [ ] 4.2 `ChatClient` 改为构造时直接接收 `SessionConfig` 引用（或在 send 时由 ChatSession 传入 snapshot）
- [ ] 4.3 `ChatClient.sendMessages` 内部读取的字段从 `config.baseUrl` 等改为读 SessionConfig 的对齐命名字段
- [ ] 4.4 删除 `ChatClient.config: Config` 暴露

## 5. 删除 Config / ConfigBuilder / ConfigHolder

- [ ] 5.1 删除文件 `s3ss10n/Config.kt`（保留 `ConfigInvalidException` 类，搬到 `ChatClient.kt` 或新建 `Errors.kt`）
- [ ] 5.2 删除文件 `s3ss10n/util/ConfigBuilder.kt`
- [ ] 5.3 删除文件 `s3ss10n/util/ConfigHolder.kt`
- [ ] 5.4 删除 `socksProxy()` / `httpProxy()` 死代码（如未在 5.2 中一并删除）
- [ ] 5.5 全局搜索确认无任何 `import com.niki914.s3ss10n.Config` / `ConfigBuilder` / `ConfigHolder` 残留

## 6. OkhttpClientManager 临时适配

- [ ] 6.1 `OkhttpClientManager` 不再持有 `ConfigHolder`，改为构造时接收一个 `() -> SessionConfig` 取值器（或直接持有 SessionConfig 引用）
- [ ] 6.2 拦截器中的 `() -> Config` 闭包改为 `() -> SessionConfig`，字段名同步更新（baseUrl → endpoint, modelName → model 等）
- [ ] 6.3 `DynamicProxySelector` 临时改为返回 NO_PROXY（因为代理 DSL 已删除）；T6 整体重构时一并清理

## 7. 烟测

- [ ] 7.1 `SessionConfigTest.kt` 新增 "snapshot isolation" 测试：验证 snapshot 后修改原 config 不影响 snapshot
- [ ] 7.2 `SessionConfigTest.kt` 新增 "appParams DSL" 测试：put + snapshot 后读取
- [ ] 7.3 新增 `SessionUpdateTest.kt`：验证 `Session.update {}` 后下次 send 用新值（用 mock endpoint 字符串差异即可，不真发请求）
- [ ] 7.4 `IntegrationTest.kt` 增加 `update {}` 调用步骤，确认编译通过

## 8. 编译与回归

- [ ] 8.1 `:s3ss10n:compileDebugKotlin` 通过
- [ ] 8.2 `:app:compileDebugKotlin` 通过（DemoChatViewModel 如使用 ConfigBuilder 需修复）
- [ ] 8.3 运行所有 smoketest main() 全 PASS
