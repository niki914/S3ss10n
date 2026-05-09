## MODIFIED Requirements

### Requirement: ChatProtocol interface defines the protocol seam

The system SHALL define `com.niki914.s3ss10n.protocol.ChatProtocol` with responsibilities that remain protocol-neutral: (1) build the protocol-specific request from a `SessionConfig` snapshot plus a neutral `List<ChatTurn>` history, (2) parse a raw stream of SSE lines into a neutral `Flow<ProtocolEvent>` including any protocol-private delta accumulation, (3) encode a tool execution result into a neutral `ChatTurn.ToolResult` for the next round.

#### Scenario: Protocol can emit reasoning deltas without leaking provider types

- **GIVEN** an OpenAI-compatible SSE stream whose delta contains `reasoning_content`
- **WHEN** `OpenAIProtocol.parseStream(...)` parses the stream
- **THEN** it emits `ProtocolEvent.ReasoningDelta(text)`
- **THEN** the event type still contains only neutral fields and no provider-specific class names

### Requirement: OpenAIProtocol is the built-in default implementation

The system SHALL provide `com.niki914.s3ss10n.protocol.OpenAIProtocol` as a built-in implementation of `ChatProtocol`, owning all OpenAI-specific request/response field names, JSON shapes, stream parsing rules, and OpenAI-compatible optional fields such as `reasoning_content`.

#### Scenario: OpenAIProtocol round-trips optional reasoning_content

- **GIVEN** an OpenAI-compatible provider that returns assistant messages containing `reasoning_content`
- **WHEN** the session records that assistant turn in neutral history and later builds the next request
- **THEN** `OpenAIProtocol` re-encodes the same `reasoning_content` into the assistant message payload
- **THEN** `ChatSession` does not need provider-specific branching to preserve that field

### Requirement: ChatTurn is the neutral history model

The system SHALL define `com.niki914.s3ss10n.ChatTurn` as a sealed interface representing protocol-neutral conversation turns, with at least these variants: `User(content)`, `Assistant(content, toolCalls, reasoningContent)`, `ToolResult(callId, toolName, resultJson)`, `System(content)`. `ToolCallSpec(callId, toolName, argumentsJson)` SHALL be the data class used inside `Assistant.toolCalls`. All fields SHALL use protocol-neutral names and SHALL NOT mirror OpenAI JSON keys, except that `reasoningContent` is allowed as a neutral semantic field describing assistant reasoning context to be preserved across rounds.

#### Scenario: Assistant turn can preserve reasoning context

- **GIVEN** an assistant turn with streamed reasoning deltas and one or more tool calls
- **WHEN** the turn is stored in history
- **THEN** the resulting value is `ChatTurn.Assistant(content = ..., toolCalls = ..., reasoningContent = ...)`
- **THEN** the reasoning content is available for future request reconstruction

### Requirement: ChatSession references only ChatProtocol and ChatTurn

The `ChatSession` class SHALL hold a reference to a `ChatProtocol` injected via its constructor, and SHALL communicate with the protocol exclusively via `buildRequest(...)`, `parseStream(...)`, and `encodeToolResult(...)`. `ChatSession` SHALL remain protocol-agnostic while being able to accumulate optional reasoning context through neutral `ProtocolEvent` and `ChatTurn` fields.

#### Scenario: ChatSession stores reasoning without provider-specific logic

- **GIVEN** a protocol that emits `ProtocolEvent.ReasoningDelta("step 1")`
- **WHEN** `ChatSession` processes the round and persists history
- **THEN** it only appends the delta to a neutral reasoning accumulator
- **THEN** it does not inspect provider names, endpoint strings, or protocol-specific JSON keys
