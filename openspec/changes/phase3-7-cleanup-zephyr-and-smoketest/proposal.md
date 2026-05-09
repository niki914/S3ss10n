## Why

T1～T6 完成后，:s3ss10n 的设计已经基本到位（配置层拍平、协议/JSON/HTTP 三个抽象层就位、ChatSession 内聚）。但仍有几处遗留：

1. **Zephyr 依赖**：用户已明确不再依赖 Zephyr。Zephyr 在当前模块中主要承担两个职责——日志与 JSON 工具。JSON 在 T5 已经通过 JsonCodec 抽象掉；日志需要本任务收口。
2. **try/catch / runCatching 散落**：T2/T3/T4/T5/T6 的过渡期允许写 `try { ... } catch { android.util.Log.e("qwerqwer", ...) }`。本任务统一替换为 `xTry`。
3. **散落的 android.util.Log 调用**：直接 import `android.util.Log` 同样需要收口。统一通过 `xLog(tag, str)` 间接调用 `Log.e`，未来要换日志框架（Timber / Logcat 等）只换一个文件。
4. **smoketest 跨包 hack**：[DemoActivity.kt:16](file:///Users/bytedance/repo/android/personal/5_8_session/app/src/main/java/com/niki914/demo/ui/activity/DemoActivity.kt#L16) 跨模块 import `smoketest`。需要清理或移到合规位置。

## What Changes

- **新增**：`s3ss10n/X.kt`（顶层 internal 工具，包路径 `com.niki914.s3ss10n`）：

```kotlin
internal fun xLog(tag: String, str: String) = android.util.Log.e(tag, str)
internal fun xLog(tag: String, str: String, t: Throwable) = android.util.Log.e(tag, str, t)
internal fun xLog(str: String) = android.util.Log.e("qwerqwer", str)

internal inline fun <T> xTry(name: String, block: () -> T): T? = try {
    block()
} catch (t: Throwable) {
    xLog("qwerqwer", "xTry($name) failed", t)
    null
}
```

- **删除**：`:s3ss10n` 模块对 Zephyr 库的 Gradle 依赖（如有），以及所有 `import` 语句
- **替换**：所有 `try { ... } catch { Log.e(...) }`、`runCatching { ... }.getOrNull()` 模式 → `xTry("name", { ... })`
- **替换**：所有 `android.util.Log.{d,e,w,i,v}(...)` 直接调用 → `xLog(tag, str[, t])`
- **修复**：smoketest 跨包问题——评估两条路径：
  - Path A：把 smoketest 文件从 `:s3ss10n/src/main` 移到 `:s3ss10n/src/test`（标准 JVM 单元测试位置），demo 完全不依赖
  - Path B：smoketest 留 main，但改为 `internal` + 提供一个 public `runSmokeTests()` 入口给 demo 调
  - 倾向 Path A——smoketest 本来就不该被 demo 跨包 import

## Capabilities

### New Capabilities

- `logging-and-error-primitives`: X.kt 提供 xLog / xTry 作为模块统一日志与异常容错原语

### Removed Capabilities

- `zephyr-dependency`: 模块不再依赖 Zephyr 库

### Modified Capabilities

- `protocol-abstraction` / `json-codec-abstraction` / `http-engine-abstraction`: 各模块内部异常处理统一走 xTry；日志统一走 xLog

## Impact

- 新增：`s3ss10n/X.kt`
- 修改：`:s3ss10n/build.gradle.kts`（删除 Zephyr 依赖）
- 修改：所有含 `try/catch + Log.e` 或 `runCatching` 的文件（清单见 tasks，由 T2~T6 累积登记）
- 修改：所有 `import android.util.Log` → 删除（仅 `X.kt` 保留这一处直接 import）
- 修改：所有 `import com.bytedance.zephyr.*`（或 Zephyr 实际包名）→ 删除
- 修改：`app/src/main/java/com/niki914/demo/ui/activity/DemoActivity.kt`（移除 smoketest import）
- 移动：`:s3ss10n/src/main/.../smoketest/*` → `:s3ss10n/src/test/.../smoketest/*`（如选 Path A）

## Non-Goals

- 引入 Timber / Logcat 等第三方日志库（X.kt 内部留这一个口子，未来要换只换 X.kt）
- 引入新异常类型层级
- 删除 OkHttp / Gson Gradle 依赖
- 任何新功能
