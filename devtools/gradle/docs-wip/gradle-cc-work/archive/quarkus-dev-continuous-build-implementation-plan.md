# Gradle-Native Quarkus Dev Implementation Plan

Status: implemented plan; archived
Last reviewed: 2026-07-14

Archive note: the first production-code slice of Gradle-native
`quarkusApplicationDev` is implemented in `devtools/gradle/gradle-app-plugin`.
The remaining larger work, especially continuous testing, dependency/classpath
rebootstrap, stdin/test-control UX, and broader integration validation, stays
tracked in `quarkus-dev-continuous-build-design.md` and
`new-application-plugin-design.md`.

## Goal

Implement the first production-code slice of Gradle-native
`quarkusApplicationDev` in the standalone `io.quarkus.application` plugin.

The implementation must let Gradle own code generation, resource processing,
compilation, task inputs, configuration cache, isolated projects, and continuous
build scheduling. Quarkus dev mode owns augmentation, runtime startup, reload,
restart, Dev UI, Dev Services, and hot-replacement decisions.

This first slice intentionally supports only application-project production
class/resource output changes. It must not implement continuous testing,
dependency/classpath rebootstrap, jar-entry diffing, or build-logic live reload.

## Non-Negotiable Constraints

- All bounded TestKit coverage for supported paths must run with
  `--configuration-cache` and `-Dorg.gradle.unsafe.isolated-projects=true`.
- Do not combine real open-ended `--continuous` TestKit/integration tests with
  `--configuration-cache` while Gradle issue
  `https://github.com/gradle/gradle/issues/38482` remains unresolved. Use
  bounded repeated invocations for the configuration-cache gate.
- Do not use `Task.getProject()` or mutable `Project` access during task
  execution.
- Do not capture Gradle `Project`, `Task`, `Configuration`, `SourceSet`, file
  collection internals, environment snapshots, or system-property maps in task
  action state.
- Keep test stubs and test-only operations under `src/test`; do not put them in
  production source.
- Preserve the existing legacy plugin behavior. This work is for the standalone
  `io.quarkus.application` plugin.
- Do not expose internal helper properties or methods from DSL-facing types with
  Java `public` visibility.
- The dev task is long-running/session-oriented and must not be build-cacheable.
- The bounded per-iteration task action must return so Gradle continuous build
  can observe inputs and schedule the next iteration.

## Existing Building Blocks

Core Quarkus already provides the first-cut external build-output transport:

- `io.quarkus.deployment.dev.BuildOutputChanges`
- `io.quarkus.deployment.dev.BuildOutputPathChange`
- `io.quarkus.deployment.dev.BuildOutputChangeKind`
- `io.quarkus.deployment.dev.BuildOutputChangeStatus`
- `io.quarkus.deployment.dev.BuildOutputChangesServer`
- `io.quarkus.deployment.dev.BuildOutputChangesTransports.createTcpServer()`
- `DevModeContext.ExternalBuildOutputTransport`

`RuntimeUpdatesProcessor.processBuildOutputChanges()` currently consumes only
production `mainClassChanges` and `mainResourceChanges`. Test output routing is
deferred.

The Gradle plugin previously had an investigation task:

- `io.quarkus.gradle.application.internal.dev.DevModeExperimentTask`
- `io.quarkus.gradle.application.internal.dev.DevModeExperimentBuildEventsService`
- task name: `devModeExperiment`

It was used as reference for variant and change-event investigation only. The
real implementation now uses production names/types and the experiment task is
retired.

## Runtime Shape

Use a two-part Gradle-side design:

1. A session coordinator owns the long-lived Quarkus dev process and
   `BuildOutputChangesServer`. Do not assume this can be an ordinary Gradle
   `BuildService` until the lifecycle validation phase proves that it survives
   the relevant continuous-build iterations and closes reliably. If it cannot,
   keep Gradle tasks bounded and coordinate with a separate dev-session process
   or equivalent explicit session handle.
2. A bounded per-iteration Gradle task observes `InputChanges`, turns successful
   Gradle output changes into a raw `BuildOutputChanges` candidate, passes that
   candidate through a Gradle-plugin-internal policy/coalescing layer, writes a
   small receipt/status file, and returns. The policy layer decides whether and
   when to call `BuildOutputChangesServer.send`.

The first iteration starts Quarkus and waits until augmentation/startup is
complete before emitting reloadable changes. Any file changes observed before
Quarkus has augmented and started are baseline state. They must be ignored for
reload purposes, though they may be logged at lifecycle/debug level as startup
baseline diagnostics.

## Phase 0: Lifecycle Validation And Naming

Status: implemented as a TestKit lifecycle proof.

Result:

- A Gradle `BuildService` can preserve mutable session state across multiple
  tasks in the same build invocation.
- Gradle calls `BuildService.close()` at the end of the build invocation.
- A second bounded TestKit invocation gets a fresh `BuildService` instance,
  even when the nested build reuses the configuration cache.
- Therefore, a `BuildService` is a valid per-invocation coordination object and
  task-to-service call path, but it is not sufficient by itself to own a
  long-lived Quarkus dev process across repeated bounded invocations.

This result came from a TestKit lifecycle proof that remains as product
coverage. The probe task, worker, and worker parameters live in `src/test` and
are added to the plugin-under-test classpath only for that nested TestKit
build, so useful lifecycle coverage does not leak fake/probe types into the
production plugin.

Decision for later phases:

- Use a BuildService-shaped coordinator for per-invocation state and for the
  task-to-service submission API.
- Before production Quarkus launch is wired, add either:
  - an explicit dev-session handle/process that can survive bounded Gradle
    invocations; or
  - a separate real `--continuous` lifecycle proof showing that the service
    lifetime is suitable for the actual command-line mode.
- Do not let Phase 3 assume that a BuildService alone owns the long-lived
  Quarkus process across all supported execution modes.
- Do not use `GradleRunner` for end-to-end `--continuous` tests. TestKit can
  pass `--continuous` through, but `GradleRunner.build()`, `buildAndFail()`, and
  `run()` only return when the build completes, while continuous build normally
  stays alive waiting for changes. TestKit does not expose the Tooling API
  cancellation token needed to stop that run cleanly.
- Prefer ordinary bounded TestKit builds for plugin/build-logic coverage:
  input/output declarations, incremental behavior, up-to-date behavior,
  configuration-cache behavior, and isolated-project behavior. Gradle
  continuous build is driven by those same input declarations.
- If a true continuous-build lifecycle test becomes necessary, use the Gradle
  Tooling API directly:
  - create a `GradleConnector`;
  - create a `CancellationTokenSource`;
  - run `connection.newBuild().forTasks(...).withArguments("--continuous")`
    with the cancellation token;
  - capture stdout/stderr;
  - run asynchronously;
  - wait for evidence of the initial build;
  - mutate an input file;
  - wait for evidence of the next build;
  - cancel through the token and assert cleanup.
- Continuous-build tests must account for Gradle caveats:
  - continuous build requires file-system watching and does not work with
    `--no-daemon`;
  - TestKit disables file-system watching on Windows by default, so any Windows
    TestKit-style continuous experiment would need `--watch-fs`;
  - continuous build does not re-evaluate build-logic changes on subsequent
    iterations, so build-script/model changes require restarting the continuous
    process.

1. Retire `devModeExperiment` once the real task has equivalent declared inputs
   and cheap coverage.
2. Add a minimal lifecycle proof before wiring Quarkus:
   - whether repeated bounded TestKit invocations can find the same logical dev
     session through a BuildService alone;
   - session state can distinguish "not started", "started but not ready",
     "ready", "failed", and "closed";
   - session cleanup happens when the build/session ends or the task fails;
   - the proof must not rely on Gradle internal deployment APIs.
3. If a Gradle `BuildService` is used in the proof, document exactly what
   lifetime it has under repeated bounded invocations and under real
   `--continuous`. If the lifetime is unsuitable, do not use it as the owner of
   the Quarkus process.
4. Add new production internal types under
   `io.quarkus.gradle.application.internal.dev`.
5. Use one always-present public task name: `quarkusApplicationDev`.
6. Add one always-present `quarkusApplication.dev { ... }` configuration block.
   It is separate from `builds { ... }` and is not registered per package
   output.
7. Do not implement run, remote-dev, or continuous-test behavior in this plan.
   Existing failing stubs remain failing unless an explicit task-name migration
   is approved separately.
8. Ensure task names still do not collide with legacy plugin names.

Rationale for a single dev configuration:

- Dev mode is not a package output. It should not build or overwrite
  `build/quarkus-builds/<name>/`, and it should not inherit package-output
  directories or result files.
- Package-output-specific configuration is often meaningless or harmful for
  dev. Native-image arguments, native-sources options, fast-jar/mutable-jar
  layout details, uber-jar archive naming, image publishing, AOT image, and
  deploy settings are package/deployment concerns, not dev-mode launch inputs.
- The dev task should consume Gradle-produced classes/resources, generated
  outputs, a dev-mode application model, common Quarkus build properties, and
  dev-specific overrides. It should write only dev-specific state, for example
  under `build/quarkus-dev/` or `build/quarkus-build-results/dev/`.
- A single `dev { ... }` block is easier to understand and avoids accidental
  multiplication of dev tasks when a build registers `fastJar`, `mutableJar`,
  `uberJar`, and `native` outputs for packaging or deployment.
- If a future use case needs multiple dev configurations, add a named
  `devModes { ... }` or equivalent container later. Do not infer one dev mode
  per package output now.

Initial DSL shape:

```kotlin
quarkusApplication {
  dev {
    quarkusBuildProperties.put("quarkus.foo", "bar")
    forkOptions {
      // dev worker/JVM options, if a dev-specific build worker is involved
    }
  }
}
```

The existing extension-level/common build properties remain common inputs. The
`dev {}` block adds dev-specific overrides and must not expose package-type
properties that do not apply to dev mode.

Acceptance:

- The implementation plan remains consistent with
  `quarkus-dev-continuous-build-design.md`.
- The plan records that the initial BuildService proof is not enough for
  cross-invocation process ownership; production Quarkus launch still needs an
  explicit session-lifetime strategy.

## Phase 1: BuildOutputChangesPolicy

Status: implemented.

Implementation result:

- Added `io.quarkus.gradle.application.internal.dev.BuildOutputChangesPolicy`.
- The policy is intentionally build-tool agnostic: it uses
  `BuildOutputChanges`, `BuildOutputPathChange`, plain Java types, and small
  internal result types; it does not expose Gradle runtime types.
- The policy separates accepting/coalescing raw candidates from delivering the
  current pending batch. This lets later session code keep coalescing while a
  Quarkus apply is busy or while the session is not ready.
- `APPLIED` is the commit point and clears pending changes.
- `NOT_APPLIED` and `IOException` keep pending changes so later candidates can
  coalesce on top of the unacknowledged state.
- Startup baseline and restart-required snapshots do not become normal
  reloadable batches.
- Failed/cancelled/superseded build results do not become reloadable changes
  and do not erase pending successful changes.

Implement `BuildOutputChangesPolicy` before Gradle mapping/task work. This is
the intentionally build-tool-agnostic coalescing pipe between raw
`BuildOutputChanges` candidates and `BuildOutputChangesServer.send`.

The first implementation lives in the Gradle plugin for development speed, but
it must be shaped so it can later move to `core/deployment` without a semantic
rewrite.

Implementation contract:

- Add an explicit class-level comment to `BuildOutputChangesPolicy` stating
  that it is intentionally build-tool agnostic despite initially living in the
  Gradle plugin.
- The public API and emitted values must not use Gradle or Quarkus Gradle
  plugin-specific types.
- Inputs and outputs should be `BuildOutputChanges`,
  `BuildOutputPathChange`, plain Java collections, `Path`, enums, and small
  policy-result types if needed.
- The policy collects raw events while the server/session is not ready or while
  a previous batch is still being delivered/applied.
- The policy eventually emits one coalesced batch when delivery is allowed.
- An emitted batch remains in-flight until Quarkus replies. The policy may
  discard it only after an `APPLIED` response.
- If Quarkus replies `NOT_APPLIED`, times out, or the connection fails, the
  policy keeps the in-flight batch and coalesces newer raw file events on top of
  that unacknowledged state.
- The policy must be bounded. It may keep maps keyed by output scope/root/path,
  but it must not keep an unbounded queue of complete historical batches.

Initial file coalescing rules:

- Multiple `MODIFIED` events for the same output root and changed path collapse
  to one `MODIFIED`.
- `ADDED` followed by `MODIFIED` for the same file collapses to `ADDED`.
- `DELETED` followed by `ADDED` followed by `MODIFIED` for the same file
  collapses to `MODIFIED`.
- `ADDED` followed by `DELETED` for the same file cancels out and emits no
  change for that file.
- `MODIFIED` followed by `DELETED` for the same file collapses to `DELETED`.
- `DELETED` followed by `ADDED` for the same file collapses to `MODIFIED`
  because the final state is "a file exists at a previously deleted path" and
  Quarkus should reload the current contents.
- Coalescing keys must include the output category and output root, not only
  the relative path. The same relative path under classes and resources, or
  under different roots, must not collide.

Initial batch/status rules:

- Drop or mark as baseline all changes observed before Quarkus startup
  readiness.
- Reject stale candidates whose sequence is not newer than the last accepted or
  emitted sequence.
- Preserve failed/cancelled/superseded build statuses as diagnostics/status, but
  never turn them into reloadable production changes.
- A failed/cancelled/superseded status must not erase a newer pending successful
  coalesced file batch unless an explicit policy-result says the pending batch
  is superseded.
- Do not forward explicit restart-required snapshots as large normal reload
  batches.
- Do not deep-merge dependency/classpath, test-output, or build-logic changes.
  Those remain deferred/non-reloadable.

Delivery behavior:

- If `BuildOutputChangesServer.send` can accept a batch immediately, emit the
  current coalesced batch and clear it only after an `APPLIED` response.
- If the server/session is busy with a previous batch, keep collecting and
  coalescing into the pending file map.
- If the previous batch returns `NOT_APPLIED`, merge the pending map into the
  unacknowledged batch state and retry/emit the coalesced result when delivery
  is allowed.
- Once the server/session is ready again, emit the latest coalesced state as one
  batch.
- Close/flush behavior must be deterministic: either emit a final allowed
  coalesced batch before close or explicitly discard it with a diagnostic
  result. Do not silently lose pending changes in normal close paths.

Tests:

- Pure unit tests for all file coalescing examples above.
- Pure unit tests that coalescing keys include output category/root/path.
- Pure unit tests that stale sequences are rejected.
- Pure unit tests that failed builds do not become reloads and do not
  accidentally erase pending successful file changes.
- Pure unit tests that `APPLIED` discards the emitted in-flight batch.
- Pure unit tests that `NOT_APPLIED` keeps the emitted in-flight batch and
  coalesces later file events on top of it.
- Pure unit tests that timeout/connection failure is treated like not applied.
- Pure unit tests that startup-baseline events produce no reloadable batch.
- Pure unit tests that restart-required snapshots do not produce a large normal
  reload batch.
- Pure unit tests that busy delivery keeps coalescing and emits one latest batch
  when delivery becomes available.
- Pure unit tests for deterministic close/flush behavior.

Acceptance:

- Unit tests do not start Gradle, Quarkus, sockets, Docker, or augmentation.
- `BuildOutputChangesPolicy` has no Gradle API or Quarkus Gradle plugin types
  in its public surface.
- The phase can be implemented and reviewed independently from Gradle
  `InputChanges` mapping.

## Phase 2: Gradle Event Model And Mapper Unit Tests

Status: implemented.

Implementation result:

- Added plain internal model types under
  `io.quarkus.gradle.application.internal.dev`:
  - `GradleDevOutputScope`
  - `GradleDevFileChange`
  - `GradleDevBuildResult`
  - `GradleDevOutputChangeMapper`
- The mapper API intentionally does not expose Gradle runtime types. The later
  task action will adapt Gradle `FileChange`/`ChangeType`/file-type data into
  these plain values and convert Gradle `ChangeType.REMOVED` to
  `BuildOutputChangeKind.DELETED` at that boundary.
- Class output changes are filtered to `.class` files.
- Resource output changes include ordinary files and skip existing directories.
  Removed-directory filtering will depend on the later task action passing only
  ordinary-file changes, because a deleted path cannot be queried with
  `Files.isDirectory`.
- The mapper preserves build status, diagnostics, user-initiated, and
  force-restart flags and leaves test-output fields empty for the first slice.

Create small Gradle-side internal value/helper types:

- `GradleDevOutputScope`: `MAIN_CLASSES`, `MAIN_RESOURCES`
- `GradleDevFileChange`: output root, changed path, `BuildOutputChangeKind`
- `GradleDevBuildResult`: sequence, status, class changes, resource changes,
  failure summary, diagnostics path, user-initiated, force-restart
- `GradleDevOutputChangeMapper`: maps Gradle `FileChange`-like data to
  `BuildOutputPathChange`/`BuildOutputChanges`

Implementation rules:

- Store paths as `RegularFile`/`Directory` providers on tasks, but convert to
  plain normalized `Path` values only inside the task action.
- Reject or skip changed paths that are not under their declared output root.
- Map Gradle `ChangeType.ADDED`, `MODIFIED`, and `REMOVED` to
  `BuildOutputChangeKind.ADDED`, `MODIFIED`, and `DELETED` in the task action
  that consumes Gradle `InputChanges`.
- Class changes must include only `.class` files.
- Resource changes must include ordinary files and preserve paths relative to
  the resource output root.
- Do not add dependency/classpath fields to `BuildOutputChanges`.

Tests:

- Pure unit tests for path normalization and under-root validation.
- Pure unit tests for class/resource filtering.
- Pure unit tests proving `BuildOutputChangeKind` values are preserved.

Acceptance:

- Unit tests do not start Gradle, Quarkus, sockets, Docker, or augmentation.
- No task action code is needed to test mapper semantics.

## Phase 3: Session Coordinator Abstraction

Status: partially implemented as a lifecycle proof.

Implementation result:

- Added `QuarkusApplicationDevSession` as the mutable session state object.
- Added `QuarkusApplicationDevSessionService` as the Gradle `BuildService`
  owner for that session.
- Added a process-launch seam so the session owns a closeable dev-process
  handle without putting the process directly in the task action.
- Added test-only `DevSessionLifecycleProbeTask` and process-isolated
  `DevSessionLifecycleProbeWorker` types for the first TestKit proof. The test
  augments the plugin-under-test classpath with the test-classes directory so
  these useful probe types do not leak into production plugin code.
- Added `TestKitPluginClasspath.withTestClasses()` as a small test-support
  utility for fixtures that need test-only task/build-service types available
  to the nested TestKit build script.
- Added TestKit coverage proving:
  - a build script can register the service and task through the
    plugin-under-test classpath;
  - the task starts the session through the service;
  - the fake dev process runs in a process-isolated worker;
  - startup input is treated as baseline;
  - later successful class-output changes are coalesced into one delivered
    batch;
  - `APPLIED` delivery clears the batch;
  - Gradle closes the build service at the end of the invocation.

The real Quarkus dev smoke is deliberately deferred to Phase 7. Phase 3 should
not grow a real `DevModeCommandLineBuilder`/augmentation launch path just to
prove the service/session/worker lifecycle.

Add a production abstraction for the dev session:

- `QuarkusApplicationDevSession`
  - starts Quarkus dev mode if not already started;
  - exposes whether Quarkus has augmented/started and is ready for reload
    batches;
  - accepts raw `BuildOutputChanges` candidates through the Gradle-plugin
    policy/coalescing layer;
  - closes the Quarkus process and transport.
- `QuarkusApplicationDevSessionFactory`
  - creates a session for task execution.
- `QuarkusApplicationDevSessionParameters`
  - contains only serializable/provider-derived values needed to launch Quarkus
    and wire `DevModeContext.ExternalBuildOutputTransport`.

Testing rule:

- Production source must contain only the abstraction and production
  implementation.
- Test source may contain recording/fake session implementations.

Implementation details:

- The production implementation creates a
  `BuildOutputChangesTransports.createTcpServer()` server.
- Pass `server.transport()` into the `DevModeContext` used to launch Quarkus dev
  mode.
- The Quarkus process/client connects back to the Gradle-owned server using the
  random per-session token from the transport.
- The session owns a `BuildOutputChangesPolicy` instance and is the
  only production code path that calls `BuildOutputChangesServer.send`.
- Do not log the token.
- Startup readiness must be explicit. The first reloadable event may be sent
  only after the dev process has completed augmentation/startup.

Required Phase 3 substeps:

- Inspect the existing Gradle launch worker path used by the standalone plugin.
- If it already has an observable "Quarkus augmentation/startup completed"
  signal, adapt it behind `QuarkusApplicationDevSession`.
- If it does not, add the smallest production seam that lets the worker/session
  report "started" without exposing Gradle internals, leaking test stubs into
  production, or changing legacy plugin behavior.
- The phase is not complete until startup readiness is explicit enough for the
  task to distinguish startup baseline input changes from post-start reloadable
  input changes.

Tests:

- Unit tests with fake session verify:
  - startup baseline events are ignored before ready;
  - first ready successful incremental event is sent;
  - failed/cancelled/superseded results are sent as status or logged but do not
    become reload batches;
  - coalescing prevents unbounded pending sends while preserving the latest
    relevant successful candidate;
  - close is called once.
- Unit tests verify token is never present in log/status strings if exposed by
  helpers.
- TestKit lifecycle proof with fake process-isolated worker verifies the
  Gradle service/session/worker boundaries without starting real Quarkus dev.

Acceptance:

- Session abstraction is usable from a Gradle task.
- A TestKit proof covers the service/session/worker lifecycle without relying
  on real `--continuous` or real Quarkus augmentation.
- Probe task/worker implementation lives in test sources, not production
  plugin sources.
- Real Quarkus dev startup remains Phase 7.

## Phase 4: Bounded Dev Iteration Task

Status: bounded iteration mechanics and the first production-shaped dev launch
path are implemented; real Quarkus smoke coverage remains Phase 7.

Implementation result:

- `QuarkusApplicationDevTask` no longer uses the generic reserved launch-task
  failure as its first behavior.
- `QuarkusApplicationTask` no longer contributes an inherited placeholder
  `@TaskAction`; each concrete task owns the action that actually describes its
  current behavior.
- Reserved launch tasks keep explicit placeholder actions named for the user
  command (`runApplication`, `runContinuousTests`, `runRemoteDev`) until those
  features are implemented.
- The task has an explicit `continuousBuild` input.
- `TaskRegistration` sets that input from
  `project.getGradle().getStartParameter().isContinuous()` during
  configuration.
- The task action does not call `Task.getProject()` or read the Gradle start
  parameter directly.
- Non-continuous execution fails early with an actionable message telling users
  to run the task with `--continuous`.
- The dev task now declares incremental application main class/resource output
  inputs using the main source-set output roots.
- The dev task also declares incremental dependency class/resource roots using
  runtime configuration variant reselection. Runtime jars are split so the
  restart-required jar stream keeps only jars whose component does not expose a
  classes/resources output variant.
- Dependency class/resource output roots are treated as reloadable main
  class/resource changes. This relies on the future Quarkus dev launch wiring
  passing the same dependency project output roots as module roots in the
  dev-mode context.
- Precise incremental runtime jar changes are detected, but they are not
  forwarded as reloadable file changes. They currently become restart-required
  metadata because jar-only dependency updates need rebootstrap/application-model
  handling that this slice does not implement.
- The dev task depends on the Gradle `classes` lifecycle, so code generation,
  resource processing, and Java compilation remain Gradle-owned.
- Production dev-session ownership moved to a Gradle
  `DeploymentRegistry`-owned handle. `QuarkusApplicationDevSessionService`
  remains only as an explicit test override seam for bounded TestKit fixtures.
  The dev task writes an iteration receipt under
  `build/quarkus-dev/dev-iteration.properties`.
- The dev task explicitly disables up-to-date checks. The receipt is diagnostic
  state, not an output that should let Gradle skip launching or iterating dev
  mode.
- A dedicated `quarkusApplicationDevModel` task now resolves a
  `LaunchMode.DEVELOPMENT` application model for dev launch. The underlying
  runtime configuration includes DEVELOPMENT-only conditional dev dependencies;
  runtime-dev artifacts are therefore represented in the app model rather than
  guessed outside dependency resolution or injected into the dev runner
  manifest.
- Application model generation now records main source/resource directories
  with their Gradle output roots in the workspace module metadata, so the
  dev launcher builds `DevModeContext.ModuleInfo` from declared Gradle
  source-set roots instead of guessing.
- The dev task has declared launch inputs for the generated dev application
  model, internal dev-mode classpath, project directory, build directory,
  application name, and application version.
- `TaskRegistration` creates an internal `quarkusApplicationDevModeClasspath`
  configuration for the dev launcher and declares the bootstrap resolver and
  core deployment artifacts there.
- The dev session now owns a `BuildOutputChangesServer`. Startup creates a TCP
  server, passes the server's transport metadata to the process launcher, and
  closes both process and server with the session.
- `GradleNativeDevModeLauncher` translates the generated application model into
  a `DevModeCommandLine`, sets `BuildUpdateSource.EXTERNAL_BUILD_TOOL`, attaches
  the external build-output transport, and starts a service-owned child JVM with
  `ProcessBuilder` so the Gradle continuous task action can complete.
- Ready iterations call the session-owned server delivery path after accepting
  pending changes, so the bounded task is the sender once Quarkus connects.
- For continuous-mode execution, the current task action maps observed
  `InputChanges` into `BuildOutputChanges`.
- Before the session is ready, the task records observed changes as startup
  baseline, starts Quarkus dev through the service-owned launcher, marks the
  session ready, and returns.
- Once the session is ready, the task routes incremental class/resource changes
  through the session/policy accept path instead of dropping them as baseline.
- Once the session is ready and Gradle reports a non-incremental successful
  iteration, the task does not forward Gradle's whole file tree. It compares a
  task-local content-fingerprint snapshot of class/resource/runtime-jar outputs
  and forwards only the precise class/resource deltas. If no prior snapshot is
  available, the iteration becomes a baseline rather than a large reload batch.
- Precise incremental runtime jar changes remain restart-required and are not
  forwarded as normal reload batches.
- Pure session tests cover baseline-before-ready, ready-session delivery, and
  restart-required behavior that preserves pending reloadable changes.

Replace the `quarkusApplicationDev` stub behavior with a bounded
configuration-cache-compatible task action.

Task inputs:

- application main class output directories;
- application main resource output directory;
- dependency project class output directories resolved from the runtime
  configuration's `classes` variants;
- dependency project resource output directories resolved from the runtime
  configuration's `resources` variants;
- runtime jar files from components without classes/resources output variants,
  used for restart-required diagnostics rather than hot reload;
- launch/application model file needed to start Quarkus dev;
- Quarkus build properties/config inputs already used by the new plugin;
- dev-specific `quarkusApplication.dev { ... }` overrides;
- dev-specific fork options if a worker/JVM is involved;
- serializable launch/session parameters;
- continuous-build-required flag.

Task outputs:

- a small receipt/status file under `build/quarkus-dev/`.

Task action behavior:

1. Fail fast unless running under Gradle continuous build.
   - Use `project.getGradle().getStartParameter().isContinuous()` during task
     registration to set a task input. Do not call `Task.getProject()` or read
     the start parameter from the task action.
   - Do not silently run as a one-shot `run` task.
2. Start or attach to the session.
3. If Quarkus is not yet ready:
   - run required startup work;
   - treat observed inputs as baseline;
   - do not send reloadable output changes;
   - write receipt/status;
   - return.
4. If Quarkus is ready after a successful Gradle continuous-build iteration:
   - collect main class/resource changes;
   - collect dependency class/resource changes and map them into the same
     reloadable main change buckets, provided the launch context contains the
     matching dependency output roots;
   - when Gradle reports precise incremental runtime jar changes, mark the
     iteration restart-required rather than sending jar paths as reloadable file
     changes;
   - create one monotonic raw `BuildOutputChanges` candidate;
   - pass it to the session/policy layer, which may send it to Quarkus,
     coalesce it, or reject it as stale/non-reloadable;
   - write receipt/status;
   - return.
5. If Gradle reports a non-incremental snapshot after startup:
   - do not forward Gradle's whole input tree as a reload batch;
   - compare the current outputs against the task-local content-fingerprint
     snapshot;
   - if a prior snapshot exists, stream only precise class/resource deltas to
     Quarkus;
   - if no prior snapshot exists, write the snapshot and treat the iteration as
     baseline/no-op;
   - keep jar-only restart detection limited to precise snapshot deltas.
6. If build logic invalidation is detected:
   - write restart-required diagnostics;
   - fail or stop with "restart `quarkusApplicationDev --continuous`".

Configuration-cache rules:

- Do not call `getProject()` in the task action.
- Do not resolve configurations in the task action except via declared task
  input files that Gradle already materialized.
- Do not use global environment/system-property capture.
- Do not store live Gradle services in serializable task state; inject services
  through Gradle-supported mechanisms.

Tests:

- ProjectBuilder test verifies task inputs/outputs are declared and task
  description/group are useful.
- Unit tests invoke the task action with fake `InputChanges`/change objects if
  feasible; otherwise keep mapper tests pure and cover task behavior through
  TestKit in Phase 6.

Acceptance:

- `quarkusApplicationDev` no longer throws the generic reserved-task message.
- Remote-dev and continuous-test tasks remain explicit reserved entry points
  unless separately implemented. Run is covered by its own implemented task
  slice.

## Phase 5: Task Registration And Wiring

Status: implemented for the single project-level dev task and DSL block.

Update `TaskRegistration` for the single dev configuration:

- Register `quarkusApplicationDev` once per project when the standalone plugin
  is applied.
- Do not register `quarkus<BuildName>Dev` tasks for package outputs. Package
  output names still drive build, image, deploy, run-stub, and continuous-test
  stub tasks, but dev mode is a project-level concern for now.
- Wire `quarkusApplicationDev` to the application main `classes` lifecycle so code
  generation, resource processing, and Java compilation are Gradle-owned.
- Wire application model generation needed for dev launch.
- Declare application classes/resources as task inputs using provider-backed
  file collections.
- Wire `quarkusApplication.dev { ... }` build properties and dev JVM settings
  into the task/session parameters. The first slice supports managed,
  input-declared `dev.forkOptions { jvmArgs(...); systemProperty(...) }`
  values because those map directly to `DevModeCommandLine`. Do not store
  task input state as arbitrary `Action<JavaForkOptions>` callbacks.
- Wire dependency classes/resources as reload inputs. They map into the same
  main class/resource change buckets as application outputs, provided the real
  Quarkus launch context is built with the same dependency output roots.
- Keep runtime jar variant inputs diagnostics-only/restart-required initially;
  promoting jar-only dependency changes to reloadable batches requires the
  dependency/classpath rebootstrap design.

Build-logic change handling:

- Do not try to model build scripts as reload inputs.
- If a build-script/configuration-cache invalidation can be detected in the task
  path, emit restart-required diagnostics.
- Otherwise rely on Gradle to fail/reconfigure and document that the user must
  restart the continuous dev session after build-logic changes.

Tests:

- ProjectBuilder verifies the single dev task name, dependencies, and
  `dev { ... }` configuration wiring.
- ProjectBuilder verifies build-scoped dev task names are not registered.
- ProjectBuilder verifies legacy and standalone plugins applied together do not
  create task-name collisions.
- ProjectBuilder verifies no ordinary `Test` task suppression is applied yet.

Acceptance:

- `tasks --group quarkus application` shows useful descriptions.
- Task registration remains configuration-cache compatible.

## Phase 6: Cheap TestKit Coverage Without Real Quarkus

Status: implemented for bounded TestKit coverage.

Implementation result:

- Added a test-only recording `QuarkusApplicationDevSessionService`
  implementation. It lives under `src/test`, is added to the
  plugin-under-test classpath only for the nested TestKit build, and prevents
  real Quarkus dev launch while preserving the production
  `quarkusApplicationDev` task path.
- The fixture uses a tiny Quarkus application with one Java source, one
  resource, and `quarkusApplication { dev { ... } }` configuration.
- The test forces the task's `continuousBuild` input to `true` for bounded
  TestKit invocations; this avoids open-ended `--continuous` while still
  exercising the production task action.
- The generated dev application model is explicit launch metadata, not a
  reload-driving input. `quarkusApplicationDev` now depends on
  `quarkusApplicationDevModel` explicitly instead of using the model file as an
  `@InputFile` that would force non-incremental reload handling on ordinary
  code changes.
- Bounded repeated TestKit invocations reuse the configuration cache and prove
  that ready-session class/resource output changes are accepted and delivered
  through the same session path as real continuous-build iterations.
- Bounded TestKit does not prove the normal incremental class/resource reload
  path. That remains Phase 7 real continuous/smoke coverage because ordinary
  repeated `GradleRunner` invocations do not faithfully model one live Gradle
  continuous-build session's incremental `InputChanges`.

Add a TestKit fixture that uses the standalone plugin and a fake/recording dev
session injected by test-only wiring.

The fixture should be tiny:

- one Java application source;
- one resource;
- one `quarkusApplication { dev { ... } }` block;
- no Docker, no actual augmentation, no external services.

Run all bounded TestKit invocations with:

```text
--configuration-cache
-Dorg.gradle.unsafe.isolated-projects=true
```

Some TestKit cases may need `--no-configuration-cache` if the scenario
intentionally exercises a known Gradle path where the configuration cache cannot
be stored, for example the current continuous-build/configuration-cache issue
tracked as `https://github.com/gradle/gradle/issues/38482`. Those cases must be
explicitly named and documented in the test so they do not weaken the general
configuration-cache gate by accident.

Coverage:

1. Initial bounded run:
   - runs codegen/classes/resource tasks as needed;
   - starts fake session;
   - records startup baseline;
   - sends no reloadable batch before ready.
2. Source change after ready in bounded TestKit:
   - rerun bounded task;
   - compile runs;
   - a ready-session reload batch is accepted and delivered.
3. Resource change after ready in bounded TestKit:
   - processResources runs;
   - a ready-session reload batch is accepted and delivered.
4. Compile failure:
   - no reloadable class/resource batch is emitted;
   - failed status/diagnostics are recorded if supported by the task path;
   - previous good state is not overwritten.
5. Non-incremental post-start snapshot:
   - a successful ready-session non-incremental Gradle snapshot is diffed
     against the task-local content-fingerprint output snapshot;
   - only changed class/resource files are sent as reload candidates;
   - unchanged recopied resources are ignored;
   - precise jar-only changes remain restart-required.
6. Configuration cache:
   - second identical invocation reuses the configuration cache;
   - no configuration-cache problems mention Quarkus task types.

Acceptance:

- Tests do not require open-ended `--continuous`.
- Tests use repeated bounded GradleRunner invocations for startup,
  configuration-cache, and ready-session delivery behavior.
- Normal incremental class/resource reload batches are covered by mapper/session
  unit tests until Phase 7 adds real continuous/smoke coverage.
- Test support classes are not in production source.

## Phase 7: Production Quarkus Smoke Test

Status: implemented for bounded real startup and transport handshake; true
continuous reload smoke exists as a disabled proof target because it exposed a
real lifecycle gap.

Implementation result:

- Added a real TestKit smoke fixture that starts `quarkusApplicationDev` for a
  tiny Quarkus application.
- The fixture forces the task's `continuousBuild` input to `true` because
  `GradleRunner` cannot safely drive an open-ended `--continuous` build.
- Startup uses the production `QuarkusApplicationDevSessionService`,
  `GradleNativeDevModeLauncher`, TCP `BuildOutputChangesServer`, generated dev
  application model, and real Quarkus dev process.
- The session now verifies the external build-output TCP connection before
  marking itself ready. It does that through the existing `send()` API with a
  stale failed sequence-0 probe. The probe waits for the authenticated Quarkus
  client and receives a response, but cannot become a reloadable update.
- The smoke asserts the dev task succeeds, writes a ready iteration receipt,
  and closes the service so the child Quarkus process is stopped.
- Added `QuarkusApplicationDevContinuousBuildTest` as a separate Tooling API
  based test class. It starts `quarkusApplicationDev --continuous`, waits for
  the initial ready receipt, mutates a source file, and expects a second
  incremental reload receipt.
- That Tooling API test is disabled because the current implementation keeps
  the dev session inside a Gradle `BuildService`. The nested continuous build
  does perform the second Gradle task execution, but the receipt never advances
  to the second session sequence. This confirms the design constraint that a
  BuildService can be coordination glue but cannot be the sole owner of the
  long-lived Quarkus dev session across continuous-build iterations.
- The next implementation slice should move long-lived Quarkus dev ownership
  into a Gradle internal `DeploymentHandle` registered through
  `org.gradle.deployment.internal.DeploymentRegistry`. `DefaultDeploymentRegistry`
  is build-session scoped in Gradle and is the internal lifecycle used by
  Gradle's own reloadable deployment support. Keep all internal Gradle imports
  behind one narrow adapter/handle boundary and keep the Tooling API test as the
  lifecycle proof.
- Gradle issue `https://github.com/gradle/gradle/issues/23984` confirms the
  missing public API: a build-session-scoped shared service compatible with
  configuration cache. Do not implement the next slice with daemon-static state
  or a raw `BuildSessionLifecycleListener` parent-listener workaround unless
  `DeploymentRegistry` is proven unusable.

Add the first real Quarkus smoke test only after cheap lifecycle/session tests
pass.

Use a tiny app fixture and keep the scope minimal:

- start `quarkusApplicationDev` through the new task path;
- verify Quarkus starts and the transport connects;
- defer production class/resource reload assertions to a real continuous-build
  harness. Bounded repeated `GradleRunner` invocations do not preserve the same
  continuous-build session and are covered separately through the recording
  session seam.

Avoid:

- Docker/container image work;
- external databases/services;
- continuous testing;
- dependency rebootstrap;
- long-running uncontrolled `--continuous` TestKit invocations.

The existing disabled Tooling API test is the first true open-ended
`--continuous` integration test. Keep it in a separate class with strict
timeouts and Tooling API cancellation. Do not pass `--configuration-cache` to
that real continuous-build test until Gradle issue
`https://github.com/gradle/gradle/issues/38482` is resolved. Isolated projects
also cannot be enabled in that test while the configuration cache is disabled,
because Gradle rejects that combination. Cover configuration-cache and
isolated-project compatibility through bounded repeated TestKit invocations
instead.

Acceptance:

- The smoke test proves the Gradle side can launch Quarkus with
  `ExternalBuildOutputTransport`.
- It proves the TCP server/client handshake works from the Gradle plugin path.
- The disabled Tooling API based continuous-build test is re-enabled after the
  dev session owner is moved from the per-iteration BuildService lifetime into
  a build-session-scoped `DeploymentHandle`, and proves that the first
  production reload batch reaches `RuntimeUpdatesProcessor`.

## Phase 8: DeploymentRegistry Session Ownership

Status: implemented.

Implementation result:

- Production `quarkusApplicationDev` uses a
  `DeploymentRegistry`-owned `QuarkusApplicationDevDeploymentHandle` as the
  long-lived session owner.
- The deployment id is stable for the logical dev task; the launch
  configuration fingerprint is separate and currently causes an actionable
  restart failure if it changes while the session is running.
- The existing `QuarkusApplicationDevSessionService` remains only as an
  explicit test override seam. Production task registration does not set it.
- A Tooling API `--continuous` smoke is enabled and proves startup baseline,
  second-iteration source output delivery, and cancellation cleanup.
- The smoke showed that Gradle can report a successful second continuous
  iteration as non-incremental even after seeding multiple initial classes.
  The task therefore keeps a task-local content-fingerprint output snapshot.
  Ready non-incremental iterations are diffed against that snapshot so only
  precise class/resource output deltas become reloadable; precise runtime-jar
  deltas remain restart-required.

Goal:

Move ownership of the long-lived Quarkus dev process, external build-output
server, token, sequence state, and coalescing policy from the per-iteration
Gradle `BuildService` lifetime into a Gradle build-session-scoped deployment
handle.

Rationale:

- `BuildService` lifetime has been proven insufficient by the disabled Tooling
  API continuous-build smoke: Gradle reruns the task after a source change, but
  the service-owned session state does not survive as the continuous-session
  owner.
- Gradle issue `https://github.com/gradle/gradle/issues/23984` confirms the
  missing public API: a configuration-cache-compatible build-session-scoped
  service.
- Gradle's internal `DeploymentRegistry` is build-session scoped and already
  integrated with Gradle's continuous-build executor.
- This phase intentionally accepts a narrow Gradle-internal API dependency
  because it is materially safer than daemon-static state, timeout-only cleanup,
  or implementing a separate supervisor process.

Hard constraints:

- Keep almost all `org.gradle.deployment.internal.*` imports in one small
  internal package/boundary, for example
  `io.quarkus.gradle.application.internal.dev.deployment`.
- The task class may need one `DeploymentRegistry` injection getter. All other
  interaction with the internal API should go through the adapter/handle
  boundary.
- Do not expose Gradle internal types from public task, DSL, or user-facing
  plugin APIs.
- Use `DeploymentRegistry.ChangeBehavior.NONE` for the first implementation.
  Quarkus owns reload/restart semantics through explicit build-output batches;
  Gradle owns only build-session lifetime.
- Do not use `ChangeBehavior.BLOCK_AND_REBUILD`; Gradle's internal
  `RegisteredDeployment.create(...)` does not handle it in checked Gradle
  versions.
- Do not flip the TCP transport. The deployment handle should own the existing
  Gradle-side `BuildOutputChangesServer`, and Quarkus dev should continue to
  connect back using `ExternalBuildOutputTransport`.
- Do not use daemon-static registries, parent-listener
  `BuildSessionLifecycleListener` hacks, or a Gretty-style external supervisor
  unless `DeploymentRegistry` proves unusable.

Implementation steps:

1. Introduce a deployment-handle class in production sources.
   - Suggested type:
     `io.quarkus.gradle.application.internal.dev.deployment.QuarkusApplicationDevDeploymentHandle`.
   - It implements Gradle internal `DeploymentHandle`.
   - Constructor parameters must be ordinary immutable values or service-safe
     objects passed through `DeploymentRegistry.start(...)`; do not capture
     `Project`, `Task`, `Configuration`, `SourceSet`, or live Gradle model
     objects.
   - It owns:
     - `QuarkusApplicationDevSession` or equivalent mutable session object;
     - `BuildOutputChangesServer`;
     - Quarkus dev process handle;
     - current config fingerprint;
     - close receipt/status path, if still useful for tests.
   - `start(Deployment)` launches Quarkus dev only once and marks the handle as
     running after the transport handshake succeeds.
   - `isRunning()` reports the child process/session status, not merely whether
     the Java object exists.
   - `stop()` must be idempotent and close the Quarkus child process and
     `BuildOutputChangesServer`. It must tolerate partial startup failures and
     attach suppressed exceptions where useful.

2. Introduce a narrow adapter/registry helper.
   - Suggested type:
     `io.quarkus.gradle.application.internal.dev.deployment.QuarkusApplicationDevDeployments`.
   - It is the only task-facing helper that imports or accepts
     `DeploymentRegistry`.
   - It should provide methods similar to:
     - `getOrStart(DeploymentRegistry registry, String id, Parameters params)`;
     - `get(DeploymentRegistry registry, String id)`, if useful;
     - `deploymentId(...)`.
   - The deployment id must be stable and specific. Include at least root
     directory identity, project path, task path, plugin/application dev marker,
     and plugin id/version. Prefer hashing long path input into a compact id for
     logs and registry lookup.
   - Do not include the dev configuration fingerprint in the deployment id.
     The id identifies the logical dev session; the fingerprint identifies
     whether the running logical session still matches the requested
     configuration.
   - If a handle with the same stable id exists, reuse it.
   - If a handle exists but its config fingerprint differs, fail with an
     actionable message asking the user to restart
     `quarkusApplicationDev --continuous`. Do not auto-replace in this phase.
   - Catch `NoClassDefFoundError`, `LinkageError`, or reflective lookup failure
     at the adapter boundary only if necessary, and turn it into a clear
     GradleException explaining that this Gradle version no longer exposes the
     internal deployment API expected by Quarkus.

3. Rework `QuarkusApplicationDevTask`.
   - Inject `DeploymentRegistry` with an `@Inject` abstract getter. Gradle task
     injection getters normally need task-type visibility, so keep this as the
     only task-level internal API touchpoint and keep all behavior in the
     adapter/handle boundary.
   - Remove production dependence on `QuarkusApplicationDevSessionService` as
     the owner of the real dev session.
   - Keep `QuarkusApplicationDevSessionService` only as an explicit test-only
     override seam for bounded TestKit fixtures. Production task registration
     must not set that property and must not register a real dev-session
     `BuildService`.
   - In the task action:
     - validate continuous mode;
     - map Gradle `InputChanges` exactly as today;
     - obtain or start the deployment handle from the registry;
     - before the handle is ready, treat observed changes as startup baseline;
     - after the handle is ready, submit reloadable batches through the handle;
     - write the existing receipt file with sequence, incremental flag,
       observed changes, runtime jar changes, session-ready flag, and outcome.
   - The sequence counter must live in the handle/session so it survives
     continuous-build iterations.
   - Do not call `Task.getProject()` from task execution.

4. Preserve current transport and launch behavior.
   - Reuse `GradleNativeDevModeLauncher`.
   - Reuse `BuildOutputChangesTransports.createTcpServer()`.
   - Reuse the sequence-0 stale failed connectivity probe unless a cleaner
     production readiness signal already exists.
   - Keep token generation inside the server/transport path. Do not log the
     token or put it in receipt/status files.

5. Update or retire `QuarkusApplicationDevSessionService`.
   - If no longer needed in production, remove it from main sources or reduce it
     to test-only support.
   - Do not leave two competing real session owners.
   - Test-only recording services may remain under `src/test`, but production
     task wiring must use the deployment handle path.

6. Re-enable and strengthen
   `QuarkusApplicationDevContinuousBuildTest`.
   - Remove `@Disabled`.
   - It must run through the Tooling API, not `GradleRunner`.
   - It must use `--continuous` and `--no-configuration-cache`.
   - Do not pass isolated-projects in this real continuous test while Gradle
     rejects isolated projects with configuration cache disabled.
   - It must:
     - wait for the initial ready receipt;
     - mutate a production source file;
     - wait for `sequence=2`;
     - assert `incremental=true`;
     - assert outcome includes `PENDING,SENT_APPLIED`;
     - cancel through `CancellationTokenSource`;
     - assert the deployment handle's stop/close path ran. A close receipt file
       is acceptable for this test.
   - Keep strict timeouts and always cancel in `finally`.

7. Add cheap focused tests before or alongside the real smoke.
   - Unit-test id/fingerprint generation if the helper has pure logic.
   - Unit-test handle lifecycle with fake process/server seams if practical.
   - ProjectBuilder or TestKit wiring test verifies the task can be realized and
     that `DeploymentRegistry` injection does not expose public task properties.
   - Existing bounded TestKit tests must continue to cover configuration cache
     and isolated projects. If test-only recording fixtures still override the
     dev path, keep them explicitly test-only.

8. Documentation comments in code.
   - Add one concise class-level comment at the internal adapter/handle boundary
     explaining:
     - Gradle has no public build-session-scoped service;
     - this code intentionally uses `org.gradle.deployment.internal.*`;
     - all usage is isolated here for compatibility and future replacement.
   - Do not spread internal-API rationale comments through unrelated task code.

Verification commands:

```bash
./mvnw -pl devtools/gradle/gradle-app-plugin process-sources
cd devtools/gradle && ./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationDevContinuousBuildTest --no-configuration-cache --rerun-tasks --stacktrace
cd devtools/gradle && ./gradlew :gradle-app-plugin:test --no-configuration-cache --rerun-tasks --stacktrace
```

If the full `:gradle-app-plugin:test` suite is too slow during iteration, run
focused tests first, but do not declare the phase done without the full module
test command above or a documented blocker.

Acceptance:

- Real continuous-build Tooling API smoke passes and is no longer disabled.
- Sequence state survives from initial startup to a second continuous-build
  iteration.
- The second iteration sends a production source/class reload batch to Quarkus
  and receives `APPLIED`.
- Cancelling the Tooling API continuous build stops the deployment handle and
  closes the Quarkus dev process/server.
- No production `BuildService` owns the long-lived Quarkus dev process.
- Internal Gradle API usage is isolated and documented.
- Bounded configuration-cache/isolated-project tests still pass.

## Phase 9: Documentation And Cleanup

Update WIP docs:

- `quarkus-dev-continuous-build-design.md`
- `new-application-plugin-design.md` if reserved task status changes
- any task topology/reference doc that lists task names and behavior

Document:

- `quarkusApplicationDev` requires Gradle continuous build for real use;
- `quarkusApplication.dev { ... }` is separate from package outputs and only
  accepts common/dev-relevant configuration;
- dev launch uses `EffectiveConfigPlanner` plus declared `configInputs` and
  overlays explicitly captured Gradle/JVM properties so compatibility keys such
  as `quarkus.native.builder-image` reach Quarkus dev bootstrap;
- Quarkus dev child stdout/stderr is forwarded to the Gradle console with a
  `[quarkus-dev]` prefix instead of relying on `ProcessBuilder.inheritIO()`;
- initial startup changes are baseline and ignored for reload;
- build-logic changes require restarting the continuous dev session;
- dependency/classpath changes are not reloadable in the first slice;
- continuous testing remains deferred.

Keep obsolete `devModeExperiment` findings clearly marked as historical
evidence. The probe task itself is retired because `quarkusApplicationDev` now
owns equivalent declared inputs and cheap coverage.

Acceptance:

- Docs and implementation agree on supported behavior.
- Deferred follow-ups are tracked in the design doc, not only in this plan.

## Deferred Follow-Ups

- Continuous testing through Gradle-produced test outputs and `TestSupport`.
- Dev UI controls for Gradle-owned continuous testing.
- Dependency/classpath rebootstrap with a refreshed application model.
- Jar-entry diffing for jar-only dependencies, if ever justified.
- Same-build dependency resource wakeup behavior.
- Included-build/composite-build dependency output behavior.
- IDE integration outside command-line `--continuous`.
- Better user experience for build-logic change detection and restart.
- Optional non-continuous run-task implementation and task-name migration.
- Multiple named dev configurations, if a real use case emerges later.
- Move the validated Gradle-plugin-internal build-output policy/coalescing
  layer into `core/deployment` as a build-tool-agnostic abstraction once the
  behavior is proven in real Gradle/Nessie development.

## Definition Of Done For This Plan

- `quarkusApplicationDev` in the standalone plugin has a real bounded
  Gradle-native implementation for production application classes/resources.
- It can start Quarkus dev with an external build-output transport.
- It forwards dev process stdout/stderr to the Gradle console.
- It passes effective dev configuration and declared config inputs to Quarkus
  bootstrap, including explicit `quarkus.*` Gradle project properties.
- It ignores baseline changes before Quarkus is augmented/started.
- It sends successful production output batches after startup once the dev
  session is ready.
- It routes raw Gradle build-output candidates through a bounded
  Gradle-plugin-internal policy/coalescing layer before calling
  `BuildOutputChangesServer.send`.
- It does not send reload batches for failed builds, precise jar-only
  dependency changes, unsupported dependency/classpath changes, or build-logic
  changes.
- Cheap unit/ProjectBuilder/TestKit coverage exists and runs with configuration
  cache and isolated projects.
- Real Quarkus smoke coverage exists or the remaining blocker is explicitly
  documented with a concrete follow-up.
