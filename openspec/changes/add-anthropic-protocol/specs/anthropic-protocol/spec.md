## ADDED Requirements

### Requirement: Anthropic Messages API SSE streaming

The system SHALL support streaming chat completions via the Anthropic Messages API (`POST /v1/messages` with `stream: true`). The protocol implementation SHALL produce `ProtocolEvent` values (`TextDelta`, `ToolCallReady`, `Completed`, `Error`) from Anthropic SSE events, using the same `Flow<String>` SSE line contract as OpenAI.

#### Scenario: Text streaming

- **WHEN** the server emits `event: content_block_delta` with `delta.type: "text_delta"`
- **THEN** the parser emits `ProtocolEvent.TextDelta(text)` with the incremental text

#### Scenario: Tool use streaming

- **WHEN** the server emits `event: content_block_start` with `content_block.type: "tool_use"` followed by `event: content_block_delta` with `delta.type: "input_json_delta"`
- **THEN** the parser accumulates partial JSON across deltas
- **WHEN** the server emits `event: content_block_stop` for the tool_use block
- **THEN** the parser emits `ProtocolEvent.ToolCallReady(callId, toolName, argumentsJson)`

#### Scenario: Stream completion

- **WHEN** the server emits `event: message_stop`
- **THEN** the parser emits `ProtocolEvent.Completed`

#### Scenario: Stream error

- **WHEN** the SSE stream contains a non-2xx HTTP response or a malformed event
- **THEN** the parser emits `ProtocolEvent.Error` with appropriate stage

### Requirement: Anthropic request building

The system SHALL build HTTP requests that conform to the Anthropic Messages API format. The request body SHALL include `model`, `messages[]`, `max_tokens`, `stream: true`, and optionally `system` (top-level), `tools[]`, `temperature`.

#### Scenario: Basic request

- **WHEN** a session with `model = "claude-opus-4-7"`, `systemPrompt = "You are helpful."`, and `maxTokens = 4096` sends a user message
- **THEN** the request body contains `model: "claude-opus-4-7"`, `system: "You are helpful."` (top-level, not a message), `max_tokens: 4096`, and `messages` includes the user message with `role: "user"`

#### Scenario: Request with tools

- **WHEN** the tool catalog contains a `ToolDescriptor(name = "get_weather", description = "...", inputSchema = {...})`
- **THEN** the request body `tools[]` contains `{name: "get_weather", description: "...", input_schema: {...}}` (flat, no `function` wrapper)

#### Scenario: Request without system prompt

- **WHEN** `systemPrompt` is null or blank
- **THEN** the `system` field is absent from the request body

### Requirement: Anthropic tool result encoding

The system SHALL encode tool execution results into the Anthropic wire format. Tool results SHALL appear as `{role: "user", content: [{type: "tool_result", tool_use_id, content}]}` messages in the request body.

#### Scenario: Tool result after tool call

- **WHEN** a tool call with `callId = "toolu_01"`, `toolName = "get_weather"` has a result `{"temp": 22}`
- **THEN** the history includes a `ChatTurn.ToolResult` and the next `buildRequest` serializes it as a user message with `content: [{type: "tool_result", tool_use_id: "toolu_01", content: "{\"temp\": 22}"}]`

### Requirement: Anthropic history serialization

The system SHALL serialize `ChatTurn` history into Anthropic-compatible message format. `ChatTurn.User` SHALL become `{role: "user", content: "..."}`. `ChatTurn.Assistant` SHALL become `{role: "assistant", content: [{type: "text", text: "..."}, {type: "tool_use", ...}]}` (with tool_use blocks when `toolCalls` is non-empty). `ChatTurn.ToolResult` SHALL become `{role: "user", content: [{type: "tool_result", ...}]}`.

#### Scenario: Assistant message with text only

- **WHEN** an assistant turn has `content = "Hello"` and `toolCalls = emptyList()`
- **THEN** the serialized message is `{role: "assistant", content: [{type: "text", text: "Hello"}]}`

#### Scenario: Assistant message with tool calls

- **WHEN** an assistant turn has `content = ""` and `toolCalls` contains one entry with `callId = "toolu_01"`, `toolName = "get_weather"`, `argumentsJson = "{\"location\":\"NYC\"}"`
- **THEN** the serialized message is `{role: "assistant", content: [{type: "tool_use", id: "toolu_01", name: "get_weather", input: {"location": "NYC"}}]}`

### Requirement: SessionProtocols.Anthropic registration

The system SHALL provide `SessionProtocols.Anthropic` as a pre-built protocol instance. It SHALL be registered in `ProtocolRegistry` during init. Callers SHALL be able to use `Session.open<SessionProtocols.Anthropic> {}`.

#### Scenario: Open session with Anthropic protocol

- **WHEN** a caller invokes `Session.open<SessionProtocols.Anthropic> { endpoint = "https://api.anthropic.com/v1/messages"; apiKey = "sk-ant-..."; model = "claude-opus-4-7" }`
- **THEN** a `Session` is returned, backed by `AnthropicProtocol`
- **WHEN** `send("Hello")` is called
- **THEN** the request is sent to `https://api.anthropic.com/v1/messages` with headers `x-api-key: sk-ant-...` and `anthropic-version: 2023-06-01`
