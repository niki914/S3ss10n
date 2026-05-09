## ADDED Requirements

### Requirement: fullText accumulates across recursive tool-call rounds

The system SHALL accumulate `TextDelta.fullText` and `RoundCompleted.fullText` across all recursive send rounds triggered by tool calls within a single `Session.send()` invocation. The accumulator SHALL be cleared only at the start of a new `send()` call from the user, not at `onStarted()` for each internal round.

#### Scenario: Single round without tool calls

- **WHEN** user calls `send("hello")` and the model responds with text "Hello World" in a single stream
- **THEN** `TextDelta` events carry `fullText` that monotonically grows: "He" → "Hel" → "Hello" → ... → "Hello World"
- **THEN** `RoundCompleted.fullText` equals "Hello World"

#### Scenario: Two rounds triggered by a tool call

- **WHEN** user calls `send("what time is it")` and the model responds:
  - Round 1: "Let me check" followed by a tool call (getCurrentTime)
  - Round 2: "The time is 3:00 PM"
- **THEN** Round 1 `TextDelta.fullText` accumulates: "Let" → "Let me" → "Let me check"
- **THEN** Round 2 `TextDelta.fullText` continues: "Let me checkThe" → "Let me checkThe time" → "Let me checkThe time is 3:00 PM"
- **THEN** `RoundCompleted.fullText` equals "Let me checkThe time is 3:00 PM"

#### Scenario: Accumulator resets on new send()

- **WHEN** user calls `send("first")` and fullText accumulates to "First response"
- **AND** user subsequently calls `send("second")`
- **THEN** fullText for the second send starts as empty, not "First response"

#### Scenario: Multiple tool calls in sequence

- **WHEN** a send triggers tool call A (round 2), then tool call B (round 3), then final text (round 4)
- **THEN** `fullText` accumulates continuously across all 4 rounds
