## ADDED Requirements

### Requirement: ChatProtocol.useApiKey explicit method

The system SHALL require every `ChatProtocol` implementation to provide a `useApiKey(apiKey: String): Map<String, String>` method. This method SHALL return the auth-related HTTP headers that the protocol derives from the `apiKey` configuration value. The returned map SHALL NOT include headers that are not auth-related (e.g., `Content-Type`, `anthropic-version`).

#### Scenario: OpenAI auth mapping

- **WHEN** `OpenAIProtocol.useApiKey("sk-abc123")` is called
- **THEN** the returned map is `{"Authorization": "Bearer sk-abc123"}`

#### Scenario: Anthropic auth mapping

- **WHEN** `AnthropicProtocol.useApiKey("sk-ant-xyz")` is called
- **THEN** the returned map is `{"x-api-key": "sk-ant-xyz"}`

#### Scenario: Empty apiKey

- **WHEN** `useApiKey("")` is called on any protocol
- **THEN** the returned map SHALL be empty (no partial auth header emitted)

### Requirement: Auth header lifecycle in ChatSession

The system SHALL call `protocol.useApiKey(snapshot.apiKey)` once per round when building the HTTP request. The returned auth headers SHALL be merged with custom headers from `SessionConfig.headers`, with custom headers taking precedence. Protocol-level non-auth headers (e.g., `Content-Type`, `anthropic-version`) SHALL be added directly in `buildRequest`, not via `useApiKey`.

#### Scenario: Auth + custom header merge

- **WHEN** `useApiKey` returns `{"x-api-key": "sk-ant-xyz"}` and custom headers include `{"X-Org-Id": "acme"}`
- **THEN** the final request headers include both `x-api-key: sk-ant-xyz` and `X-Org-Id: acme`

#### Scenario: Custom header overrides auth

- **WHEN** `useApiKey` returns `{"x-api-key": "sk-ant-xyz"}` and custom headers include `{"x-api-key": "custom-key"}`
- **THEN** the final request header is `x-api-key: custom-key`
