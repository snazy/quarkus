# Quarkus Core External Build Updates Design Seed

Status: design seed, not an implementation plan
Last reviewed: 2026-07-09

## Objective

Define the Quarkus core/dev-mode changes needed so a build tool such as Gradle
can own compilation and resource processing while Quarkus dev mode owns runtime
reload, augmentation, Dev UI, Dev Services, and continuous-test state.

This document is the core-side companion to
`quarkus-dev-continuous-build-design.md`. The current direction is to reuse the
existing remote-dev apply/sync/reload mechanics where possible, not to build a
second reload subsystem. It intentionally stays design-level until a focused
source investigation confirms the exact implementation seams in
`RuntimeUpdatesProcessor`, `RemoteSyncHandler`, `HotReplacementContext`,
`TestSupport`, watched-file handling, and dev-mode launch/bootstrap code.

## Current Working Model

Gradle-native dev mode should not ask Quarkus to watch source roots and compile
changed sources. Gradle should emit build-iteration batches describing the
outputs it produced, and Quarkus should consume those output changes.

Core likely needs two focused capabilities:

1. A reusable file-apply/reload path for externally produced application
   outputs, preferably by extracting or adapting current remote-dev mechanics.
2. A mode that disables Quarkus-owned source watching and source compilation.

Existing remote-dev code already has much of the Quarkus-running-side machinery
needed to receive changed files, update the mutable application tree, and
trigger reload. The missing part is a clean producer seam so an external build
tool can provide build-output batches instead of Quarkus watching source roots
and compiling them itself.

Continuous testing is the place where current remote-dev mechanics are not
enough. Test output changes need a focused path into `RuntimeUpdatesProcessor`
and `TestSupport` so Quarkus can rerun affected tests without compiling test
sources itself.

Transport remains important, especially for remote dev, but it should be
treated as delivery plumbing around a build-output batch model. The core design
should not start by inventing a new transport-first reload system.

## Non-Goals

- Do not make Quarkus core depend on Gradle APIs or Gradle concepts.
- Do not replace existing Maven-style source watching and source compilation.
- Do not make `doScan(...)` mean both "scan source roots and compile" and
  "consume externally built outputs".
- Do not model dependency-project source/resource directories in core.
- Do not replace current remote-dev behavior in the first slice.
- Do not make transport choice the primary design axis; the primary axis is
  which side produces build outputs.

## Existing Remote-Dev Mechanics To Reuse

Current `quarkusRemoteDev` proves that Quarkus can keep a running remote
application synchronized from externally supplied application-file changes.
The current producer is still Quarkus-owned local source watching and
compilation, but the consumer side is directly relevant.

Relevant current mechanics:

- `QuarkusRemoteDev` switches the normal dev-mode command line to
  `QuarkusBootstrap.Mode.REMOTE_DEV_CLIENT` and uses
  `IsolatedRemoteDevModeMain` as the local developer-side entry point.
- `IsolatedRemoteDevModeMain` creates a production mutable application,
  computes file hashes below the mutable application root, and uses a
  `RemoteDevClient` to send changed and removed files.
- The Vert.x HTTP extension provides `HttpRemoteDevClient`, which performs the
  `/connect`, `/dev`, `PUT`, and `DELETE` protocol against the remote server.
- `RemoteSyncHandler` on the Quarkus-running side receives those files, checks
  a password/session/counter hash, calls `HotReplacementContext.updateFile(...)`
  for updates and `HotReplacementContext.deleteFile(...)` for deletes, and
  coordinates reload through existing hot-replacement hooks.
- `RuntimeUpdatesProcessor.syncState(...)` already compares local and remote
  mutable-app hashes on the server side and schedules cleanup for extra files.
- The remote server side is a mutable jar launched with
  `QUARKUS_LAUNCH_DEVMODE=true`, which bootstraps
  `QuarkusBootstrap.Mode.REMOTE_DEV_SERVER`.

These pieces strongly suggest that the Quarkus-running side should be evolved,
not bypassed. The first implementation should look for small extractions or
entrypoints around file application and reload triggering before introducing a
parallel mechanism.

## Current Remote-Dev Producer Limitations

The current remote-dev local side is not the model wanted for Gradle-native
dev:

- it asks Quarkus to watch source roots and compile through
  `RuntimeUpdatesProcessor` / `QuarkusCompiler`;
- it regenerates a mutable application tree and derives changed files by
  hashing that tree;
- it does not let Gradle own Kotlin/KAPT/KSP, annotation processors, generated
  sources/resources, custom source sets, dependency variants, or project
  dependency outputs;
- it does not consume Gradle continuous-build iterations or task-output
  batches.

Therefore, reuse should focus on the consumer/apply/reload mechanics, not on
the current source-watching producer.

## Proposed Core Seams

Do not start by assuming a broad new reload subsystem. The first design should
separate two seams:

1. **Application output apply/reload.** Reuse or extract the current remote-dev
   file-apply path (`RemoteSyncHandler`, `HotReplacementContext.updateFile(...)`,
   `HotReplacementContext.deleteFile(...)`, and restart/resource handling)
   where possible.
2. **Continuous-test output consumption.** Add a focused build-tool-neutral
   output-update path that can convert externally built test and production
   class outputs into a `ClassScanResult`-like trigger and route it through
   `TestSupport`.

The continuous-test seam is conceptually:

```java
RuntimeUpdatesProcessor.processBuildOutputChanges(BuildOutputChanges changes)
```

`BuildOutputChanges` should describe outputs, not source roots:

- strictly monotonic event sequence generated by the build tool;
- build status, such as succeeded, failed, cancelled, or superseded;
- optional failure summary and diagnostics location;
- main class output added/modified/deleted paths;
- main resource output changed paths;
- dependency artifact or dependency output changes;
- test class/resource output changes for continuous-test mode;
- coarse reload hint when the build tool can classify the change;
- optional user initiated / force restart flags.

The monotonic sequence is important. Core must not rely on wall-clock time,
filesystem mtimes, or NTP-sensitive ordering to decide whether an external
event is stale. Wall-clock timestamps may be carried for diagnostics, but not
for event ordering.

## Internal Processing Direction

`processBuildOutputChanges(...)` should:

- reject stale events using the monotonic sequence;
- ignore or record failed/cancelled batches without reloading from partial
  outputs;
- bypass `QuarkusCompiler` entirely;
- map changed class output paths to known `DevModeContext.ModuleInfo` class
  roots;
- build or reuse a `ClassScanResult`-like internal representation;
- update relevant timestamp/state maps so later ordinary scans do not replay
  already-consumed changes;
- reuse existing restart/no-restart/instrumentation paths instead of inventing a
  second reload engine;
- apply production class/resource/dependency output changes before publishing
  test output changes to continuous testing;
- route continuous-test output changes through existing `TestSupport` paths so
  Dev UI state remains consistent only after production output changes in the
  same batch have been successfully accepted.

The production-before-test ordering is required so continuous tests do not run
against a partially applied or failed production application state. Pure
test-only batches can skip production application, but only if the current
production state is already healthy. If production output application or reload
fails, test reruns should be held back and the production failure should become
the visible diagnostic state.

The exact implementation may require small extractions from
`RuntimeUpdatesProcessor`, `RemoteSyncHandler`, or related remote-dev helpers so
that file update/delete application, restart, and resource handling can be
reused without also invoking source scanning and compilation.

## External Build Mode

Add an explicit dev-mode setting that means:

```text
external build tool owns source watching, code generation, compilation,
resource processing, and test-suite compilation
```

In that mode, Quarkus should:

- not register source-root watchers for reload compilation;
- not invoke `QuarkusCompiler` for source changes;
- still allow runtime reload from externally reported class/resource outputs;
- still support existing Dev UI and continuous-test state where possible;
- keep Maven-style dev mode unchanged when the flag is absent.

This should be explicit rather than relying on empty watch lists as a hidden
contract. Empty lists can be an implementation detail, but the mode itself
should be named and testable.

## Delivery And Transport

The first design priority is the external build-output batch and the Quarkus
apply/reload seam. The transport is secondary delivery plumbing. Local
Gradle-native dev and remote dev may use different delivery mechanisms while
sharing the same producer/consumer contract.

Current HTTP remote-dev is already a viable delivery precedent for remote
mutable applications. A local Gradle-native dev prototype may still prefer a
small socket or in-process/session protocol, but it should not duplicate the
remote-dev apply/reload logic merely because the delivery mechanism differs.

If a new local-delivery mechanism is needed, model the connection target as an
endpoint URI, not as "TCP" directly. A first prototype may support only `tcp`,
but the internal shape should leave room for other transports:

```text
tcp://127.0.0.1:6666
stdin:
file:///path/to/events
```

The purpose is not to support every scheme immediately. It is to keep protocol
parsing, event handling, and transport mechanics separate so tests can exercise
most logic without opening sockets. If current HTTP remote-dev is evolved for a
Gradle-native remote-dev slice, it should be evaluated as another transport for
the same build-output batch/update contract.

Conceptual internal shape:

```java
interface ExternalBuildUpdateTransport {
    InputStream openInput(BuildUpdateEndpoint endpoint);
}
```

If a production transport registry is introduced in the first implementation,
it should accept only the selected production scheme, likely `tcp`. `stdin` and
`file` can stay documented alternatives until a real need exists. Unit tests
should avoid adding a production-only `test:` scheme; they can inject an
in-memory transport or call the core reload/apply API directly.

Preferred local-delivery direction if the selected prototype needs a new local
channel:

- Gradle opens a local socket listener for the current build invocation.
- Gradle generates a random per-session authorization token.
- Gradle passes host, port, and token to the Quarkus dev process at launch.
- Quarkus dev connects back and presents the token before Gradle sends any
  build-iteration events on that connection.
- The token is never printed in diagnostics or persisted in event logs.
- Gradle owns process/session lifecycle and stops Quarkus dev when the build is
  stopped.

This is not meant to be a complex security protocol. It is a local
authorization guard so unrelated local processes cannot accidentally or
maliciously attach to the build listener. If the prototype can reuse an existing
remote-dev delivery path instead, do that rather than adding a second transport
only for symmetry.

Alternatives to keep in mind:

- stdin protocol: attractive because the process is already a child of the
  build, but it competes with dev-mode console input and needs careful
  multiplexing;
- file protocol: simple, but Windows file-locking and partial-write semantics
  are likely to create avoidable failure modes;
- Quarkus-owned listener: possible, but then Gradle has to discover and
  authenticate against it; this may complicate lifecycle and stale-session
  cleanup.

Only one production delivery scheme should be in the first implementation
unless investigation finds a hard blocker. Supporting fewer schemes initially
reduces lifecycle and cross-platform risk while still preserving the transport
abstraction.

The protocol payload should remain build-tool-neutral. Gradle can be the first
producer, but the event names and data model should not mention Gradle task
types or providers.

## Delivery Backpressure And Blocking

Any delivery mechanism must be designed so neither side can accidentally block
the other side indefinitely.

Examples to avoid:

- Gradle finishes an iteration and blocks forever writing the next batch because
  Quarkus is busy applying the previous batch and is not reading from the pipe or
  socket.
- Quarkus performs reload work on the same thread that must drain transport
  input, causing the transport buffer to fill and stall Gradle.
- A file-based delivery path relies on readers observing partially written
  files or writers waiting on platform-specific file locks.

Preferred shape:

- separate transport I/O from batch processing;
- let the transport reader validate authorization, parse one complete batch,
  and enqueue or reject it quickly;
- process accepted batches serially on a separate reload/apply path;
- use a bounded queue or single-slot "latest pending batch" policy, not an
  unbounded in-memory backlog;
- use the strictly monotonic batch sequence to reject stale or superseded
  batches;
- make any Gradle-side send/accept wait bounded by a timeout with a clear
  diagnostic and cleanup path;
- distinguish "batch accepted for processing" from "batch successfully applied"
  in protocol/status terms;
- never hold a transport write open while waiting for Quarkus reload,
  augmentation, or continuous-test execution to complete.

For a local socket prototype, this means the Quarkus side should keep a small
reader loop active even while another thread applies the previous batch. For a
file-based prototype, writers should publish complete files atomically, such as
write-to-temp then move, and readers should only consume complete batch files.

## Resource Handling

Resources need a narrower scope than source roots.

For the application project:

- Gradle can report changed resource output paths.
- Core can map those to application-relative paths where possible.
- Existing watched-file restart/no-restart semantics should be reused where the
  application-relative path is known.

For dependency projects:

- Core should not inspect dependency project source/resource directories.
- The build tool should report dependency jars, dependency class directories, or
  other resolved dependency outputs.
- Quarkus should treat those as dependency artifact/output changes, not as
  dependency source/resource changes.

This matches Gradle's model: dependency resources usually matter to the
consumer through the built artifact or selected output variant, not by the
consumer inspecting the producer's source/resource directories.

## Tests And Continuous Test

Pure `quarkusApplicationDev` does not run tests. Test outputs should not drive
reload behavior unless continuous testing is enabled inside dev mode. They may
be included as diagnostic metadata if they are already part of a batch.

For `quarkusContinuousTest`, the initial default should be the default Gradle
`test` suite. Additional JVM test suites should require explicit opt-in.

Gradle-native continuous testing should receive externally built test outputs
only after Quarkus has accepted any production output changes from the same
build iteration. Ordinary Gradle `Test` task execution should be suppressed by
the Gradle plugin for the same project when a Quarkus dev, remote-dev, or
continuous-test session task is explicitly requested, while test compilation and
resource processing remain enabled so their outputs can be consumed.

Core-side continuous-test integration still needs more investigation:

- pause/resume/run-failed/run-all commands must continue to flow through
  `TestSupport`;
- Gradle-owned test compilation must not bypass Dev UI state updates;
- a no-compile path is needed for externally built test outputs.

## Build Logic Changes

Gradle continuous build does not recompute the build model between iterations.
If build scripts or plugin configuration change, users generally need to
restart the continuous build.

Core should not try to solve this. If the Gradle side can detect likely
build-logic changes, it can send a diagnostic warning batch, but correctness
must not depend on detection.

## Package Placement Direction

Current recommendation after the core production-output and TCP transport
slices:

- Keep `BuildOutputChanges` / external-output DTOs in `core/deployment` near
  `RuntimeUpdatesProcessor`.
- The DTOs are public because external build tools need to construct batches
  and send them through `BuildOutputChangesServer`.
- `BuildOutputChangesTransports.createTcpServer()` and
  `BuildOutputChangesServer` provide the build-tool-facing local listener API.
- Do not move `ClassScanResult` to `core/devmode-spi` in the first slice. It is
  deployment-internal today and tied to affected-test selection.
- A method on `RuntimeUpdatesProcessor` is enough for the first internal
  continuous-test seam, with public SPI exposure deferred.
- Which parts of `RuntimeUpdatesProcessor.doScan(...)`,
  `RuntimeUpdatesProcessor.syncState(...)`, `RemoteSyncHandler`, and
  `HotReplacementContext` file update/delete handling need extraction so the
  external-output path can reuse restart/resource logic without source
  compilation?
- Where should optional local transport code live if the first Gradle-native
  dev prototype needs it: `core/deployment/dev`, a small dev-mode transport
  package, or another module loaded by the dev-mode JVM?

## Implementation Slices To Consider

These are candidate slices, not a final plan:

1. Investigate/extract reusable file-apply/reload mechanics from current
   remote-dev so external build-output changes do not duplicate
   `RemoteSyncHandler` / `HotReplacementContext` file update/delete behavior.
2. Add explicit external-build mode and prove Quarkus source watching/compile is
   disabled in that mode while existing Maven-style mode is unchanged.
3. Add public `BuildOutputChanges` DTOs and a package-private
   `RuntimeUpdatesProcessor.processBuildOutputChanges(...)` for external output
   batches.
4. Add resource-output handling for application project resources.
5. Add failed-batch diagnostics without reload.
6. Add authenticated local delivery only if needed for the selected
   Gradle-native dev prototype.
7. Add continuous-test/test-output handling.

Each slice needs focused tests before a full implementation plan is written.

## Testing Direction

Prefer core unit tests around the reload processor, reusable file-apply logic,
and build-output batch codec before expensive integration tests.

Minimum test themes:

- stale events with lower monotonic sequence are ignored;
- successful class-output changes trigger the existing restart path without
  invoking `QuarkusCompiler`;
- failed batches keep the previous app state and expose diagnostics;
- external-build mode does not register source watchers or compile changed
  sources;
- application resource output changes preserve existing restart/no-restart
  semantics where paths can be mapped;
- any selected delivery handshake rejects missing or wrong tokens and never logs
  the token;
- delivery I/O does not block indefinitely while Quarkus processes a previous
  batch; stale/superseded batches are rejected or coalesced using the monotonic
  sequence;
- continuous-test output changes update `TestSupport` state when that slice is
  implemented.

## Current Recommendation

Design the first implementation around a small internal core seam:

```text
BuildOutputChanges
  -> RuntimeUpdatesProcessor.processBuildOutputChanges(...)
  -> existing restart/resource/test-support paths
```

For application dev reload, first reuse current remote-dev mechanics on the
Quarkus-running side wherever this can be done without preserving the current
Quarkus-owned source-watching producer. For continuous testing, add the focused
`RuntimeUpdatesProcessor`/`TestSupport` output-batch seam. Keep any protocol
adapter tiny and authenticated. Keep Gradle-specific logic in the Gradle plugin.
Keep existing Maven-style dev mode unchanged.
