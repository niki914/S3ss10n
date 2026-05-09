## ADDED Requirements

### Requirement: SessionConfig is the single source of truth

The system SHALL hold all session configuration in a single `SessionConfig` class. The internal types `Config`, `ConfigBuilder`, `ConfigHolder` SHALL be removed. Field names SHALL match PRD verbatim (`endpoint`, `apiKey`, `model`, `systemPrompt`, `temperature`, `connectTimeoutSeconds`, `readTimeoutSeconds`, `writeTimeoutSeconds`).

#### Scenario: Removed types no longer exist

- **WHEN** the refactor is complete
- **THEN** `s3ss10n/Config.kt` does not exist
- **THEN** `s3ss10n/util/ConfigBuilder.kt` does not exist
- **THEN** `s3ss10n/util/ConfigHolder.kt` does not exist
- **THEN** no `import com.niki914.s3ss10n.Config` exists in the codebase
- **THEN** no `import com.niki914.s3ss10n.util.ConfigBuilder` exists in the codebase

#### Scenario: SessionConfig field names match PRD

- **WHEN** caller writes `Session.open { endpoint = "..."; apiKey = "..."; model = "..."; systemPrompt = "..."; temperature = 0.5f; connectTimeoutSeconds = 30; readTimeoutSeconds = 60; writeTimeoutSeconds = 30 }`
- **THEN** all assignments compile and take effect
- **THEN** no internal renaming (e.g. `baseUrl`, `modelName`, `prompt`, `readTimeout`) appears in the public surface or in any internal layer

### Requirement: SessionConfig.snapshot returns an isolated copy

`SessionConfig` SHALL expose `internal fun snapshot(): SessionConfig` that returns a new instance whose subsequent mutations do not affect the original, and whose contents reflect the original at the moment of snapshot.

#### Scenario: Snapshot is isolated from later mutations

- **GIVEN** a `SessionConfig` with `endpoint = "A"`
- **WHEN** `val s = config.snapshot()` is taken, then `config.endpoint = "B"`
- **THEN** `s.endpoint` is still `"A"`

#### Scenario: Snapshot copies DSL containers

- **GIVEN** a `SessionConfig` with one local tool registered and one appParams entry
- **WHEN** snapshot is taken, then a new tool is added to the original `localTools`, and a new appParams entry is added
- **THEN** the snapshot still reports the original tool count and appParams entries

### Requirement: Session.update updates active config without affecting in-flight rounds

`Session` SHALL expose `fun update(block: SessionConfig.() -> Unit)`. Calling `update` SHALL atomically replace the active config. Any round started before the `update` call SHALL continue to use the snapshot taken at its `send()` entry. The next `send()` after `update` SHALL use the updated config.

#### Scenario: In-flight round uses snapshot

- **GIVEN** a session is in the middle of a `send()` round whose snapshot has `endpoint = "A"`
- **WHEN** caller invokes `session.update { endpoint = "B" }`
- **THEN** the in-flight round continues to use `endpoint = "A"` until completion

#### Scenario: Next send uses updated config

- **GIVEN** a session whose current config has `model = "X"`
- **WHEN** caller invokes `session.update { model = "Y" }`, then `session.send("hi") { ... }`
- **THEN** the new round uses `model = "Y"`

#### Scenario: update does not clear history

- **GIVEN** a session with one user message and one assistant response in history
- **WHEN** caller invokes `session.update { endpoint = "..." }`
- **THEN** history remains unchanged

### Requirement: appParams DSL allows injecting arbitrary objects

`SessionConfig` SHALL expose `fun appParams(block: MutableMap<String, Any?>.() -> Unit)`. Entries written via this DSL SHALL be accessible to downstream layers via `internal fun appParamsSnapshot(): Map<String, Any?>`. The map SHALL allow arbitrary value types including Android `Context`, repository instances, etc.

#### Scenario: Caller writes appParams via DSL

- **WHEN** caller writes `Session.open { appParams { put("context", appContext); put("dao", myDao) } }`
- **THEN** `appParamsSnapshot()` returns a map containing both entries

#### Scenario: appParams snapshot is isolated

- **GIVEN** a session config with `appParams { put("k", "v1") }`
- **WHEN** the snapshot is taken, then `appParams { put("k", "v2") }` is called on the original
- **THEN** the snapshot's `appParamsSnapshot()` still returns `"v1"` for key `"k"`

### Requirement: ChatSession holds active config via atomic reference

`ChatSession` SHALL hold the active `SessionConfig` via an atomic reference. `send()` entry SHALL take a `snapshot()` and pass it down through the round. `update {}` SHALL replace the atomic reference with a new `SessionConfig` whose mutations have been applied.

#### Scenario: Concurrent update and send are safe

- **GIVEN** a `ChatSession` with config endpoint `"A"`
- **WHEN** `update { endpoint = "B" }` is invoked concurrently with `send(...)` (whichever wins is acceptable)
- **THEN** the in-flight round sees a consistent snapshot (either fully-A or fully-B), never a torn state

### Requirement: applyConfig is removed

`ChatSession.applyConfig()` and any per-send config copy from `SessionConfig` to `ConfigBuilder` SHALL be removed. Configuration is propagated by passing the snapshot directly.

#### Scenario: No applyConfig method exists

- **WHEN** the refactor is complete
- **THEN** `ChatSession.applyConfig` does not exist
- **THEN** no code path calls `client.updateConfig { ... }` with a `ConfigBuilder` lambda

### Requirement: socksProxy and httpProxy DSL helpers are removed

The DSL helpers `socksProxy(host, port)` and `httpProxy(host, port)` SHALL be removed since they are not part of PRD and are not surfaced via `SessionConfig`.

#### Scenario: Proxy helpers no longer exist

- **WHEN** the refactor is complete
- **THEN** no symbol named `socksProxy` or `httpProxy` exists in the s3ss10n module
