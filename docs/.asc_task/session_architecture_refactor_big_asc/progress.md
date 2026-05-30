# Session Architecture Refactor Big ASC Progress

## Current Phase
Planning Created

## Current Task
ASC-05 completed

## Batch Pause Mode
Continuous execution for current child ASC batches. Acceptance requires unit tests and compile to pass.

## Task Progress
| ID | Status | Child ASC Dir | Notes |
|---|---|---|---|
| ASC-00 | Completed | `docs/.asc_task/session_architecture_refactor_00_tests/` | 行为锁定测试已补充；review、单测、编译均通过 |
| ASC-01 | Completed | `docs/.asc_task/session_architecture_refactor_01_mcp_discovery/` | MCP discovery coordinator 已拆出；review、单测、编译均通过 |
| ASC-02 | Completed | `docs/.asc_task/session_architecture_refactor_02_tool_call/` | ToolCall Coordinator 已拆出；review、单测、编译均通过 |
| ASC-03 | Completed | `docs/.asc_task/session_architecture_refactor_03_round_runner/` | RoundRunner 已拆出；review、单测、编译均通过 |
| ASC-04 | Completed | `docs/.asc_task/session_architecture_refactor_04_session_state/` | 子 ASC 已 Phase 3 Completed；review、单测、编译均通过 |
| ASC-05 | Completed | `docs/.asc_task/session_architecture_refactor_05_protocol_boundary/` | 协议边界保持现状；已补 OpenAI/Anthropic 直接协议单测；review、单测、编译均通过 |
| ASC-06 | Pending | `docs/.asc_task/session_architecture_refactor_06_cleanup/` | 最终回归、清理、review |

## Global Context
`ChatSession.kt` 当前承担过多运行时职责。大 ASC 的目标不是一次性开发，而是作为上层路线图，驱动 subagent 串行执行多个完整小 ASC。每个小 ASC 都必须有充分单元测试，且默认不改变外部行为。

## Notes
- ASC-04 子 ASC 已标记 Phase 3 Completed；大 ASC 中的 ASC-04 Pending 为滞后状态，已在 ASC-05 B-01 修正。
- ASC-05 已完成；当前策略为保持 `ChatProtocol` 现状并补 OpenAI/Anthropic 直接协议单测。

## Stop Conditions
- 任一小 ASC 的测试无法覆盖关键行为。
- 任一小 ASC 发现需要改变公开 API。
- 任一小 ASC review 发现行为回归。
- 任一小 ASC 需要同时修改多个职责边界。

## Modified Files
- `docs/.asc_task/session_architecture_refactor_big_asc/big_asc.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/progress.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/tasks/ASC-00-characterization-tests.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/tasks/ASC-01-mcp-discovery-coordinator.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/tasks/ASC-02-tool-call-coordinator.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/tasks/ASC-03-round-runner.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/tasks/ASC-04-session-state-boundary.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/tasks/ASC-05-protocol-boundary-review.md`
- `docs/.asc_task/session_architecture_refactor_big_asc/tasks/ASC-06-final-regression-and-cleanup.md`
- `docs/.asc_task/session_architecture_refactor_01_mcp_discovery/tech_survey.md`
- `docs/.asc_task/session_architecture_refactor_01_mcp_discovery/tech_design.md`
- `docs/.asc_task/session_architecture_refactor_01_mcp_discovery/plan.md`
- `docs/.asc_task/session_architecture_refactor_01_mcp_discovery/progress.md`
- `s3ss10n/src/test/java/com/niki914/s3ss10n/TestSupport.kt`
- `s3ss10n/src/test/java/com/niki914/s3ss10n/McpRefreshTest.kt`
- `s3ss10n/src/main/java/com/niki914/s3ss10n/McpDiscoveryCoordinator.kt`
- `s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt`
- `docs/.asc_task/session_architecture_refactor_02_tool_call/tech_survey.md`
- `docs/.asc_task/session_architecture_refactor_02_tool_call/tech_design.md`
- `docs/.asc_task/session_architecture_refactor_02_tool_call/plan.md`
- `docs/.asc_task/session_architecture_refactor_02_tool_call/progress.md`
- `s3ss10n/src/test/java/com/niki914/s3ss10n/TestSupport.kt`
- `s3ss10n/src/test/java/com/niki914/s3ss10n/SessionFlowRegressionTest.kt`
- `s3ss10n/src/main/java/com/niki914/s3ss10n/ToolCallCoordinator.kt`
- `s3ss10n/src/main/java/com/niki914/s3ss10n/ChatSession.kt`
- `docs/.asc_task/session_architecture_refactor_03_round_runner/tech_survey.md`
- `docs/.asc_task/session_architecture_refactor_03_round_runner/tech_design.md`
- `docs/.asc_task/session_architecture_refactor_03_round_runner/plan.md`
- `docs/.asc_task/session_architecture_refactor_03_round_runner/progress.md`
- `s3ss10n/src/main/java/com/niki914/s3ss10n/RoundRunner.kt`
- `s3ss10n/src/main/java/com/niki914/s3ss10n/McpDiscoveryCoordinator.kt`
- `s3ss10n/src/test/java/com/niki914/s3ss10n/McpRefreshTest.kt`
