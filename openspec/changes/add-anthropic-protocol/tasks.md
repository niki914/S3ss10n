## 1. Protocol auth contract

- [x] 1.1 Add `useApiKey(apiKey: String): Map<String, String>` to `ChatProtocol` interface
- [x] 1.2 Implement `useApiKey` in `OpenAIProtocol` — returns `{"Authorization": "Bearer $apiKey"}` when apiKey is non-blank
- [x] 1.3 Remove hardcoded `Authorization` header construction from `OpenAIProtocol.buildRequest`

## 2. SessionConfig / SessionSnapshot extensions

- [x] 2.1 Add `maxTokens: Int = 4096` field to `SessionConfig`, `SessionSnapshot`, and `copyInto`
- [x] 2.2 Add internal `_headers: MutableMap<String, String>` to `SessionConfig`
- [x] 2.3 Add public `header(name: String, value: Any)` DSL method to `SessionConfig`
- [x] 2.4 Carry `headers` and `maxTokens` through `toRoundSnapshot()` and `snapshot()`

## 3. ChatSession wiring

- [x] 3.1 Call `protocol.useApiKey(snapshot.apiKey)` before `buildRequest`, merge with `snapshot.headers` (custom headers take precedence)
- [x] 3.2 Build final request headers: content-type + protocol non-auth headers + merged auth+custom headers
- [x] 3.3 Pass `maxTokens` to `buildRequest` via snapshot (protocols read it from snapshot)

## 4. AnthropicProtocol implementation

- [x] 4.1 Create `AnthropicModels.kt` — request body, message, content block, and SSE frame data classes
- [x] 4.2 Create `AnthropicProtocol.kt` — implement `useApiKey`, `buildRequest`, `parseStream`, `encodeToolResult`, `withCodec`
- [x] 4.3 Implement `buildRequest`: system as top-level field, messages as content blocks, tools with flat `input_schema`, required `max_tokens`, `anthropic-version` header
- [x] 4.4 Implement `parseStream`: SSE `event:` line dispatch, `content_block_delta` text delta, `input_json_delta` accumulation, `content_block_stop` tool call ready
- [x] 4.5 Implement `encodeToolResult` → `ChatTurn.ToolResult` (same as OpenAI, history model unchanged)
- [x] 4.6 Implement history serialization: `ChatTurn.User` → `{role: "user"}`, `ChatTurn.Assistant` → `{role: "assistant", content: [...]}`, `ChatTurn.ToolResult` → `{role: "user", content: [{type: "tool_result"}]}`

## 5. SessionProtocols registration

- [x] 5.1 Add `SessionProtocols.Anthropic` object delegating to `AnthropicProtocol()`
- [x] 5.2 Register in `ProtocolRegistry` init block

## 6. OpenAIProtocol clean-up

- [x] 6.1 Adapt `buildRequest` to merge auth headers from `useApiKey` with snapshot custom headers
- [x] 6.2 Verify OpenAI path with demo app — no regressions in text streaming, tool calling, MCP

## 7. Demo app

- [x] 7.1 Add Anthropic endpoint toggle in `ChatViewModel` or a separate test path
- [ ] 7.2 Verify end-to-end: `Session.open<SessionProtocols.Anthropic> {}` → send message → receive streaming text

## 8. Verification

- [x] 8.1 Run `./gradlew :s3ss10n:compileDebugKotlin` to verify compilation
- [ ] 8.2 Manual smoke test with real Anthropic API key: text streaming, tool use round-trip, history serialization
