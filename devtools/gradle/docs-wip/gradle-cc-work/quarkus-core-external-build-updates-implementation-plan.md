# Quarkus Core External Build Updates Implementation Plan

Status: implementation plan; production-output core seam completed for PR 2
Last reviewed: 2026-07-09

## Objective

Add the Quarkus core/dev-mode seams needed by Gradle-native dev and continuous
testing without introducing a Gradle dependency in core and without replacing
the existing Maven-style source-watching dev mode.

After this plan, Quarkus core should have an internal, build-tool-neutral path
that can consume externally built output batches, bypass Quarkus source
compilation for those batches, reuse existing reload/test infrastructure, and
leave transport and Gradle task wiring to later Gradle-plugin work. That path
must be reachable only through the new explicit API/update source and must not alter
existing dev-mode or remote-dev behavior.

The later transport slice adds a disabled-by-default
`DevModeContext.ExternalBuildOutputTransport` launch-context object. That
object carries an optional loopback endpoint URI plus auth token. When present,
`RuntimeUpdatesProcessor` opens the corresponding Quarkus-side connection and
feeds received production-output batches into
`processBuildOutputChanges(...)`.

## Required Reading

Before editing code, read:

- `devtools/gradle/docs-wip/gradle-cc-work/quarkus-core-external-build-updates-design.md`
- `devtools/gradle/docs-wip/gradle-cc-work/quarkus-dev-continuous-build-design.md`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/RuntimeUpdatesProcessor.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/ClassScanResult.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/DevModeContext.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/IsolatedDevModeMain.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/IsolatedRemoteDevModeMain.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/testing/TestSupport.java`
- `core/devmode-spi/src/main/java/io/quarkus/dev/spi/HotReplacementContext.java`
- `extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/devmode/RemoteSyncHandler.java`

## Hard Gates

- Do not add Gradle APIs or Gradle concepts to core, deployment, runtime,
  devmode-spi, or vertx-http.
- Do not change default Maven-style source watching or compilation behavior.
- Do not change any existing behavior without explicit approval and evidence
  from characterization tests or source analysis proving the behavior is broken.
  When in doubt, add characterization tests first and preserve the observed
  contract.
- Do not make `HotReplacementContext.doScan(...)` mean "consume external
  output"; it remains source-scan and compile oriented.
- Keep transports out of the first core seam. The implemented follow-up
  transport slice is documented in
  `archive/quarkus-core-external-build-transport-implementation-plan.md`.
- Do not move `ClassScanResult` to `core/devmode-spi` in this plan.
- Do not introduce unbounded event queues or blocking transport semantics.
- Keep helper types package-private or deployment-internal unless a public SPI
  is explicitly needed. The external build-tool listener API now intentionally
  exposes public `BuildOutputChanges*` DTOs plus `BuildOutputChangesServer` and
  `BuildOutputChangesTransports.createTcpServer()`.
- Preserve current remote-dev behavior. Any fix to existing remote-dev behavior
  must be proposed separately after characterization tests prove the current
  behavior is broken and after explicit approval.
- New external-output behavior must be additive and default-inactive. Existing
  dev-mode, remote-dev, and continuous-testing code paths must not change unless
  explicitly approved.

## Scope

In scope:

- Characterization tests for current remote-dev PUT/delete behavior and current
  `RuntimeUpdatesProcessor.updateFile(...)` behavior.
- An explicit external-build update source carried by `DevModeContext`.
- A build-tool-neutral external output batch DTO in `core/deployment`, near
  `RuntimeUpdatesProcessor`.
- A direct `RuntimeUpdatesProcessor.processBuildOutputChanges(...)` style API
  that can be exercised without sockets, files, or Gradle.
- Main/test class-output change mapping into `ClassScanResult`.
- Failed, cancelled, superseded, stale, and busy batch handling.
- Continuous-test routing through `TestSupport`.
- Unit tests or focused deployment tests for the new core behavior.

Out of scope:

- Gradle plugin continuous-build orchestration.
- Local socket, stdin, file, or HTTP protocol implementation for Gradle-native
  delivery in the production-output core seam PR. TCP delivery is tracked in
  `archive/quarkus-core-external-build-transport-implementation-plan.md`.
- Dev UI protocol changes beyond preserving existing `TestSupport` behavior.
- Full remote-dev redesign.
- Remote-dev PUT/delete behavior changes unless separately approved after
  characterization.
- Kotlin/KAPT/KSP-specific Gradle behavior. Core consumes outputs only.
- Cross-project source/resource inspection.

## Phase 0 Findings

Phase 0 source verification is complete.

Concrete findings:

- `RuntimeUpdatesProcessor.doScan(...)` currently owns the reusable reload
  decision path after it has computed class and resource changes:
  - runs pre-scan hooks;
  - calls `checkForChangedClasses(...)`, which is source/compile oriented;
  - calls `checkForFileChange(...)`, which copies resources and maps watched
    files to restart/no-restart decisions;
  - attempts instrumentation reload when possible;
  - runs pre-restart hooks, `restartCallback.accept(...)`, and post-restart
    hooks for restart-required changes;
  - calls `notifyExtensions(...)` for no-restart file changes.
- The right extraction point is inside `RuntimeUpdatesProcessor`, after class
  and resource changes are already known. Add a package-private/private helper
  that accepts a precomputed `ClassScanResult`, changed resource paths,
  `userInitiated`, `forceRestart`, and a start time. `doScan(...)` should call
  that helper after doing source scanning; the external-output path should call
  the same helper without invoking source scanning or compilation.
- `ClassScanResult` is already the correct internal representation for class
  output changes. Its `addAddedClass(...)`, `addChangedClass(...)`, and
  `addDeletedClass(...)` methods compute class names from a classes root and a
  class file path, which is exactly what externally reported class-output roots
  need. Package path separators become `.`, but inner-class `$` separators are
  preserved. For example, `com/acme/Foo$Bar.class` becomes
  `com.acme.Foo$Bar`, not `com.acme.Foo.Bar`.
- `TestSupport.runTests(ClassScanResult)` already owns continuous-test queueing
  and should remain the only queue used for test execution. The external-output
  path should feed it only after production output changes in the same batch are
  successfully applied.
- `DevModeContext` is the best carrier for the external-build update source. It is
  the serialized launch context from the build tool to the isolated dev-mode
  process and already carries static launch/session metadata. `DevModeType`
  should not be extended for this first slice because the new update source changes who
  owns update production, not the broad runtime identity.
- There is no existing build-tool-neutral output-batch API in core. Existing
  "build output" references are unrelated packaging/codegen/container concepts.
- The remote-dev delete concern is confirmed and fixed as an independent
  remote-dev bug fix. `RemoteSyncHandler.handleDelete(...)` previously passed
  `null` into `HotReplacementContext.updateFile(...)`, while
  `RuntimeUpdatesProcessor.updateFile(...)` writes the byte array directly. The
  fixed shape adds `HotReplacementContext.deleteFile(String file)` and keeps
  write/update semantics separate from delete semantics.
- DELETE path handling previously differed from PUT: PUT stripped the HTTP root
  path before calling `updateFile(...)`, while DELETE passed `event.path()`
  directly. The fixed shape keeps the existing client-side DELETE URL/password
  proof shape, but strips the configured root path before calling
  `deleteFile(...)` on the server side.
- Existing cheap tests are sparse:
  - `core/deployment` has `RecompilationDependenciesProcessorTest`, which shows
    a precedent for a package-local `RuntimeUpdatesProcessor` test subclass but
    does not test reload processing;
  - `extensions/vertx-http/runtime` has devmode unit tests, but no
    `RemoteSyncHandler` test covering PUT/DELETE;
  - many `QuarkusDevModeTest` and continuous-testing integration tests exist,
    but they are too heavy for the first direct core seam.

Implementation consequences:

- Add focused unit tests in `core/deployment/src/test/java/io/quarkus/deployment/dev`
  for the new direct external-output processing helpers.
- Add a focused `RemoteSyncHandler` runtime test or extract a tiny path
  normalizer helper that can be tested cheaply without a full Vert.x server.
- Be precise about "disabling watchers" in external-build mode. The first slice
  should disable Quarkus-owned source/codegen/test-compilation scan loops for
  external-output sessions, but must preserve watched-file metadata from
  `HotDeploymentWatchedFileBuildItem` because that metadata is still needed to
  decide restart versus no-restart for externally reported resource outputs.

## Phase 0: Source Verification And Final Shape

This phase is read-mostly and should be completed before code changes beyond
tests or tiny local probes.

1. Confirm the exact restart/no-restart path currently inside
   `RuntimeUpdatesProcessor.doScan(...)`:
   - class change scan;
   - resource/watch file scan;
   - instrumentation reload;
   - pre-restart and post-restart callbacks;
   - `restartCallback.accept(...)`;
   - `notifyExtensions(...)`.
2. Confirm which pieces can be extracted without changing existing behavior.
   Preferred extraction target:
   - a private/package-private helper that receives already computed
     `ClassScanResult`, changed resource paths, `userInitiated`, and
     `forceRestart`, then performs instrumentation/restart/no-restart handling.
3. Confirm `DevModeContext` is the best existing convention for the
   external-build update source.
   - Preferred shape: `BuildUpdateSource buildUpdateSource`.
   - Values: `QUARKUS`, `EXTERNAL_BUILD_TOOL`.
   - Default and null fallback: `QUARKUS`.
   - Rationale: `DevModeContext` is the serialized static launch context from
     the build tool to the dev-mode process.
   - Do not add a new `DevModeType` unless source review shows that the mode
     affects broad runtime identity rather than only update ownership.
4. Confirm no existing public API already represents a build-output batch.
5. Confirm existing tests around `RuntimeUpdatesProcessor`, remote-dev, and
   continuous testing that can be extended cheaply.
6. Record any surprise in
   `quarkus-core-external-build-updates-design.md` before implementation.

Acceptance:

- Completed. The extraction points are documented in `Phase 0 Findings`.
- Completed. Use `DevModeContext` for the external-build update source unless code review
  during implementation finds a concrete existing convention that Phase 0
  missed.
- Completed. No open source-shape question blocks Phase 1.

## Phase 1: Characterize And Fix Remote-Dev File Application Semantics

The original remote-dev delete path was ambiguous and likely broken:

- `RemoteSyncHandler.handleDelete(...)` calls
  `hotReplacementContext.updateFile(path, null)`;
- `RuntimeUpdatesProcessor.updateFile(...)` currently writes the byte array
  directly with `Files.write(...)`.

The safe sequence was to first lock the current contract, then make the narrow
delete fix as an independent remote-dev bug fix.

1. Add characterization tests before changing implementation:
   - lock the current PUT path passed from `RemoteSyncHandler` to
     `HotReplacementContext.updateFile(...)`;
   - lock the current DELETE path behavior;
   - source-characterize the current client-side DELETE URL/password-hash path
     shape from `HttpRemoteDevClient`; defer direct client tests until there is
     an approved cheap test seam or transport-level test, because the current
     client starts a session thread and opens real connections;
   - document the observed contract in test names or comments.
2. Do not change the remote-dev HTTP wire shape or URL/root-path contract in
   this phase except for the approved server-side fix that routes DELETE through
   the same root-path stripping as PUT before applying it to the application
   root.
3. Add direct characterization tests for `RuntimeUpdatesProcessor.updateFile(...)`
   as it behaves today:
   - non-null data writes a nested file;
   - leading slash handling behaves as currently implemented;
   - `null` data behavior is captured exactly, even if that means the test
     currently demonstrates an exception rather than a successful delete.
4. Add the narrow delete fix:
   - add `HotReplacementContext.deleteFile(String file)`;
   - update `RuntimeUpdatesProcessor.deleteFile(...)` to normalize the file path
     the same way as `updateFile(...)` and delete the target with
     `Files.deleteIfExists(...)`;
   - keep `RuntimeUpdatesProcessor.updateFile(...)` as the write/update API and
     reject `null` data explicitly;
   - update `RemoteSyncHandler.handleDelete(...)` to verify the request exactly
     as before, then call `deleteFile(stripRootPath(event.path()))`.
5. Do not add path traversal hardening in this phase. If hardening is desired,
   record it as a separate compatibility-reviewed follow-up because it can
   change accepted inputs.

Acceptance:

- Remote-dev PUT and DELETE path contracts are explicit and tested.
- `HttpRemoteDevClient` DELETE path/hash behavior is source-characterized and
  remains unchanged.
- `RuntimeUpdatesProcessor.updateFile(...)` current write behavior is explicit
  and tested.
- `RuntimeUpdatesProcessor.deleteFile(...)` delete behavior is explicit and
  tested.
- The only implementation behavior changed by this phase is the approved
  remote-dev delete fix.

## Phase 1 Findings

Phase 1 characterization and the approved remote-dev delete fix are complete.

- `RemoteSyncHandler.handlePut(...)` strips the configured HTTP root path before
  calling `HotReplacementContext.updateFile(...)`.
- `RemoteSyncHandler.handlePut(...)` leaves the request path unchanged when it
  does not start with the configured root path.
- `RemoteSyncHandler.handleDelete(...)` originally passed the raw request path
  and `null` data to `HotReplacementContext.updateFile(...)`.
- `RemoteSyncHandler.handleDelete(...)` now verifies the same raw request path
  used by the client password proof, then calls
  `HotReplacementContext.deleteFile(...)` with the configured root path stripped.
- `RuntimeUpdatesProcessor.updateFile(...)` writes non-null data to nested
  paths, strips one leading slash, and rejects `null` data.
- `RuntimeUpdatesProcessor.deleteFile(...)` strips one leading slash and deletes
  the target if it exists.
- `HttpRemoteDevClient` currently builds DELETE requests from `url + "/" +
  file` and hashes `"/" + file` for the DELETE password proof. Direct client
  tests are deferred until a cheap seam or transport-level test is approved.

## Out Of Current Scope: Path Hardening For Remote-Dev File Application

The remote-dev delete fix intentionally preserves the existing path
normalization behavior. It does not add path traversal hardening or otherwise
change which relative paths are accepted by remote-dev file application.

If hardening is desired, handle it as a separate compatibility-reviewed
follow-up with explicit tests for accepted and rejected paths.

## Phase 2A: Add Inert External Build Mode To DevModeContext

Add an explicit mode that means the external build tool owns source watching,
code generation, compilation, resource processing, and test-suite compilation.
This is additive and default-inactive; existing dev-mode behavior must remain
unchanged when the update source is `QUARKUS`. Phase 2A only carries the mode through the
serialized dev-mode launch context; it does not change runtime behavior.

1. Add an enum-backed field to `DevModeContext`.
   Preferred names:
   - enum: `BuildUpdateSource`;
   - values: `QUARKUS`, `EXTERNAL_BUILD_TOOL`;
   - field: `buildUpdateSource`;
   - getter: `getBuildUpdateSource()`;
   - setter: `setBuildUpdateSource(BuildUpdateSource buildUpdateSource)`.
2. Keep the default `QUARKUS`.
3. Treat `null` as `QUARKUS`, including for older serialized contexts where the
   field is absent or deserializes as `null`.
4. Add focused tests that prove the default, setter, Java serialization, and
   null fallback behavior.
5. Do not change `RuntimeUpdatesProcessor`, watcher registration, compiler
   setup, or dev-mode launch behavior in this slice.

Acceptance:

- `DevModeContext` carries the update source.
- The default is `QUARKUS`.
- `null` is normalized to `QUARKUS`.
- A serialized/deserialized `DevModeContext` preserves the update source.
- No runtime behavior changes.

## Phase 2A Findings

Phase 2A is complete. `DevModeContext` now carries the inert
`BuildUpdateSource` enum, defaults it to `QUARKUS`, normalizes `null` to
`QUARKUS`, and preserves explicit enum values through Java serialization. No
dev-mode runtime behavior uses the mode yet.

## Phase 2B: Apply External Build Mode In RuntimeUpdatesProcessor

Use the Phase 2A update source to prevent Quarkus-owned source/codegen/test-compilation
work from running in external-build sessions while preserving existing behavior
when the update source is `QUARKUS`.

1. Update dev-mode setup so `BuildUpdateSource.EXTERNAL_BUILD_TOOL` prevents Quarkus-owned source/codegen
   watching and source/test compilation scan loops from running for
   external-output update processing.
2. Do not disable extension hot-replacement setup, restart hooks, Dev UI state,
   or `TestSupport`.
3. Do not change behavior when the update source is `QUARKUS`.

Implementation guidance:

- Do not remove `QuarkusCompiler` construction in the first cut unless tests
  prove it is unnecessary and safe. It may still be used by existing code paths.
- The important invariant is that `processBuildOutputChanges(...)` does not
  invoke `QuarkusCompiler` and that external-build sessions do not register or
  run Quarkus-owned source/codegen/test-compilation scan loops.
- Preserve watched-resource metadata registration for restart/no-restart
  decisions; external build mode changes who reports resource output changes,
  not the hot-deployment watched-file semantics.
- If disabling watcher registration requires a narrower mode than the whole
  `RuntimeUpdatesProcessor`, introduce a package-private helper rather than
  spreading update-source checks through unrelated code.

Acceptance:

- Default dev mode still watches and compiles sources as before.
- External-build mode can be enabled by a launch context.
- Tests prove that the new external-output path bypasses compiler-driven source
  scanning.

## Phase 2B Findings

Phase 2B is complete for the production-output PR 2 slice.

- `RuntimeUpdatesProcessor.doScan(...)` now returns without compiler-driven
  source/resource scanning when `DevModeContext.getBuildUpdateSource()` is
  `EXTERNAL_BUILD_TOOL`.
- The common "computed changes -> instrumentation/restart/no-restart handling"
  path has been extracted into `processApplicationChanges(...)`.
- Existing default behavior remains on the original path when the update source
  is `QUARKUS`.

Deferred Phase 2B work:

- Decide whether any test-scanning timer or watcher registration paths also
  need explicit external-build gating once continuous-test output handling is
  wired.
- Add broader characterization around existing `doScan(...)` behavior if later
  refactoring touches source/resource scan order.

## Phase 3: Add BuildOutputChanges DTOs

Add build-tool-neutral DTOs under `core/deployment` in
`io.quarkus.deployment.dev`. These DTOs are inert until the new external-output
API is called, but they are public because external build tools must construct
them and pass them to `BuildOutputChangesServer.send(...)`.

Recommended initial types:

```java
public enum BuildOutputChangeStatus {
    BUILD_SUCCEEDED,
    BUILD_FAILED,
    BUILD_CANCELLED,
    BUILD_SUPERSEDED
}

public enum BuildOutputChangeKind {
    ADDED,
    MODIFIED,
    DELETED
}

public record BuildOutputPathChange(Path outputRoot, Path changedPath, BuildOutputChangeKind kind) {
}

public record BuildOutputChanges(
        long sequence,
        BuildOutputChangeStatus status,
        List<BuildOutputPathChange> mainClassChanges,
        List<BuildOutputPathChange> mainResourceChanges,
        List<BuildOutputPathChange> testClassChanges,
        List<BuildOutputPathChange> testResourceChanges,
        List<Path> changedDependencyPaths,
        String failureSummary,
        Path diagnosticsPath,
        boolean userInitiated,
        boolean forceRestart) {
}
```

Phase 3 production-output implementation is complete for the PR 2 slice:

- public DTOs exist under `io.quarkus.deployment.dev`;
- `RuntimeUpdatesProcessor.processBuildOutputChanges(...)` accepts successful
  main class/resource output changes and routes them through
  `processApplicationChanges(...)`;
- monotonic sequence handling rejects stale batches;
- every non-stale batch advances the sequence, including failed, cancelled,
  superseded, and live-reload-disabled batches, so an older success cannot be
  applied later;
- non-success statuses no-op and do not reload;
- main class output paths are mapped to `ClassScanResult`;
- main resource output paths are mapped to OS-agnostic paths relative to their
  reported output root;
- each changed output path must normalize under its declared output root;
- test class/resource fields are accepted but intentionally ignored for now;
- changed dependency paths are accepted but intentionally ignored for now.

Deferred Phase 3 work:

- Decide how failed-build diagnostics should be surfaced.
- Add test class/resource output routing through `TestSupport`.
- Add changed dependency handling.

Adjust names and null handling to match Quarkus style, but preserve these
semantics:

- `sequence` is strictly monotonic and is the only stale-event ordering input;
- failed/cancelled/superseded batches are not reloadable;
- all path lists default to empty lists, never `null`;
- paths describe output locations, not source roots;
- Gradle terms do not appear in the type names or fields.

Acceptance:

- DTOs are immutable or effectively immutable.
- DTOs are public build-tool-facing API because the external build tool creates
  and sends batches through the core-provided listener.
- Unit tests cover null/default handling and stale sequence comparison helper
  behavior if such a helper exists.

## Phase 4: Implement Direct Batch Processing

Add a direct processing API on `RuntimeUpdatesProcessor`.
This API is additive; existing `doScan(...)`, remote-dev, and continuous-test
callers must continue to behave as before unless the new API is explicitly
used.

Recommended first shape:

```java
BuildOutputProcessResult processBuildOutputChanges(BuildOutputChanges changes)
```

The result should distinguish at least:

- accepted/applied;
- stale rejected;
- failed/cancelled/superseded recorded but not reloaded;
- busy rejected;
- apply failed.

Do not add a transport-facing acknowledgement object in this phase. The result
is for direct callers and tests; later transport can map it to protocol terms.

Processing rules:

1. Acquire the same locks needed for coherent reload state through a
   non-blocking or timeout-bounded path for external batches.
   - Do not change the existing blocking lock behavior of `doScan(...)`.
   - If the lock cannot be acquired for the external batch, return the busy
     result instead of blocking indefinitely.
2. Reject events whose sequence is not greater than the last accepted or
   processed sequence.
3. Choose the initial busy policy explicitly:
   - first implementation should reject busy/new concurrent processing with a
     clear result;
   - single-slot latest-pending coalescing remains a tracked follow-up for the
     transport phase.
4. For failed, cancelled, or superseded batches:
   - record the latest build problem/status for diagnostics where practical;
   - do not reload from outputs;
   - do not run tests.
5. For successful batches:
   - map main class output changes to a `ClassScanResult`;
   - map main resource output changes to changed resource paths where possible;
   - apply production changes before test changes;
   - invoke the extracted restart/no-restart handling path;
   - only after successful production handling, map test class changes and call
     `TestSupport.runTests(...)` when continuous testing is started.
6. Never call `QuarkusCompiler` from this method.

Acceptance:

- Stale batches are rejected by sequence.
- Failed batches keep the previous app/test state and do not reload.
- Successful main class changes trigger existing restart/no-restart behavior.
- Test changes are held back when production apply fails.
- Pure test-only batches can run only when current production state is healthy.
- Tests prove no compiler invocation happens on this path.

## Phase 5: Map Output Paths To ClassScanResult

Add focused mapping helpers used by Phase 4.

1. Match each reported class output root against
   `DevModeContext.ModuleInfo` main/test classes paths.
2. Reject or ignore changes outside known output roots. Prefer a diagnostic
   result over throwing for normal invalid events.
3. For each class file:
   - added: `ClassScanResult.addAddedClass(root, classFile)`;
   - modified: `ClassScanResult.addChangedClass(root, classFile)`;
   - deleted: `ClassScanResult.addDeletedClass(root, classFile)`.
4. Update the relevant `TimestampSet.classFileChangeTimeStamps` state so later
   ordinary scans do not replay consumed changes.
5. Do not use source-file lookup for this external-output path. The external
   build tool is authoritative for compilation.

Acceptance:

- Added, modified, and deleted class outputs produce expected class names.
- Inner-class names preserve `$` separators exactly as
  `ClassScanResult.toName(...)` does today.
- Unknown output roots are handled deterministically.
- Consumed class changes are not replayed by a later ordinary class scan.

## Phase 6: Resource Output Handling

Implement application resource-output handling for the direct batch path.

1. Treat external resource changes as already-processed output changes.
2. Map application-project resource output changes to the same OS-agnostic
   strings used by `HotDeploymentWatchedFileBuildItem` matching where possible.
3. Use existing restart/no-restart matching through `TimestampSet.isRestartNeeded(...)`.
4. Do not inspect dependency-project source/resource roots.
5. Treat dependency artifact/output changes conservatively as requiring restart.

Acceptance:

- Resource-only no-restart changes call `notifyExtensions(...)`.
- Resource changes marked restart-needed trigger restart.
- Dependency output changes are conservative and tested.

## Phase 7: Continuous Testing Integration

Wire test output changes into existing continuous testing.

1. Use the same `TestSupport` instance already owned by
   `RuntimeUpdatesProcessor`.
2. If `testSupport` is absent or not started, do not run tests.
3. If the batch includes production and test changes, run tests only after
   production apply succeeds.
4. If the batch is pure test-only, run tests only if current production state is
   healthy.
5. Preserve existing `TestSupport` queueing behavior. Do not add another test
   queue in this phase.

Acceptance:

- Test output changes call `TestSupport.runTests(ClassScanResult)` when
  eligible.
- Existing pause/start/queue semantics remain owned by `TestSupport`.
- Production failures prevent test reruns from the same batch.

## Phase 8: Focused Verification

Run focused tests before broader module builds.

Minimum test coverage:

- `RuntimeUpdatesProcessor.updateFile(...)` and
  `RuntimeUpdatesProcessor.deleteFile(...)` characterized write/delete
  behavior.
- `BuildOutputChanges` default/null handling.
- output root to `ClassScanResult` mapping.
- stale sequence rejection.
- failed/cancelled/superseded batch no-reload behavior.
- non-success batches advance sequence so older successes cannot apply later.
- live-reload-disabled batches advance sequence so the same batch cannot apply
  later after live reload is re-enabled.
- successful main class change restart path with a stub restart callback,
  covering added, modified, and deleted classes.
- successful forced main resource change restart path with OS-agnostic relative
  resource paths.
- external-output processing does not invoke `QuarkusCompiler`.
- pure test-only batches are currently ignored until `TestSupport` routing is
  implemented.

Deferred test/dependency coverage:

- pure test-only batch routes to `TestSupport` only when eligible;
- production plus test batch applies production first;
- changed dependency paths trigger the agreed reload behavior;
- resource-only no-restart notification path after a successful app start.

Suggested commands, adjusted to actual test class names:

```bash
./mvnw -pl core/deployment -am -Dtest=io.quarkus.deployment.dev.DevModeContextTest,io.quarkus.deployment.dev.RuntimeUpdatesProcessorBuildOutputChangesTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl extensions/vertx-http/runtime -Dtest=RemoteSyncHandlerTest test
```

If exact module-level tests are impractical, add the smallest existing-module
test fixture that exercises the behavior without starting full dev mode.

Acceptance:

- Focused tests pass.
- No open-ended continuous-build process is started by tests.
- Existing dev-mode tests still pass for unchanged default behavior.

## Deferred Follow-Ups

- Non-TCP transport implementations for Gradle-native dev updates.
- Single-slot latest-pending or bounded coalescing policy for transport
  delivery.
- Dev UI status display for failed external build batches.
- Dev UI continuous-test controls review after the direct `TestSupport` seam
  exists.
- Gradle plugin wiring for `quarkusApplicationDev --continuous` and
  `quarkusContinuousTest`.
- Broader remote-dev reuse or protocol unification once the direct output-batch
  seam is proven.
