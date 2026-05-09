## Why

s3ss10n currently hardwires OpenAI protocol as the only chat backend. Developers who use Anthropic Claude models need first-class support — different request/response shapes, different auth headers, different tool use format. The `ChatProtocol` abstraction already exists and is the right seam; this change extends it to enforce per-protocol auth contract and adds the first non-OpenAI protocol implementation.

## What Changes

- **`ChatProtocol` gains `useApiKey(apiKey)` method**: Each protocol must explicitly declare how it consumes `apiKey` into auth headers, replacing the current implicit coupling in `buildRequest`.
- **`SessionConfig` gains `header(name, value)` DSL and `maxTokens` field**: Headers are injected into every request after protocol auth headers. `maxTokens` defaults to 4096 (required by Anthropic, compatible with OpenAI).
- **New `AnthropicProtocol`**: Implements Anthropic Messages API — SSE streaming, tool use with `input_schema` / `tool_result` content blocks, `x-api-key` + `anthropic-version` headers.
- **`SessionProtocols.Anthropic` entry**: Registered alongside OpenAI, accessible via `Session.open<SessionProtocols.Anthropic> {}`.
- **`OpenAIProtocol` adapts**: Implements `useApiKey` by producing `Authorization: Bearer <apiKey>`; no behavior change for callers.
- **BREAKING**: `ChatProtocol` interface changes (new `useApiKey` method). Only affects code that implements `ChatProtocol` directly — `Session.open` callers are unaffected.

## Capabilities

### New Capabilities
- `anthropic-protocol`: Full Anthropic Messages API protocol implementation covering SSE streaming, tool use, and history serialization
- `config-headers-dsl`: `header(name, value)` DSL in `SessionConfig` for custom HTTP headers, carried through `SessionSnapshot` into `HttpRequest`
- `protocol-apikey-contract`: `ChatProtocol.useApiKey(apiKey)` explicit method forcing each protocol to declare auth header mapping

### Modified Capabilities
- None (no existing spec-level requirements change)

## Impact

- `ChatProtocol.kt` — new `useApiKey` method (breaking interface change)
- `SessionConfig.kt` — new `maxTokens`, `header()` DSL, internal `_headers` map
- `SessionSnapshot.kt` — new `maxTokens`, `headers` fields
- `OpenAIProtocol.kt` — implement `useApiKey`, adapt `buildRequest` to consume headers from snapshot
- `SessionProtocols.kt` — new `Anthropic` entry, registration
- New files: `AnthropicProtocol.kt`, `AnthropicModels.kt`
- `ChatSession.kt` — call `useApiKey` before `buildRequest`, pass headers into snapshot
- Zero regression on OpenAI path; all existing `Session.open<SessionProtocols.OpenAI> {}` code works without changes
