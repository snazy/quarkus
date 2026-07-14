# Gradle App Plugin Holistic Review

Status: archived closed review ledger from a holistic pass over
`devtools/gradle/gradle-app-plugin`.

Archived: 2026-07-13

Purpose: preserve review provenance and resolution notes. This is not an active
finding queue. Current design and follow-up decisions live in the active
workstream docs such as `new-application-plugin-design.md`,
`quarkus-dev-continuous-build-design.md`, `pom-resolution-boundary-design.md`,
and `application-plugin-build-shapes/design.md`.

Scope:

- Correctness of Gradle task modeling, inputs, outputs, lifecycle, and process handling.
- Code clarity and maintainability of the new plugin surface and internals.
- Performance risks in the new dev-mode and model-generation paths.
- Test-only code or probe seams that currently live in production sources.

## Findings Addressed Immediately

### Worker File Headers

The first review pass incorrectly changed four worker files to ASF headers. Quarkus is not an ASF project. Fixed by removing those incorrect ASF headers from:

- `src/main/java/io/quarkus/gradle/application/internal/execution/worker/BuildWorker.java`
- `src/main/java/io/quarkus/gradle/application/internal/execution/worker/BuildWorkerParams.java`
- `src/main/java/io/quarkus/gradle/application/internal/execution/worker/QuarkusParams.java`
- `src/main/java/io/quarkus/gradle/application/internal/execution/worker/QuarkusWorker.java`

### Codegen Operation Test Seam

`QuarkusApplicationGenerateCodeTask` had a transient `CodegenOperations` field plus a package-visible setter used only by tests. Fixed by replacing that field with an internal Gradle-managed `Property<CodegenOperations>` and updating the unit test to set that property.

### Source-Directory Provider Captured Live SourceDirectorySet

`TaskRegistration.directoryPaths(...)` used `project.provider(...)` and closed over a live `SourceDirectorySet`. Fixed by mapping from `sourceDirectories.getSourceDirectories().getElements()` so the provider no longer captures the source set object directly.

### Dev Process Shutdown

`GradleNativeDevModeLauncher.ProcessHandle.close()` called `destroyForcibly()` after a graceful timeout but did not wait for the forced termination to complete. Fixed by adding a second bounded wait and by failing if output pump threads remain alive after their bounded join.

### Fork-Option Actions Were Opaque To Gradle

Build/codegen fork options were originally stored as `Action<? super JavaForkOptions>` values and marked `@Internal`. Fixed by replacing that API with a Quarkus-owned managed `QuarkusApplicationForkOptions` model backed by `ListProperty`, `MapProperty`, and `Property` values. Worker operation classes now receive an immutable `ForkOptionsSnapshot` and only apply it to Gradle's `JavaForkOptions` at the process-worker boundary.

### Test-Oriented Dev Session Service Hook In Production Task API

`QuarkusApplicationDevTask` exposed a `QuarkusApplicationDevSessionService` property and branched to a BuildService-backed lifecycle path when it was present. The BuildService path was test-only and duplicated the production `DeploymentRegistry` lifecycle path.

Fixed by deleting `QuarkusApplicationDevSessionService`, removing the `getDevSessionService()` task property and test-session branch from `QuarkusApplicationDevTask`, deleting the TestKit service/probe fixtures that depended on it, and keeping dev task execution on the production `DeploymentRegistry` / `QuarkusApplicationDevDeploymentHandle` path.

### Dev Output Snapshot Re-Hashes The Whole Output Set On Incremental Iterations

`QuarkusApplicationDevTask.observedChanges(...)` collected Gradle incremental changes but still rewrote the dev output snapshot by walking and hashing every tracked output root.

Fixed by adding `GradleDevOutputSnapshot.updatedBy(...)` and wiring incremental dev iterations to update only the changed snapshot entries from Gradle's `InputChanges`. Non-incremental iterations, startup baseline writes, and missing/corrupt prior snapshots still fall back to full snapshot capture.

### Image Tasks Depend On Package Tasks While Re-Running Quarkus Build

Fixed by making image and AOT-image tasks standalone Quarkus build operations with operation-specific output/result directories:

- package/native outputs now live under `build/quarkus-builds/<buildName>/package` with receipts under `build/quarkus-build-results/<buildName>/package`;
- image build/push outputs now live under `build/quarkus-builds/<buildName>/image-build` and `image-push` with matching receipt directories;
- AOT-image build/push outputs now live under `build/quarkus-builds/<buildName>/aot-build` and `aot-push` with matching receipt directories;
- image build/push tasks no longer depend on the package/native task;
- `QuarkusApplicationImageTask` no longer reclassifies the Quarkus build output directory as an input directory.

Package element variants remain tied to the package task's primary jar provider, and AOT-image tasks still depend on the corresponding base image task because they consume its image receipt.

### Image Reference Configuration Does Not Match Quarkus Defaults

Fixed by making image build/push tasks available for every named build while keeping image reference inputs optional:

- `image {}` is now customization, not the registration trigger for image build/push tasks;
- image builder has no default convention and is only forced into Quarkus config when explicitly set;
- repository, tag, and full image reference are optional;
- Quarkus container-image properties are emitted only for explicitly configured Gradle image values;
- duplicate-reference validation only runs when the plugin can know an explicit image reference during configuration;
- image receipts can represent an absent modeled builder/reference and rely on Quarkus augmentation metadata for the actual result.

This preserves Quarkus' ability to derive the effective image from normal Quarkus configuration when the Gradle DSL does not explicitly override it.

## Residual Decisions And Follow-Ups

### Dev-Mode Delivery Can Block Stop/Cancel

Status: deferred to `new-application-plugin-design.md`.

`QuarkusApplicationDevSession.deliver()` is synchronized and calls `policy.deliver(buildOutputChangesServer::send)` while holding the session monitor at `src/main/java/io/quarkus/gradle/application/internal/dev/QuarkusApplicationDevSession.java:124`. The deployment handle also synchronizes `deliverReadyChangesOutcome()` and `stop()` at `src/main/java/io/quarkus/gradle/application/internal/dev/QuarkusApplicationDevDeploymentHandle.java:76` and line 105.

The send path is synchronous and waits for a Quarkus-side response. If Quarkus is slow, stuck, or the transport stalls, stopping the continuous build can be delayed behind the same monitor.

Recommendation: split delivery into two phases: select/snapshot pending changes under lock, perform the blocking transport send outside the session and handle monitor, then finalize policy state under lock based on the returned status. Preserve the current behavior that failed sends or non-applied batches remain pending and are coalesced with later changes.

### Internal Gradle Deployment API Is A Maintenance Risk

Status: accepted design decision.

`QuarkusApplicationDevDeploymentHandle` intentionally implements Gradle's internal `DeploymentHandle` API, with a local comment explaining the lack of a public alternative at `src/main/java/io/quarkus/gradle/application/internal/dev/QuarkusApplicationDevDeploymentHandle.java:33`.

This is a deliberate design decision for continuous-build lifecycle ownership
today. Keep it isolated to the current narrow adapter and revisit only if
Gradle exposes a public build-session deployment/lifecycle API or the internal
contract breaks.

### Descriptor Reading Can Race With Jar Producers

Status: fixed.

The descriptor-reading value sources now receive artifact files from a `ModuleComponentIdentifier`-filtered artifact view instead of raw runtime artifact files. This keeps same-build project jars out of descriptor scanning while preserving Gradle's live `FileCollection` dependency metadata for the external artifacts that still need descriptor discovery.

Regression coverage was added for local project extensions whose generated descriptors contain poison conditional, conditional-dev, and deployment coordinates. Runtime/dev/deployment classpath resolution must continue to use the local extension variant path and must not attempt to resolve those poison coordinates.

Follow-up hardening remains possible: `ExtensionDescriptorReader` could add a narrow bounded retry for transient `ZipException` cases such as `zip END header not found`, but the primary modeling bug is addressed.

### Accepted: POM Closure Is Discovered During Execution

Status: accepted design decision.

`GeneratePomClosureTask` says the resolved parent/imported-BOM POM closure is discovered dynamically during task execution at `src/main/java/io/quarkus/gradle/application/internal/modelgen/GeneratePomClosureTask.java:48`. The task models selected POM files and selected GAV-to-POM paths, but additional parent/imported-BOM POMs can be discovered by `GradlePomResolver` during the action. The task also supplies all JVM system properties to the dependency collector for Maven model interpolation.

A conservative `upToDateWhen(false)` fix was tested during this review, but it caused the application model and all package tasks to rerun on a no-change second build. That is too expensive for the normal package path.

Accepted tradeoff: resolving the full parent/imported-BOM closure during configuration or task input snapshotting would move dependency/POM resolution earlier and make the normal configuration path more expensive. The current task intentionally discovers the full closure during execution, remains non-cacheable, and models the selected POM files, selected GAV mapping, and Maven local repository roots that seed the closure. Dynamically discovered parent/import POMs are treated as repository artifacts for fixed GAVs rather than eagerly modeled Gradle inputs.

Follow-up only if real stale POM-closure behavior appears: consider a dedicated two-stage closure task, a modeled closure input snapshot, or narrower declared inputs for interpolation properties that materially affect Maven model collection.

### Fixed: DSL-Facing Types Expose Internal Helper Methods

Severity: medium-low.

DSL-facing types previously exposed internal lifecycle helpers publicly:

- `QuarkusApplicationBuild.whenAotEnhancedImageConfigured(...)` at `src/main/java/io/quarkus/gradle/application/dsl/QuarkusApplicationBuild.java`

The module hard gate says internal helper methods and properties should not be exposed from DSL-facing types with Java `public` visibility.

Fixed by removing the public helper surface. `QuarkusApplicationBuildName` was removed from the public model surface. Public descriptors now carry raw names as `String` values, and task-name segment/collision-key handling lives in the internal `TaskNameSegment` planner helper. `QuarkusApplicationBuild.getBuildName()`, `QuarkusApplicationBuild.whenImageConfigured(...)`, `QuarkusApplicationDeployment.getDeploymentName()`, and `QuarkusApplicationBuilds.asContainer()` are gone. `QuarkusApplicationBuild.whenAotEnhancedImageConfigured(...)` is package-private and is reached by task registration through the explicitly internal `PluginInternalHelper`. `QuarkusApplicationBuild.getBuildType()` is intentionally kept as script-facing model state.

Remaining caveat: `PluginInternalHelper` must stay Java-public because `TaskRegistration` lives outside the DSL package, but it is not part of the curated DSL surface.

### Accepted: Continuous-Test And Remote-Dev Tracking Lives Elsewhere

Status: not tracked in this archived review.

Run tasks are implemented now and are covered by `quarkus-run-task-design.md`
and the archived implementation record. Continuous-test and remote-dev planning
is deliberately not carried forward in this closed review ledger.

### Fixed: Config Input Filtering Was Duplicated

Severity: low.

The logic that filters configured Gradle properties, environment variables, and system properties is duplicated across build, codegen, and dev task types. This increases the chance that legacy ambient config capture, exact-name matching, and prefix matching drift over time.

Fixed by centralizing filtering in `QuarkusApplicationBaseTask`. Build, codegen, and dev tasks now consume the shared `gradleProperties()`, `environmentVariables()`, and `systemProperties()` helpers, which all use the same prefix/name filtering and legacy ambient config capture behavior.

## Test-Only Code Inventory

The previously noted TestKit service/probe implementations were removed when the production `QuarkusApplicationDevSessionService` hook was deleted.

`QuarkusApplicationRemoteDevTask` is intentionally retained as a reserved future-development task stub and is not treated as test-only code.
