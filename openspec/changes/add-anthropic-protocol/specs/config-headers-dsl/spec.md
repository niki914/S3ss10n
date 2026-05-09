## ADDED Requirements

### Requirement: Custom HTTP headers in SessionConfig

The system SHALL provide a `header(name: String, value: Any)` DSL in `SessionConfig` for adding custom HTTP headers. Headers SHALL be carried through `SessionSnapshot` and injected into every `HttpRequest` built by the protocol. Custom headers SHALL override protocol auth headers with the same name (case-insensitive).

#### Scenario: Add a single custom header

- **WHEN** a caller configures `header("X-Org-Id", "acme-corp")` in `Session.open {}`
- **THEN** every HTTP request includes `X-Org-Id: acme-corp`

#### Scenario: Custom header overrides protocol auth header

- **WHEN** a caller configures `header("x-api-key", "custom-override")` and `apiKey = "sk-ant-original"`
- **THEN** the HTTP request includes `x-api-key: custom-override` (not the protocol-generated value)

#### Scenario: Multiple headers

- **WHEN** a caller configures `header("X-Org-Id", "acme")` and `header("X-Request-Id", "uuid-123")`
- **THEN** both headers appear in the HTTP request

#### Scenario: Headers survive update

- **WHEN** a session is created with `header("X-Org-Id", "acme")` and later `update { header("X-Org-Id", "newcorp") }` is called
- **THEN** subsequent requests include `X-Org-Id: newcorp`

#### Scenario: Headers in snapshot

- **WHEN** `send()` is called
- **THEN** the `SessionSnapshot` generated for the round includes the current headers map

### Requirement: maxTokens config field

The system SHALL provide a `maxTokens: Int` field in `SessionConfig`, defaulting to `4096`. The value SHALL be carried through `SessionSnapshot` and used in `buildRequest` by protocols that require it (Anthropic) or accept it as optional (OpenAI).

#### Scenario: Default maxTokens

- **WHEN** a caller does not explicitly set `maxTokens`
- **THEN** `maxTokens` defaults to `4096`

#### Scenario: Custom maxTokens

- **WHEN** a caller sets `maxTokens = 8192` in `Session.open {}`
- **THEN** the snapshot includes `maxTokens = 8192`

#### Scenario: maxTokens in update

- **WHEN** a caller calls `session.update { maxTokens = 16384 }`
- **THEN** subsequent rounds use `maxTokens = 16384`
