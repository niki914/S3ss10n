## Why

当前配置层有 3 套并存的字段表示——`SessionConfig`（公开 DSL）、`Config`（internal data class）、`ConfigBuilder`（DSL 中转 Builder）。每次 `send()` 之前 `ChatSession.applyConfig()` 都要把 `SessionConfig` 字段逐个拷贝到 `ConfigBuilder`，再 `build()` 出新 `Config`，最后写入 `ConfigHolder.AtomicReference`。同一份信息存在三处，命名还互相不一致（`baseUrl` vs `endpoint`，`modelName` vs `model`，`prompt` vs `systemPrompt`）。

更严重的是 PRD §Session 要求的 `update {}` 入口当前没有实现，因此 `SessionConfig` 一旦构造完就没有合法入口去修改；同时 PRD 提到的 `appParams`（用于把 `applicationContext` 这类对象传给 hooks 内的本地工具）当前也没有 DSL 入口，`buildAppParams()` 永远返回空 map。

本 change 把配置层拍平为单一公开 DSL `SessionConfig`（命名严格对齐 PRD），新增 `Session.update {}` 入口与"进行中 round 不受影响"的快照语义，并落地 `appParams { }` DSL。这是后续所有重构的基础：协议泛型、Tool 重构、HTTP/JSON 抽象都要在确定的 `SessionConfig` 字段上展开。

## What Changes

- **BREAKING**: 删除 `Config.kt` / `util/ConfigBuilder.kt` / `util/ConfigHolder.kt`
- **BREAKING**: `SessionConfig` 字段命名严格对齐 PRD（已对齐的字段保持不变；新增 connect/read/writeTimeoutSeconds 已存在则不动）
- **BREAKING**: `ChatClient.updateConfig(block)` 入口删除（由后续任务进一步拆 ChatClient，此处只先剥离 ConfigBuilder 用法）
- 新增 `Session.update(block: SessionConfig.() -> Unit)`：原子替换内部 SessionConfig 引用
- 新增 SessionConfig 内部 `snapshot(): SessionConfig`：浅拷贝出一份不可变视图，供单次 `send()` 全程使用
- 新增 `SessionConfig.appParams { }` DSL：写入 `MutableMap<String, Any?>`；SessionConfig 暴露 `appParamsSnapshot(): Map<String, Any?>` 供下游读取
- `ChatSession` 持有 `currentConfig: SessionConfig`（替代旧 `sessionConfig` + `Config` 双份），`send()` 入口先取 snapshot 再发起 round
- `ChatSession.applyConfig()` 删除（不再有跨层字段拷贝）
- 删除 `ConfigBuilder.socksProxy()` / `httpProxy()`（PRD 外溢能力，本轮先一并清理）

## Capabilities

### New Capabilities

- `session-config-single-source`: SessionConfig 是唯一的配置真理源，命名严格对齐 PRD，无 Config / ConfigBuilder 中间层
- `session-update-runtime`: Session.update {} 入口 + 进行中 round 看快照、下次 send 用最新配置的语义
- `session-app-params`: appParams { } DSL 落地，hooks 内可通过 ToolCallRequest.appParams 读取（接口字段在 T2 落地）

### Modified Capabilities

<!-- 无现有 spec 需要修改 -->

## Impact

- 删除：`s3ss10n/Config.kt`、`s3ss10n/util/ConfigBuilder.kt`、`s3ss10n/util/ConfigHolder.kt`
- 修改：`s3ss10n/SessionConfig.kt`（新增 snapshot / appParams DSL）
- 修改：`s3ss10n/Session.kt`（新增 update 入口）
- 修改：`s3ss10n/ChatSession.kt`（去掉 applyConfig，改为 snapshot 持有）
- 修改：`s3ss10n/ChatClient.kt`（剥离 ConfigBuilder 用法；本任务只做这一步，ChatClient 完全删除留给 T3）
- 修改：`s3ss10n/net/OkhttpClientManager.kt`（不再依赖 ConfigHolder；改为构造时直接吃 SessionConfig 字段，T6 之前临时妥协）
- 修改：`app/DemoChatViewModel.kt`（如果用到 Session 引用，新增 update 调用示例可选）
- 烟测：`SessionConfigTest.kt` 增加 update / appParams / snapshot 场景

## Non-Goals

- ChatClient 的最终删除（属于 T3）
- 拦截器动态配置改造（属于 T6）
- 协议字段如何使用（属于 T4）
- ToolCallRequest.appParams 字段曝出（属于 T2）
