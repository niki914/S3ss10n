# session Wiki Index

> 本文件是 lookup 总入口。任何检索先从这里开始，再按场景路由到对应子文档。

## 使用目标

这套 wiki 只负责三件事：

- 给出稳定的知识地图
- 说明应该去哪里继续读源码
- 区分源码事实、进行中能力、未来提案和未核实线索

它不复制源码，不把设计意图写成已实现事实。

## 知识地图

| 文件 | 状态 | 主要源码依据 | 用途 | Start here when |
|:--|:--|:--|:--|:--|
| `reference/source-map.md` | `Stable` | `.wiki_generator/source_inventory.md` | 聚合后的源码入口地图 | You need source entry points |
| `overview/session-overview.md` | `Stable` | `app/src/main/java/com/niki914/demo/App.kt` | 介绍 Android UI 壳、Compose MVI 与底层引擎的关系 | You need to understand project structure and modules |
| `architecture/mcp-protocol.md` | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/McpClient.kt` | 解析基于 HTTP 的 MCP 工具发现与调用架构 | You want to trace MCP execution |
| `domains/s3ss10n/index.md` | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/Session.kt` | 对话引擎的状态流转、历史记录及 API 协议扩展点 | You need to integrate or extend the core chat engine |

## 检索建议

| 场景 | 推荐阅读顺序 |
|:--|:--|
| 工程结构、模块职责、关键入口 | `overview/session-overview.md` -> `reference/source-map.md` |
| 核心链路、MCP 运行机制 | `architecture/mcp-protocol.md` -> `reference/source-map.md` |
| 业务引擎 API、状态流转、LLM 协议 | `domains/s3ss10n/index.md` -> `reference/source-map.md` |
| 判断文档事实是否可信 | `reference/source-map.md` -> 对应源码路径 |

## 状态规则

| 层级 | 含义 | 处理 |
|:--|:--|:--|
| `Stable` | 源码证据明确，能力边界清楚 | 可作为 lookup 首选入口 |
| `In Progress` | 部分实现或能力边界未闭合 | 阅读时必须核对缺口 |
| `Proposal` | 目标态、设计意图或计划 | 不作为已实现事实 |
| `Unverified` | 有线索但源码证据不足 | 先回 source-map 或源码核对 |
