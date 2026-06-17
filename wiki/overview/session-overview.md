# session-overview

## 定位

Android/Kotlin session 项目包含 `app`、`composebase` 和 `s3ss10n` 核心模块，支持通过 MCP 构建对话和工具调用体系。该文档描述了项目的模块架构与边界关系。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| Android App 壳 | `Stable` | `app/src/main/java/com/niki914/demo/App.kt` | 提供测试宿主和基于 Compose 的聊天界面 |
| UI 架构 (MVI) | `Stable` | `composebase/src/main/java/com/niki914/composebase/ComposeMVIViewModel.kt` | 为 App 提供状态管理与副作用分发 |
| 核心对话引擎 | `Stable` | `s3ss10n/src/main/java/com/niki914/s3ss10n/Session.kt` | 提供纯 Kotlin 的对话流转、历史记录及 MCP 调用能力 |

## 关键源码

### `app/src/main/java/com/niki914/demo/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `App.kt` | `class App` | Android 全局配置 |

### `app/src/main/java/com/niki914/demo/ui/compose/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `DemoChatScreen.kt` | `fun DemoChatScreen` | 完整的聊天界面、配置弹窗以及 UI 状态订阅 |

## 核心链路

1. UI 交互：用户在 `DemoChatScreen.kt` 发送消息。
2. Intent 分发：基于 `composebase` 的 MVI 架构，ViewModel 处理该 Intent。
3. 引擎调用：ViewModel 调用 `s3ss10n` 模块中的 `Session` 接口进行流式对话。

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `reference/source-map.md` | 提供本页源码入口的完整路径地图 |
| `domains/s3ss10n/index.md` | 提供业务引擎的状态流转细节 |
