## ADDED Requirements

### Requirement: Old ToolModel/ToolManager system is removed

The system SHALL remove the legacy tool execution layer. The files `s3ss10n/toolbase/ToolManager.kt`, `s3ss10n/toolbase/ToolModel.kt`, `s3ss10n/toolbase/ToolCallJsonTransformLayer.kt` SHALL be deleted. `ChatSession` SHALL not hold any `ToolManager` instance.

#### Scenario: Old files no longer exist

- **WHEN** the refactor is complete
- **THEN** `s3ss10n/toolbase/ToolManager.kt` does not exist
- **THEN** `s3ss10n/toolbase/ToolModel.kt` does not exist
- **THEN** `s3ss10n/toolbase/ToolCallJsonTransformLayer.kt` does not exist
- **THEN** `ChatSession` source contains no `toolManager` field or `ToolManager` reference

### Requirement: LocalToolRegistry is the sole source of local tool schemas

The system SHALL use `LocalToolRegistry` (driven by `localTools { }` DSL) as the only source of local tool schema definitions sent to the model.

#### Scenario: Tools registered via DSL appear in request

- **WHEN** caller registers two tools via `localTools { add("toast") {...}; add("setVolume") {...} }`
- **THEN** the request body sent to the model includes both tool definitions
- **THEN** no other registration mechanism is required to surface the tools

### Requirement: ToolCallRequest exposes appParams

`ToolCallRequest` SHALL expose `val appParams: Map<String, Any?>` populated from the round-scoped snapshot of `SessionConfig.appParamsSnapshot()`.

#### Scenario: appParams accessible in hooks

- **GIVEN** a session opened with `appParams { put("ctx", contextInstance) }`
- **WHEN** a tool call arrives and hooks block runs
- **THEN** `call.appParams["ctx"]` returns the original `contextInstance`

#### Scenario: appParams reflects snapshot taken at send entry

- **GIVEN** a session whose appParams currently has `{"k": "v1"}`
- **WHEN** `send()` starts (snapshot taken), then caller invokes `update { appParams { put("k", "v2") } }`
- **THEN** the in-flight tool call still observes `appParams["k"] == "v1"`

### Requirement: delegate has explicit semantics

`ToolCallRequest.delegate()` SHALL have explicit, predictable semantics:

- For `kind == ToolCallKind.Local`: returns a standardized `Message.Tool` whose content indicates "local tool requires hooks implementation". The implementation SHALL NOT attempt to look up or execute any registered tool model.
- For `kind is ToolCallKind.Mcp`: returns a placeholder error indicating MCP is not implemented yet (until T4 supersedes this).

#### Scenario: delegate on Local returns error tool message

- **GIVEN** a local tool call with name `"toast"`
- **WHEN** hooks calls `call.delegate()`
- **THEN** the returned `Message.Tool` has content explaining no built-in implementation exists
- **THEN** the call's outcome is recorded as Failure (so ChatSession emits ToolFailed)

### Requirement: ok and error record explicit outcome

`ToolCallRequest.ok(contentJson)` SHALL record the call as Success with `resultJson = contentJson`. `ToolCallRequest.error(message, contentJson)` SHALL record the call as Failure with the given message and json. `ChatSession` SHALL read this recorded outcome to decide whether to emit `ToolSucceeded` or `ToolFailed`. The framework SHALL NOT use string-content matching (e.g. `"error" in content`) to infer success/failure.

#### Scenario: ok produces ToolSucceeded

- **GIVEN** a hooks block that returns `call.ok("""{"shown":true}""")`
- **WHEN** ChatSession processes the result
- **THEN** a `SessionEvent.ToolSucceeded` is emitted with `resultJson == """{"shown":true}"""`

#### Scenario: error produces ToolFailed

- **GIVEN** a hooks block that returns `call.error("permission denied")`
- **WHEN** ChatSession processes the result
- **THEN** a `SessionEvent.ToolFailed` is emitted with `message == "permission denied"`

#### Scenario: ok content containing the substring "error" is still Success

- **GIVEN** a hooks block that returns `call.ok("""{"detail":"no error occurred"}""")`
- **WHEN** ChatSession processes the result
- **THEN** a `SessionEvent.ToolSucceeded` is emitted (NOT ToolFailed)

### Requirement: Tool stage is correctly populated

`SessionEvent.Error` and `SessionEvent.ToolFailed` events related to tool dispatch problems SHALL use `Stage.Tool`. Specifically:

- When `hooks` block throws an exception: emit `ToolFailed(stage = Tool, ...)`
- When no `hooks` block is configured at all: emit `ToolFailed(stage = Tool, message = "no hooks configured")`

#### Scenario: hooks throws, ToolFailed with Tool stage

- **GIVEN** a hooks block that throws `IllegalStateException("boom")`
- **WHEN** ChatSession processes the tool call
- **THEN** a `SessionEvent.ToolFailed` is emitted (note: ToolFailed is itself stage-less; the related `Error` follow-up event, if any, SHALL use `Stage.Tool`)

#### Scenario: no hooks configured

- **GIVEN** a session opened without any `hooks { }` block
- **WHEN** the model issues a tool call
- **THEN** a `SessionEvent.ToolFailed` is emitted with message indicating hooks is missing
- **THEN** the round still completes (with a tool error injected back to the model) instead of hanging
