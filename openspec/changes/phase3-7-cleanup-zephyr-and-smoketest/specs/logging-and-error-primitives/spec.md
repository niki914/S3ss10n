## ADDED Requirements

### Requirement: X.kt provides xLog and xTry primitives

The system SHALL provide a top-level Kotlin file `s3ss10n/src/main/java/com/niki914/s3ss10n/X.kt` containing the following internal-visible functions:

- `xLog(tag: String, str: String)` — delegates to `android.util.Log.e(tag, str)`
- `xLog(tag: String, str: String, t: Throwable)` — delegates to `android.util.Log.e(tag, str, t)`
- `xLog(str: String)` — delegates to `android.util.Log.e("qwerqwer", str)`
- `xLog(str: String, t: Throwable)` — delegates to `android.util.Log.e("qwerqwer", str, t)`
- `xTry(name: String, block: () -> T): T?` — runs the block; on `Throwable` (except `CancellationException` which is rethrown), logs via `xLog("qwerqwer", "xTry($name) failed", t)` and returns `null`

#### Scenario: xTry swallows non-cancellation exceptions

- **GIVEN** a block that throws `IllegalArgumentException("boom")`
- **WHEN** `xTry("compute", { block() })` is called
- **THEN** it returns `null`
- **THEN** an `Log.e("qwerqwer", "xTry(compute) failed", <exception>)` line is emitted

#### Scenario: xTry rethrows CancellationException

- **GIVEN** a block that throws `kotlinx.coroutines.CancellationException`
- **WHEN** `xTry("foo", { block() })` is called from a coroutine being cancelled
- **THEN** the CancellationException propagates out of `xTry`
- **THEN** no log is emitted for it

#### Scenario: xLog default tag is "qwerqwer"

- **WHEN** `xLog("hello")` is called
- **THEN** the underlying `Log.e` is invoked with tag `"qwerqwer"` and message `"hello"`

### Requirement: Module forbids try/catch and runCatching outside X.kt

The `:s3ss10n` module SHALL NOT contain any `try { ... } catch { ... }` blocks or `runCatching { ... }` calls outside `X.kt`. All exception handling SHALL go through `xTry`.

#### Scenario: Codebase scan finds no forbidden patterns

- **WHEN** searching the `:s3ss10n` source tree (excluding `X.kt` and `src/test`) for `runCatching` or `} catch (`
- **THEN** zero matches are found

### Requirement: Module forbids direct android.util.Log usage outside X.kt

The `:s3ss10n` module SHALL NOT directly call `android.util.Log.{d,e,w,i,v,wtf}` outside `X.kt`. All logging SHALL go through `xLog`.

#### Scenario: Direct Log usage is forbidden

- **WHEN** searching the `:s3ss10n` source tree (excluding `X.kt`) for `import android.util.Log` or `android.util.Log.`
- **THEN** zero matches are found

### Requirement: Zephyr dependency is fully removed

The `:s3ss10n` module SHALL NOT depend on Zephyr. The Gradle dependency SHALL be removed from `build.gradle.kts`. All `import` statements referencing the Zephyr package SHALL be removed.

#### Scenario: Gradle does not declare Zephyr

- **WHEN** reading `s3ss10n/build.gradle.kts`
- **THEN** no `implementation` / `api` / `compileOnly` line references Zephyr

#### Scenario: No Zephyr imports remain

- **WHEN** searching the `:s3ss10n` source tree for the Zephyr package prefix (to be confirmed by tasks)
- **THEN** zero matches are found

### Requirement: smoketest is moved to src/test or removed from main consumers

The smoketest files SHALL be relocated from `s3ss10n/src/main/.../smoketest/` to `s3ss10n/src/test/.../smoketest/`. The `:app` module SHALL NOT import any class from the smoketest package.

#### Scenario: smoketest no longer in main source set

- **WHEN** the refactor is complete
- **THEN** `s3ss10n/src/main/java/.../smoketest/` does not exist (or is empty)
- **THEN** `s3ss10n/src/test/java/.../smoketest/` contains the smoketest files

#### Scenario: DemoActivity does not import smoketest

- **WHEN** searching `:app` sources for `import com.niki914.s3ss10n.smoketest`
- **THEN** zero matches are found
- **THEN** `DemoActivity.kt` does not reference any smoketest class

### Requirement: Each migrated file replaces previous transitional patterns

For each file that was modified during T2/T3/T4/T5/T6 with transitional `try/catch + Log.e` blocks (registered in those tasks' "T7 todo" lists), this task SHALL replace those blocks with `xTry` and the logs with `xLog`.

#### Scenario: OpenAIProtocol uses xTry

- **GIVEN** OpenAIProtocol previously had `try { codec.decode(...) } catch (t: Throwable) { Log.e(...); null }`
- **WHEN** T7 is complete
- **THEN** the code is `xTry("OpenAIProtocol.parseFrame", { codec.decode(...) })`
