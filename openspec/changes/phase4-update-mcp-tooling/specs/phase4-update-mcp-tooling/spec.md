## ADDED Requirements

### Requirement: update uses explicit round snapshots

The system SHALL construct an immutable `SessionSnapshot` at the beginning of each `send()` call. All work in that round, including request building, tool definitions, tool dispatch, hooks, appParams, MCP registry, and timeouts, SHALL use the same snapshot. Calls to `update {}` during an active round SHALL NOT affect that active round and SHALL affect only subsequent `send()` calls.

#### Scenario: update does not affect active round

- **GIVEN** a session opened with endpoint `old` and a fake engine that captures requests
- **WHEN** `send("a")` starts and blocks inside the fake engine
- **AND** `update { endpoint = "new" }` is called before the first send completes
- **THEN** the in-flight request uses endpoint `old`
- **AND** the next `send("b")` uses endpoint `new`

#### Scenario: update changes dynamic tools for next round

- **GIVEN** a session opened with local tool `toolA`
- **WHEN** `update { localTools { replace("toolB") { ... } } }` is called
- **AND** the next `send()` builds a request
- **THEN** the request exposes `toolB`
- **AND** it no longer exposes `toolA` unless the update block kept it

### Requirement: open-only fields are not dynamically updated

The system SHALL treat `jsonCodec`, `httpEngine`, and `protocol` as open-only infrastructure. `update {}` SHALL NOT replace these objects for an existing session. If an update block attempts to change `jsonCodec` or `httpEngine`, the system SHALL ignore the change and log via `xLog("X", ...)`.

#### Scenario: update jsonCodec is ignored

- **GIVEN** a session opened with `jsonCodec = codecA`
- **WHEN** `update { jsonCodec = codecB }` is called
- **AND** a later `send()` runs
- **THEN** JSON operations still use `codecA`
- **AND** a log line explains that `jsonCodec` is open-only

### Requirement: rawJsonSchema participates in tool schema output

The system SHALL make `rawJsonSchema(json)` effective for local and MCP tool configs. When raw schema is present, it SHALL be parsed via the active `JsonCodec.decodeMap(json)` and used as the tool input schema. Raw schema SHALL take precedence over property DSL fields. Invalid raw schema SHALL fail the round with a visible `SessionEvent.Error`.

#### Scenario: raw schema overrides property DSL

- **GIVEN** a local tool config with both `rawJsonSchema("{...enum...}")` and property DSL fields
- **WHEN** OpenAIProtocol builds the request
- **THEN** the generated OpenAI tool parameters equal the decoded raw schema
- **AND** property DSL fields are ignored for that tool

#### Scenario: invalid raw schema fails visibly

- **GIVEN** a local tool config with `rawJsonSchema("{bad")`
- **WHEN** `send()` tries to build tool definitions
- **THEN** the round emits `SessionEvent.Error`
- **AND** no request is dispatched to HttpEngine

### Requirement: local and MCP tools share ToolCatalog

The system SHALL build one `ToolCatalog` per `SessionSnapshot`. The catalog SHALL include enabled local tools and enabled MCP tools. Each entry SHALL be a `ToolDescriptor` with `name`, `description`, `inputSchema`, and `kind` (`Local` or `Mcp(serverName)`). Protocol implementations SHALL encode tools from `ToolCatalog`, not from raw registries.

#### Scenario: catalog contains local and MCP tools

- **GIVEN** a config with local tool `localWeather` and MCP server `remote` containing tool `remoteSearch`
- **WHEN** a snapshot is created
- **THEN** `snapshot.tools` contains two descriptors
- **AND** `localWeather.kind == Local`
- **AND** `remoteSearch.kind == Mcp("remote")`

### Requirement: MCP registry supports explicit tool declarations

`McpServerConfig` SHALL support explicit tool declarations using a DSL such as `tool(name) { description = ...; rawJsonSchema(...) }`. Since Phase4 does not implement MCP discovery, only explicitly declared enabled MCP tools SHALL be exposed to the model.

#### Scenario: disabled MCP server is not exposed

- **GIVEN** `mcp { add("remote") { enabled = false; tool("search") { ... } } }`
- **WHEN** a snapshot builds the tool catalog
- **THEN** no descriptor from server `remote` is present

### Requirement: tool calls dispatch by ToolCatalog kind

The system SHALL dispatch tool calls by looking up the tool name in the current snapshot's `ToolCatalog`. Local descriptors SHALL create `LocalToolCallRequest`; MCP descriptors SHALL create `McpToolCallRequest`; unknown tools SHALL emit `ToolFailed` and `SessionEvent.Error(Stage.Tool)` and SHALL NOT be treated as local tools.

#### Scenario: MCP tool call creates McpToolCallRequest

- **GIVEN** a snapshot catalog containing `remoteSearch` with kind `Mcp("remote")`
- **WHEN** the model emits a tool call named `remoteSearch`
- **THEN** ChatSession creates `McpToolCallRequest`
- **AND** `delegate()` invokes the configured MCP client

#### Scenario: unknown tool fails clearly

- **GIVEN** a snapshot catalog that does not contain `ghostTool`
- **WHEN** the model emits a tool call named `ghostTool`
- **THEN** ChatSession emits `SessionEvent.ToolFailed`
- **AND** ChatSession emits `SessionEvent.Error(Stage.Tool)`

### Requirement: MCP has a minimal callable client

The system SHALL define an internal `McpClient` interface and a minimal HTTP implementation for `McpTransport.Http`. `McpToolCallRequest.delegate()` SHALL call this client and return the raw JSON result string on success. Unsupported transports SHALL return a structured tool error.

#### Scenario: HTTP MCP delegate returns JSON result

- **GIVEN** an MCP server configured with HTTP URL and headers
- **WHEN** `McpToolCallRequest.delegate()` is called
- **THEN** the MCP client sends a JSON request to that URL
- **AND** the returned response body is used as the tool result JSON

### Requirement: OkHttpEngine.close cancels active calls

`OkHttpEngine` SHALL track active `Call` instances. `close()` SHALL cancel all active calls before shutting down the dispatcher and evicting the connection pool. Calls SHALL be removed from the active set when they complete, fail, or are cancelled by Flow collection cancellation.

#### Scenario: close cancels in-flight call

- **GIVEN** an `OkHttpEngine` with an in-flight streaming call
- **WHEN** `engine.close()` is called
- **THEN** the underlying `Call.cancel()` is invoked
- **AND** the call is removed from the active set

### Requirement: Phase4 manual smoke entrypoints exist

The system SHALL provide Phase4 manual smoke entrypoints using `android.util.Log.e` directly: `main1()`, `main2()`, `main3()`, and `main()`. `main()` SHALL call `main1()`, `main2()`, and `main3()` in order. `DemoActivity` SHALL call the unified `main()`.

#### Scenario: main calls all smoke cases

- **WHEN** `com.niki914.s3ss10n.smoketest.main()` is called
- **THEN** `main1()` runs update snapshot checks
- **AND** `main2()` runs raw schema / tool catalog checks
- **AND** `main3()` runs MCP dispatch / engine close checks
- **AND** each case logs progress using `Log.e`

#### Scenario: DemoActivity triggers smoke main

- **WHEN** DemoActivity is opened in the demo app
- **THEN** it calls `com.niki914.s3ss10n.smoketest.main()` once in the intended debug path
