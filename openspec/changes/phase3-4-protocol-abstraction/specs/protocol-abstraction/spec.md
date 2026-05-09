## ADDED Requirements

### Requirement: ChatProtocol interface defines the protocol seam

The system SHALL define an interface `com.niki914.s3ss10n.protocol.ChatProtocol` with exactly three responsibilities: (1) build the protocol-specific request body from a `SessionConfig` snapshot plus a neutral `List<ChatTurn>` history, (2) parse a raw stream of SSE lines into a neutral `Flow<ProtocolEvent>` (handling any per-protocol delta accumulation such as OpenAI tool_call argument concatenation internally), (3) encode a tool execution result into a neutral `ChatTurn.ToolResult` for the next round.

#### Scenario: ChatProtocol exposes only neutral types

- **WHEN** `ChatProtocol` is declared
- **THEN** its public method signatures reference only `SessionConfig`, `ChatTurn`, `ProtocolEvent`, and primitive/standard types
- **THEN** no method signature mentions OpenAI/Anthropic/Google specific class names

#### Scenario: ToolCall delta accumulation is protocol-private

- **WHEN** OpenAIProtocol parses an SSE stream containing a tool_call split across multiple delta frames
- **THEN** OpenAIProtocol internally concatenates the partial `arguments` strings until a complete JSON is detected
- **THEN** it emits a single `ProtocolEvent.ToolCallReady(callId, toolName, argumentsJson)` event with the fully assembled JSON
- **THEN** ChatSession sees only the assembled event and contains no concatenation logic

### Requirement: OpenAIProtocol is the built-in default implementation

The system SHALL provide `com.niki914.s3ss10n.protocol.OpenAIProtocol` as a built-in implementation of `ChatProtocol`, owning all OpenAI-specific request/response field names, JSON shapes, and stream parsing rules. The previous OpenAI-shaped code in `chat/protocol/ChatApiRequestBody.kt`, `chat/SseToChatTransformLayer.kt`, `chat/protocol/Message.kt`, `chat/protocol/ChatBeans.kt`, and `util/ToolCallHandler.kt` SHALL be either deleted or moved into OpenAIProtocol's private/internal scope.

#### Scenario: OpenAI-specific field names live only inside OpenAIProtocol

- **WHEN** searching the s3ss10n module for `tool_calls` / `choices` / `delta.content`
- **THEN** all hits are within files under `s3ss10n/protocol/` (specifically the OpenAIProtocol implementation tree)
- **THEN** `ChatSession.kt` contains zero references to these strings

#### Scenario: OpenAIProtocol can use Gson directly during T4

- **WHEN** OpenAIProtocol needs to serialize/deserialize JSON during T4
- **THEN** it MAY directly import `com.google.gson.Gson`
- **AND** these imports SHALL be migrated to an injected `JsonCodec` in T5

### Requirement: Session.open is generic over a ChatProtocol type

The `Session.open` factory SHALL be generic in a reified protocol type parameter:

```kotlin
inline fun <reified P : ChatProtocol> open(noinline builder: SessionConfig.Builder.() -> Unit): Session
```

The implementation SHALL resolve the concrete `ChatProtocol` instance bound to `P` via a `ProtocolRegistry` lookup keyed by `KClass`. Built-in protocols SHALL be auto-registered when `SessionProtocols` is class-loaded.

#### Scenario: PRD-form open call compiles and runs

- **GIVEN** `SessionProtocols.OpenAI` is the type token for the OpenAI protocol
- **WHEN** the developer writes `Session.open<SessionProtocols.OpenAI> { baseUrl = "..."; apiKey = "..." }`
- **THEN** it compiles
- **THEN** the returned `Session` is bound to an `OpenAIProtocol` instance for its lifetime

#### Scenario: Custom protocol registration

- **GIVEN** a developer defines `class MyProtocol : ChatProtocol { ... }`
- **WHEN** they call `ProtocolRegistry.register(MyProtocol::class, MyProtocol())` once at app startup
- **AND** then call `Session.open<MyProtocol> { ... }`
- **THEN** the session is bound to their custom protocol

#### Scenario: Unregistered protocol fails fast

- **WHEN** the developer calls `Session.open<UnknownProtocol> { ... }` without prior registration
- **THEN** an `IllegalStateException` (or domain-specific subclass) is thrown with a clear message naming the missing protocol class

### Requirement: ChatTurn is the neutral history model

The system SHALL define `com.niki914.s3ss10n.ChatTurn` as a sealed interface representing protocol-neutral conversation turns, with at least these variants: `User(content)`, `Assistant(content, toolCalls)`, `ToolResult(callId, toolName, resultJson)`, `System(content)`. `ToolCallSpec(callId, toolName, argumentsJson)` SHALL be the data class used inside `Assistant.toolCalls`. All fields SHALL use protocol-neutral names and SHALL NOT mirror OpenAI JSON keys.

#### Scenario: HistoryKeeper stores ChatTurn

- **WHEN** the refactor is complete
- **THEN** `HistoryKeeper` internal storage is `MutableList<ChatTurn>`
- **THEN** `ChatPair.kt` is deleted from the module
- **THEN** the previous `ChatPair.RoundState` enum is deleted (its semantics flow through `SessionEvent` now)

#### Scenario: Tool result content is opaque JSON string

- **GIVEN** a tool returns `{"answer":42}`
- **WHEN** ChatSession records the result as `ChatTurn.ToolResult(callId, toolName, resultJson = "{\"answer\":42}")`
- **THEN** OpenAIProtocol can re-encode this resultJson into `{"role":"tool","tool_call_id":callId,"content":"{\"answer\":42}"}` shape without ChatSession knowing the shape

### Requirement: getHistory returns List<ChatTurn>

The `Session` interface SHALL expose `suspend fun getHistory(): List<ChatTurn>` (re-added in T4 after being removed in T3). The list SHALL include all `User`, `Assistant`, and `ToolResult` turns in temporal order, and SHALL NOT include `System` turns (system prompt is configuration, not history).

#### Scenario: getHistory returns turns in order

- **GIVEN** a session that has completed: user "hi" → assistant "hello"
- **WHEN** `getHistory()` is called
- **THEN** it returns `[ChatTurn.User("hi"), ChatTurn.Assistant("hello", emptyList())]`
- **THEN** no `ChatTurn.System` is present

#### Scenario: System prompt is not in history

- **GIVEN** a session with `systemPrompt = "be terse"`
- **WHEN** `getHistory()` is called before any send
- **THEN** it returns an empty list

### Requirement: ChatSession references only ChatProtocol and ChatTurn

The `ChatSession` class SHALL hold a reference to a `ChatProtocol` injected via its constructor, and SHALL communicate with the protocol exclusively via `buildRequestBody`, `parseStream`, and `encodeToolResult`. `ChatSession` SHALL NOT import any OpenAI-specific types (`ChatApiRequestBody`, `Message`, `ChatBeans` shapes, `tool_calls` keys, etc.).

#### Scenario: ChatSession is protocol-agnostic

- **WHEN** the refactor is complete
- **THEN** `ChatSession.kt` imports `ChatProtocol`, `ChatTurn`, `ProtocolEvent`, `SessionConfig`, `SessionEvent`
- **THEN** `ChatSession.kt` does NOT import any class under `s3ss10n.chat.protocol` (those types either no longer exist or are private to OpenAIProtocol)

### Requirement: ProtocolRegistry is thread-safe

`ProtocolRegistry` SHALL use a `ConcurrentHashMap<KClass<out ChatProtocol>, ChatProtocol>` for storage. Built-in protocols SHALL register themselves in `SessionProtocols`'s static initialization block.

#### Scenario: Concurrent registration is safe

- **WHEN** two threads simultaneously call `ProtocolRegistry.register(...)` with different protocol classes
- **THEN** both registrations succeed
- **THEN** subsequent lookups return the correct instances
