## ADDED Requirements

### Requirement: ChatClient is removed and inlined into ChatSession

The system SHALL delete `s3ss10n/ChatClient.kt`. `ChatSession` SHALL directly hold the underlying network components (`OkhttpClientManager`, `ChatService`) and implement the request-build and validation logic that previously lived in `ChatClient`.

#### Scenario: ChatClient.kt no longer exists

- **WHEN** the refactor is complete
- **THEN** `s3ss10n/ChatClient.kt` does not exist
- **THEN** no source references `com.niki914.s3ss10n.ChatClient`

#### Scenario: ChatSession owns the network components directly

- **WHEN** ChatSession is constructed
- **THEN** it instantiates `OkhttpClientManager` and `ChatService` internally
- **THEN** no intermediate `ChatClient` object is created

### Requirement: ChatSession has exactly one constructor

`ChatSession` SHALL expose exactly one constructor: `internal constructor(initialConfig: SessionConfig)`. All other constructors (no-arg, multi-arg legacy form) SHALL be removed.

#### Scenario: Only the SessionConfig constructor remains

- **WHEN** the refactor is complete
- **THEN** `ChatSession()` no-arg constructor does not exist
- **THEN** `ChatSession(baseUrl, apiKey, modelName, prompt, tools)` constructor does not exist
- **THEN** only `ChatSession(initialConfig: SessionConfig)` is callable

### Requirement: Per-send state is encapsulated in RoundContext

`ChatSession` SHALL NOT hold per-send state in fields. The state of a single `send()` call (including the round-scoped config snapshot, the user-supplied `onEvent` lambda, the original input text, and the cumulative text accumulator) SHALL be captured in a private `RoundContext` object created at the entry of `send()` and passed to all internal methods participating in that round.

#### Scenario: Sequential sends do not leak state

- **GIVEN** a session with one in-flight `send` whose RoundContext has `initialInput = "A"`
- **WHEN** the round completes and a new `send("B")` starts
- **THEN** the new round's RoundContext has `initialInput = "B"`
- **THEN** no field on ChatSession was reassigned by the second send that would have affected the first round's late-arriving events (since the first round is already complete)

#### Scenario: Recursive tool-call rounds share the same RoundContext

- **GIVEN** a `send("hi")` that triggers a tool call and recurses internally
- **WHEN** the recursion runs
- **THEN** the recursive call uses the same RoundContext as the parent
- **THEN** `textAccumulator` continues to accumulate across the recursion (preserving the fullText behaviour established in phase2)

### Requirement: Session interface contains exactly the four PRD methods

The `Session` interface SHALL contain exactly: `send`, `update`, `resetConversation`, `close`. `getHistory()` SHALL be removed from the interface and from `ChatSession`. `preConnect()` SHALL be removed from `ChatSession`.

#### Scenario: getHistory no longer exists

- **WHEN** the refactor is complete
- **THEN** `Session.kt` interface declares only `send`, `update`, `resetConversation`, `close` plus the companion `open`
- **THEN** `ChatSession` has no public/internal `getHistory()` method

#### Scenario: preConnect no longer exists

- **WHEN** the refactor is complete
- **THEN** `ChatSession` has no `preConnect()` method
- **THEN** `ChatService` has no `preConnect()` method

### Requirement: ChatPair is downgraded to internal

`ChatPair` (and its companion / inner enum `RoundState`) SHALL be marked `internal` since it is no longer surfaced through any public API. Its internal usage in `HistoryKeeper` SHALL be unchanged.

#### Scenario: ChatPair is not part of the public API

- **WHEN** the refactor is complete
- **THEN** `ChatPair` is declared `internal class ChatPair`
- **THEN** consumer code outside the s3ss10n module cannot reference `ChatPair`
