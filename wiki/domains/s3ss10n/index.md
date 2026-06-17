# s3ss10n 引擎流程

## 定位

分析 `s3ss10n` 模块，了解对话引擎的公开 API、内部核心状态机以及底层网络和协议扩展点的设计。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| Session API | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/Session.kt` | 暴露发送消息、刷新 MCP、历史记录等核心能力 |
| 内部状态协调 | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt` | 串联 Protocol、HttpEngine、HistoryKeeper 与 MCP 逻辑 |
| 网络与协议扩展 | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/ChatProtocol.kt` | 定义底层 HTTP 发送与响应流解析的标准接口 |

## 关键源码

### `s3ss10n/src/main/java/com/niki914/s3ss10n/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `Session.kt` | `interface Session` | 对话引擎的对外公开 API |
| `ChatSession.kt` | `class ChatSession` | 内部状态机协调器 |

### `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `ChatProtocol.kt` | `interface ChatProtocol` | LLM 协议接口 |

### `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/net/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `OkHttpEngine.kt` | `class OkHttpEngine` | 默认底层网络引擎 |

## 核心链路

1. 创建：通过 `Session.Companion.open` 构建 `ChatSession` 实例。
2. 发送：调用 `Session.send()` 触发 `ChatSession` 内部状态机，它将历史、系统 prompt 组装成 Snapshot。
3. 请求生成：委托给 `ChatProtocol.buildRequest()` 生成 HTTP 请求，交由 `OkHttpEngine` 执行。
4. 流解析：流数据经过 `ChatProtocol.parseStream()`，产出文本或工具调用意图，并在 `ChatSession` 中分发给 MCP Client 或抛给 UI。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 新接入一种 LLM 模型不生效 | `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/ChatProtocol.kt` | `buildRequest` 和 `parseStream` 是否按照新模型规范实现 |
| 会话中断或状态错乱 | `s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt` | `awaitRound` 或协程 Scope 是否正常管理了异常 |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `reference/source-map.md` | 提供本页源码入口的完整路径地图 |
| `architecture/mcp-protocol.md` | `ChatSession` 将解析出的意图转交 MCP Client 调用的内部机制 |
