## ADDED Requirements

### Requirement: Hooks return Message.Tool

The system SHALL expose a public `Message.Tool` result type for tool calls. `SessionConfig.hooks`, `ToolCallRequest.delegate`, `ToolCallRequest.ok`, and `ToolCallRequest.error` SHALL return `Message.Tool`.

#### Scenario: local tool returns success

- **GIVEN** a local tool call request is delivered to hooks
- **WHEN** the developer handles the call with `ok`
- **THEN** the returned value is a `Message.Tool`
- **AND** the session emits `ToolSucceeded`
- **AND** the tool result is encoded back into the next model request

#### Scenario: local tool returns failure

- **GIVEN** a local tool call request is delivered to hooks
- **WHEN** the developer handles the call with `error`
- **THEN** the returned value is a `Message.Tool`
- **AND** the session emits `ToolFailed`
- **AND** the round still receives a tool result that can be sent back to the model

#### Scenario: unhandled tool delegates

- **GIVEN** a tool call request is delivered to hooks
- **WHEN** the developer calls `delegate`
- **THEN** the returned value is a `Message.Tool`
- **AND** local tools without built-in implementation return an error tool result
- **AND** MCP tools delegate to the MCP client

### Requirement: Message.Tool does not expose MCP transport details

The public `Message.Tool` type SHALL represent tool result content and identity only. It SHALL NOT expose MCP transport, endpoint, protocol version, or server-specific response envelopes.

#### Scenario: MCP result returns through public API

- **GIVEN** an MCP tool call succeeds
- **WHEN** the result is returned to the session
- **THEN** hooks and event handling observe a normal tool result
- **AND** no MCP JSON-RPC envelope is required in public API usage

### Requirement: MCP initialize lifecycle precedes tools/list and tools/call

The HTTP MCP client SHALL initialize each enabled MCP server before calling `tools/list` or `tools/call`. After a successful initialize response, the client SHALL send `notifications/initialized` for that server fingerprint.

#### Scenario: discovery initializes server first

- **GIVEN** an enabled HTTP MCP server has no initialized state for its current fingerprint
- **WHEN** discovery wants to call `tools/list`
- **THEN** the client sends `initialize`
- **AND** the client sends `notifications/initialized` after successful initialize
- **AND** only then sends `tools/list`

#### Scenario: tool call initializes server first

- **GIVEN** an MCP tool call is delegated
- **AND** the server is not initialized for its current fingerprint
- **WHEN** `delegate` executes
- **THEN** the client completes initialize lifecycle before `tools/call`
- **AND** the call result is returned as `Message.Tool`

#### Scenario: server config update invalidates initialized state

- **GIVEN** server `local_ide` was initialized for URL `A`
- **WHEN** the session updates `local_ide` to URL `B`
- **THEN** URL `A` initialized state is not reused
- **AND** the next discovery or call for URL `B` performs initialize again

### Requirement: MCP initialize failure is non-blocking for discovery

Discovery SHALL NOT block `send()` or clear the previous successful discovery cache when initialize fails. The failure SHALL be logged with tag `qwerqwer`.

#### Scenario: initialize fails during discovery refresh

- **GIVEN** a server has a previous successful discovery cache
- **WHEN** a later discovery refresh fails during initialize
- **THEN** the previous matching cache remains available
- **AND** `send()` request construction is not blocked
- **AND** Logcat tag `qwerqwer` contains the initialize failure

### Requirement: MCP initialize failure returns tool error for calls

An MCP tool call SHALL return an error `Message.Tool` if initialize fails before `tools/call`.

#### Scenario: initialize fails during delegated call

- **GIVEN** a model requested an MCP tool
- **WHEN** initialize fails before `tools/call`
- **THEN** the session emits `ToolFailed`
- **AND** the model receives a tool result describing the failure
- **AND** the round does not hang waiting for a missing tool result

### Requirement: MCP result normalization follows PRD priority

The MCP client SHALL normalize `tools/call` responses into tool result JSON using this priority: `structuredContent`, then `content[]`, then JSON parsed from text content.

#### Scenario: structuredContent wins

- **GIVEN** an MCP `tools/call` response contains `result.structuredContent`
- **WHEN** the response is normalized
- **THEN** `structuredContent` is used as the tool result content
- **AND** lower-priority `content[]` values do not override it

#### Scenario: content array is used when structuredContent is absent

- **GIVEN** an MCP `tools/call` response has no `structuredContent`
- **AND** it contains `result.content[]`
- **WHEN** the response is normalized
- **THEN** `content[]` is converted into tool result content

#### Scenario: text content may contain JSON

- **GIVEN** an MCP `tools/call` response has text content whose text is a JSON string
- **WHEN** no higher-priority structured result exists
- **THEN** the text JSON is parsed and used as the tool result content

#### Scenario: isError marks failure

- **GIVEN** an MCP `tools/call` response contains `isError == true`
- **WHEN** the response is normalized
- **THEN** the session treats the tool call outcome as failure
- **AND** a tool result is still returned to the model

### Requirement: Session.open PRD compatibility is explicit

The project SHALL make the PRD compatibility strategy for `Session.open` explicit. It SHALL either provide a default OpenAI `Session.open {}` convenience entrypoint or document protocol-first `Session.open<SessionProtocols.OpenAI> {}` as the intentional public API.

#### Scenario: default OpenAI entrypoint is added

- **GIVEN** the project chooses compatibility strategy A
- **WHEN** a developer calls `Session.open` without a protocol type
- **THEN** the session uses the default OpenAI protocol
- **AND** existing protocol-first entrypoints continue to work

#### Scenario: protocol-first API is retained

- **GIVEN** the project chooses compatibility strategy B
- **WHEN** the PRD or project docs describe Session creation
- **THEN** the docs show the protocol-first API as intentional
- **AND** no stale default-open examples remain in authoritative docs

### Requirement: SessionConfig shape decision is explicit

The project SHALL explicitly decide whether `SessionConfig` remains class + Builder or gains a data class compatible public shape.

#### Scenario: class plus Builder remains

- **GIVEN** the project keeps the current `SessionConfig` shape
- **WHEN** docs describe Session config
- **THEN** they state that the current implementation intentionally differs from the PRD data class sketch

#### Scenario: data class compatibility is added

- **GIVEN** the project adds data class compatibility
- **WHEN** a config snapshot is created
- **THEN** dynamic update fields and open-only fields preserve the existing update semantics
