# Proper Gradle Quarkus Dev Design Seed

Status: investigation results folded in, not an implementation plan
Last reviewed: 2026-07-09

## Objective

Capture what we need to know before designing a proper Gradle-native
`quarkusDev` and `quarkusContinuousTest` implementation for the standalone
`io.quarkus.application` plugin.

The goal is not to port the legacy `quarkusDev` internals. The goal is to let
Gradle remain authoritative for source-set selection, generated sources,
compilation, resources, project dependencies, test suites, toolchains, and
incremental build state, while Quarkus dev mode remains authoritative for
runtime reload, augmentation, Dev UI, Dev Services, and continuous-test
behavior.

## Current Conclusion

A proper Gradle implementation likely needs a build-iteration event pipe from
Gradle to Quarkus dev mode, plus an explicit Quarkus dev bridge/session that is
not owned by a blocking Gradle task or by a Gradle build service.

The Quarkus-running side should reuse the existing remote-dev apply/sync/reload
mechanics where possible. Current `quarkusRemoteDev` already proves that a
running mutable Quarkus application can receive changed files, update its
application tree, and reload through existing hot-replacement paths. The part
that should change for Gradle-native dev is the producer: Gradle should produce
the output batch instead of Quarkus watching and compiling source roots.

The current legacy model asks Quarkus dev mode to watch source roots and run
its own compiler. That is convenient for Maven-style projects, but it is easy
to get wrong in Gradle because a Gradle project can have:

- custom source sets and JVM test suites;
- generated sources and generated resources;
- Kotlin, Scala, Groovy, annotation processors, KAPT, KSP, or other language
  plugins;
- toolchain-specific compilation settings;
- project dependency artifacts selected through variants;
- included builds and composite builds;
- compile avoidance, incremental compilation, and build-cache behavior that
  Quarkus cannot safely reconstruct by inspecting directories.

For Gradle, Quarkus dev mode should usually reload from Gradle-produced class
and resource outputs, not compile source files itself. The reload consumer can
still be based on the existing Quarkus remote-dev/runtime-update machinery.

## Existing Documentation Pointers

The current WIP docs already say this feature is deferred, but they do not
design it in detail:

- `new-application-plugin-design.md` says run, dev mode, and continuous testing
  are failing stubs until Quarkus can integrate natively with Gradle's
  continuous build model.
- `application-model-and-codegen.md` says dev/run/continuous-test need a
  separate design because they genuinely need source/resource/output roots.
- `declared-dependencies-gradle-native-design.md` says Gradle-native
  `quarkusDev` should reference the shared dependency model, but it is outside
  the dependency-model slice.
- `application-plugin-build-shapes/design.md` says named launch/dev/run/
  remote-dev/continuous-test behavior needs a design that leaves room for
  Gradle continuous-build integration.

This file is the missing dedicated seed for that design.

## Gradle Continuous Build Facts

Official Gradle continuous build behavior:

- `--continuous` / `-t` re-executes the requested tasks when file inputs change.
- Gradle behaves as if the requested task command were run again after relevant
  input changes.
- Gradle watches task inputs through file-system watching.
- Gradle waits for a quiet period before starting the next build; this is
  controlled by `org.gradle.continuous.quietperiod`.
- Continuous build does not recalculate the build model between iterations.
  Build-script and task-configuration changes require restarting the continuous
  build.
- Continuous build does not work with `--no-daemon`.
- Gradle only watches files under project directories, and there are known
  limitations around newly-created input directories, symlinks, untracked tasks,
  and tasks with no outputs.
- Gradle documents `--continuous` as primarily a command-line feature; IDEs
  often have their own continuous compilation/watch loops.
- As of Gradle 9.6.1, Gradle issue
  `https://github.com/gradle/gradle/issues/38482` tracks configuration-cache
  failures with real `--continuous` builds. Bounded TestKit simulations should
  still run with configuration cache enabled, but tests that execute a real
  open-ended `--continuous` process must not require `--configuration-cache`
  until that Gradle bug is fixed.

Official references:

- Gradle Continuous Builds:
  `https://docs.gradle.org/current/userguide/continuous_builds.html`
- Gradle BuildEventsListenerRegistry:
  `https://docs.gradle.org/current/javadoc/org/gradle/build/event/BuildEventsListenerRegistry.html`
- Gradle Shared Build Services:
  `https://docs.gradle.org/current/userguide/build_services.html`

Gradle plugin-facing event hooks:

- `BuildEventsListenerRegistry` can subscribe a build service to task finish
  events.
- The listener receives `TaskFinishEvent` events one at a time.
- Events are delivered concurrently with task execution and do not block task
  execution.
- For configuration-cache compatibility, the listener provider must come
  directly from a registered Gradle build service.
- Shared build services are configuration-cache compatible and are scoped to the
  build, not a single project.
- Old listener APIs such as `BuildListener.buildFinished` and
  `TaskExecutionGraph` execution listeners are deprecated or unsupported with
  configuration cache and should not be used for the new plugin.
- Gradle's Flow API can expose the final build-work result after scheduled work
  completes, but it is not a live event stream.

Open Gradle API question:

- `BuildEventsListenerRegistry` gives task-completion events, but not directly
  the `InputChanges` set that caused a task to run.
- No public Gradle API has been identified that streams changed-file events to a
  plugin task or build service while a long-running task is active.
- A task can access changed files through `InputChanges` only during that task's
  own action.
- A practical design may need one or more small marker/snapshot tasks that
  translate Gradle task state into a stable reload event file consumed by the
  dev session bridge.

Important implication:

- a proper Gradle-native dev mode should not be modeled as one blocking task
  that starts Quarkus and waits forever while Gradle sends it changed files;
- if the selected task never returns, Gradle cannot finish the build iteration
  and enter the normal continuous-build wait/re-execute loop;
- Gradle iterations must perform bounded work and return; any long-lived
  Quarkus runtime needs to survive across iterations outside the task action or
  be coordinated through a separate process/protocol.

## Current Legacy Quarkus Gradle Dev Shape

Legacy `quarkusDev` lives in:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusDev.java`

Current behavior:

- initial Gradle execution wires `quarkusDev` to `classes`, resource
  processing, test compilation/resources, and code generation tasks;
- launches a Quarkus dev JVM through `DevModeCommandLine`;
- consumes serialized development and test application models;
- uses `DevModeCommandLineBuilder` to pass project directory, build directory,
  system/build properties, JVM arguments, compiler options, app metadata,
  module metadata, dev-mode classpath entries, and extension dev-mode metadata;
- resolves additional Quarkus dev-mode dependencies;
- maps reloadable local project dependencies into `DevModeContext.ModuleInfo`;
- passes source paths, resource paths, classes paths, generated-sources paths,
  test source paths, and test classes/resource paths into the dev-mode context.

Application-model wiring:

- `quarkusDev` consumes the DEVELOPMENT app model and the TEST app model;
- `quarkusTest` consumes the TEST app model for both app-model slots;
- the legacy model tasks are `quarkusGenerateDevAppModel` and
  `quarkusGenerateTestAppModel`;
- the model task serializes a `DefaultApplicationModel` and marks reloadable
  workspace modules for non-NORMAL launch modes.

Same-build dependency behavior:

- Gradle project dependencies discovered as workspace modules are marked
  `reloadable` and added to the application model's reloadable workspace module
  set.
- `QuarkusDev` does not rely only on those dependencies' resolved jar
  artifacts. It also converts reloadable workspace modules into
  `DevModeContext.ModuleInfo` entries containing source roots, class-output
  roots, resource roots, resource-output roots, and test output metadata when
  available.
- The serialized application model can still contain the Gradle-resolved
  dependency artifact path for a workspace dependency, but that dependency is
  not treated like an ordinary static external jar in dev mode.
- `CuratedApplication` excludes reloadable workspace dependencies from the base
  runtime classloader, so they are not frozen into the stable runtime parent
  like external jar-only dependencies.
- Reloadable workspace dependencies can still be present in deployment/runtime
  reloadable classloaders and compiler classpaths. The key distinction is not
  "no artifact path exists"; it is "same-build dependencies have workspace
  source/output metadata and reloadable classloader treatment."

Legacy `quarkusTest` is not a Gradle `Test` task. It extends the same dev-mode
task shape, switches the bootstrap mode to continuous-test, and uses
`IsolatedTestModeMain`.

Current Gradle-hostile assumptions:

- the task still depends on a live `Project` reference for some execution-time
  behavior;
- dev-mode dependency enrichment resolves and mutates configurations during the
  launch path;
- workspace module metadata includes source roots for local dependency projects;
- workspace source roots are mostly discovered by Gradle-model code that walks
  all projects and included builds and inspects source sets/tasks;
- source discovery uses `afterEvaluate` and task/source-set implementation
  details such as `Jar`, `Test`, copy specs, and source-set output matching;
- Quarkus dev mode receives enough source-root and compiler metadata to
  recompile on its own;
- this model tries to reconstruct Gradle build semantics inside Quarkus dev
  mode instead of consuming Gradle-produced outputs.
- legacy `compileTestJava` adjusts `failOnError` based on requested task names
  containing `quarkusDev`, which is brittle for task aliases, included builds,
  and configuration-cache reuse.

## Current Quarkus Dev Runtime Shape

Core dev mode lives mainly in:

- `core/deployment/src/main/java/io/quarkus/deployment/dev/RuntimeUpdatesProcessor.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/QuarkusCompiler.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/JavaCompilationProvider.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/DevModeCommandLineBuilder.java`

Important current behavior:

- `RuntimeUpdatesProcessor` owns source/resource watching for dev mode and
  continuous testing.
- For continuous testing, it watches main and test source/resource paths and
  periodically compiles changed test classes.
- `checkForChangedClasses(...)` walks source roots, detects changed files by
  timestamp, maps source files to class names, and invokes `QuarkusCompiler`.
- `QuarkusCompiler` builds compilation contexts from `DevModeContext.ModuleInfo`
  source paths, classes paths, generated sources, annotation processor paths,
  compiler options, and classpath elements.
- `JavaCompilationProvider` invokes the JDK `JavaCompiler` directly.

This is the main semantic mismatch with Gradle. It means Quarkus dev mode can
compile something different from what Gradle would compile.

## Core Reload Seam Findings

`RuntimeUpdatesProcessor` is the real reload coordinator. `HotReplacementContext`
is useful as a public-ish dev-mode handle, but `doScan(...)` is a source and
resource timestamp scan entry point, not a generic external build-output event
API.

Current reload flow:

```text
pre-scan hooks
  -> compile changed sources with QuarkusCompiler
  -> scan class output directories
  -> copy/check resources
  -> decide restart/no-restart/instrumentation
  -> invoke restartCallback
```

The existing neutral internal shape closest to what Gradle needs is
`ClassScanResult`: added/changed/deleted class files, changed class names, and
whether compilation happened. It is not a public API, but it is a useful
implementation target for an external-output path.

`DevModeContext.ModuleInfo` already contains static, build-tool-neutral output
metadata for modules:

- main and test classes paths;
- main and test resource paths and output paths;
- generated source path;
- target directory;
- artifact key and module identity.

That means a dev-mode runtime process already has enough launch-time metadata
to map externally reported output changes to known module outputs. Dynamic
Gradle build-iteration events should not be placed into `DevModeContext`; that
class should remain static launch metadata.

Smallest plausible Quarkus-side seam for continuous testing:

```java
RuntimeUpdatesProcessor.processBuildOutputChanges(BuildOutputChanges changes)
```

where `BuildOutputChanges` is build-tool-neutral and contains:

- main class output added/modified/deleted paths;
- test class output added/modified/deleted paths;
- main/test resource output changed paths;
- a strictly monotonic build-event sequence or timestamp supplied by the build
  tool;
- optional compile/test-compile problem state;
- flags such as `userInitiated`, `forceRestart`, and possibly `runTests`.

Internally, that method should:

- use existing `scanLock` and `codeGenLock`;
- map class output paths to `DevModeContext` module class roots;
- build a `ClassScanResult` without invoking `QuarkusCompiler`;
- update the same timestamp state the scanner uses, so later source-based scans
  do not replay stale changes;
- reuse the existing restart callback path;
- route test-output changes through `TestSupport` so Dev UI and continuous-test
  state continue to update through existing listeners.

Possible API placement:

- start with a method on `RuntimeUpdatesProcessor` because it has the needed
  state, locks, callbacks, timestamp maps, and `TestSupport`;
- expose a default method on `HotReplacementContext` later only if a stable
  external SPI is needed;
- do not overload `doScan(...)`, because its current contract is source-scan
  and compile oriented.

For plain dev reload, the first choice is different: reuse or extract the
current remote-dev file-apply/reload mechanics before adding a new generic core
reload API. The continuous-test seam is still needed because current remote-dev
does not model test outputs or call `TestSupport`.

Current remote-dev reuse points:

- `IsolatedRemoteDevModeMain` already creates and hashes a mutable application
  tree and sends changed/removed files through a `RemoteDevClient`.
- `RemoteSyncHandler` already receives update/delete requests on the
  Quarkus-running side, validates a password/session/counter hash, calls
  `HotReplacementContext.updateFile(...)` for updates and
  `HotReplacementContext.deleteFile(...)` for deletes, and coordinates reload
  with existing hot-replacement hooks.
- `RuntimeUpdatesProcessor.syncState(...)` already compares the mutable
  application tree state on the server side and asks for missing/changed files.
- The remote-dev server side already runs as a mutable jar in
  `REMOTE_DEV_SERVER` mode.

Current remote-dev producer limitations:

- the local side still asks Quarkus to watch source roots and compile;
- it derives changed files by regenerating and hashing a mutable application
  tree;
- it does not consume Gradle task-output batches;
- it does not make Gradle authoritative for Kotlin/KAPT/KSP, annotation
  processors, generated sources/resources, or dependency variant outputs.

So the desired design is not "copy current `quarkusRemoteDev`." It is "reuse
the Quarkus-running-side update/reload mechanics and replace the producer with
Gradle continuous-build outputs."

Risks to design around:

- external output events must bypass `QuarkusCompiler`, otherwise Gradle and
  Quarkus can compile differently;
- timestamp maps must be kept coherent or future ordinary scans can replay old
  changes. Gradle-originated events should use a strictly monotonic sequence or
  timestamp generated by the Gradle side, not wall-clock time, so NTP updates or
  filesystem timestamp races cannot reorder events;
- test-output events need a no-compile path parallel to current continuous-test
  compilation;
- resource semantics are not just path changes, because watched files can imply
  restart or no-restart behavior;
- remote dev has a different lifecycle and transport/security surface, but its
  existing file-apply/reload mechanics are still the best starting point for
  the Quarkus-running side.

## Prior Local Branch

Local branch:

- `gradle-dev-continuous-gradle`
- tip: `e839b5f43cae1b8ecc55e5e561c66af22d325f44`
- commit: `WIP quarkusDev using Gradle continuous build`

What it attempted:

- changed legacy `QuarkusDev` to take `InputChanges` in the task action;
- added an incremental `getWatch()` input collection covering the build file,
  source-set Java/resource dirs, classes/resources outputs, compile/runtime/
  annotation-processor classpaths, and Quarkus runtime/platform/deployment
  configurations;
- used Gradle's internal `DeploymentRegistry` and `DeploymentHandle` to keep a
  Quarkus dev process running across continuous-build iterations;
- started the Quarkus dev JVM as a child process from the generated dev-mode
  command line;
- gathered `FileChange` events on subsequent iterations;
- started classifying changed paths into main/test/runtime classpath changes.

What it did not finish:

- `ContinuousDeploymentHandle.reload(...)` only logged changed paths;
- there was no socket, stdin protocol, file protocol, or other real signal into
  Quarkus dev mode;
- restart/reload/test-only classification remained TODO;
- it used internal Gradle APIs;
- it still relied heavily on live `Project` state and source-set inspection.

Value of the branch:

- proves the broad idea that a Gradle task can participate in continuous build
  and see changed inputs;
- provides useful prior-art for change classification;
- demonstrates why Gradle's internal deployment mechanism is tempting for this
  problem;
- should not be copied directly into the new plugin.

Reason not to copy it directly:

- `DeploymentRegistry` / `DeploymentHandle` are internal Gradle APIs;
- using them would violate the new plugin's compatibility bar and create a
  high-risk dependency on Gradle internals.

## DeploymentRegistry Prior Art

Gradle's internal deployment API is not public, but it is real prior art for
continuous-build-aware long-running processes.

Confirmed Gradle facts:

- Gradle issue `https://github.com/gradle/gradle/issues/2336` identifies the
  internal deployment API as the prior internal mechanism for `PlayRun` and asks
  for a public API.
- Gradle issue `https://github.com/gradle/gradle/issues/23984` asks for a
  configuration-cache-compatible build-session-scoped service, using the same
  class of problem: start a local web server and use `--continuous` to
  regenerate served files. It is closed as duplicate/not planned, which means
  there is still no public replacement for the internal build-session lifecycle
  APIs.
- In Gradle 9.6.1, `DeploymentRegistry`, `DeploymentHandle`, and `Deployment`
  still live in `org.gradle.deployment.internal`.
- The API shape has been stable enough from Gradle 4.2.x through Gradle 9.6.x
  for the core methods this design needs:
  - `DeploymentRegistry.start(String, ChangeBehavior, Class<T>, Object...)`;
  - `DeploymentRegistry.get(String, Class<T>)`;
  - `DeploymentHandle.isRunning()`;
  - `DeploymentHandle.start(Deployment)`;
  - `DeploymentHandle.stop()`.
- `DefaultDeploymentRegistry` is scoped to Gradle `Scope.BuildSession`, tracks
  pending changes, and stops all registered deployment handles when the
  registry stops.
- Gradle's own `ContinuousBuildActionExecutor` receives the build-session
  deployment registry and owns the outer continuous-build loop.
- Gradle has had cross-version coverage for stopping deployment handles when a
  continuous build is cancelled
  (`DeploymentHandleContinuousBuildCrossVersionSpec` in Gradle's test suite).
- Gradle also has an internal `BuildSessionLifecycleListener` in
  `org.gradle.internal.session`. Its Javadoc says one or more builds may run
  during a session and gives continuous mode as the example. `beforeComplete()`
  is called immediately before session services are torn down. Gradle uses this
  internally for session-scoped worker daemon cleanup and configuration-cache
  collection.

Important API nuance:

- Some secondary descriptions mention a deployment-handle `onNewBuild(...)`
  callback. That is not the current Gradle 9.x interface. The current
  synchronization API is `Deployment.status()` plus registry-managed pending
  and up-to-date state.
- `ChangeBehavior.BLOCK_AND_REBUILD` exists in the enum, but Gradle's
  `RegisteredDeployment.create(...)` has not handled it in checked Gradle
  versions. Do not use it.
- Gradle's built-in internal `RunApplication` uses the deployment registry and
  a `JavaApplicationHandle`; `PlayRun` historically used `ChangeBehavior.BLOCK`
  and called `Deployment.status()` from its worker-side reload path.
- Scope IDs are useful conceptual vocabulary, but they are internal and should
  not be Quarkus plugin inputs. In current local Gradle 9.6.1 source,
  `BuildInvocationScopeId` is build-tree scoped and changes per build
  invocation, while `WorkspaceScopeId` and `UserScopeId` are persistent local
  IDs. A public/stable `BuildSessionScopeId` was not found in the local Gradle
  9.6.1 source; use `DeploymentRegistry` or `BuildSessionLifecycleListener`
  behavior for session lifetime, not scope IDs.
- A workaround that climbs from `ListenerManager` to a parent registry and
  registers `BuildSessionLifecycleListener.beforeComplete()` can model
  session teardown, but it is deeper internal API than `DeploymentRegistry` and
  should not be the primary Quarkus path unless the deployment registry proves
  insufficient.

Third-party and ecosystem evidence:

- The current `node-gradle` and old `moowork` Node plugins do not use the
  deployment API; they execute Node processes directly and therefore do not
  provide useful lifecycle prior art for this problem.
- A Gradle forum workaround for Node continuous execution did use
  `DeploymentRegistry` / `DeploymentHandle`, and added a JVM shutdown hook
  because older Gradle behavior around interruption was not fully trusted.
- JetBrains' Kotlin Gradle plugin webpack task is a production example that
  uses `DeploymentRegistry`, `DeploymentHandle`, and `Deployment` for a
  Node-backed webpack dev server. The practical compatibility guard there is
  Kotlin's Gradle-version compatibility matrix and test coverage, not an
  explicit public fallback.
- `bennofs/gradle-continuous-exec` is a small generic plugin using the same
  internal deployment API for long-running commands.
- Gretty does not use the deployment API. It implements its own runner JVM and
  loopback control protocol, uses explicit stop/restart/redeploy commands, and
  has intentionally removed unrelated Gradle internal API usage in the past.
  This is useful counter-prior-art: an external supervisor is viable, but it is
  heavier and recreates lifecycle machinery Gradle already has internally.

Compatibility guidance for Quarkus:

- Treat use of `org.gradle.deployment.internal.*` as an explicit, documented
  internal Gradle dependency.
- Keep all imports and API calls behind one adapter/handle package so breakage
  is isolated.
- Prefer `DeploymentRegistry` over a raw `BuildSessionLifecycleListener`
  workaround. The deployment registry is internal too, but it is purpose-built
  for long-running deployments and already integrates with the continuous-build
  executor.
- Add tests modeled on Gradle's own deployment tests:
  - duplicate id / existing handle lookup;
  - handle survives a second continuous-build iteration;
  - handle `stop()` is called when a Tooling API continuous build is cancelled;
  - `ChangeBehavior.NONE` does not trigger Gradle's automatic restart path.
- Prefer `ChangeBehavior.NONE` for the first Quarkus implementation. Quarkus
  owns reload/restart decisions through explicit build-output batches; Gradle
  should only own build-session lifetime.
- Keep Gretty-style external supervisor plus TTL/heartbeat cleanup as a
  fallback if the internal API changes or becomes unusable.

## Target Architecture Sketch

The preferred model is a two-side architecture:

1. Gradle side
   - owns source/resource/codegen/test compilation;
   - owns project dependency builds;
   - owns incremental and continuous-build triggering;
   - emits a typed reload event after the relevant Gradle work has completed.

2. Quarkus dev side
   - owns the long-running dev application process;
   - owns augmentation, runtime reload, Dev UI, Dev Services, and test runner
     state;
   - receives Gradle-produced class/resource output changes;
   - applies production output changes through existing remote-dev/runtime-update
     mechanics where possible;
   - sends externally built test output changes through the focused
     `RuntimeUpdatesProcessor`/`TestSupport` continuous-test seam.

A future implementation should prefer Gradle-produced outputs over raw source
roots for reload decisions.

## User Experience Direction

Real Gradle-native `quarkusApplicationDev` should be continuous-build-first.
The expected command is:

```bash
./gradlew :app:quarkusApplicationDev --continuous
```

Dev mode should have one always-present configuration block on the standalone
application extension:

```kotlin
quarkusApplication {
  dev {
    quarkusBuildProperties.put("quarkus.foo", "bar")
    forkOptions {
      // dev worker/JVM options, if a dev-specific worker is involved
    }
  }
}
```

This `dev { ... }` block is separate from `builds { ... }`. Dev mode is not a
package output and must not build or overwrite `build/quarkus-builds/<name>/`.
It may consume common extension-level Quarkus build properties plus
dev-specific overrides, but it should not expose package-type options such as
native-image arguments, fast-jar/mutable-jar layout settings, uber-jar archive
naming, container image publishing, AOT image, or deploy settings. A future
named `devModes { ... }` container can be added if real use cases need multiple
dev configurations; do not infer one dev mode per package output.

The dev launcher must build its Quarkus bootstrap properties through the same
effective-config planning model as build/codegen tasks, including declared
`quarkusApplication.configInputs`. It must also preserve the explicitly
captured Gradle-side `quarkus.*`/`platform.quarkus.*` properties when launching
the dev JVM. This matters for compatibility keys such as
`quarkus.native.builder-image`: Quarkus native config defaults that value from
`platform.quarkus.native.builder-image`, and the effective-config fallback may
contain only the placeholder platform value until the real platform properties
are available. A user-provided project property must still be visible to
dev-mode bootstrap.

The Quarkus dev child process stdout/stderr should be forwarded to the Gradle
console with a stable prefix, currently `[quarkus-dev]`. Do not rely on
`ProcessBuilder.inheritIO()` for the Gradle daemon path; it can make dev-mode
startup or augmentation failures invisible in real builds.

Gradle-native `quarkusApplicationDev --continuous` must not expose Quarkus
dev's stdin-driven console commands as if they were usable. Gradle owns the
terminal and the continuous-build lifecycle; the Quarkus dev process is a child
process whose stdout/stderr are forwarded, but whose stdin is not a reliable
interactive terminal. Capturing or multiplexing stdin from a Gradle continuous
build is hard to make correct because Gradle itself needs to receive
cancellation/termination input and owns the long-running command. Quarkus'
`ConsoleStateManager` installs commands such as `e`, `h`, `r`, and `o` only
when `QuarkusConsole.isInputSupported()` is true. The Gradle-native dev launcher
should therefore force the Quarkus console into non-input mode, for example by
setting `quarkus.console.basic=true` and `quarkus.console.disable-input=true`,
so Quarkus does not print misleading "Press [...]" prompts.

The actions behind those prompts should be replaced by Gradle-native controls
where they are useful:

- command-line arguments edited by `e`: model as typed
  `quarkusApplication.dev { ... }` properties or task options that require
  restarting the continuous build when changed;
- force restart (`s`) and live-reload/instrumentation toggles (`l`/`i`): model
  as explicit Gradle task options or follow-up control-channel operations only
  after the build-tool event transport supports them;
- continuous-test controls (`r`, `o`, and related status prompts): defer to the
  future Gradle-native continuous-test design rather than enabling Quarkus'
  stdin console inside pure `quarkusApplicationDev`.

Pure `quarkusApplicationDev` should also disable Quarkus continuous testing by
default, for example by setting `quarkus.test.continuous-testing=disabled` in
the Gradle-launched dev JVM unless the user explicitly opts into a future
Gradle-native continuous-test mode. `quarkus.console.disable-input=true` only
prevents stdin command handling. It does not by itself prevent
`TestConsoleHandler` from registering status lines when continuous testing is in
the default `paused` mode, which leads to misleading output such as
`Tests paused` and test-related prompt fragments in a process that cannot
consume the advertised key commands.

This restriction is specific to Gradle-native continuous dev. A future
`quarkusApplicationRun` or explicit non-continuous run-like task may be able to
inherit or bridge stdin more naturally, because it does not need Gradle's
continuous-build loop to stay in control of the terminal. That should be
designed separately from `quarkusApplicationDev --continuous`.

The dev runner manifest classpath must stay narrower than the application
runtime classpath. Quarkus bootstrap receives the full application model and is
responsible for curating ordinary runtime, deployment, and runtime-dev
dependencies. The DEVELOPMENT application model must be resolved from a
DEVELOPMENT runtime configuration that includes normal conditional dependencies
and `conditional-dev-dependencies`. Let Gradle resolve both dependency streams
through the modeled configurations; runtime-dev artifacts belong in the
DEVELOPMENT application model, not in the dev runner manifest. The Gradle
launcher should add only:

- Quarkus dev-mode support jars needed to start `DevModeMain`;
- local module output roots through `DevModeContext.ModuleInfo`;
- application artifacts that Quarkus classloading marks as parent-first.

Do not add every runtime jar from the application model to the runner manifest.
That can pollute or override the classpath Quarkus derives from the application
model, especially for runtime-dev artifacts. Separately,
`getRuntimeJarsWithoutOutputVariants()` is only an incremental-input bucket for
jar-only dependency changes that require restarting dev mode; it is not the
complete dev-mode classpath.

Without `--continuous`, a bounded-task implementation would build the current
outputs, start or update a Quarkus dev session, and then return. That is a poor
default user experience for a task named `Dev`: users reasonably expect the
command to stay attached, stream logs, accept console input, reload on changes,
and stop when they terminate the command.

Therefore the preferred default is:

- `quarkusApplicationDev` fails fast when invoked without Gradle continuous
  build;
- the error explains that Gradle-native dev mode requires `--continuous`;
- users who intentionally want a non-continuous one-shot can use
  `quarkusApplicationRun`;
- if a non-continuous escape hatch is still useful, it must be explicit, for
  example a task option such as `--no-continuous-build`, and the docs should
  describe it as run-like rather than full dev mode.

The opt-out should not become the primary UX. It would not provide much more
than `quarkusApplicationRun`, because Gradle would not keep rebuilding and
emitting build-iteration batches.

Conceptual flow:

```text
./gradlew quarkusApplicationDev --continuous

initial continuous-build iteration:
  quarkusApplicationDev preparation
    -> generate code
    -> process resources
    -> compile main classes
    -> optionally compile test classes
    -> generate dev workspace model
    -> write initial reload/session metadata
  quarkusApplicationDev
    -> start or attach to Quarkus dev process
    -> return after the session has accepted the current build state

subsequent continuous-build iteration:
  Gradle detects changed task inputs
    -> reruns affected codegen/resource/compile/model tasks
    -> writes a reload event describing changed outputs
    -> long-running Quarkus dev session receives the event
    -> Quarkus reloads/restarts/reruns tests as appropriate
    -> Gradle task returns and waits for the next continuous-build trigger
```

The exact task split is not settled. A likely direction is to keep the
long-running Quarkus process outside a blocking task action and let small
bounded tasks write event files or send protocol messages that describe the
latest Gradle outputs.

Session ownership direction:

- `./gradlew :app:quarkusApplicationDev --continuous` is the preferred user
  command and should be the simplest supported path;
- the build should start the Quarkus dev process and should stop it when the
  build is stopped;
- the Quarkus dev process must not be owned by a blocking Gradle task action,
  because bounded Gradle iterations must return;
- process lifecycle therefore needs an explicit session coordinator owned by the
  build invocation, with cleanup for normal termination, interrupted builds, and
  stale sessions;
- a documented two-command model is only a fallback if a good single-command
  implementation cannot be made robust without Gradle internal APIs.

Do not make a Gradle `BuildService` the owner of the long-lived Quarkus dev
JVM. A build service is fine as configuration-cache-compatible listener or
coordination glue, but public Gradle APIs do not give it a reliable
continuous-build session lifetime. Gradle build services are build-scoped and
closed when Gradle discards the service; continuous build runs multiple bounded
build iterations inside one invocation/session. That lifetime is not the same
thing as "keep this user-facing dev process alive across iterations."

Gradle's internal deployment API does model the lifetime this feature needs.
`org.gradle.deployment.internal.DefaultDeploymentRegistry` is scoped to
`Scope.BuildSession`, and `ContinuousBuildActionExecutor` uses the deployment
registry while it owns the outer continuous-build loop. The registry also
implements `Stoppable`, so registered `DeploymentHandle` instances are stopped
when the build session ends. This is internal API, but it is materially better
than daemon-static state, timeout-only cleanup, or an external supervisor for
the first working single-command implementation.

The current Gradle 9.x internal shape is:

- `DeploymentRegistry.start(id, changeBehavior, handleType, params...)`
  creates a `DeploymentHandle` via Gradle object construction and calls
  `handle.start(Deployment)`;
- `DeploymentRegistry.get(id, handleType)` returns the existing handle in later
  continuous-build iterations;
- `DeploymentHandle.stop()` is called when the deployment registry stops;
- `Deployment.status()` can be used by a handle when it wants Gradle's
  built-in deployment status semantics, but the Quarkus integration can keep
  explicit build-output batch delivery in the task action and use
  `ChangeBehavior.NONE` initially.

Use the internal API behind a narrow compatibility boundary:

- create one small package or adapter class that imports
  `org.gradle.deployment.internal.*`;
- make the rest of the plugin talk to a Quarkus-owned
  `QuarkusApplicationDevDeploymentHandle` abstraction;
- fail with a clear diagnostic if the internal API is unavailable or changes;
- keep the disabled Tooling API continuous-build test as the compatibility
  proof that the handle survives iteration-to-iteration and stops on
  cancellation;
- document this as an intentional internal Gradle dependency until Gradle
  exposes a public build-session deployment API.

Practical Gradle-side shape:

- bounded tasks run per continuous-build iteration;
- a small incremental marker/event task can use `InputChanges` and write the
  iteration event file or send a local protocol message;
- a `BuildEventsListenerRegistry` build service may collect task finish/failure
  metadata, but it cannot provide changed files;
- a Flow action may observe final build-work success/failure, but it is not a
  live event stream;
- Quarkus dev process ownership belongs to a `DeploymentHandle`, not to a
  blocking Gradle task or build service;
- the task action obtains or starts the handle through `DeploymentRegistry`,
  maps the current iteration's `InputChanges` into `BuildOutputChanges`, and
  submits that batch to the handle;
- the handle owns the Quarkus dev child process, token, transport endpoint,
  coalescing policy, and stop/cleanup path;
- the deployment id is stable and specific, for example a hash of root
  directory, project path, task path, plugin id/version, and dev configuration
  fingerprint;
- if the current dev configuration fingerprint differs from the running handle,
  the task should stop/replace the old handle or fail with an actionable
  restart message. Prefer fail-first until replacement semantics are proven.
- Prefer a local socket protocol for the first serious prototype. File-based
  protocols are simple, but Windows file-locking and partial-write semantics are
  easy to get wrong. A possible shape is that the Gradle side opens the listener
  and the Quarkus dev process connects back to it. Stdin may be viable for a
  child-process protocol, but it needs careful treatment because dev mode also
  has console input.
- The local socket protocol needs simple authorization. The Gradle side should
  generate a random per-session token, pass it to the Quarkus dev process during
  launch, and require the connecting client to present that token before any
  build-iteration events are accepted. This is not intended to be a complex
  security protocol; it prevents unrelated local processes from attaching to
  the build listener accidentally or maliciously.
- Any transport must be backpressure-safe. A bounded Gradle iteration must not
  block indefinitely because Quarkus is busy applying a previous batch and is
  not reading from the pipe/socket. The Quarkus side should separate transport
  I/O from reload/test processing, accept or reject a complete batch quickly,
  and process accepted batches serially on a separate path.

## Reload Event Contract

The bridge needs a stable event model. It should not expose Gradle internals to
Quarkus core. It should also avoid duplicating current remote-dev file
application and reload behavior when those mechanics can be cleanly reused.

The protocol should be batch-first. Gradle should emit one build-iteration
result after a continuous-build iteration has completed, not a stream of
individual file events. Users often edit several related files one after
another. Gradle's continuous-build quiet period and task graph execution are the
right boundary for coalescing those edits into one coherent state update.

Quarkus should treat each message as "the state after this Gradle build
iteration", not as a low-level watcher event. This gives Quarkus an
atomic-ish change set:

```text
Gradle detects changed inputs
  -> waits for the continuous-build quiet period
  -> runs the affected tasks
  -> emits one build-iteration result
  -> Quarkus reloads, reports diagnostics, or keeps the previous app state
```

Every completed Gradle iteration should emit a batch result. Only successful
batches are reloadable:

- `BUILD_SUCCEEDED`: Quarkus may reload/restart from the new Gradle-produced
  production outputs. Test output changes become eligible for continuous-test
  reruns only after any required production-code/resource changes in the same
  batch have been successfully applied by Quarkus.
- `BUILD_FAILED`: Quarkus must not reload from partial or failed outputs. It
  should keep the previous running app/test state and surface the Gradle failure
  as the latest build state.
- `BUILD_CANCELLED` / `BUILD_SUPERSEDED`: Quarkus should normally ignore the
  batch for reload purposes, but may update pending/status UI if useful.

Holding failed batches back would hide useful state. If Gradle compilation or
code generation fails, Quarkus and Dev UI should be able to report "the previous
application is still running, but the latest Gradle iteration failed" instead
of silently showing stale behavior.

The initial startup iteration is special. Until Quarkus has completed
augmentation and startup, Gradle-observed output changes are baseline state, not
reload events. The Gradle side may log or persist those startup observations for
diagnostics, but it must not send them as reloadable `BuildOutputChanges`
batches. The first reloadable batch is the first successful incremental
application-output change after Quarkus reports readiness.

The first TCP transport is a bounded synchronous request/response protocol:
the build-tool side sends one `BuildOutputChanges` batch and waits for an
apply response. The Quarkus side reads the batch, calls
`RuntimeUpdatesProcessor.processBuildOutputChanges()`, and replies with the
result:

- `APPLIED`: Quarkus successfully applied the reloadable production state from
  the batch.
- `NOT_APPLIED`: Quarkus did not apply the reloadable production state. This
  includes failed processing, invalid message payloads, and consumer exceptions
  after the TCP authentication handshake succeeded.

The build-tool side may discard an emitted batch only after an `APPLIED`
response. If Quarkus returns `NOT_APPLIED`, times out, or the connection fails,
the build-tool policy must keep the emitted batch as unacknowledged and
coalesce later file events on top of it.

The transport layer must not allow Gradle to block forever while waiting for a
response. If Quarkus is still processing a previous batch when newer raw events
arrive, the build-tool policy must keep collecting/coalescing them using the
monotonic sequence. It must not grow an unbounded queue.

The first Gradle implementation may keep this coalescing/policy layer inside
the standalone Gradle application plugin while the behavior is validated through
the Nessie included-build development loop. Keep the policy shape
build-tool-neutral where practical, but defer moving it into `core/deployment`
until the Gradle-side behavior is proven.

The coalescing layer is not just a send gate. It is a build-output event pipe
that can collect multiple raw file events and emit one reduced batch when the
server/session is ready. Coalescing must happen per output category, output
root, and changed path. Initial file-level reduction rules should include:

- multiple `MODIFIED` events for the same file become one `MODIFIED`;
- `ADDED` followed by `MODIFIED` becomes `ADDED`;
- `DELETED` followed by `ADDED` followed by `MODIFIED` becomes `MODIFIED`;
- `ADDED` followed by `DELETED` cancels out;
- `MODIFIED` followed by `DELETED` becomes `DELETED`;
- `DELETED` followed by `ADDED` becomes `MODIFIED`.

The policy must keep collecting/coalescing while a previous batch is busy, then
emit the latest coalesced batch once delivery is possible. It must not expose
Gradle or Gradle-plugin-specific types in its public API, even if the first
implementation lives in the Gradle plugin.

Initial event fields may include:

- session id;
- per-session authorization token handshake status, never the token in
  diagnostic output;
- build name / launch name;
- build iteration number or monotonic timestamp;
- status for the Gradle iteration;
- failure summary and diagnostics location when the iteration failed;
- main classes output directories;
- main resources output directories;
- generated resources output directories;
- test classes output directories;
- test resources output directories;
- changed output paths when available;
- changed source paths only as diagnostic metadata, not as compile authority;
- whether the change is main-runtime, test-only, resource-only, config-only, or
  unknown;
- whether Quarkus should attempt no-restart reload, application restart, test
  rerun, or full dev-process restart.

The bridge should tolerate coarse class/resource output events. If Gradle
cannot safely classify an application class/resource output change, the correct
fallback is a conservative Quarkus restart/reload, not a best-effort source
recompilation. Dependency/classpath changes are different: they should be
diagnostic-only or handled by a separate rebootstrap contract until Quarkus can
consume a refreshed application model and classloader state.

Granular class/resource output events require Gradle-side tracking. If the
external build tool wants Quarkus to distinguish `ADDED`, `MODIFIED`, and
`DELETED`, the build tool must track the output files and emit that change kind
per output path. `RuntimeUpdatesProcessor` can then map class-output file paths
to `ClassScanResult` and resource-output file paths to existing watched-file
semantics.

Dependency artifact changes are different and are deliberately outside the
first `BuildOutputChanges` contract. A changed jar-only dependency is not just
a class/resource file change:

- current `RuntimeUpdatesProcessor` does not watch dependency jars or diff jar
  contents;
- current `ClassScanResult` is rooted in classes directories and cannot
  represent jar entries without a separate model;
- ordinary dev-mode restart does rerun augmentation, but it does so against the
  existing `CuratedApplication`, existing application model, and potentially
  reused classloader/classpath-element state;
- a changed dependency can affect deployment indexes, build steps, generated
  bytecode/resources, runtime base classloader contents, and dependency
  selection.

Therefore dependency/classpath changes should not be added to
`BuildOutputChanges` as a simple `changedDependencyPaths` field. Such a field
would look accepted by the transport, but `RuntimeUpdatesProcessor` cannot
apply it correctly without more intrusive rebootstrap behavior. The safe future
contract is a separate coarse dependency/classpath-change event that escalates
to a stronger rebootstrap path:

1. stop the current application;
2. discard or recreate the relevant `CuratedApplication`, classloaders, and
   classpath/archive caches;
3. consume an updated application model when Gradle dependency resolution,
   selected variants, or dependency artifact contents changed;
4. rerun augmentation with that updated model;
5. start the application again.

There are two dependency-change cases:

- same coordinates and same artifact path, but jar contents changed. The
  dependency graph may be unchanged, but cached archive/classpath state can
  still be stale, so Quarkus needs a full rebootstrap-style refresh rather than
  a normal live-reload restart;
- dependency graph, version, variant, deployment dependency, or artifact path
  changed. The application model itself is stale and Gradle must provide or
  trigger a fresh model before Quarkus reaugments.

Same-build project dependencies with workspace metadata are a third category.
They should not be collapsed into jar-only dependency diagnostics. When Gradle
can identify a dependency project's class/resource output directories and track
their individual file changes, the future Gradle-native design can choose to
report those as explicit output changes or as a dedicated reloadable-workspace
dependency event. That still requires care, because the output belongs to a
dependency module rather than the application root, but it is a different
problem from an opaque external jar.

This needs a Gradle-side experiment before locking in the contract. The useful
questions are:

- can resolving `runtimeElements` plus `classes` and `resources` secondary
  variants reliably expose same-build dependency outputs;
- does that work for included builds and composite builds;
- when does Gradle still resolve only jars;
- can an incremental bounded task observe added/modified/deleted files for the
  selected output directories in continuous build;
- what can `BuildEventsListenerRegistry` contribute beyond task outcome and
  build-iteration status.

The standalone `io.quarkus.application` plugin previously carried an
investigation task named `devModeExperiment` for this purpose. It was not a
Quarkus dev-mode task and did not communicate with Quarkus. It was a bounded
Gradle task intended to be run directly, preferably with Gradle continuous
build, to show what the Gradle side could observe. The task has since been
retired now that `quarkusApplicationDev` owns equivalent declared inputs.

The probe consumes:

- the application project's main class-output directories;
- the application project's main resource-output directory;
- runtime dependency artifacts selected through `runtimeElements` with
  `classes` secondary-variant reselection;
- runtime dependency artifacts selected through `runtimeElements` with
  `resources` secondary-variant reselection;
- runtime dependency artifacts selected through the normal `jar` runtime view.

The task action logged Gradle `InputChanges` for each scope using the
`[quarkus-dev-probe]` prefix at lifecycle level. A small build service also
logged the `devModeExperiment` task-finish outcome, so the experiment could
compare the bounded task's own task-status event with the file changes visible
to that task without adding probe noise to unrelated task invocations.
The probe deliberately did not produce reload batches, did not start Quarkus,
did not diff jar entries, and did not claim dependency/classpath reload
semantics.

Nessie experiments with `:nessie-quarkus:devModeExperiment --continuous`
showed the expected distinction between application outputs and build-logic
changes:

- changing an application Java source in the Quarkus project produced
  `application-classes MODIFIED` entries;
- changing an application resource in the Quarkus project produced
  `application-resources MODIFIED` entries;
- changing a Java source in a same-build dependency produced
  `dependency-classes MODIFIED` entries and a modified runtime jar;
- changing a resource source in a same-build dependency produced
  `dependency-resources MODIFIED` entries, for example for
  `META-INF/services/org.projectnessie.model.types.ContentTypeBundle` under
  the dependency project's `build/resources/main` output;
- changing `servers/quarkus-server/build.gradle.kts` invalidated Gradle's
  configuration cache and forced task graph recalculation. One such rebuild
  stored a new configuration-cache entry successfully; a later no-op script edit
  failed while storing the configuration cache with a Gradle runtime
  serialization error for `Settings_gradle$5$2$1$1`.

The build-script failure happened after `devModeExperiment` itself completed
successfully. The failure was reported while Gradle stored the configuration
cache, in `ConfigurationCacheState.writeGradleEnterprisePluginManager`, and
Gradle printed "Please report this error". That is evidence of a
Gradle/Kotlin-DSL/settings/Develocity serialization problem exposed by
continuous build-script edits, not evidence that the probe captured forbidden
state.

After a build-script change, the probe can also lose its incremental input
history and run with `incremental=false`. In the Nessie experiment that caused a
fresh snapshot to appear as thousands of `ADDED` entries across application
classes/resources, dependency classes/resources, and runtime jars. The real
dev-mode task must not forward build-logic changes as Quarkus reload events.
It should summarize them for diagnostics and treat the surrounding build-logic
invalidation as a restart-required condition.

The first Tooling API continuous smoke for the production dev task also showed
that a successful second continuous-build iteration may still report
`InputChanges.isIncremental() == false`. Seeding multiple initial compiled
classes before adding a new source class did not change that behavior. The
Gradle task therefore must not forward Gradle's whole input tree when the
session is ready. Instead, it keeps a task-local content-fingerprint snapshot of
class/resource/runtime-jar outputs. Ready non-incremental iterations are diffed
against that snapshot, and only precise class/resource output deltas are sent to
Quarkus. If no prior snapshot exists, the iteration becomes a baseline rather
than a large reload batch.

The Nessie smoke test confirmed why content fingerprints are required:
`processResources` may refresh multiple copied output files after a single
source resource edit. Timestamp/size snapshots reported unchanged resources
such as `application.properties` and `logo.png`; content fingerprints reduced
the forwarded reload batch to only the edited `nessie-banner.txt`.

Runtime jar changes remain different: only precise jar-only deltas should
become restart-required diagnostics, because a full non-incremental jar snapshot
is too coarse to distinguish from Gradle's continuous-build bookkeeping.

Jar-entry granularity is a later optimization. It would require snapshotting
and diffing jar entries, mapping changed `.class` entries to class names,
mapping resource entries to Quarkus resource semantics, and validating
classloader/cache invalidation behavior. The first implementation should prefer
the coarse, correct dependency/classpath-change path.

## Build-Logic Changes

Gradle build scripts, settings scripts, convention plugins, included build
logic, plugin-management configuration, dependency-resolution management, and
other task-configuration inputs are not Quarkus reload inputs. They define the
Gradle model that decides what should be compiled, generated, resolved, and
watched.

Gradle continuous build can detect that such files changed and can rerun the
requested task graph, but the Quarkus dev session must not try to interpret the
result as ordinary source/resource changes. The correct user contract is:

- production source/resource output changes can be streamed to Quarkus after a
  successful Gradle iteration;
- test output changes can be streamed to Quarkus continuous testing only after
  the production state is healthy;
- dependency/classpath changes require a stronger rebootstrap/reaugmentation
  design and are not part of the first reload contract;
- build-logic changes require restarting the Gradle continuous dev session.

If the Gradle side can cheaply detect likely build-logic invalidation, it should
log a lifecycle warning and stop or fail the dev task with an actionable message
that asks the user to restart `quarkusApplicationDev --continuous`. Detection
must be best-effort only; correctness must not depend on recognizing every
possible Gradle model change.

The event bridge should therefore never emit build-script, settings-script, or
plugin-code changes as Quarkus file-change events. At most it can emit a
non-reloadable status explaining that the build model changed and the session
needs to be restarted.

For continuous testing, the batch application order matters:

1. Reject stale, failed, cancelled, or superseded batches for reload purposes.
2. Apply production class/resource output changes first. A future separate
   dependency/classpath-change event must instead use the stronger
   rebootstrap/reaugmentation path.
3. If production output application or the required Quarkus reload/restart
   fails, keep the previous continuous-test state and surface the production
   failure. Do not run tests against a partially applied or failed production
   state.
4. Only after production changes are successfully accepted should test
   class/resource output changes be converted into a `ClassScanResult`-like
   trigger and handed to `TestSupport`.

Pure test-only batches can skip the production-apply step, but they still need a
valid current production application state. If the previous production apply is
failed or unresolved, test reruns should be held back until production state is
healthy again.

The build iteration marker must be strictly monotonic and generated by Gradle's
coordination layer. It must not rely on wall-clock time or filesystem mtimes for
ordering. Wall-clock values can still be included for diagnostics, but ordering
and stale-event rejection should use the monotonic sequence.

For transport authorization, the token must be generated by the Gradle side for
the current invocation and passed to Quarkus dev out of band from the event
stream. Event logs and persisted diagnostics must not print it.

## Language And Generated-Source Findings

Quarkus-owned source watching and compilation is not sufficient for full Gradle
JVM builds because it does not re-enter the Gradle task graph.

Current dev mode detects files by `CompilationProvider.handledExtensions()` and
invokes Quarkus compilation providers directly:

- Java uses plain `javax.tools.JavaCompiler`;
- Kotlin uses direct `K2JVMCompiler`;
- KAPT and KSP Gradle tasks are not invoked;
- Groovy has no Quarkus compilation provider in the current source tree;
- Scala support was not visible as authoritative source in this worktree.

The legacy Gradle plugin has build-time Kotlin/KAPT wiring for
`compileKotlin`, `compileTestKotlin`, and KAPT stub tasks, but that wiring only
helps normal Gradle builds. Once legacy dev mode is running, reload compilation
uses Quarkus compilation providers instead of Gradle tasks.

The standalone `io.quarkus.application` plugin does not currently have full
Kotlin/KAPT/KSP runtime-dev support. That is acceptable while its dev and
continuous-test tasks are explicit failing stubs. It becomes a blocker before
claiming real Gradle-native dev-mode support, because the main design goal is
to let Gradle own those language and generated-source tasks rather than
reimplementing them in Quarkus dev mode.

Concrete risk examples:

- Kotlin + KAPT: editing a mapper/model can require `kaptGenerateStubsKotlin`
  and annotation processor output regeneration; Quarkus dev recompilation will
  not run that task.
- Kotlin + KSP: existing Gradle fixtures cover `clean build`, but there is no
  equivalent Gradle dev-mode reload coverage for KSP.
- Generated sources: legacy metadata tracks one generated-sources path per
  compilation unit, while Gradle builds can have multiple generators, languages,
  and source sets.
- Groovy/Scala: without Quarkus compilation providers and tests, edits are
  either ignored by Quarkus-owned reload or require Gradle compilation.

This reinforces the core design rule: Gradle should own code generation,
language compilation, resource processing, and test-suite compilation; Quarkus
should consume the resulting outputs.

## Required Quarkus Core/Dev-Mode Changes

The Gradle plugin alone is probably not enough, but the Quarkus-side change set
should be smaller than a new reload subsystem. Existing remote-dev already
contains much of the Quarkus-running-side behavior.

Likely Quarkus-side changes:

- introduce a dev-mode update input that accepts build-tool-produced class and
  resource output changes;
- introduce an explicit dependency/classpath-change path that can consume an
  updated application model and recreate bootstrap/classloader state before
  reaugmentation;
- allow disabling Quarkus-owned source watching and source compilation for
  Gradle-native dev sessions;
- separate "detect/compile changed sources" from "consume changed compiled
  outputs and reload";
- extract or expose reusable seams around the existing remote-dev
  file-apply/reload path, including `RemoteSyncHandler`,
  `HotReplacementContext.updateFile(...)`,
  `HotReplacementContext.deleteFile(...)`, and `RuntimeUpdatesProcessor`
  restart/resource handling;
- expose a delivery path for external build-tool reload events only after the
  output-batch/apply seam is clear. A local socket with a per-session random
  token handshake is still a plausible first local-delivery mechanism, but the
  API should remain build-tool-neutral and transport should not drive the core
  reload design;
- keep existing Maven-style source watching as a separate mode;
- make continuous testing able to react to Gradle-produced test class/resource
  output changes;
- for resources, avoid recreating cross-project source/resource inspection.
  Gradle should report the application project's resource output directories.
  Those changes can be mapped to application-relative paths where available and
  then checked against existing watched-file restart/no-restart semantics.
  Dependency project outputs need an explicit design that preserves the legacy
  distinction between reloadable workspace dependencies and jar-only
  dependencies. Packaged jar-only dependency artifacts belong to the separate
  dependency/classpath-change design, not the first `BuildOutputChanges`
  contract;
- for dependency jars, do not claim fine-grained reload support until Quarkus
  has an explicit jar-entry diff model and cache invalidation story. The
  initial behavior should be coarse and correct: dependency/classpath changed
  means rebootstrap/reaugment/restart with an updated model.
- keep the retired `devModeExperiment` findings as historical evidence for
  runtime dependency variant resolution. The production task now owns equivalent
  declared inputs; do not reintroduce an investigation-only task unless a new
  unknown needs it.

## Test Scope Direction

For `quarkusApplicationDev`, test output changes are relevant only when
continuous testing is enabled inside dev mode. Pure dev mode does not run tests,
so test outputs should not drive reload behavior; at most they can be diagnostic
metadata if they are already part of a produced event.

For `quarkusContinuousTest`, default to the default Gradle `test` suite. Support
additional JVM test suites through explicit opt-in.

When a Gradle-native Quarkus dev, remote-dev, or continuous-test session is the
requested task, the plugin should prevent ordinary Gradle `Test` tasks in the
same project from also executing as part of that same invocation. The purpose is
to avoid accidental double execution where Gradle runs `test` normally while
Quarkus continuous testing also runs tests from the same compiled outputs.

This should be a narrow safety net, not a global build mutation:

- apply only to the Gradle project that owns the requested Quarkus session;
- apply only for the invocation that explicitly requests a Quarkus dev,
  remote-dev, or continuous-test task;
- do not disable test compilation or resource processing tasks, because those
  outputs are inputs to Quarkus continuous testing;
- do not disable test tasks in unrelated projects unless those projects have
  their own requested Quarkus session task;
- log a clear lifecycle message when ordinary `Test` task execution is
  suppressed;
- provide an explicit opt-out only if a real use case appears.

The exact Gradle implementation needs care. The intent is to prevent execution
of `Test` task actions, not to break task graph construction, IDE imports, test
suite registration, or output production needed by the Quarkus session.

## Gradle Plugin Design Constraints

Hard gates for the new plugin still apply:

- no `Task.getProject()` or live mutable `Project` access during task execution;
- no cross-project mutable model reads;
- configuration-cache and isolated-projects TestKit coverage for every public
  supported path;
- task names must not collide with legacy task names;
- test-supporting stubs must stay out of production source;
- expensive Quarkus/dev-mode operations must be abstracted so cheap unit and
  ProjectBuilder tests can verify wiring.

Additional dev-mode-specific constraints:

- long-running process tasks are not cacheable;
- a task action that starts Quarkus and blocks forever is not a valid
  continuous-build design because Gradle must finish each iteration before it
  can wait for changes and schedule the next one;
- support for `--continuous` should not require users to run a second terminal
  command unless the design explicitly chooses a two-process model;
- the design must specify what happens outside `--continuous`;
- the design must specify what happens in IDE imports and IDE-run contexts;
- command-line interaction, terminal input, and graceful shutdown need explicit
  lifecycle handling;
- event transport must not leak credentials or environment snapshots into
  cacheable task outputs;
- event transport sends and acknowledgements must be timeout-bounded and must
  not depend on Quarkus reading while it is synchronously processing a previous
  reload or test batch;
- build-script, settings-script, convention-plugin, and other build-logic
  changes are restart-required conditions, not reloadable Quarkus file-change
  events;
- ordinary Gradle `Test` task actions in the same project should be suppressed
  during requested Quarkus dev/remote-dev/continuous-test sessions, while test
  compile/resource tasks remain enabled so Quarkus can consume their outputs.

## Open Questions

1. Can the build-owned session coordinator provide robust process lifecycle
   without Gradle internal deployment APIs?
2. If local socket delivery is selected for the first prototype, can a
   Gradle-owned listener with a per-session token handshake handle process
   startup/shutdown robustly across Linux, macOS, and Windows?
3. Which observed dependency-resource output changes are safe to treat as
   reloadable, and which should be escalated to dependency/classpath
   rebootstrap?
4. What does Gradle expose for same-build dependency classes/resources variants
   in real builds, including included builds and jar-producing projects?
5. What exactly can a `BuildEventsListenerRegistry` service contribute in
   continuous build beyond task finish outcomes, and how should that combine
   with an incremental `InputChanges` task?
6. What is the minimal Quarkus-side rebootstrap API for dependency/classpath
   changes: a new updated-model input on the existing dev session, a restart of
   the dev subprocess with a new serialized model, or a smaller
   `CuratedApplication` recreation seam?
7. What is the least surprising Gradle implementation for suppressing ordinary
   `Test` task actions during requested Quarkus sessions without breaking task
   graph construction, IDE imports, or test-suite wiring?
8. Preserving Dev UI continuous-test controls likely needs more investigation:
   pause/resume/run-failed/run-all commands need to keep flowing through
   `TestSupport` even when Gradle owns compilation.

## Testing Strategy

Most coverage should be below expensive end-to-end dev-mode integration tests.
Do not run open-ended `--continuous` invocations in TestKit by default; model
continuous build as repeated bounded builds:

```text
run bounded event-producing task
  -> mutate fixture files
  -> run the same task again
  -> assert task outcomes and emitted event batches
```

Bounded TestKit simulations remain the configuration-cache compatibility gate
and should run with `--configuration-cache` and isolated projects enabled. Real
open-ended `--continuous` tests are different: because of Gradle issue
`https://github.com/gradle/gradle/issues/38482`, they should either omit
`--configuration-cache` or remain disabled/deferred until Gradle fixes the
continuous-build/configuration-cache interaction.
The current disabled Tooling API smoke also confirms that keeping the dev
session only in a Gradle `BuildService` is not enough: Gradle reruns the task
for the next continuous-build iteration, but the service-owned session state
does not survive as the long-lived Quarkus dev owner.

Future tests should live under `devtools/gradle/gradle-app-plugin`:

- pure unit tests under `src/test/java/io/quarkus/gradle/application/internal/dev/`;
- ProjectBuilder wiring tests in a dedicated dev-wiring test class or the
  existing plugin tests;
- TestKit fixtures under `src/test/resources/io/quarkus/gradle/application/dev/`;
- TestKit task tests under `src/test/java/io/quarkus/gradle/application/tasks/`.

Minimum useful test coverage:

1. Event/codec unit tests:
   - successful `BuildOutputChanges` events include main/test class/resource
     outputs only;
   - `BuildOutputChanges` has no dependency/classpath field until the separate
     rebootstrap contract exists;
   - failed events include failure summary and diagnostics location and are
     non-reloadable;
   - schema contains no Gradle `Project`, task instances, environment snapshots,
     or source roots as reload authority;
   - unknown/coarse changes classify conservatively.
2. ProjectBuilder wiring tests:
   - prepare/event tasks depend on classes, resources, codegen, and selected
     test-suite compile/resource tasks;
   - dev task fails without `--continuous` unless an explicit opt-out is used;
   - task names do not collide with legacy `quarkusDev`;
   - task actions do not require `Task.getProject()`.
3. Tiny Java TestKit fixture:
   - first run emits `BUILD_SUCCEEDED`;
   - Java source change reruns compile and emits one new successful batch;
   - resource/config change reruns resource processing and classifies as
     resource/config.
4. Generated source/resource fixture:
   - Gradle generator tasks are wired through source-set inputs;
   - mutating generator inputs emits one batch after generator and compile/
     resource tasks finish.
5. Project dependency fixture:
   - dependency project outputs are built before the app event;
   - same-build dependency projects with classes/resources metadata are tracked
     separately from jar-only dependencies;
   - changing a same-build dependency project can be modeled later as granular
     dependency output changes or a dedicated reloadable-workspace dependency
     event, but should not be collapsed into opaque jar-only diagnostics;
   - changing a jar-only dependency does not get encoded into the first
     `BuildOutputChanges` event schema;
   - record the required future jar-only behavior as a separate
     dependency/classpath event with an updated application-model input rather
     than pretending to know jar-entry changes;
   - run with isolated projects enabled.
6. Multiple JVM test-suite fixture:
   - selected test-suite outputs appear in the event;
   - test-only changes do not trigger main reload classification.
7. Compile-failure fixture:
   - valid build emits `BUILD_SUCCEEDED`;
   - invalid source emits `BUILD_FAILED` or a stable diagnostics file through a
     finalizer/listener path;
   - failed batch is non-reloadable;
   - fixing the source emits a later successful batch.
8. Test task suppression fixture:
   - requesting `quarkusApplicationDev`, `quarkusApplicationRemoteDev`, or
     `quarkusContinuousTest` suppresses ordinary `Test` task actions in the same
     project for that invocation;
   - test compilation/resource tasks still run when required;
   - unrelated projects' `Test` tasks are not suppressed unless they own their
     own requested Quarkus session task.
9. Continuous-test ordering fixture:
   - a batch with production and test output changes applies production changes
     first and only then triggers `TestSupport`;
   - failed production apply/reload prevents test reruns and reports the
     production failure;
   - a later successful production batch re-enables eligible test reruns.
10. Transport/backpressure fixture:
   - Gradle-side send or accept waits are timeout-bounded;
   - Quarkus can keep reading or quickly rejecting incoming batches while a
     previous batch is processing;
   - stale or superseded batches are rejected or coalesced through a bounded
     policy;
   - the test proves delivery acknowledgement does not wait for full reload or
     test execution.

Keep these fixtures tiny and synthetic. They should prove Gradle integration
behavior, not Quarkus runtime behavior. Reserve a small number of later
Quarkus-side integration tests for proving that Quarkus reloads from emitted
output batches.

## Initial Recommendation

Do not implement this as "Quarkus watches Gradle source roots better."

Implement it as "Gradle builds; Quarkus reloads." The new plugin should make
Gradle's continuous build the change detector and compiler, then feed
build-result events to Quarkus dev mode through a small, explicit,
build-tool-neutral output-batch contract that reuses existing remote-dev and
runtime-update mechanics wherever possible.
