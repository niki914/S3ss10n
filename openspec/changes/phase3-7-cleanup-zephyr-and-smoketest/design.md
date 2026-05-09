## Context

T1～T6 把 :s3ss10n 模块的架构定型，过渡期允许各任务沿用 try/catch + android.util.Log + Zephyr。本任务作为收尾，把所有过渡态收口：
- 日志走 X.kt 的 xLog
- 异常容错走 X.kt 的 xTry
- 删除 Zephyr 依赖
- smoketest 跨包问题彻底修复

X.kt 是 :s3ss10n 内部 helper（internal 可见），不暴露给消费方。

## Goals / Non-Goals

**Goals:**
- X.kt 落地，xLog / xTry 可用
- 整个模块内除 X.kt 外无 try/catch、runCatching、android.util.Log 直接调用
- Zephyr 依赖完全删除
- smoketest 不再被 :app 跨包 import

**Non-Goals:**
- 引入第三方日志库
- 自定义异常类型
- 修改业务逻辑（仅做"等价替换"+ 依赖清理）

## Decisions

### Decision 1: X.kt 位置与可见性

**选择**：
- 放 `s3ss10n/src/main/java/com/niki914/s3ss10n/X.kt`（顶层文件，包路径 `com.niki914.s3ss10n`）
- 函数全部 `internal` 可见性
- 不导出给 :app 或第三方

**原因**：
- 短包路径让调用方零成本：`xTry("foo", { ... })` / `xLog("hello")`
- internal 防止 SDK 消费者依赖
- 单文件便于未来换日志框架

**替代方案**：
- 放 `util/X.kt`：被否，路径多一层，import 更长
- 函数 public：被否，污染 SDK 外暴露面

### Decision 2: xLog 签名簇

**选择**：

```kotlin
internal fun xLog(tag: String, str: String) = android.util.Log.e(tag, str)
internal fun xLog(tag: String, str: String, t: Throwable) = android.util.Log.e(tag, str, t)
internal fun xLog(str: String) = android.util.Log.e(DEFAULT_TAG, str)
internal fun xLog(str: String, t: Throwable) = android.util.Log.e(DEFAULT_TAG, str, t)

private const val DEFAULT_TAG = "qwerqwer"
```

**原因**：
- 用户已明确 `xLog(tag, str)` 间接调 `Log.e`
- 默认 TAG `"qwerqwer"`（用户 user_rules 中规定）
- 带 Throwable 重载是日志实践必备
- 全部 `Log.e` 级别——用户已明确（"间接调用 Log.e"）

**替代方案**：
- 提供 d/w/i 多个等级：被否，用户只要求 e
- 用 inline + crossinline 减少调用栈：被否，过度优化

### Decision 3: xTry 签名与失败语义

**选择**：

```kotlin
internal inline fun <T> xTry(name: String, block: () -> T): T? = try {
    block()
} catch (t: Throwable) {
    xLog("qwerqwer", "xTry($name) failed", t)
    null
}
```

- 失败返回 null
- 失败时 xLog 输出 "xTry({name}) failed" + 完整 stack
- inline 让无 lambda allocation

**原因**：
- 用户明确 `xTry(name, block: () -> T): T?`
- name 用于日志区分定位
- inline 性能合理

**替代方案**：
- xTry 失败 throw 包装异常：被否，用户明确返回 T?
- 加 default value 重载 `xTry(name, default, block)`：被否，YAGNI；调用方自己 `?: default`

### Decision 4: 替换策略（机械替换为主）

**选择**：

| 旧模式 | 新模式 |
|---|---|
| `try { foo() } catch (t: Throwable) { Log.e(TAG, "err", t); null }` | `xTry("foo", { foo() })` |
| `try { foo() } catch (t: Throwable) { Log.e(TAG, "err", t); fallback }` | `xTry("foo", { foo() }) ?: fallback` |
| `runCatching { foo() }.getOrNull()` | `xTry("foo", { foo() })` |
| `runCatching { foo() }.onFailure { Log.e(...) }.getOrElse { fallback }` | `xTry("foo", { foo() }) ?: fallback` |
| `Log.e(TAG, msg, t)` | `xLog(TAG, msg, t)` |
| `Log.d(TAG, msg)` / `Log.w(...)` / `Log.i(...)` | `xLog(TAG, msg)`（统一降为 e 级别） |

**原因**：
- 替换是机械操作，可批量做
- 等级统一为 e：用户已明确——所有日志走 Log.e

**替代方案**：
- 保留多等级：被否，违反用户决策

### Decision 5: 例外——必须保留 try/catch 的位置

**选择**：本任务原则上禁止 try/catch；但以下场景例外：
- X.kt 自身的 xTry 实现（xTry 必须用 try/catch 实现）
- 协程取消异常的特别处理（`catch (e: CancellationException) { throw e }`）—— 但这种应该在 xTry 内部统一处理（CancellationException 直接 throw 不要 swallow）

更新 xTry 实现：

```kotlin
internal inline fun <T> xTry(name: String, block: () -> T): T? = try {
    block()
} catch (ce: kotlinx.coroutines.CancellationException) {
    throw ce  // 不吞协程取消
} catch (t: Throwable) {
    xLog("qwerqwer", "xTry($name) failed", t)
    null
}
```

**原因**：
- CancellationException 是协程协作取消信号，必须传播
- 否则 ChatSession.close() 会被 xTry 吃掉

### Decision 6: smoketest 移到 src/test 还是保留 main

**选择**：Path A——把 smoketest 移到 `:s3ss10n/src/test/java/.../smoketest/`，作为 JVM 单元测试运行。

**原因**：
- main 源码不应有 main() 入口测试代码
- :app 不需要 import smoketest
- IDE 直接 right-click run main()

**风险**：smoketest 当前依赖 Android 类（如 `android.util.Log`）。在 src/test 跑 JVM 单元测试时 `android.util.Log` 是 stub，行为 NOOP。可接受——smoketest 验证的是流程，不是日志输出。如需断言日志，用 mockito-kotlin mock。

**替代方案**：
- Path B 保留 main + 暴露 `runSmokeTests()` public：被否，污染 SDK
- 完全删除 smoketest：被否，T1~T6 的烟测都依赖此

### Decision 7: Zephyr 依赖删除

**选择**：
- 修改 `:s3ss10n/build.gradle.kts`，删除 Zephyr 相关 `implementation`/`api`
- 全文搜索 Zephyr 实际包名（待 tasks 中确认精确名称），逐个 import 删除
- 替换 Zephyr 中可能用到的两类 API：
  - 日志 → xLog
  - JSON → 已在 T5 通过 JsonCodec 替换

**原因**：用户明确不依赖 Zephyr。

## Risks / Trade-offs

- **机械替换可能误伤特殊语义**：极少数 try/catch 是用于"区分异常类型分别处理"，不应直接换 xTry。tasks 中要求人工 review 每一处替换。
- **xLog 全 Log.e 会让 logcat 噪音变大**：可接受，用户已决策；线上可考虑 Application 层 ProGuard 把 X.kt 的 xLog 调用 strip
- **smoketest 移到 test 后，IDE 跑 main() 路径会变**：开发者需要从 test 目录右键运行；可接受，标准做法

## Open Questions

- Zephyr 实际包名是什么？需要在 tasks 1.1 中通过 `Grep "com.bytedance.*zephyr"` 等命令确认
- :s3ss10n/src/test 当前是否已有测试基础设施（依赖、目录）？需要在 tasks 6.1 中检查
