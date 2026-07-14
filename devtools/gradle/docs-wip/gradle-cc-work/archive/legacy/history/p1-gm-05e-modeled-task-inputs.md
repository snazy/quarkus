# P1-GM-05E Modeled Task Inputs Plan

Date: 2026-07-03

Status: historical design note. The `P1-GM-05E5` producer-task slice was
implemented locally, then superseded by the 2026-07-06 M1 containment commit
`1e8ed6feb14` (`Remove Gradle declared dependency producer tasks`).

Owner / audience: Gradle configuration-cache workstream

Supersedes: None

Superseded by: [declared dependencies Gradle-native design](../../../declared-dependencies-gradle-native-design.md)

Revisit trigger: a future design needs to reintroduce a declared-dependencies
producer task with a clear Gradle-native role, or `P1-GM-03` changes the
project-dependency metadata boundary first.

## Supersession Note

The producer-task approach was valuable because it made external Maven
declared-dependency work explicit, batchable, and independently testable.
Follow-up dry-run/configuration-cache investigation showed that it also added
another resolution-backed task boundary and was not the intended final
Gradle-native shape.

Implemented M1 local work therefore:

- removes `QuarkusDeclaredDependenciesTask`;
- removes `enableDeclaredDependencyCollector`;
- keeps selected graph/artifact data on Gradle-supported lazy
  resolution-result providers and file collections;
- runs external Maven POM lookup and effective-model declared-dependency
  enrichment from `QuarkusApplicationModelTask` execution;
- keeps broad Maven model system properties out of stable cache-key inputs;
- leaves cross-project project-dependency metadata reads under `P1-GM-03`.

M2 is not a continuation of this producer-task plan. It is tracked as broader
build-tool-agnostic dependency model work; a Gradle-native `quarkusDev` run is
related but belongs to a separate dev-mode/continuous-build effort.

The rest of this file records the earlier producer-task plan and evidence. It
should not be treated as the active implementation plan.

## Context

`P1-GM-05A-D/F` split declared-dependency collection into clearer pieces.
`P1-GM-05E1/E2` introduced modeled external module inputs, `P1-GM-05E3`
exposed those inputs on application-model tasks, and committed
`P1-GM-05E4/E5` work moves external Maven declared-dependency collection into
`QuarkusDeclaredDependenciesTask`.

The current local task path no longer runs external Maven POM lookup or Maven
effective-model building from a provider used as an application-model task
input. `ApplicationModelTaskConfigurator` registers launch-mode-specific
`QuarkusDeclaredDependenciesTask` instances, and
`QuarkusApplicationModelTask` consumes the serialized declared-dependency
output file.

Follow-up dry-run regression coverage added in `daab163bd3a` shows that this
producer-task direction still needs a task-wiring redesign. The answer should
not be `StartParameter.isDryRun()` branching; the dry-run design options are
now folded into the
[declared dependencies Gradle-native design](../../../declared-dependencies-gradle-native-design.md).

The collector is opt-in through `enableDeclaredDependencyCollector`.
This limits default-user impact, but it does not make the task path compatible
with configuration-cache and project-isolation constraints when the option is
enabled.

Maven effective-model building has a broad property surface.
Maven does not define a small fixed set of "useful" system properties for model
building or dependency resolution.
A profile activation, interpolation expression, parent POM, imported BOM, or
repository behavior can depend on any JVM system property.
Therefore, `P1-GM-05E` should not pretend that broad Maven model system
properties can be narrowed safely as a general rule.
At the same time, exposing all JVM system properties directly as stable Gradle
task/cache inputs would make cache artifacts highly non-portable and could
record sensitive values.
The compatibility goal is to avoid hidden nondeterminism without making the
raw Maven model system-property map part of the stable cache key.

Project-dependency declared-dependency collection has a different compatibility
problem. It still reads mutable Gradle project model state through
`GradleProjectDependencyDeclaredDependencyCollector` and is intentionally kept
separate from the external Maven producer task. That remains under `P1-GM-03`.

## Goals

- Move external Maven artifact declared-dependency collection on the Gradle
  task path behind serializable, explicit inputs.
- Keep Maven effective-model behavior correct for parents, imported BOMs,
  profiles, properties, optional dependencies, scopes, exclusions, and missing
  POM fallback.
- Keep Maven system-property-sensitive effective-model behavior correct while
  avoiding broad raw system properties as stable Gradle task/cache inputs.
- Avoid Worker API migration until the input boundary no longer depends on live
  Gradle model objects.
- Keep project-dependency declared-dependency replacement scoped to `P1-GM-03`.

## Non-Goals

- Do not make `QuarkusApplicationModelTask` build-cacheable as part of this
  work.
- Do not replace cross-project project-dependency inspection in this slice.
- Do not change the declared-dependency collector opt-in semantics.
- Do not claim complete isolated-projects compatibility for the whole
  application-model task path.
- Do not add Worker API execution before inputs are detached from Gradle model
  objects.

## Proposed Input Boundary

Introduce a small task-path input model for external Maven artifacts.

The input model should contain:

- artifact identity:
  `groupId`, `artifactId`, `classifier`, `type`, and `version`;
- precomputed application-model key:
  `ArtifactKey` derived during Gradle input collection;
- POM lookup identity:
  `groupId`, `artifactId`, and `version`, because Maven POM lookup is based on
  module GAV even when the resolved artifact key has a classifier or a
  non-default type;
- artifact file identity if still needed for key derivation:
  either the resolved file path or a precomputed key that avoids passing the
  artifact file itself;
- primary POM lookup result:
  path to the POM file or an explicit missing-POM marker;
- declared-dependency result snapshot:
  the deterministic output of Maven effective-model collection, represented
  with `DependencyDataCollector.toSnapshot(...)`.

The task path can still use Gradle resolution during configuration/task input
snapshotting to produce this input model.
The collector execution itself should not need `Project`, `Configuration`,
`ResolvedArtifactResult`, or Gradle artifact result objects for external Maven
modules.

Maven model building may still receive the current JVM system properties while
computing the declared-dependency results.
Those raw properties should not be exposed as the stable modeled task input.
Instead, the declared-dependency snapshot should change when a property actually
changes the effective model and should remain stable when unrelated raw
properties change without affecting the result.

## Maven POM Resolution Boundary

The current `GradlePomResolver` has two roles:

- batch-resolve known external module POMs through Gradle;
- lazily resolve parent POMs and imported BOMs discovered by Maven model
  building.

For `P1-GM-05E`, prefer splitting the task path into two levels:

- Gradle-backed input collection resolves and records known module POMs.
- Maven model building consumes a `PomResolver` that can resolve from the
  modeled known-POM map and, if needed, delegates parent/import fallback to a
  clearly named Gradle-backed path outside any Worker API boundary.

This is an intermediate state.
It is better than the current shape because known module inputs become explicit
and serializable.
It is not enough for Worker API if fallback POM resolution still calls Gradle
APIs during worker execution.

A stricter later state would batch or pre-discover parent/import POM inputs
before execution.
That may require iterative Maven model probing and should be considered only
after the smaller boundary is in place.

Decision: parent/import POM fallback may remain Gradle-backed and iterative for
the first reviewable slice.
That slice must not claim Worker API compatibility while fallback POM
resolution can still call Gradle APIs.
This keeps Maven local out of the design: Maven model building asks the
`PomResolver` for model sources, and the Gradle-backed resolver obtains POMs
through Gradle artifact resolution and Gradle's dependency cache rather than by
letting Maven populate a local repository.

## Maven System Properties Decision

Decision: treat all JVM system properties as possible Maven model inputs during
declared-dependency computation, but do not expose the full raw system-property
map as a stable Gradle task/cache input.

The stable boundary should be the deterministic declared-dependency snapshot
produced by the collector.

Rationale:

- Maven profile activation and model interpolation can depend on arbitrary
  system properties.
- Imported BOMs and parent POMs can add more property-sensitive model behavior
  after the first POM is read.
- Narrowing properties during Maven model building could create false results
  for valid Maven builds.
- Tracking all raw system properties as Gradle task/cache inputs would make
  cache artifacts machine/session-specific and could expose sensitive values.
- The collector is opt-in, so accepting a broad input surface is preferable to
  hidden non-determinism, but the cache key should be based on the computed
  declared-dependency result.

Consequence:

- Changing a Maven-relevant system property should change the computed
  declared-dependency snapshot when it changes the effective model.
- Changing an unrelated system property should not invalidate the stable
  declared-dependency input if the effective model result does not change.
- The task path should document this as the cost of Maven model correctness.
- Sensitive values are possible in system properties.
  This argues against using the raw full system-property map as a stable
  modeled input and against making the collector broadly enabled by default
  without another security review.

## Artifact Key Decision

Decision: precompute the `ArtifactKey` during Gradle input collection and keep
POM lookup identity separate.

The collector should not need `ResolvedArtifactResult` or
`DependencyUtils.getKey(...)` when processing modeled external Maven inputs.
That keeps Gradle artifact result objects out of the execution boundary and
avoids depending on artifact-file-sensitive key derivation after the input
model has been built.
The modeled input should still retain the module GAV used for POM lookup,
because Maven model resolution is tied to the module POM, not to the full
artifact key classifier/type tuple.

## Missing POM Decision

Decision: preserve the current enrichment behavior for missing or unresolvable
POMs.

The declared-dependency collector should model missing POMs as unresolved
declared-dependency results instead of failing application-model generation.
This matches the current collector behavior, where declared dependency data is
best-effort enrichment and failures become warnings plus
`DeclaredDepsResult.unresolved()`.

## Candidate Implementation Phases

### `P1-GM-05E1`: Add focused input model and unit coverage

Status: completed locally by `baa96d0ffda` (`Model external declared
dependency collection inputs`).

Create serializable value types for external declared-dependency inputs.

Suggested names:

- `DeclaredDependencyCollectionInput`
- `ExternalModuleDeclaredDependencyInput`
- `ExternalDeclaredDependencyCollectionResult`

Add unit tests for deterministic ordering and snapshot behavior.

This phase should not change Gradle task behavior yet.

### `P1-GM-05E2`: Split external module collection from Gradle collection

Status: completed locally by `baa96d0ffda` (`Model external declared
dependency collection inputs`).

Add a collector method that accepts the modeled external module inputs plus a
`PomResolver`/effective-model resolver and returns
`Map<ArtifactKey, DeclaredDepsResult>`.

Keep the current `collectDeclaredDependencies(Project, Configuration)` as a
Gradle-facing adapter while behavior is moved behind the modeled method.

Tests should prove that existing Maven effective-model cases still work through
the modeled method.

### `P1-GM-05E3`: Wire task path to modeled inputs

Status: completed locally by `d567695bf6b` (`Expose declared dependency
module inputs on app model tasks`).

Change `ApplicationModelTaskConfigurator` and `QuarkusApplicationModelTask`
wiring so the task path snapshots external module inputs explicitly.

The task path may still call Gradle APIs to create the input provider, but
`QuarkusApplicationModelTask.execute()` should not resolve external Maven
declared dependencies by reaching back through `Project` or `Configuration`.

Project-dependency declared dependency data can remain provided by the existing
project collector until `P1-GM-03`.
If necessary, keep it as a separate internal provider and document that it is
the remaining project-isolation blocker.

### `P1-GM-05E4`: Iterative batch POM resolution

Status: fixed locally by `4871f87cecb` (`Generate declared dependencies with
cacheable Gradle tasks`).

Resolve Maven parent POMs and imported BOM POMs discovered during effective
model building in batches instead of one Gradle artifact-resolution query per
missing POM.

The current implementation already batch-resolves the initial module POMs.
It does not batch the transitive POM closure discovered by Maven model
building.  Parent POMs and imported BOMs can therefore still fall back to
single-GAV Gradle queries through `GradlePomResolver.resolvePom(...)`.

The next implementation slice should keep the current Gradle-build execution
boundary, but make that fallback explicit and iterative:

1. Add a batch-oriented `GAV` prefetch path to the Gradle-backed POM resolver.
2. Wrap Maven effective-model resolution with a recording resolver that notes
   parent/import POM `GAV`s requested by Maven but not yet present in the
   resolver cache.
3. Attempt effective-model resolution for the current module batch.
4. If Maven requests unknown parent/import POMs, batch-resolve those `GAV`s
   through Gradle, add the resolved or missing results to the resolver cache,
   and retry affected models.
5. Repeat until no new POM requests are discovered, then preserve the current
   best-effort behavior: unresolved models become
   `DeclaredDepsResult.unresolved()` plus a warning, not application-model
   generation failures.

The retry loop must be bounded by the set of newly discovered `GAV`s.  If an
iteration discovers no new `GAV`, it must stop.  Missing POMs should be cached
as missing so the same unresolved parent/import does not trigger repeated
Gradle queries.

This phase should not claim Worker API compatibility.  It still lets Maven
model building ask a Gradle-backed resolver for newly discovered POMs during
execution.  The value is making the POM resolution boundary clearer and
reducing resolution chatter while preserving existing Maven behavior and
avoiding Maven-local repository writes.

Suggested tests:

- a unit test where several root modules share the same parent POM and imported
  BOM, proving the parent/import requests are discovered and batch-prefetched
  once;
- a unit test with nested parent/import discovery across at least two
  iterations;
- a missing parent/import POM test proving the result remains unresolved and
  repeated retries stop;
- existing `DependencyDataCollectorTest` effective-model coverage remains green.

Implementation notes:

- Folded the POM prefetch/cache contract into `PomResolver`. Resolvers can
  prefetch several `GAV` POM lookup results and expose whether a POM is already
  resolved or known missing.
- `GradlePomResolver` now implements `PomResolver` directly and uses the same
  batch query path for initial module POMs and later parent/import POM `GAV`s.
- `DependencyDataCollector` now uses iterative batch POM resolution whenever
  the configured resolver can prefetch POMs. The tooling-model and Gradle-build
  paths therefore share the same collector path. Resolvers without prefetch
  support use the default `PomResolver` methods and fall back to direct
  `resolvePom(...)` calls.
- `DeclaredDependencyExecutionStrategy` was removed. With batched POM lookup in
  place, the previous tooling/build strategy split no longer carried enough
  value to justify keeping another execution abstraction.
- A recording resolver detects parent/import POMs requested by Maven effective
  model building before allowing fallback to one-off Gradle queries.
- Missing POMs are cached as known-missing results so retries stop and the
  current best-effort unresolved behavior is preserved.

Verification:

- `cd devtools/gradle && ./gradlew :gradle-model:test --tests io.quarkus.gradle.tooling.GradlePomResolverTest --tests io.quarkus.gradle.tooling.dependency.DependencyDataCollectorTest --tests io.quarkus.gradle.tooling.dependency.MavenEffectiveModelResolverTest --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskShouldNotReportConfigurationCacheProblems --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskWithDeclaredDependencyCollectorShouldNotReportConfigurationCacheProblems --rerun-tasks`
- `./mvnw -f devtools/gradle/pom.xml process-sources -DskipTests`

### `P1-GM-05E5`: Move declared dependency collection to a producer task

Status: fixed locally by `4871f87cecb` (`Generate declared dependencies with
cacheable Gradle tasks`).

Move external Maven declared-dependency collection out of
`QuarkusApplicationModelTask` input providers and into an explicit producer
task.

The goal is not to avoid normal Gradle classpath/file-collection resolution.
Gradle may still resolve the deployment classpath as part of native task input
snapshotting.  The goal is to avoid extra work during configuration or input
snapshotting:

- no provider used as a task input should call `ArtifactCollection#getArtifacts()`;
- no provider used as a task input should run POM lookup;
- no provider used as a task input should run Maven effective-model building.

The producer task should instead keep the Gradle resolution view internal and
expose only Gradle-native file inputs:

- `@Internal Property<ArtifactCollection>` for the deployment artifacts view;
- `@Classpath` POM files selected from an artifact view over the deployment
  configuration, so Gradle tracks the POM artifacts whose contents drive most
  declared-dependency collection decisions without immediately snapshotting the
  larger deployment JAR classpath;
- `@Input Property<Boolean>` for whether the collector is enabled;
- `@Internal Property<Boolean>` for an explicit user refresh request that
  disables local up-to-date reuse and build-cache reuse without becoming a
  cache-key input;
- `@OutputFile RegularFileProperty` for serialized declared dependencies.

During the producer task action:

1. If the collector is disabled, write an empty declared-dependency result.
2. Call `ArtifactCollection#getArtifacts()` inside the action.
3. Convert `ResolvedArtifactResult` values to
   `ExternalModuleDeclaredDependencyInput` values.
4. Run external Maven declared-dependency collection, including iterative batch
   parent/import POM resolution.
5. Write a deterministic, versioned declared-dependency output file.

`QuarkusApplicationModelTask` should then consume only that output file:

- replace the provider-backed `declaredDependencies` map with an `@InputFile`;
- read the serialized declared-dependency result during the task action;
- keep using `DependencyDataCollector.setDirectDeps(...)` to enrich the
  application model.

`QuarkusGoOffline` also needs to depend on the producer task when the declared
dependency collector is enabled.  Its purpose is to force all artifacts needed
by the Gradle plugin workflow into Gradle's dependency cache ahead of offline
use.  If declared-dependency collection is moved into a separate producer task,
offline preparation must include that task so POM lookup and parent/import POM
closure resolution have already been exercised before the user goes offline.

This makes the expensive POM and Maven effective-model work visible as a real
task action with a real output.  It also keeps
`QuarkusApplicationModelTask` input snapshotting limited to Gradle-native file
inputs instead of hidden provider work.

First-slice output format can be simple Java serialization if it is versioned
and deterministic enough for local up-to-date checks.  A more portable or
cache-oriented format can be revisited later if the producer task needs to
model the full recursive effective-model POM closure.

Naming and implementation choices:

- task class: `QuarkusDeclaredDependenciesTask`;
- task names should use the Quarkus prefix for build-script usability, for
  example `quarkusDeclaredDependencies`, `quarkusDeclaredTestDependencies`,
  and `quarkusDeclaredDevDependencies` where those launch modes are wired;
- task location: likely `:gradle-model`, because both plugins share generated
  application model tasks;
- one producer task per generated application-model task, wired to the same
  deployment configuration;
- `QuarkusGoOffline` dependency wiring for the producer task;
- plugin wiring should avoid repeating per-launch-mode task registration code.
  Prefer a small shared helper around app-model task naming/configuration,
  declared-dependencies producer task wiring, and offline dependencies instead
  of open-coded repeated task registration in each plugin class;
- whether to include project-dependency declared dependencies in the first
  producer task or leave them to `P1-GM-03`.

Recommended first slice:

- external Maven module declared dependencies only;
- project-dependency declared dependencies stay on the existing path until
  `P1-GM-03`;
- producer task is cacheable using Gradle-selected first-level POM artifacts as
  the stable file input boundary;
- cache correctness is pragmatic rather than perfect: parent POMs and imported
  BOM POMs discovered during Maven effective-model resolution are not modeled as
  task inputs in this slice;
- Gradle property `quarkus.declared-dependencies.refresh=true` disables both
  local up-to-date reuse and build-cache reuse for the producer task when a
  build needs to force regeneration;
- no Worker API;
- no claim that the full recursive Maven effective-model POM closure is modeled
  yet.

Suggested tests:

- existing `generatedApplicationModelTaskWithDeclaredDependencyCollector...`
  configuration-cache test still passes;
- a focused test proves enabling the collector wires a producer task and makes
  `QuarkusApplicationModelTask` consume its output file;
- a `QuarkusGoOffline` test proves offline preparation depends on the
  declared-dependencies producer task when the collector is enabled;
- unit coverage for reading/writing empty and non-empty declared-dependency
  result files.

Implementation notes:

- Added `QuarkusDeclaredDependenciesTask` in `:gradle-model`.
- Added launch-mode task names:
  `quarkusDeclaredDependencies`, `quarkusDeclaredDevDependencies`, and
  `quarkusDeclaredTestDependencies`.
- The task keeps `ArtifactCollection` internal and exposes
  first-level POM artifacts from a Gradle artifact view as the Gradle-native
  cacheable file input.
- The task is cacheable, but documents the pragmatic input boundary in Javadoc.
  `quarkus.declared-dependencies.refresh=true` is an internal policy override
  that disables local up-to-date reuse and build-cache reuse without becoming
  part of the cache key.
- The task action calls `ArtifactCollection#getArtifacts()`, computes external
  module declared dependencies, and writes a serialized declared-dependencies
  output file.
- `QuarkusApplicationModelTask` now reads that output file for external Maven
  declared dependencies and merges the remaining project-dependency declared
  dependency map.
- `ApplicationModelTaskConfigurator` owns producer task registration and
  generated app-model task wiring for the shared launch-mode path.
- `QuarkusGoOffline` reaches the producer tasks through the generated
  application-model tasks and has explicit test coverage with the collector
  enabled.
- `GradlePomResolver` now stores a `DependencyHandler` instead of a `Project`.
  `QuarkusDeclaredDependenciesTask` obtains `DependencyHandler` and
  `ProviderFactory` through task service injection, avoiding execution-time
  `Task.project` access and avoiding a non-serializable captured `Project` in
  the configuration cache.
- `ApplicationModelTaskConfigurator` now passes the launch-mode-derived test
  scope decision directly into project-dependency declared-dependency
  collection. The Gradle task path no longer guesses test scope inclusion from
  a configuration name; the tooling adapter keeps the old name-based inference
  for compatibility.
- `DependencyDataCollector` now uses the same `PomResolver`-based collector
  path for tooling and Gradle build use. There is no `TOOLING_USE` versus
  `GRADLE_BUILD` split.
- `QuarkusDeclaredDependenciesTask` Javadoc records the pragmatic cache
  boundary: Gradle-selected first-level POM artifacts are the stable file input
  boundary, while parent POMs/imported BOMs discovered during Maven model
  building are still resolved through Gradle during the task action and are not
  modeled as first-class cache-key inputs in this slice.

Verification:

- `cd devtools/gradle && ./gradlew :gradle-model:test --tests io.quarkus.gradle.tooling.dependency.DependencyDataCollectorTest --tests io.quarkus.gradle.tooling.GradlePomResolverTest --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskShouldNotReportConfigurationCacheProblems --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskWithDeclaredDependencyCollectorShouldNotReportConfigurationCacheProblems --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-application-plugin:test --tests io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest.quarkusGoOfflineRunsDeclaredDependencyTasksWhenCollectorIsEnabled --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-application-plugin:test --tests io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest.declaredDependencyRefreshPropertyDisablesUpToDateReuse --rerun-tasks`
- `./mvnw -f devtools/gradle/pom.xml process-sources -DskipTests`

### `P1-GM-05E6`: Decide Worker API execution boundary

Status: later follow-up.

After `P1-GM-05E5`, reassess whether Worker API is useful.

Use Worker API only if:

- all worker parameters are serializable;
- no worker code calls Gradle APIs;
- parent/import POM fallback no longer needs Gradle resolution from inside the
  worker;
- tests cover configuration-cache reuse with the collector enabled.

If those conditions are not met, keep caller-thread execution and document why.

## Test Plan

Unit tests:

- deterministic external module input ordering;
- deterministic declared-dependency result snapshot ordering;
- missing POM marker behavior;
- effective-model behavior through modeled external module inputs:
  parent POM, imported BOM, property interpolation, optional dependency,
  scopes, and exclusions.

Gradle/TestKit tests:

- `quarkusGenerateAppModel` with
  `-PenableDeclaredDependencyCollector=true --configuration-cache`;
- second run reuses configuration cache or at least reports no collector-caused
  configuration-cache serialization problem;
- changing a Maven model system property that affects a profile changes the
  declared dependencies and therefore the modeled snapshot;
- changing an unrelated system property does not change the modeled snapshot
  when the effective model is unchanged.

Regression tests to preserve:

- current `DependencyDataCollectorTest` behavior around parent POMs, imported
  BOMs, project dependencies, disabled collector, and missing POM fallback;
- existing application-model task tests that assert declared-dependency
  snapshots.

## Deferred Questions

- Which small set of well-known Maven-related system properties, if any, is
  safe and useful to expose as direct stable inputs later?
  Leave this out unless a concrete property is required by a real build-cache
  or correctness bug. Broad raw Maven model system properties should not become
  stable cache-key inputs.
- What is the smallest TestKit fixture that enables the collector and exercises
  a system-property-activated Maven profile without relying on `mavenLocal()`
  state? This is optional follow-up coverage, not a blocker for the current
  producer-task slice.

## Suggested First PR Boundary

The first reviewable PR is `P1-GM-05E1` plus `P1-GM-05E2`, implemented locally
by `baa96d0ffda` (`Model external declared dependency collection inputs`).

That PR introduces the modeled external Maven input contract and routes
external Maven effective-model collection through it, while keeping Gradle task
wiring mostly unchanged.

It does not add Worker API and should not claim full configuration-cache or
isolated-projects compatibility.
The value is making the next task-wiring PR smaller and giving reviewers a
clear input model to evaluate.

Verification:

- `cd devtools/gradle && ./gradlew :gradle-model:test --tests io.quarkus.gradle.tooling.dependency.DependencyDataCollectorTest --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-model:test --tests io.quarkus.gradle.tooling.GradlePomResolverTest --tests io.quarkus.gradle.tooling.dependency.DependencyDataCollectorTest --tests io.quarkus.gradle.tooling.dependency.MavenEffectiveModelResolverTest --rerun-tasks`
- `./mvnw -f devtools/gradle/pom.xml process-sources -DskipTests`

## Second PR Boundary

The second reviewable PR is `P1-GM-05E3`, implemented locally by
`d567695bf6b` (`Expose declared dependency module inputs on app model tasks`).

That PR exposes external Maven module declared-dependency inputs as a nested
`QuarkusApplicationModelTask` property and wires
`ApplicationModelTaskConfigurator` to populate it only when
`enableDeclaredDependencyCollector=true`.

The stable semantic input remains `declaredDependenciesSnapshot`.
Project-dependency declared dependencies remain on the existing Gradle adapter
path and are still tracked under `P1-GM-03`.
The PR still does not add Worker API and does not claim full
configuration-cache or isolated-projects compatibility.

Verification:

- `cd devtools/gradle && ./gradlew :gradle-model:test --tests io.quarkus.gradle.tooling.dependency.DependencyDataCollectorTest --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskShouldNotReportConfigurationCacheProblems --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskWithDeclaredDependencyCollectorShouldNotReportConfigurationCacheProblems --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-model:test --tests io.quarkus.gradle.tooling.GradlePomResolverTest --tests io.quarkus.gradle.tooling.dependency.DependencyDataCollectorTest --tests io.quarkus.gradle.tooling.dependency.MavenEffectiveModelResolverTest --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-application-plugin:compileTestJava :gradle-extension-plugin:compileTestJava`
- `./mvnw -f devtools/gradle/pom.xml process-sources -DskipTests`

## Current PR Boundary

The reviewable `P1-GM-05E4/E5` slice is committed locally as `4871f87cecb`
(`Generate declared dependencies with cacheable Gradle tasks`).

Suggested PR story:

- batch parent/import POM lookup discovered during Maven effective-model
  building instead of resolving each discovered POM individually;
- introduce cacheable `QuarkusDeclaredDependenciesTask` producer tasks for
  external Maven declared-dependency collection;
- make generated application-model tasks consume the serialized
  declared-dependency output file;
- wire `QuarkusGoOffline` through generated model tasks so offline preparation
  also exercises declared-dependency producer tasks;
- remove `DeclaredDependencyExecutionStrategy` because the tooling and Gradle
  build paths now share the same `PomResolver`-based collector path;
- keep project-dependency declared-dependency replacement under `P1-GM-03`;
- document the pragmatic cache boundary: first-level POM artifacts are task
  inputs, discovered parent/import POMs are resolved during the task action,
  and `quarkus.declared-dependencies.refresh=true` is the explicit refresh
  escape hatch.

Verification:

- `cd devtools/gradle && ./gradlew :gradle-model:compileJava :gradle-application-plugin:compileJava :gradle-extension-plugin:compileJava`
- `./mvnw -f devtools/gradle/pom.xml process-sources -DskipTests`
- `cd devtools/gradle && ./gradlew :gradle-model:test --tests io.quarkus.gradle.tooling.dependency.DependencyDataCollectorTest --tests io.quarkus.gradle.tooling.GradlePomResolverTest --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskWithDeclaredDependencyCollectorShouldNotReportConfigurationCacheProblems --rerun-tasks`
- `cd devtools/gradle && ./gradlew :gradle-application-plugin:test --tests io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest.configurationCacheIsReusedTest --tests io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest.configurationCacheIsReusedWhenProjectIsolationIsUsedTest --tests io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest.declaredDependencyRefreshPropertyDisablesUpToDateReuse --rerun-tasks`
