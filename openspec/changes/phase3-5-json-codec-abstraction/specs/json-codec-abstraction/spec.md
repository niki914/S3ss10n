## ADDED Requirements

### Requirement: JsonCodec interface defines minimal JSON seam

The system SHALL define an interface `com.niki914.s3ss10n.json.JsonCodec` with exactly four methods: `encode(value: Any?): String`, `decode(json: String, type: Class<T>): T?`, `decodeMap(json: String): Map<String, Any?>?`, `decodeList(json: String): List<Any?>?`. Decode methods SHALL return `null` on failure (not throw) and SHALL log the failure via `android.util.Log.e("qwerqwer", ...)` (T7 will replace with `xLog`).

#### Scenario: JsonCodec returns null on parse failure

- **GIVEN** a JsonCodec instance and an invalid JSON string `"{not valid"`
- **WHEN** `decode("{not valid", String::class.java)` is called
- **THEN** it returns `null`
- **THEN** an `Log.e` line is emitted with tag `"qwerqwer"`

#### Scenario: JsonCodec interface does not expose Gson types

- **WHEN** `JsonCodec.kt` is read
- **THEN** no method signature mentions `Gson`, `JsonElement`, `TypeToken`, or any other library-specific type
- **THEN** all parameters and return types are JDK / Kotlin standard library types

### Requirement: GsonJsonCodec is the sole Gson dependency point

The system SHALL provide `com.niki914.s3ss10n.json.GsonJsonCodec` as the default `JsonCodec` implementation. `GsonJsonCodec.kt` SHALL be the only file in the `:s3ss10n` module that contains `import com.google.gson.*`. All other Gson usages elsewhere in the module SHALL be removed.

#### Scenario: Gson imports outside GsonJsonCodec are forbidden

- **WHEN** searching the `:s3ss10n` module sources for `import com.google.gson`
- **THEN** the only file matching is `s3ss10n/json/GsonJsonCodec.kt`

#### Scenario: GsonJsonCodec is the default for built-in protocol

- **GIVEN** a developer calls `Session.open<SessionProtocols.OpenAI> { /* no jsonCodec set */ }`
- **WHEN** the session executes a send
- **THEN** all JSON encode/decode operations inside OpenAIProtocol go through a `GsonJsonCodec` instance

### Requirement: SessionConfig allows custom JsonCodec injection

`SessionConfig.Builder` SHALL expose a `jsonCodec(codec: JsonCodec)` DSL method (or a property assignment) that lets developers override the default codec. When a custom codec is provided, the session SHALL use it for all subsequent rounds.

#### Scenario: Custom codec is honoured

- **GIVEN** a developer provides a custom `class MyCodec : JsonCodec` and calls `Session.open<SessionProtocols.OpenAI> { jsonCodec = MyCodec() }`
- **WHEN** the session sends a message
- **THEN** all encode/decode calls inside OpenAIProtocol invoke methods on the `MyCodec` instance, not on `GsonJsonCodec`

#### Scenario: Default codec is GsonJsonCodec when not provided

- **GIVEN** a developer calls `Session.open<SessionProtocols.OpenAI> { /* jsonCodec unset */ }`
- **WHEN** the session is created
- **THEN** the effective codec is a `GsonJsonCodec()` instance

### Requirement: OpenAIProtocol routes all JSON through injected JsonCodec

`OpenAIProtocol` SHALL accept `JsonCodec` via its constructor (with default `GsonJsonCodec()`). All internal JSON encode/decode operations SHALL be delegated to this injected codec. Direct `Gson()` instantiation or `gson.toJson(...)` calls SHALL NOT appear in OpenAIProtocol or its sub-files.

#### Scenario: OpenAIProtocol does not import Gson

- **WHEN** the refactor is complete
- **THEN** files under `s3ss10n/protocol/openai/` do NOT contain `import com.google.gson`
- **THEN** all JSON operations in OpenAIProtocol go through the injected `JsonCodec` reference

### Requirement: LocalToolRegistry routes all JSON through JsonCodec

`LocalToolRegistry` SHALL receive a `JsonCodec` reference (either via constructor or as an internal var set by ChatSession during construction). All schema-JSON and tool-argument-JSON operations SHALL go through this codec.

#### Scenario: LocalToolRegistry uses the session's codec

- **GIVEN** a session opened with a custom `JsonCodec`
- **WHEN** the developer registers a tool and the LocalToolRegistry produces or consumes JSON
- **THEN** it uses the session's codec, not a separate Gson instance

### Requirement: JsonUtil and other JSON helpers are removed

Any pre-existing `s3ss10n/util/JsonUtil.kt` (or similarly named JSON helper) SHALL be deleted. All call sites SHALL be migrated to use a `JsonCodec` reference.

#### Scenario: JsonUtil no longer exists

- **WHEN** the refactor is complete
- **THEN** `s3ss10n/util/JsonUtil.kt` does not exist
- **THEN** no source file imports or references `JsonUtil`
