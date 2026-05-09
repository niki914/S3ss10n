## Context

s3ss10n is an Android streaming Chat Completions client. Its `ChatProtocol` interface abstracts protocol differences (OpenAI, Anthropic, etc.) behind four methods: `buildRequest`, `parseStream`, `encodeToolResult`, `withCodec`. `SessionConfig`/`SessionSnapshot` carry configuration through the session lifecycle.

The current `ChatProtocol` has no explicit contract for how `apiKey` maps to auth headers — each protocol implicitly reads `snapshot.apiKey` inside `buildRequest`. There is also no way for callers to inject custom HTTP headers, and `maxTokens` does not exist as a config field.

Adding Anthropic support requires: (a) making auth header mapping an explicit protocol contract, (b) adding `maxTokens` and custom header support to `SessionConfig`, (c) implementing Anthropic's Messages API with SSE streaming and tool use.

## Goals / Non-Goals

**Goals:**
- Add `ChatProtocol.useApiKey(apiKey)` — a single explicit method each protocol implements to declare auth header mapping
- Add `header(name, value)` DSL + `maxTokens` to `SessionConfig`, carried through `SessionSnapshot`
- Implement `AnthropicProtocol` covering: standard Messages API, SSE streaming, tool use (schema + result), all required Anthropic headers
- Register `SessionProtocols.Anthropic` for `Session.open<SessionProtocols.Anthropic> {}`
- Zero changes to public `Session` API surface beyond config DSL additions
- OpenAPI path zero regression

**Non-Goals:**
- Anthropic non-streaming mode (only SSE streaming in scope)
- Anthropic extended thinking / citations / computer use (only basic tool use)
- `stdio` MCP transport (already out of scope per PRD)
- Multi-modal or image inputs in Anthropic protocol

## Decisions

### 1. `useApiKey(apiKey): Map<String, String>` is called once per `buildRequest`

Each protocol returns auth headers from `apiKey`. The returned headers are merged with custom headers from `SessionConfig`, with custom headers taking precedence (user override wins). Decision: protocol auth headers are the base, user headers override.

Alternative considered: having protocol produce ALL headers — rejected because it mixes concerns (protocol shouldn't know about gateway headers).

### 2. `maxTokens` defaults to 4096

Anthropic requires `max_tokens`; OpenAI accepts `max_tokens` as optional. 4096 is a pragmatic default that works for both. Callers can override.

### 3. Anthropic SSE parsing uses `event:` line discrimination

Anthropic SSE events use `event: content_block_delta` / `event: message_stop` etc. The parser grabs the `event:` line, then parses the `data:` JSON based on event type. This is a different SSE dispatch model than OpenAI's "every line is a JSON frame" model, but both emit from the same `Flow<String>` of raw SSE lines — no change to `HttpEngine`.

### 4. Tool result encoding stays in `ChatProtocol`

`encodeToolResult` produces `ChatTurn.ToolResult`. Anthropic's `buildRequest` then maps `ChatTurn.ToolResult` → `{role: "user", content: [{type: "tool_result", tool_use_id: ..., content}]}`. The history model (`ChatTurn`) stays unchanged.

### 5. Anthropic tool schema uses `ToolDescriptor.inputSchema` directly

Anthropic's tool format is `{name, description, input_schema}` — the `inputSchema: Map<String, Any?>` from `ToolDescriptor` maps directly without the `function` wrapper that OpenAI uses. Both consume the same `ToolDescriptor`, just serialize differently in `buildRequest`.

### 6. `SessionConfig` stays an open class (not frozen to data class)

Per existing project convention (noted in CLAUDE.md), `SessionConfig` remains `open class` with `Builder`. New fields (`maxTokens`, `_headers`) follow the same internal pattern as `_appParams`.

## Risks / Trade-offs

- **[Anthropic `max_tokens` is required]** → Default 4096 prevents hard failures. Callers who need higher limits can set `maxTokens` explicitly.
- **[`ChatProtocol` interface change is breaking]** → Only affects internal `OpenAIProtocol` implementation. External callers use `Session.open<P>()` which is unchanged. The break is contained to the single `ChatProtocol` implementor.
- **[Anthropic SSE event model differs from OpenAI]** → `parseStream` returns the same `Flow<ProtocolEvent>`, so `ChatSession` is unaffected. The complexity is self-contained in `AnthropicProtocol.parseStream`.
- **[Anthropic tool results go into `role: "user"` messages]** → `buildRequest` handles this internally when converting `ChatTurn.ToolResult` to Anthropic message format. No change to history model or tool orchestration.
