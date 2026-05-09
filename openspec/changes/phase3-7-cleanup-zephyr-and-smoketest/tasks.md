## 1. 调研 Zephyr 实际包名与依赖范围

- [ ] 1.1 在 `s3ss10n/build.gradle.kts` 找到 Zephyr 的依赖坐标，记录精确 group:artifact:version
- [ ] 1.2 全文 Grep 搜索 `import .*zephyr.*`（大小写不敏感），列出所有引用文件
- [ ] 1.3 分类引用：纯日志 / 纯 JSON / 其他
- [ ] 1.4 如果有"其他"类（不可简单替换），单独列出风险并在 design.md 补一个 Decision

## 2. 落地 X.kt

- [ ] 2.1 新建 `s3ss10n/src/main/java/com/niki914/s3ss10n/X.kt`：

```kotlin
package com.niki914.s3ss10n

import android.util.Log
import kotlinx.coroutines.CancellationException

private const val DEFAULT_TAG = "qwerqwer"

internal fun xLog(tag: String, str: String) {
    Log.e(tag, str)
}

internal fun xLog(tag: String, str: String, t: Throwable) {
    Log.e(tag, str, t)
}

internal fun xLog(str: String) {
    Log.e(DEFAULT_TAG, str)
}

internal fun xLog(str: String, t: Throwable) {
    Log.e(DEFAULT_TAG, str, t)
}

internal inline fun <T> xTry(name: String, block: () -> T): T? = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    xLog(DEFAULT_TAG, "xTry($name) failed", t)
    null
}
```

- [ ] 2.2 KDoc 标注：X.kt 是模块唯一允许 import android.util.Log / 写 try catch 的位置
- [ ] 2.3 编译通过

## 3. 替换所有 try/catch 与 runCatching

- [ ] 3.1 收集 T2~T6 留下的"T7 待办清单"（包括但不限于）：
  - OpenAIProtocol.parseStream / buildRequest 内部
  - `openai-compatible-reasoning-context` 新增的 `ProtocolEvent.ReasoningDelta` / assistant `reasoningContent` 累积与回传路径
  - OpenAIProtocol 中的 toolCall delta 拼接判断完整 JSON
  - `openai-compatible-reasoning-context` 对 toolCall 完整性判断的新实现（优先无异常控制流；剩余异常容错再收口到 `xTry/xLog`）
  - GsonJsonCodec.decode / decodeMap / decodeList
  - OkHttpEngine.stream / close
  - ChatSession.runRound 网络层翻译
  - ToolCallWaiter / hooks 调用点
  - T4 实际遗留：`s3ss10n/ChatSession.kt` 中 `doRound()` 的总兜底异常处理
  - T4 实际遗留：`s3ss10n/ChatSession.kt` 中 `handleToolCall()` 的 hooks 调用异常处理
  - T4 实际遗留：`s3ss10n/protocol/openai/OpenAIProtocol.kt` 中 `parseStream()` 的 JSON 反序列化异常处理
  - T4 实际遗留：`s3ss10n/protocol/openai/OpenAIProtocol.kt` 中 toolCall 完整 JSON 判定的 `try/catch`
- [ ] 3.2 全文 Grep `runCatching` —— 逐个替换为 `xTry("name", { ... })`
- [ ] 3.3 全文 Grep `} catch (` —— 逐个判断是否能用 xTry 替换；不能的（如必须区分异常类型分别处理）单独评估，并在该处加 `// xTry-exempt: <reason>` 注释
- [ ] 3.4 替换示例：

  | 旧 | 新 |
  |---|---|
  | `try { foo() } catch (t: Throwable) { Log.e(TAG, "err", t); null }` | `xTry("foo", { foo() })` |
  | `runCatching { foo() }.getOrNull()` | `xTry("foo", { foo() })` |
  | `runCatching { foo() }.getOrElse { x }` | `xTry("foo", { foo() }) ?: x` |

- [ ] 3.5 对每个被替换的位置，name 参数取"类名.方法名"或"语义短语"

## 4. 替换所有 android.util.Log 直接调用

- [ ] 4.1 全文 Grep `import android.util.Log`，逐个删除（除 X.kt）
- [ ] 4.2 全文 Grep `Log\\.(d|e|w|i|v|wtf)\\(`，把每个调用改为 `xLog(...)`
- [ ] 4.3 等级映射：所有 d/w/i/v/wtf 统一降为 e（Decision 4 已确认）
- [ ] 4.4 编译通过

## 5. 删除 Zephyr 依赖

- [ ] 5.1 修改 `s3ss10n/build.gradle.kts`，删除 Zephyr `implementation/api/compileOnly` 条目
- [ ] 5.2 全文删除所有 `import .*zephyr.*` 语句
- [ ] 5.3 替换 Zephyr 调用：
  - 日志 → xLog
  - JSON → 检查 T5 后是否还有遗漏，如有，改为通过 codec 调用
- [ ] 5.4 同步 settings.gradle / 顶级 build.gradle 中可能的 Zephyr Maven 仓库声明（如其他模块仍依赖则保留）
- [ ] 5.5 Sync 项目，确认无未解析符号

## 6. smoketest 移到 src/test

- [ ] 6.1 检查 `s3ss10n/src/test/` 当前结构与依赖配置
- [ ] 6.2 在 `s3ss10n/build.gradle.kts` 确认 testImplementation 已含 kotlinx-coroutines-test、junit、mockk/mockito（按现有偏好）
- [ ] 6.3 把 `s3ss10n/src/main/java/com/niki914/s3ss10n/smoketest/*.kt` 移动到 `s3ss10n/src/test/java/com/niki914/s3ss10n/smoketest/*.kt`
- [ ] 6.4 调整 smoketest 文件中可能的 `internal` 访问（test 与 main 同模块，可访问 internal）
- [ ] 6.5 修改 `app/src/main/java/com/niki914/demo/ui/activity/DemoActivity.kt` 第 16 行：删除 `import com.niki914.s3ss10n.smoketest.*` 与对应调用
- [ ] 6.6 如果 DemoActivity 之前有运行 smoketest 的 UI 入口，改为提示用户从 IDE 跑测试
- [ ] 6.7 全文搜索 `:app` 中的 `smoketest` 引用，确保零匹配

## 7. CI/Lint 守护（可选但推荐）

- [ ] 7.1 评估方案：通过自定义 Detekt rule 或简易 Gradle task 阻止以下模式：
  - 除 X.kt 外 import android.util.Log
  - 除 X.kt 外的 try/catch/runCatching
  - 除 GsonJsonCodec 外的 import com.google.gson
  - 除 OkHttpEngine 外的 import okhttp3
- [ ] 7.2 如时间紧张，至少在本任务结束后写一个一次性 Grep 检查脚本作为人工 PR 检查

## 8. 烟测

- [ ] 8.1 跑 `:s3ss10n:test`（移到 test 后是 JVM 单元测试），全 PASS
- [ ] 8.2 抽样人工 review 5～10 处 try/catch → xTry 的替换，确认语义等价
- [ ] 8.3 抽样人工 review 5～10 处 Log → xLog 的替换，确认 tag 与 message 等价

## 9. 编译与回归

- [ ] 9.1 `:s3ss10n:compileDebugKotlin` 通过
- [ ] 9.2 `:app:compileDebugKotlin` 通过
- [ ] 9.3 `./gradlew :s3ss10n:test` PASS
- [ ] 9.4 demo 在真机/模拟器手动验证（用户偏好不自动跑）—— 由用户人工执行；本 task 提供"建议手动验证步骤"清单

## 10. 收尾文档

- [ ] 10.1 更新 `CLAUDE.md` 索引：标注模块新约束（X.kt / 协议 / json / net 三层抽象到位 / 无 Zephyr）
- [ ] 10.2 更新 `STATUS.md`：标注 Phase 3 完成
