# Source Map

> 本文件只做源码入口地图。它按公共目录前缀聚合路径，给 lookup 提供下一步阅读入口。

## 使用规则

- 路径使用项目根相对路径。
- 公共前缀写成小节标题，小节内使用短文件名或子路径。
- 每个入口只写职责摘要、关键符号和适用场景。
- 不粘贴函数体、长日志或生成物内容。

## App / Demo

### `app/src/main/java/com/niki914/demo/`

| 文件 | 关键符号 | 职责 | 适用场景 |
|:--|:--|:--|:--|
| `App.kt` | `class App` | Android Application 初始化 | 查阅应用启动时的全局配置 |

### `app/src/main/java/com/niki914/demo/ui/compose/`

| 文件 | 关键符号 | 职责 | 适用场景 |
|:--|:--|:--|:--|
| `DemoChatScreen.kt` | `fun DemoChatScreen` | 完整的聊天界面、配置弹窗以及 UI 状态订阅 | 查阅用户交互、视图结构与 MVI Intent 分发 |

## ComposeBase

### `composebase/src/main/java/com/niki914/composebase/`

| 文件 | 关键符号 | 职责 | 适用场景 |
|:--|:--|:--|:--|
| `ComposeMVIViewModel.kt` | `class ComposeMVIViewModel` | 提供 Compose UI 的基础架构支持 (MVI) | 查阅如何管理 UI State、Effect 和 Intent |

## Core Engine

### `s3ss10n/src/main/java/com/niki914/s3ss10n/`

| 文件 | 关键符号 | 职责 | 适用场景 |
|:--|:--|:--|:--|
| `Session.kt` | `interface Session` | 对话引擎的对外公开 API (发送消息、刷新 MCP、历史获取) | 查阅如何集成和调用核心对话能力 |
| `ChatSession.kt` | `class ChatSession` | 内部状态机，协调 RoundRunner、HistoryKeeper 和 MCP Client | 查阅单轮对话的生命周期控制 |
| `McpClient.kt` | `internal class HttpMcpClient` | 基于 HTTP 的 MCP 服务器交互 (initialize, tools/list, tools/call) | 查阅工具列表发现和工具调用执行细节 |

## Extensions

### `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/`

| 文件 | 关键符号 | 职责 | 适用场景 |
|:--|:--|:--|:--|
| `ChatProtocol.kt` | `interface ChatProtocol` | LLM API 协议适配边界 (请求构建、流解析) | 查阅如何支持新的 LLM (如 OpenAI, Anthropic) |

### `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/net/`

| 文件 | 关键符号 | 职责 | 适用场景 |
|:--|:--|:--|:--|
| `OkHttpEngine.kt` | `class OkHttpEngine` | 底层网络引擎实现 | 查阅 HTTP 帧发送与流处理 |
