## ADDED Requirements

### Requirement: MCP discovery is asynchronous and cached

The system SHALL discover MCP tools by calling `tools/list` asynchronously and storing successful results in a session-scoped cache. `send()` SHALL NOT wait for MCP discovery network requests. Each round SHALL use only the discovery cache state that has completed before that round's `SessionSnapshot` is created.

#### Scenario: send does not block on initial discovery

- **GIVEN** a session configured with `mcp { add("local_ide") { http { url = "http://127.0.0.1:51337/mcp" } } }`
- **AND** no explicit MCP tools are declared
- **AND** discovery has not completed
- **WHEN** `send("a")` starts
- **THEN** request construction does not await `tools/list`
- **AND** the round uses the current cache state
- **AND** discovery may complete asynchronously after the request is built

#### Scenario: completed discovery affects the next round

- **GIVEN** a session configured with an enabled HTTP MCP server
- **AND** `send("a")` starts before discovery completes
- **WHEN** discovery later returns `search_file_names`
- **AND** `send("b")` starts after discovery completion
- **THEN** the second round's `SessionSnapshot.tools` contains `search_file_names`
- **AND** `OpenAIProtocol.buildRequest()` includes `search_file_names` in the request body

### Requirement: MCP discovery cache is keyed by server fingerprint

The system SHALL key discovered tools by server name and a fingerprint derived from discovery-relevant server config. A discovery result SHALL be written only if its fingerprint still matches the latest config for that server.

#### Scenario: stale discovery result is ignored

- **GIVEN** server `local_ide` is configured with URL `A`
- **AND** discovery for URL `A` is in flight
- **WHEN** `update { mcp { replace("local_ide") { http { url = "B" } } } }` is applied before URL `A` discovery completes
- **AND** URL `A` discovery completes after the update
- **THEN** URL `A` tools are not written into the cache for URL `B`
- **AND** future snapshots do not expose URL `A` tools for server `local_ide`

### Requirement: explicit MCP tools override discovered tools

The system SHALL merge explicit MCP tools and discovered MCP tools for each enabled server. If a discovered tool and explicit tool have the same name, the explicit tool's description and input schema SHALL win.

#### Scenario: explicit schema wins over discovered schema

- **GIVEN** discovery returns tool `search_file_names` with schema `remoteSchema`
- **AND** config explicitly declares `tool("search_file_names")` with schema `explicitSchema`
- **WHEN** a snapshot builds `ToolCatalog`
- **THEN** `search_file_names.inputSchema == explicitSchema`
- **AND** `search_file_names.kind == Mcp("local_ide")`

### Requirement: disabled MCP servers are not discovered or exposed

The system SHALL NOT schedule discovery for disabled MCP servers. Cached discovered tools from a disabled server SHALL NOT be included in snapshots while the server remains disabled.

#### Scenario: disabled server suppresses cached tools

- **GIVEN** server `local_ide` has a successful discovery cache containing `search_file_names`
- **WHEN** `update { mcp { replace("local_ide") { enabled = false } } }` is applied
- **AND** a later `send()` starts
- **THEN** no discovery is scheduled for `local_ide`
- **AND** `search_file_names` is not included in `SessionSnapshot.tools`

### Requirement: discovery failure preserves the last successful cache

The system SHALL keep the last successful matching discovery cache when a later discovery attempt fails. Discovery failure SHALL be visible through `android.util.Log.d("qwerqwer", ...)` logs and SHALL NOT block request construction.

#### Scenario: failed refresh keeps previous tools

- **GIVEN** server `local_ide` has a successful discovery cache containing `search_file_names`
- **WHEN** a later discovery refresh fails due to transport or parse error
- **THEN** the cache still contains `search_file_names`
- **AND** a later `send()` can still expose `search_file_names`
- **AND** Logcat tag `qwerqwer` includes a discovery failure message

### Requirement: discovery logs make cache behavior observable

The system SHALL log MCP discovery scheduling, skips, success, failure, cache usage, catalog merge, and OpenAI request tool names using Logcat tag `qwerqwer`.

#### Scenario: cache miss then discovery success is diagnosable

- **GIVEN** no discovery cache exists for `local_ide`
- **WHEN** `send()` triggers opportunistic discovery scheduling
- **THEN** Logcat tag `qwerqwer` shows a cache miss or discovery schedule message
- **WHEN** discovery completes
- **THEN** Logcat tag `qwerqwer` shows discovered tool names
- **AND** the next `OpenAIProtocol.buildRequest()` log includes the discovered MCP tool names

### Requirement: demo MCP server registration uses discovery

The demo app SHALL support MCP server registration without explicit tool schemas. The demo session SHALL be able to expose remote MCP tools after asynchronous discovery completes.

#### Scenario: demo exposes local IDE MCP tools after discovery

- **GIVEN** adb reverse is configured with `adb reverse tcp:51337 tcp:51337`
- **AND** the demo config contains `mcp { add("local_ide") { http { url = "http://127.0.0.1:51337/mcp" } } }`
- **AND** no explicit MCP tools are declared in the demo
- **WHEN** discovery completes
- **AND** a later chat round starts
- **THEN** the OpenAI request body contains discovered IDE MCP tools such as `search_file_names`
- **AND** MCP tool calls are delegated through `McpToolCallRequest.delegate()`
