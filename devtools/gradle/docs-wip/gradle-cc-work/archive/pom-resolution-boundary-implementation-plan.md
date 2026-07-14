# POM Resolution Boundary Implementation Plan

Date: 2026-07-11

Status: implemented for the new `io.quarkus.application` plugin path

Owner / audience: Gradle configuration-cache and project-isolation workstream

Related:

- [POM Resolution Boundary Design](../pom-resolution-boundary-design.md)
- [Declared Dependencies Gradle-Native Design](../declared-dependencies-gradle-native-design.md)
- [Tooling Model Consumers Investigation](../tooling-model-consumers-investigation.md)

## Objective

Add a Gradle-native POM/effective-model enrichment path for the new
`io.quarkus.application` plugin's package/build application model while leaving
the legacy `io.quarkus` application-model path functionally unchanged.

Implementation status: complete for the new-plugin task-produced model path as
of 2026-07-11. The legacy application-model path, extension deployment test model
path, and tooling-model builder remain intentionally unchanged and are follow-up
work.

The new implementation must:

- keep dev, run, continuous-test, and codegen model generation out of external
  Maven effective-POM enrichment by default;
- enrich the new plugin's package/build model with full Maven effective-model
  declared dependency metadata, including parent POMs and imported BOM POMs;
- remove `DependencyHandler.createArtifactResolutionQuery()` and live
  `DependencyHandler` usage from the new plugin's `GenerateModelTask` action;
- keep the existing legacy `QuarkusApplicationModelTask`,
  `ApplicationModelTaskConfigurator`, and `GradleApplicationModelBuilder` paths
  intact;
- preserve the existing `GradlePomResolver(DependencyHandler, ...)` path only
  for legacy/tooling fallback code.

## Non-Goals

- Do not refactor the legacy `io.quarkus` application-model task path.
- Do not move `gradle-extension-deployment-plugin` away from the shared legacy
  model task in this phase.
- Do not make application-model or POM-closure tasks build-cacheable.
- Do not fully modernize the Gradle Tooling API model builder.
- Do not change Maven effective-model declared dependency semantics.

## Hard Gates

Every implementation phase must preserve these gates:

- no `Task.getProject()` or other Gradle project access from task actions in the
  new implementation;
- no live `DependencyHandler`, `Configuration`, `ArtifactView`, `Project`,
  `ResolvedArtifactResult`, or `ArtifactCollection` stored as task state;
- no `DependencyHandler.createArtifactResolutionQuery()` from
  `GenerateModelTask.execute()`. The dedicated POM-closure producer may use
  Gradle POM resolution internally because parent/imported-BOM `GAV`s are
  discovered dynamically during Maven effective-model building;
- no POM resolution or POM snapshotting for new-plugin dev/codegen model tasks;
- full parent/imported-BOM POM closure for new-plugin package/build model
  enrichment;
- deterministic output serialization using
  `io.quarkus.bootstrap.util.PropertyUtils.store(...)`, not
  `java.util.Properties.store(...)`;
- tests that claim configuration-cache/project-isolation compatibility must run
  with `--configuration-cache` and `-Dorg.gradle.unsafe.isolated-projects=true`.

## Current Code To Leave Alone

Do not change these except for trivial imports/comments required by shared value
types:

- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tasks/QuarkusApplicationModelTask.java`
- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/tasks/ApplicationModelTaskConfigurator.java`
- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/GradleApplicationModelBuilder.java`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
- `devtools/gradle/gradle-extension-deployment-plugin/src/main/java/io/quarkus/extension/deployment/gradle/QuarkusExtensionDeploymentPlugin.java`

If an implementation step appears to require changing one of those files, stop
and revisit the plan. The intended path is new-plugin-owned wiring.

## Phase 0: Baseline And Guard Tests

Purpose: lock in the intended scope before changing behavior.

Status: implemented.

1. Add or update a TestKit test for the new plugin that runs a codegen/dev-model
   task with `--configuration-cache` and isolated projects and asserts:
   - the task succeeds;
   - the current task path is understood before the new POM-closure producer is
     added.
2. Add a narrow test or assertion that the legacy plugin still registers the
   existing model tasks and does not see the new enrichment-mode properties.
   This should be a behavioral guard only; do not alter legacy code.

Implementation notes:

- Put new tests near existing `gradle-app-plugin` registration/TestKit tests.
- Prefer project-builder tests for task property conventions and TestKit only
  where task execution or configuration cache is required.

## Phase 1: New-Plugin Value Types And Deterministic Codec

Purpose: introduce serializable, Gradle-friendly data contracts without changing
task behavior.

Status: implemented.

1. Add `DeclaredDependencyEnrichmentMode` in the new plugin internal modelgen
   package, for example:
   `io.quarkus.gradle.application.internal.modelgen.DeclaredDependencyEnrichmentMode`.
   Values:
   - `NONE`;
   - `SELECTED_MODULE_POMS`.
2. Add a serializable value type for a resolved POM entry. Keep it internal to
   the new plugin unless reuse is required:
   - `GAV`;
   - POM file path;
   - resolved/missing marker.
3. Add a codec for a POM-closure result file under the new plugin internal
   modelgen package.
   Requirements:
   - write with `PropertyUtils.store(...)`;
   - read with ordinary `Properties.load(...)`;
   - stable ordering by `GAV.toString()`;
   - support missing entries so repeated unresolved parent/import lookups do not
     loop forever;
   - reject malformed entries with a clear `IllegalArgumentException`.
4. Add pure unit tests for the codec:
   - round-trip multiple entries;
   - stable output ordering;
   - missing entry round-trip;
   - malformed file failure;
   - no generated timestamp/comment noise.

Suggested file shape:

```properties
entry.0.gav=group:artifact:version
entry.0.file=/absolute/path/to/artifact-version.pom
entry.0.resolved=true
entry.1.gav=group:missing:version
entry.1.resolved=false
```

Do not use this exact shape as API; it is an internal deterministic task output.

## Phase 2: Selected Module POM Inputs

Purpose: collect selected module POMs through Gradle artifact views in the new
plugin without using `DependencyHandler.createArtifactResolutionQuery()`.

Status: implemented for the new-plugin package/build model path.

1. Add a new-plugin helper that accepts one or more runtime/deployment
   configurations and returns:
   - selected external module identity inputs derived from resolved artifacts;
   - POM files resolved through a lenient artifact view with
     `artifactType=pom`;
   - no live Gradle objects stored on task instances.
2. The helper should use Gradle-supported provider/file-collection boundaries:
   - file input collection for resolved POM files;
   - nested/list input for selected module `GAV` and artifact key metadata.
3. Filter to `ModuleComponentIdentifier`.
4. Exclude project components from external POM enrichment. Project dependency
   declared metadata remains a separate Gradle-native dependency-model problem.
5. Add project-builder tests for helper wiring where possible.
6. Add TestKit coverage with a tiny application and a simple external dependency
   proving the selected module POM file becomes an input for the package model
   path only.

Implementation notes:

- The new plugin already resolves app/deployment classpaths through
  `ClasspathBuilder` and `ResolvedClasspath`. Reuse the same configuration
  selection points in `TaskRegistration.registerApplicationModelTask(...)`.
- Do not pass `Configuration` or `ArtifactView` into task action state.

## Phase 3: POM Closure Producer Task

Purpose: produce the full Maven effective-model POM closure required by
packaging enrichment, including parent POMs and imported BOM POMs.

Status: implemented as `GeneratePomClosureTask`, registered as
`quarkusApplicationModelPomClosure` for the package/build model.

Add a new task type in the new plugin internal modelgen package, for example
`GeneratePomClosureTask`.

Inputs:

- selected external module metadata;
- selected module POM files;
- Maven local repository roots from modeled provider values;
- relevant system properties if Maven model building needs them. Prefer the
  same constrained provider pattern already used by new application tasks;
- no `DependencyHandler` task property;
- no `Project`, `Configuration`, or `ArtifactView` task property.

Output:

- deterministic POM-closure result file, for example under
  `build/quarkus/application-model/pom-closure/<model-name>.properties`.

Algorithm:

1. Read the initial selected module POM map from task inputs.
2. Construct `GradlePomResolver(Map<GAV, File>, repositoryRoots)` from known
   selected POMs.
3. Run the existing `DependencyDataCollector` effective-model discovery loop
   against selected module inputs, but with a resolver that records missing
   parent/import `GAV`s.
4. When missing `GAV`s are discovered, resolve them inside the POM-closure
   producer task. It is acceptable for this producer task to use an injected
   Gradle POM resolver or a small resolver wrapper around
   `DependencyHandler.createArtifactResolutionQuery()`, because this dynamic
   closure is not known until task execution. Do not expose that resolver to
   `GenerateModelTask`.
5. Repeat until:
   - all selected modules resolve;
   - only known-missing POMs remain; or
   - no new `GAV`s are discovered.
6. Write the full closure, including missing entries, to the output file.

Important implementation detail:

The existing `DependencyDataCollector` already has the Maven-side recording loop
and calls `PomResolver.prefetchPoms(...)`. Reuse that behavior. The new work is
the boundary: the dynamic Gradle POM lookup is localized in
`GeneratePomClosureTask`, and `GenerateModelTask` receives only a deterministic
closure file.

Recommended shape:

- Introduce a small `PomClosureResolver` helper used by
  `GeneratePomClosureTask`.
- Seed it with selected module POM files resolved through `artifactType=pom`
  artifact views.
- Give it a producer-task-only fallback resolver for dynamically discovered
  parent/imported-BOM `GAV`s. Reusing `GradlePomResolver(DependencyHandler, ...)`
  inside this producer task is acceptable if configuration-cache tests pass,
  because the legacy resolver remains isolated from `GenerateModelTask`.
- Mark `GeneratePomClosureTask` with `@DisableCachingByDefault`; do not claim
  build-cacheability for this dynamic closure.

Tests:

- pure unit test with an in-memory `PomResolver` proving parent/import `GAV`s
  are discovered and prefetched in batches;
- TestKit test with an external dependency whose POM has a parent POM;
- TestKit test with an external dependency importing a BOM that contributes
  dependency management;
- TestKit test for missing parent/import POM that terminates and records a clear
  unresolved result.

## Phase 4: Wire Package Model Enrichment In New Plugin

Purpose: make the new plugin's package/build model consume the POM closure file
and avoid live Gradle POM resolution.

Status: implemented.

1. Add properties to `GenerateModelTask`:
   - `Property<DeclaredDependencyEnrichmentMode> getDeclaredDependencyEnrichmentMode()`;
   - `RegularFileProperty getPomClosureFile()` annotated as optional input file;
   - possibly a nested/list input for selected module identity if the task still
     needs it to map declared dependencies to artifacts.
2. Set conventions:
   - package/build model task: `SELECTED_MODULE_POMS`;
   - dev model task: `NONE`;
   - main codegen model task: `NONE`;
   - test codegen model task: `NONE`.
3. In `TaskRegistration`, register one POM-closure task only for the package
   application model. Wire it as an input to `quarkusApplicationModel`.
4. Update `GenerateModelTask.execute()`:
   - if enrichment mode is `NONE`, skip `DependencyDataCollector` entirely and
     do not call `setDirectDeps(...)` with external declared dependency results;
   - if enrichment mode is `SELECTED_MODULE_POMS`, read the POM closure file,
     construct `GradlePomResolver(Map<GAV, File>, repositoryRoots)`, collect
     external declared dependencies, and call `setDirectDeps(...)`;
   - never instantiate `GradlePomResolver(getDependencyHandler(), ...)`.
5. Remove the injected `DependencyHandler` from `GenerateModelTask` if no longer
   used.

Behavior expectations:

- package/build model retains direct-dependency metadata needed by modular
  packaging and package-time SBOM;
- dev/codegen models retain selected graph, workspace module, runtime/deployment
  classpath, compile-only, platform, and extension metadata, but do not enrich
  external declared dependencies;
- legacy plugin behavior remains unchanged.

Tests:

- focused project-builder test proving model task registration modes:
  `quarkusApplicationModel` is `SELECTED_MODULE_POMS`; dev/codegen/test-codegen
  models are `NONE`.
- project-builder test for task dependencies:
  `quarkusApplicationModel` depends on or consumes the POM-closure producer;
  dev/codegen model tasks do not.
- TestKit build of a tiny Quarkus app invoking `quarkusApplicationModel` with
  configuration cache and isolated projects.
- TestKit build invoking `quarkusApplicationCodegenModel` or a codegen task
  proving no POM-closure task runs.

## Phase 5: Source Root Path-Only Inputs

Purpose: prevent ordinary source/resource content edits from rerunning model
generation when only root directories are needed.

Status: implemented.

1. Replace `GenerateModelTask` source-root file content inputs with path-only
   modeled values:
   - main source root directories;
   - resource source root directories.
2. Keep compiled class/resource output directories as file inputs only where the
   model actually needs output existence/content state.
3. Add a TestKit up-to-date test:
   - run a codegen/dev model task;
   - edit a Java source file without changing source roots;
   - rerun and assert the model task is up-to-date or not re-executed when no
     output/classpath-relevant input changed.

This phase is orthogonal to POM closure but should happen in the same
implementation series because it avoids repeatedly exposing POM resolution during
continuous dev iterations.

## Phase 6: Regression And Compatibility Tests

Run or add tests covering:

Status: implemented for the focused new-plugin and shared resolver coverage.
Broader legacy/tooling modernization remains follow-up work.

1. New plugin package model with modular-packaging-relevant declared dependency
   metadata:
   - optional dependency;
   - provided/runtime/compile scope;
   - missing from selected application graph;
   - parent POM influence;
   - imported BOM influence.
2. New plugin dev/codegen models:
   - no POM closure task;
   - no POM closure output;
   - no `Unable to resolve effective model` warnings from those tasks.
3. Legacy plugin smoke:
   - existing legacy Gradle application-model tests still pass;
   - no task names or public DSL changes.
4. Extension deployment plugin smoke:
   - existing serialized test model behavior still passes;
   - no attempted migration to the new POM-closure path.
5. Configuration cache / isolated projects:
   - new plugin package model task path passes with
     `--configuration-cache -Dorg.gradle.unsafe.isolated-projects=true`;
   - dev/codegen paths pass with the same flags;
   - if any continuous-build related TestKit test hits Gradle's continuous-build
     configuration-cache bug, run that specific test with
     `--no-configuration-cache` and document why in the test.

Suggested commands:

```bash
cd devtools/gradle
./gradlew :gradle-model:test :gradle-app-plugin:test
```

Do not treat the outer `devtools/gradle` build itself as the hard gate for
isolated projects until its test-harness wiring is modernized: today
`gradle-app-plugin/build.gradle.kts` and `gradle-extension-plugin/build.gradle.kts`
add plugin-under-test classpath entries by reading another project's
`sourceSets`, which makes the outer build fail configuration-cache storage under
`-Dorg.gradle.unsafe.isolated-projects=true` before it proves or disproves the
new application plugin behavior. The hard gate for this phase is the TestKit
coverage that runs sample builds with `--configuration-cache` and
`-Dorg.gradle.unsafe.isolated-projects=true`.

Run broader Gradle integration tests only after focused tests are green.

Validation completed for this implementation:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test :gradle-model:test --tests io.quarkus.gradle.tooling.GradlePomResolverTest
```

The Nessie included-build smoke was also run:

```bash
cd /home/snazy/devel/projectnessie/nessie/nessie
./gradlew :nessie-quarkus:quarkusApplicationDev --continuous
```

The Gradle iteration completed, Quarkus dev started Nessie on port `19120`, `/`
returned the expected HTML page, and `/api/v1/config` returned JSON with
`"defaultBranch" : "main"`.

## Phase 7: Documentation And Follow-Up Ledger

1. Update [POM Resolution Boundary Design](../pom-resolution-boundary-design.md) if
   implementation discoveries change the shape.
2. Update [build-tooling-model-design.md](../build-tooling-model-design.md) only if
   the tooling-model fallback constraints change.
3. Record deferred work for:
   - moving extension deployment test model generation to a Gradle-native model
     task;
   - replacing legacy/tooling `GradlePomResolver(DependencyHandler, ...)`;
   - making POM-closure outputs build-cacheable only if the full closure is
     completely modeled and relocatability is reviewed.
   - modernizing the outer `devtools/gradle` plugin-under-test metadata wiring
     so the outer build can store configuration cache under isolated projects.

Status: implemented.

## Completion Criteria

The phase is complete when:

- new-plugin package/build model generation enriches declared dependencies using
  a deterministic POM closure file;
- new-plugin dev/codegen model generation does not resolve or snapshot external
  POMs;
- parent POM and imported BOM POM cases are covered by tests;
- `GenerateModelTask` no longer injects or uses `DependencyHandler`;
- legacy application-model code remains functionally unchanged;
- focused Gradle tests pass with configuration cache and isolated projects where
  applicable;
- docs reflect any implementation-driven adjustments.

Completion status: complete for the new-plugin task-produced model path. The
outer `devtools/gradle` build still has unrelated isolated-project violations in
plugin-under-test metadata wiring; TestKit sample builds remain the hard gate for
the new plugin's configuration-cache and isolated-project behavior in this
slice.
