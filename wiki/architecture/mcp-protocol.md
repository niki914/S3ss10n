# MCP Protocol Architecture

## 定位

描述 `s3ss10n` 模块中，如何基于 HTTP Transport 与 MCP Server 交互，包括服务的发现、工具调用及生命周期管理。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| MCP 初始化 | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/McpClient.kt` | 通过 JSON-RPC 2.0 发送 `initialize` 请求 |
| 工具发现 | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/McpClient.kt` | 解析 `tools/list` 响应并提取 Schema |
| 工具调用 | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/McpClient.kt` | 发送 `tools/call`，提取文本或结构化数据，返回字符串结果 |

## 关键源码

### `s3ss10n/src/main/java/com/niki914/s3ss10n/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `McpClient.kt` | `internal class HttpMcpClient` | 封装 JSON-RPC Payload、发起 HTTP 请求、解析 MCP 响应 |

## 核心链路

1. 引擎触发：在每轮对话开始或手动触发时，调度 `tools/list`。
2. 初始化：`HttpMcpClient` 检查生命周期缓存，未初始化则发送 `initialize` 并在成功后发送 `notifications/initialized`。
3. 执行：接收到工具调用意图后，组装 JSON 参数，请求 `tools/call`。
4. 解析：规范化 `content` 或 `structuredContent`，并拦截错误状态 (`isError`)，统一返回 String 给 LLM 侧。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 工具没有被发现 | `McpClient.kt` | `listTools` 方法中 `tools/list` 响应解析是否抛异常 |
| 工具调用返回了异常格式 | `McpClient.kt` | `normalizeResult` 方法中是否正确处理了 `isError` 或数组格式 |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `reference/source-map.md` | 提供本页源码入口的完整路径地图 |
| `domains/s3ss10n/index.md` | `Session` 是如何协调 `McpClient` 进行调用的 |
