# 任务规划清单 v1.0

## 1. Feature 列表

> 校验标记: LOC|预估

| Feature ID | 功能描述 | 预估 LOC | 依赖 Feature |
|:-----------|:---------|:---------|:-------------|
| F-01 | 协议直接测试补强：新增 `ProtocolBoundaryTest.kt`，直接覆盖 OpenAI/Anthropic stream parse 与 `encodeToolResult` 行为 | ~180 | - |
| F-02 | 大 ASC 状态修正：修正 `session_architecture_refactor_big_asc/progress.md` 中 ASC-04 滞后与 ASC-05 进行中状态 | ~10 | - |

## 2. Batch 编排表

| Batch ID | 包含 Feature | 预估总 LOC | 前置 Batch | 可并行 |
|:---------|:------------|:-----------|:-----------|:-------|
| B-01 | F-01 + F-02 | ~190 | - | - |

## 3. 任务清单 (Task List)

### Feature F-01: 协议直接测试补强

| ID | 阶段 | 类型 | 任务详情（含伪代码签名与实现步骤） | 目标文件 | 视野（依赖文件） | 匹配 Skill | 复杂度 | 预估规模 | 验收标准 (AC) |
|:---|:-----|:-----|:-------------------------------|:---------|:--------------|:-----------|:-------|:---------|:-------------|
| T-01 | Tests | Logic | 新增 `class ProtocolBoundaryTest`，直接实例化 `OpenAIProtocol(GsonJsonCodec())` 与 `AnthropicProtocol(GsonJsonCodec())`；实现测试方法：`fun \`OpenAI 普通文本 delta 解析为 TextDelta\`(): Unit`、`fun \`OpenAI reasoning content 解析为 ReasoningDelta\`(): Unit`、`fun \`OpenAI tool call arguments 分片累积到完整 JSON 后发出 ToolCallReady\`(): Unit`、`fun \`OpenAI invalid frame 解析为 Parse error\`(): Unit`、`fun \`Anthropic text delta 解析为 TextDelta\`(): Unit`、`fun \`Anthropic thinking 和 signature delta 解析为 reasoning events\`(): Unit`、`fun \`Anthropic tool_use input_json_delta 累积到 content_block_stop 后发出 ToolCallReady\`(): Unit`、`fun \`Anthropic error frame 解析为 Transport error\`(): Unit`、`fun \`OpenAI 和 Anthropic encodeToolResult 保持透传行为\`(): Unit`；可新增私有 helper：`private fun collectOpenAIEvents(vararg frames: String): List<ProtocolEvent>`、`private fun collectAnthropicEvents(vararg frames: String): List<ProtocolEvent>`、`private fun assertCompleted(events: List<ProtocolEvent>): Unit` | `s3ss10n/src/test/java/com/niki914/s3ss10n/ProtocolBoundaryTest.kt` | `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/ChatProtocol.kt`; `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/ProtocolEvent.kt`; `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/openai/OpenAIProtocol.kt`; `s3ss10n/src/main/java/com/niki914/s3ss10n/ext/protocol/anthropic/AnthropicProtocol.kt`; `s3ss10n/src/test/java/com/niki914/s3ss10n/SessionFlowRegressionTest.kt`; `s3ss10n/src/test/java/com/niki914/s3ss10n/TestSupport.kt` | - | M | ~180 lines | AC-01: OpenAI content frame 输出 `ProtocolEvent.TextDelta("hello")` 且最终 `Completed`；AC-02: OpenAI `reasoning_content` 输出 `ReasoningDelta("why")`；AC-03: OpenAI 两段 tool call arguments 仅在 JSON 完整后输出 `ToolCallReady("call-1","lookup","{\"query\":\"hi\"}")`；AC-04: OpenAI invalid frame 输出 `ProtocolEvent.Error(stage = SessionEvent.Stage.Parse)`；AC-05: Anthropic `text_delta` 输出 `TextDelta("hello")`；AC-06: Anthropic `thinking_delta` 与 `signature_delta` 分别输出 reasoning event；AC-07: Anthropic `tool_use` 在 `content_block_stop` 后输出完整 `ToolCallReady`；AC-08: Anthropic error frame 输出 `Error(stage = SessionEvent.Stage.Transport)`；AC-09: 两 provider 的 `encodeToolResult` 均返回透传 `ChatTurn.ToolResult` |

### Feature F-02: 大 ASC 状态修正

| ID | 阶段 | 类型 | 任务详情（含伪代码签名与实现步骤） | 目标文件 | 视野（依赖文件） | 匹配 Skill | 复杂度 | 预估规模 | 验收标准 (AC) |
|:---|:-----|:-----|:-------------------------------|:---------|:--------------|:-----------|:-------|:---------|:-------------|
| T-02 | Docs | Config | 修改大 ASC 进度状态，不改任务定义；伪代码：`updateBigAscProgress(currentTask = "ASC-05 in progress", asc04Status = "Completed", asc05Status = "In Progress")`；将 `Current Task` 从旧 ASC-03/04 状态更新为 ASC-05 进行中；将 Task Progress 中 ASC-04 标记为 Completed，ASC-05 标记为 In Progress，并注明 ASC-04 子 ASC 已 Phase 3 Completed | `docs/.asc_task/session_architecture_refactor_big_asc/progress.md` | `docs/.asc_task/session_architecture_refactor_big_asc/progress.md`; `docs/.asc_task/session_architecture_refactor_big_asc/big_asc.md`; `docs/.asc_task/session_architecture_refactor_04_session_state/progress.md`; `docs/.asc_task/session_architecture_refactor_05_protocol_boundary/progress.md` | - | L | ~10 lines | AC-01: 大 ASC `progress.md` 中 ASC-04 为 Completed；AC-02: 大 ASC `progress.md` 中 ASC-05 为 In Progress；AC-03: `Current Task` 指向 ASC-05；AC-04: 不改动 `big_asc.md` 的任务索引 |

## 4. 实施步骤 (Steps per Task)

### T-01: 新增 `ProtocolBoundaryTest.kt`

- [ ] 读取 `tech_design.md`、`tech_survey.md`、`plan.md`，确认 ASC-05 选择“不拆生产协议接口”。
- [ ] 新建 `ProtocolBoundaryTest.kt`，导入 `GsonJsonCodec`、`ProtocolEvent`、`OpenAIProtocol`、`AnthropicProtocol`、`flowOf`、`toList`、`runBlocking`、JUnit assert。
- [ ] 实现 `collectOpenAIEvents(vararg frames: String): List<ProtocolEvent>`，内部调用 `OpenAIProtocol(GsonJsonCodec()).parseStream(flowOf(*frames)).toList()`。
- [ ] 实现 `collectAnthropicEvents(vararg frames: String): List<ProtocolEvent>`，内部调用 `AnthropicProtocol(GsonJsonCodec()).parseStream(flowOf(*frames)).toList()`。
- [ ] 实现 `assertCompleted(events: List<ProtocolEvent>): Unit`，断言最后一个 event 为 `ProtocolEvent.Completed`。
- [ ] 添加 OpenAI text/reasoning/tool-call/invalid-frame 四组测试。
- [ ] 添加 Anthropic text/thinking-signature/tool-use/error-frame 四组测试。
- [ ] 添加两 provider `encodeToolResult` 透传测试。
- [ ] 确认测试不修改生产可见性、不访问 private accumulator、不断言 request JSON shape。

### T-02: 修正大 ASC `progress.md`

- [ ] 读取 ASC-04 子 `progress.md`，确认 `Current Phase` 为 `Phase 3 Completed`。
- [ ] 修改大 ASC `progress.md` 的 `Current Task` 为 `ASC-05 in progress`。
- [ ] 修改大 ASC Task Progress：ASC-04 为 Completed，ASC-05 为 In Progress。
- [ ] 在 Notes 中记录 ASC-04 子 ASC 已完成、ASC-05 正在推进协议边界测试补强。
- [ ] 确认不修改 `big_asc.md` 任务索引。

## 5. 审查修正记录

### Round 1: PM
- 检查结果：ASC-05 Required Test Cases 全部映射到 T-01 的 AC；大 ASC 状态冲突映射到 T-02。
- 修正记录：未加入 request JSON shape 测试，因 ASC-05 当前验收重点是 stream parse 与 `encodeToolResult`，避免扩大范围。

### Round 2: 架构师
- 检查结果：每个 Task 仅对应一个目标文件；不新增生产抽象；不修改 `ChatProtocol`、`RoundRunner`、`ChatSession`。
- 修正记录：将协议测试集中在 `ProtocolBoundaryTest.kt`，避免污染 `SessionFlowRegressionTest.kt`。

### Round 3: 结对伙伴
- 检查结果：每个 Task 均包含伪代码签名、依赖视野和可执行 AC；B-01 不跨依赖层级，两个 Feature 均无前置依赖。
- 修正记录：将 helper 函数签名写入 T-01，减少 implementer 自由发挥空间。
