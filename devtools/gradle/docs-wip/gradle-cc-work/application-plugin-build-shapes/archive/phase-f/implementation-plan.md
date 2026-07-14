# P1-AP-02F Native Outputs Plan

Status: implementation plan draft
Last reviewed: 2026-07-07

## Objective

Make named native output tasks production-ready for:

- native executable;
- native sources.

Phase F builds on the named-output model and worker-backed execution boundary,
but treats native output modeling separately from JVM package outputs because
native executable and native-sources outputs have different output facts,
inputs, toolchain assumptions, and test needs.

## Required Reading

Before implementing this phase, read:

- `design.md`, especially `Named-Output Execution Principles`,
  `AugmentResult And Managed Outputs`, `Existing Task Reuse Boundary`, and
  native/AOT deferred follow-ups;
- `phase-b-task-topology.md`, for task names and dependency expectations;
- `phase-b-augment-result-image-metadata.md`, for the augmentation-result
  side-file pattern and receipt determinism;
- `phase-d-aot-enhanced-image-investigation.md`, for AOT/native image
  boundaries that must not be conflated with native executable outputs;
- `effective-config-history.md`, for effective-config behavior and worker
  propagation constraints.

Relevant current code entry points:

- `io.quarkus.gradle.QuarkusPlugin`;
- `io.quarkus.gradle.tasks.application.QuarkusApplicationNativeTask`;
- `io.quarkus.gradle.tasks.application.QuarkusApplicationBuildTask`;
- `io.quarkus.gradle.tasks.application.execution.BuildOperations`;
- `io.quarkus.gradle.tasks.application.execution.WorkerBackedBuildOperations`;
- `io.quarkus.gradle.tasks.worker.BuildWorker`;
- `io.quarkus.gradle.tasks.worker.BuildWorkerParams`;
- `io.quarkus.gradle.tasks.application.planning.BuildIntentPlanner`;
- `io.quarkus.gradle.tasks.application.planning.OutputLayoutPlanner`;
- `core/deployment/src/main/java/io/quarkus/deployment/pkg/steps/NativeImageBuildStep.java`.

## Current State

The DSL already supports named native outputs. `nativeExecutable(...)` and
`nativeSources(...)` register `QuarkusApplicationNativeTask` instances.
Native executable outputs also register the current placeholder native-test
task; native-sources outputs do not.

`QuarkusApplicationNativeTask` is currently thin. It declares
`nativeArguments`, but calls the same generic build path as package tasks. It
does not expose native-specific result properties, does not write a native
receipt, and does not currently merge native arguments through a native-specific
operation result.

`QuarkusApplicationBuildTask.descriptorShapeProperties()` currently forces
`quarkus.native.enabled=true` for native outputs, but it does not force
`quarkus.native.sources-only=true` for the `NATIVE_SOURCES` build type. The
separate `BuildIntentPlanner` already knows that
native-sources requires `quarkus.native.sources-only=true`; Phase F must
reconcile those paths.

Core Quarkus exposes useful native facts through `AugmentResult`:

- native executable: `getNativeResult()` and an `ArtifactResult` of type
  `native`, with GraalVM metadata;
- native-sources: an `ArtifactResult` of type `native-sources`, but the result
  path can point at the source jar path, while the useful copied output
  directory is `<target-output-directory>/native-sources`.

Native-sources therefore still needs Gradle-side planner knowledge until
Quarkus exposes a richer output manifest.

## Non-Goals

- Do not implement named native-test suites in this phase unless this plan is
  explicitly expanded.
- Do not wire native-test tasks into `check`.
- Do not implement AOT training test-suite wiring; keep that in deferred
  follow-ups unless separately planned.
- Do not implement JVM package output behavior already assigned to Phase E.
- Do not implement dev/run/remote-dev/continuous-test tasks.
- Do not copy or materialize named outputs into legacy shared locations.
- Do not mark native executable tasks cacheable in this phase.

## Implementation Phases

### F0. Shared Augmentation Facts Baseline

Reuse the Phase E augmentation-facts infrastructure if Phase E has already
landed. If Phase F starts first, implement the shared subset needed by both
phases before native task wiring.

Required facts:

- `ArtifactResult` entries with type, path, and metadata;
- `JarResult` fields when present, even if Phase F does not consume them;
- native executable path from `AugmentResult.getNativeResult()`;
- GraalVM metadata from `AugmentResult.getGraalVMInfo()`.

Keep the facts model separate from output-layout inference. The facts model
records what Quarkus reported after augmentation; native-sources output
directory inference remains Gradle-side planner knowledge.

Acceptance for F0:

- existing image extraction still reads artifact results successfully;
- facts tests cover native executable and native-sources artifact results;
- facts serialization uses deterministic `PropertyUtils.store(...)`;
- readers ignore unknown fields and reject malformed required fields.

### F1. Native Result Model And Receipts

Add a native result model and deterministic receipt codec.

Minimum support types:

- `NativeResult`;
- `NativeResultCodec`;
- `NativeResultFactory` or extractor.

The result model must represent:

- build name;
- native output type: `native-executable` or `native-sources`;
- output root;
- output name;
- optional native executable path;
- optional native-sources directory;
- optional native source jar path;
- optional native image args file path;
- optional GraalVM metadata;
- raw artifact result facts useful for diagnostics.

Suggested native executable receipt:

```properties
schema.version=1
result.type=native-executable
build.name=native1
native.output-root=.
native.output-name=my-native
native.executable.path=my-native-runner
```

Suggested native-sources receipt:

```properties
schema.version=1
result.type=native-sources
build.name=nativeSources1
native.output-root=.
native.output-name=my-native
native.sources.directory=native-sources
native.image.args.path=native-sources/native-image.args
```

Optional fields:

- `native.source-jar.path`;
- `native.graalvm.<key>`;
- `native.builder-image.path`;
- `native.graalvm-version.path`;
- `native.artifact.<n>.type`;
- `native.artifact.<n>.path`;
- `native.artifact.<n>.metadata.<key>`.

Acceptance for F1:

- pure unit tests cover receipt round trips for both native output types;
- native-sources receipt does not claim `ArtifactResult.path` is the output
  directory;
- unknown optional fields are omitted;
- malformed required fields fail with useful messages.

### F2. Native Task Type And Output Modeling

Avoid dynamic or ambiguous Gradle output annotations.

Preferred implementation:

- keep `QuarkusApplicationNativeTask` as a common abstract base if useful;
- introduce concrete task types such as:
  - `QuarkusApplicationNativeExecutableTask`;
  - `QuarkusApplicationNativeSourcesTask`.

If separate concrete task types are not introduced, the single task must still
avoid optional/dynamic output annotations that make Gradle snapshots unclear.

Mandatory outputs:

- broad named output root as `@OutputDirectory`, unless replaced by
  non-overlapping type-specific outputs;
- `@OutputFile RegularFileProperty getNativeResultFile()` or equivalent
  receipt property.

Conservative downstream accessors may be `@Internal` provider-style properties
while the broad output root remains the declared output:

- native executable path provider;
- native-sources directory provider;
- native image args path provider;
- native receipt provider.

Acceptance for F2:

- `nativeExecutable(...)` and `nativeSources(...)` register the correct task
  shape;
- receipt conventions are wired for both task shapes;
- native executable still registers the existing placeholder native-test task
  behavior; native-sources still does not;
- no output annotations overlap under the same directory.

### F3. Shape Intent, Native Arguments, And Validation

Centralize native shape intent so descriptor-owned shape cannot drift.

Required forced shape keys:

- native executable:
  - `quarkus.native.enabled=true`;
  - `quarkus.native.sources-only=false` or absent only if effective config
    semantics make absence equivalent and tests prove it;
  - `quarkus.package.jar.enabled=false` unless core build requirements prove a
    jar must remain enabled internally;
- native-sources:
  - `quarkus.native.enabled=true`;
  - `quarkus.native.sources-only=true`;
  - `quarkus.package.jar.enabled=false` unless core build requirements prove a
    jar must remain enabled internally.

Native arguments from the DSL must be stable task inputs and must merge with
common build properties without being able to override descriptor-owned shape
keys. Use tests to lock the merge order: common build properties and native
arguments are collected first, then descriptor-owned shape keys are applied
last.

Implementation should reconcile `descriptorShapeProperties()` with
`BuildIntentPlanner` so native-sources shape logic exists in
one tested path.

Acceptance for F3:

- pure tests prove native executable and native-sources forced properties;
- tests prove `nativeArguments` reach the operation request;
- tests prove `nativeArguments` and config files cannot turn
  `nativeSources(...)` into a normal native executable, or vice versa;
- shape mismatch failures name the build name, task path, expected shape, and
  resolved conflicting value.

### F4. Native Operation Boundary

Extend `BuildOperations` with a native-specific operation.

Preferred shape:

- `NativeResult buildNative(BuildRequest request)`;
- or a single typed output operation if Phase E introduced one general
  `buildApplicationOutput(...)` operation.

The worker-backed implementation must:

- execute the existing production build worker;
- request augmentation facts or a native result side file;
- extract native executable and native-sources facts from the full
  `AugmentResult`;
- validate extracted facts against the descriptor;
- return a `NativeResult`;
- write the task receipt through the task action or through the operation,
  consistently with Phase E.

Do not perform real Docker/Podman/native-image probing in pure or stub tests.

Acceptance for F4:

- native task actions call the native operation, not the generic void build;
- stubs can exercise native task behavior without invoking native-image;
- worker-backed native-sources execution can be tested without requiring local
  GraalVM native-image, because core writes native-sources using the dummy
  runner path;
- native executable execution remains gated where it requires native-image
  tooling.

### F5. Native-Sources Execution

Implement and test named native-sources first.

Required behavior:

- force `quarkus.native.sources-only=true`;
- produce the copied `native-sources` directory under the named output root;
- receipt records the native-sources directory and `native-image.args`;
- receipt may record the source jar path from `ArtifactResult`, but must not
  confuse it with the copied output directory;
- no dependency on legacy global native task mutation.

Acceptance for F5:

- a named native-sources task writes an isolated named output root;
- `native-sources/native-image.args` is modeled in the result when present;
- tests prove the output directory inference is isolated in a replaceable
  planner/helper;
- no legacy `build/native-sources` materialization is added.

### F6. Native Executable Execution

Implement named native executable execution on top of the same result model.

Required behavior:

- force normal native executable shape;
- produce a native executable under the named output root;
- receipt records the executable path from `AugmentResult.getNativeResult()`
  or the native `ArtifactResult`;
- optional GraalVM metadata is copied into the receipt when available;
- the task remains non-cacheable unless a later phase fully models toolchain,
  OS, container runtime, and ambient native-image inputs.

Acceptance for F6:

- stub tests prove operation mapping and receipt writing;
- gated real native executable coverage exists or an explicit follow-up is
  recorded if local native-image requirements make it unsuitable for normal CI;
- no image/AOT-specific behavior is introduced into normal native executable
  tasks.

### F7. Tests And Verification

Use the established cheap-to-expensive test layering.

Pure unit tests:

- native receipt codec round trips;
- native result extraction from synthetic augmentation facts;
- native-sources directory inference;
- native shape property planning and merge precedence;
- native result/descriptor mismatch messages.

ProjectBuilder tests:

- `nativeExecutable(...)` and `nativeSources(...)` task type selection;
- output and receipt conventions;
- native-test placeholder registration only for native executable;
- native arguments are task inputs.

TestKit/stub tests:

- one named native-sources task writes a deterministic stub receipt;
- one named native executable task writes a deterministic stub receipt;
- multiple native outputs can be configured in one build without mutating
  legacy global native task state;
- descriptor-owned shape keys reach the operation request.

Real integration tests:

- native-sources named output should be the first real execution test because
  it should avoid a local native-image requirement;
- native executable real execution should be gated consistently with existing
  native Gradle integration tests.

Suggested targeted command for the Gradle plugin module:

```bash
cd devtools/gradle
./gradlew :gradle-application-plugin:compileJava :gradle-application-plugin:compileTestJava :gradle-application-plugin:test --tests 'io.quarkus.gradle.tasks.application.nativeimage.*' --tests 'io.quarkus.gradle.tasks.application.execution.AugmentResultCodecTest' --tests 'io.quarkus.gradle.tasks.application.QuarkusApplicationTaskRegistrationTest' --no-scan
```

Run broader application-task tests if shared operation or augmentation-facts
code is changed.

## Acceptance Criteria

Phase F is complete when:

- `nativeExecutable(...)` and `nativeSources(...)` named outputs execute through
  native-specific operation methods;
- each native task writes an isolated named output root and deterministic
  native receipt;
- native executable receipts use native executable facts from `AugmentResult`;
- native-sources receipts model the copied `native-sources` directory and do
  not mistake the source jar artifact path for that directory;
- descriptor shape validation prevents native executable/native-sources drift;
- native arguments are modeled as stable inputs and cannot override shape keys;
- no new behavior mutates legacy global native task state or materializes named
  outputs into legacy shared locations.

## Deferred Follow-Ups

Moved to `design.md` under `Cross-Phase Deferred Follow-Ups` so remaining
native/test/AOT/output-manifest work is tracked with the other higher-level
follow-ups.
