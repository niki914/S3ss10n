# AX Contract — S3ss10n

本文档是给代码 Agent 使用的项目导航。不要把 PRD 示例或实现代码复制到这里；需要细节时直接跳转源码。

## 项目定位

- `:s3ss10n`: 核心 Android/Kotlin 库，源码根目录为 `s3ss10n/src/main/java/com/niki914/s3ss10n/`。
- `:app`: Demo 应用，演示集成方如何配置 Session、发送消息、处理事件与工具调用。
- `:composebase`: Demo UI 使用的 MVI ViewModel 基类，和 Session 主能力无强绑定。
- 当前实现目标以 `PRD.md` 的新版 Session DX 为基线，但尚未完全覆盖所有 PRD 要求。

## PRD 覆盖状态

- 已覆盖: 长生命周期 `Session`、`send` 事件回调、`update` 配置更新、`resetConversation`、`close`、会话历史、OpenAI 协议适配、localTools DSL、MCP HTTP server 配置、MCP tool discovery、MCP tool call delegate。
- 已覆盖: `SessionEvent` 的主要事件集合，包括 round、文本增量、tool running/succeeded/failed、错误阶段。
- 已覆盖: `ToolCallKind.Local` 与 `ToolCallKind.Mcp(serverName)`，开发者可以在 hooks 中按来源分流。
- 已覆盖: 公开 `Message` 类型，包含 `Message.Tool`；hooks、`delegate()`、`ok()`、`error()` 均返回 `Message.Tool`，符合 PRD 强约束。
- 已覆盖: `Session.open {}` 默认 OpenAI 入口，无协议类型参数；`Session.open<SessionProtocols.OpenAI> {}` 仍可用。
- 已覆盖: MCP `initialize` 请求与 `notifications/initialized` 通知；`tools/list` 和 `tools/call` 前完成 lifecycle，按 server fingerprint 缓存初始化状态。
- 已覆盖: MCP result 正规化层，优先级 `structuredContent` → `content[]` → `content[].text` 内嵌 JSON，支持 `isError`，失败时仍回填模型。
- 部分偏离: `SessionConfig` 当前是可继承配置类加 `Builder`，不是 PRD 里的纯 data class。这是有意保留的设计——`Builder` 便于 mutable-while-configuring 模式，`snapshot()` 提供轮次不可变视图。
- 未覆盖: PRD 示例中的 `update { block: SessionConfig.() }` 签名与当前 `update { block: SessionConfig.Builder.() }` 有细微差异，但功能等价。

## 核心导航

- Session 公开入口: `s3ss10n/src/main/java/com/niki914/s3ss10n/Session.kt`。
- Session 配置与 DSL 聚合: `s3ss10n/src/main/java/com/niki914/s3ss10n/SessionConfig.kt`。
- Session 运行实现: `s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt`。
- 公开 Message 类型: `s3ss10n/src/main/java/com/niki914/s3ss10n/Message.kt`。
- 单轮快照与工具目录: `s3ss10n/src/main/java/com/niki914/s3ss10n/SessionSnapshot.kt`。
- 事件类型: `s3ss10n/src/main/java/com/niki914/s3ss10n/SessionEvent.kt`。
- 工具调用请求与结果记录: `s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallRequest.kt`。
- 工具来源类型: `s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallKind.kt`。
- 本地工具 DSL: `s3ss10n/src/main/java/com/niki914/s3ss10n/LocalToolRegistry.kt`。
- MCP 配置 DSL: `s3ss10n/src/main/java/com/niki914/s3ss10n/McpTypes.kt`。
- MCP HTTP client 与 discovery: `s3ss10n/src/main/java/com/niki914/s3ss10n/McpClient.kt`、`s3ss10n/src/main/java/com/niki914/s3ss10n/McpDiscoveryCache.kt`。
- MCP initialize lifecycle 缓存: `s3ss10n/src/main/java/com/niki914/s3ss10n/McpLifecycleCache.kt`。
- 协议注册入口: `s3ss10n/src/main/java/com/niki914/s3ss10n/SessionProtocols.kt`。
- OpenAI 协议实现: `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/openai/OpenAIProtocol.kt`。
- HTTP 抽象与 OkHttp 实现: `s3ss10n/src/main/java/com/niki914/s3ss10n/net/HttpEngine.kt`、`s3ss10n/src/main/java/com/niki914/s3ss10n/ext/net/OkHttpEngine.kt`。
- JSON 抽象: `s3ss10n/src/main/java/com/niki914/s3ss10n/json/JsonCodec.kt`、`s3ss10n/src/main/java/com/niki914/s3ss10n/ext/json/GsonJsonCodec.kt`。

## 运行链路

- 创建 Session 时，入口在 `Session.kt`，协议由 `SessionProtocols.kt` 与 `ProtocolRegistry` 解析，最终实例化 `ChatSession.kt`。
- 每次 `send` 会从当前 config 生成 `SessionSnapshot`，该快照冻结本轮 endpoint、model、hooks、tools、MCP server 等配置。
- `ChatSession.kt` 负责 round 串行化、取消旧工作、调协议构建请求、收集协议事件、写入 `HistoryKeeper`、推进工具调用后的下一轮请求。
- 文本和 tool call delta 的协议解析在 `OpenAIProtocol.kt`，网络流读取在 `OkHttpEngine.kt`。
- 工具调用由 `ToolCallWaiter` 收集并等待，`ChatSession.kt` 根据工具目录构造 local 或 MCP 的 `ToolCallRequest`。
- MCP discovery 在 `ChatSession.kt` 后台调度，缓存键来自 `McpServerConfig` 的 server 指纹；实际 `tools/list` 在 `McpClient.kt`。

## 配置语义

- `update` 已实现，位于 `ChatSession.kt`，更新的是后续 round 使用的 active config。
- 已经开始的 round 使用启动时生成的 `SessionSnapshot`，不会被后续 `update` 改写。
- `jsonCodec` 与 `httpEngine` 是 open-only 字段，`update` 中变更会被忽略。
- `resetConversation` 清空历史并取消当前工作，适合新会话、切换模型、切换 MCP 后使用。
- `getHistory` 是当前实现里的额外公开能力，返回 `ChatTurn` 列表；这不是 PRD 主文档列出的最小接口。

## MCP 状态

- 已支持 HTTP MCP endpoint 配置，入口在 `McpTypes.kt`。
- 已支持从 MCP `tools/list` 发现工具，并把发现工具合并到当前 tool catalog。
- 已支持 MCP `tools/call`，通过 `McpToolCallRequest.delegate` 路由到 `HttpMcpClient`。
- 已支持 MCP initialize lifecycle：`initialize` → `notifications/initialized` → discovery/call，初始化状态按 server fingerprint 缓存在 `McpLifecycleCache.kt`。
- 已支持 MCP result normalization in `McpClient.kt`：优先 `structuredContent`，其次 `content[]`，最后兼容 `text` 内嵌 JSON；`isError` 映射为 failure。
- initialize 失败时 discovery 只打日志并返回空列表（不阻塞 `send()`）；call 失败时抛异常由上层转为 `Message.Tool` error。
- 当前 discovery 是异步调度；首次 `send` 可能只能拿到缓存中已有的发现结果，显式工具配置不受 discovery 缓存影响。

## Demo 导航

- Demo 集成入口: `app/src/main/java/com/niki914/demo/DemoChatViewModel.kt`。
- Demo UI: `app/src/main/java/com/niki914/demo/ui/compose/DemoChatScreen.kt`。
- Demo 当前展示了 OpenAI protocol Session 创建、local toast tool、MCP delegate、事件到 UI state 的映射。
- Demo 里 MCP 地址是本地示例配置，不等同于 PRD 中记录的全部 MCP 样本。

## 构建导航

- 根构建文件: `build.gradle.kts`。
- 模块声明与仓库: `settings.gradle.kts`。
- 版本目录: `gradle/libs.versions.toml`。
- 库模块构建: `s3ss10n/build.gradle.kts`。
- Demo 模块构建: `app/build.gradle.kts`。
- Compose 基础模块构建: `composebase/build.gradle.kts`。

## 修改守则

- 改 Session DX 时先对照 `PRD.md`，不要只跟随 `CLAUDE.md`。
- 不要恢复旧的 `ChatClient`、`ChatService`、`toolbase`、`ConfigBuilder` 分层描述；这些已不是当前主实现。
- 不要在文档里粘贴 API 示例代码；用路径和职责描述引导阅读源码。
- 修改 MCP 行为时优先收敛在 `McpClient.kt`、`McpTypes.kt`、`ChatSession.kt`，不要把 server-specific 兼容扩散到公开 API。
- 修改 hooks 返回类型会影响 `SessionConfig.kt`、`ToolCallRequest.kt`、`SessionSnapshot.kt`、`ChatSession.kt` 和 Demo，需要作为一次显式 API 变更处理。
