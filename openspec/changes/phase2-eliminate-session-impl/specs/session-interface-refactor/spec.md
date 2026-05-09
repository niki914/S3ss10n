## ADDED Requirements

### Requirement: ChatSession implements Session directly

The system SHALL have `ChatSession` directly implement the `Session` interface, eliminating the `SessionImpl` adapter class. `Session.open{}` SHALL construct a `ChatSession` instance.

#### Scenario: Session.open returns ChatSession

- **WHEN** caller invokes `Session.open { endpoint = "..."; apiKey = "..."; model = "..." }`
- **THEN** a `ChatSession` instance is returned that satisfies the `Session` interface
- **THEN** `SessionImpl.kt` no longer exists in the source tree

### Requirement: Remove ChatSession.Callback

The system SHALL remove the `ChatSession.Callback` interface. All callback logic (event emission, hooks dispatch, text accumulation) SHALL be inlined into `ChatSession` private methods.

#### Scenario: No Callback references remain

- **WHEN** the refactor is complete
- **THEN** `ChatSession.Callback` is deleted from the codebase
- **THEN** no other type references `ChatSession.Callback`
- **THEN** the `callback` property is removed from `ChatSession`

### Requirement: ChatSession emits SessionEvent directly

`ChatSession` SHALL emit `SessionEvent` instances directly to the user-provided `onEvent` lambda during `send()`. No intermediate callback interface or event type conversion SHALL exist between `ChatSession` and the caller.

#### Scenario: send emits SessionEvent directly

- **WHEN** caller invokes `session.send("hello") { event -> /* handle */ }`
- **THEN** `SessionEvent` instances are delivered directly to the lambda
- **THEN** no `SessionImpl` or `Callback` intercepts the events

### Requirement: Companion.open constructs ChatSession

`Session.Companion.open()` SHALL construct a `SessionConfig` from the DSL block and pass it to `ChatSession(config)`, removing the `SessionImpl` construction.

#### Scenario: open() no longer references SessionImpl

- **WHEN** the refactor is complete
- **THEN** `Session.kt` does not import or reference `SessionImpl`
- **THEN** `Session.open{}` constructs `ChatSession` directly

### Requirement: Internal ChatEvent type preserved

The internal `ChatEvent` sealed interface and `SseToChatTransformLayer` SHALL continue to produce `ChatEvent` instances. `ChatSession` SHALL consume `ChatEvent` internally for `HistoryKeeper` updates and `ToolCallWaiter` coordination, then emit the corresponding `SessionEvent`.

#### Scenario: SseToChatTransformLayer still produces ChatEvent

- **WHEN** streaming an API response
- **THEN** `SseToChatTransformLayer.transformEvent()` returns `List<ChatEvent>`
- **THEN** `ChatEvent.ToolCallIntent` retains the raw `ToolCall` object for internal use
