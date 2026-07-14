# P1-AP-02D Implementation Plan

Status: implemented
Last reviewed: 2026-07-07

Correction after implementation review: the initial plan proposed adding an
explicit enhanced-image reference to the core AOT request build item and
changing Docker/Jib/Podman processors to consume it. That cross-extension API
change was rejected. The implemented Phase D scope keeps the existing core AOT
request contract and supports production execution through the existing
`original image + quarkus.container-image.aot-image-suffix` behavior only.
Repository/tag/full-reference overrides remain a future core/container-image
API follow-up.

## Objective

Implement Phase D of `P1-AP-02`: named AOT-enhanced container image build and
push tasks for the Quarkus Gradle application plugin.

Phase D must produce executable:

- `quarkus<Name>AotEnhancedImageBuild`;
- `quarkus<Name>AotEnhancedImagePush`.

These tasks must:

- consume the named normal image build/push receipts from Phase B;
- consume a modeled AOT file, with optional producer-task dependency wiring;
- execute Quarkus' existing AOT-enhanced image augmentation path through the
  named-task production operation boundary;
- write deterministic AOT image receipts that downstream deploy tasks can read;
- enable deployment image source `AOT_ENHANCED_IMAGE_PUSH`;
- keep legacy `buildAotEnhancedImage` behavior unchanged.

Phase D does not implement the Quarkus-owned `JvmTestSuite` AOT-training
producer. It must support externally provided AOT files and task-provided AOT
files so the future suite can plug into the same producer contract.

## Required Reading

Read these files before code changes:

1. `design.md`
2. `phase-b-task-topology.md`
3. `phase-b-augment-result-image-metadata.md`
4. `phase-d-aot-enhanced-image-investigation.md`
5. `effective-config-history.md`
6. `README.md`
7. `core/deployment/src/main/java/io/quarkus/deployment/cmd/BuildAotEnhancedCustomizerProducer.java`
8. `core/deployment/src/main/java/io/quarkus/deployment/cmd/BuildEnhancedAotContainerImageCommandHandler.java`
9. `core/deployment/src/main/java/io/quarkus/deployment/pkg/builditem/BuildAotOptimizedContainerImageRequestBuildItem.java`
10. `core/deployment/src/main/java/io/quarkus/deployment/pkg/builditem/BuildAotOptimizedContainerImageResultBuildItem.java`
11. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/BuildAotEnhancedImage.java`
12. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/BuildAotEnhancedImageWorker.java`
13. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
14. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/QuarkusApplicationAotEnhancedImageTask.java`
15. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/QuarkusApplicationImageTask.java`
16. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/execution/BuildOperations.java`
17. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/execution/WorkerBackedBuildOperations.java`
18. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/image/BuiltContainerImage.java`
19. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/image/BuiltContainerImageResultCodec.java`
20. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/deployment/DeploymentImageSourceResolver.java`

Apply the Quarkus project instructions and relevant Java, Gradle, and testing
rules before code changes.

## Current Code Inventory

Already implemented:

- named AOT DSL and task names;
- `QuarkusApplicationAotTrainingTask` with an `@OutputFile` AOT file property;
- `QuarkusApplicationAotEnhancedImageTask` with modeled `aotFile`,
  producer-task name, and enhanced-image reference inputs;
- normal named image build/push execution and deterministic image receipts;
- deploy image-source plumbing with `AOT_ENHANCED_IMAGE_PUSH`;
- worker-backed production operation boundary for named build/image/deploy.

Implemented by Phase D:

- core AOT command result extraction from
  `BuildAotOptimizedContainerImageResultBuildItem`;
- suffix-based execution through the existing core AOT request contract;
- named AOT image request/result operation model;
- worker-backed named AOT image operation;
- task actions for AOT image build and push tasks;
- deterministic AOT image receipt file properties and registration conventions;
- dependencies from AOT tasks to normal image tasks and AOT producer tasks;
- deploy resolver support for `AOT_ENHANCED_IMAGE_PUSH`;
- focused unit, ProjectBuilder, and stubbed TestKit coverage.

## Non-Goals

Do not do these in Phase D:

- do not change legacy `buildAotEnhancedImage`;
- do not remove `build/quarkus-container-it.properties` legacy behavior;
- do not implement the Quarkus-owned `JvmTestSuite` AOT-training producer;
- do not require `quarkus<Name>AotTraining` to execute successfully;
- do not add native-test suite behavior;
- do not add launch/dev/run/remote-dev/continuous-test behavior;
- do not add multi-platform AOT image orchestration;
- do not try to inspect Docker/Podman/registry state to prove whether the base
  image is single-platform or multi-platform;
- do not require Docker, Podman, Jib, a registry, or Kubernetes-family tooling
  in the default test suite;
- do not mark AOT image tasks cacheable unless a later design proves external
  image state can be modeled safely;
- do not call `Task.getProject()` during task execution;
- do not mutate JVM-global system properties from named AOT task actions.

## Invariants

- The default enhanced image reference is the base image reference plus
  `quarkus.container-image.aot-image-suffix`, whose current default is `-aot`.
- Users may override the enhanced image with full-reference or repository/tag
  settings; contradictory settings must fail clearly.
- AOT image tasks are current-platform convenience tasks. Documentation should
  say this; implementation should not attempt platform detection.
- `quarkus<Name>AotEnhancedImageBuild` depends on `quarkus<Name>ImageBuild`.
- `quarkus<Name>AotEnhancedImagePush` depends on `quarkus<Name>ImagePush`.
- AOT tasks also depend on the configured AOT file producer when one is
  declared.
- AOT tasks read normal image receipts, not
  `build/quarkus-container-it.properties`.
- AOT tasks require the base image receipt to contain an image reference and a
  working directory.
- AOT tasks require the modeled AOT file to exist.
- Receipt files are deterministic `.properties` files written with
  `io.quarkus.bootstrap.util.PropertyUtils.store(...)`, never
  `java.util.Properties.store(...)`.
- Deploy tasks consume AOT image push receipts exactly like normal image push
  receipts after Phase D enables `AOT_ENHANCED_IMAGE_PUSH`.
- Prefer pure unit tests first, then ProjectBuilder, then TestKit with stubs,
  then gated real-container integration tests outside the default suite.

## Package Layout

Use existing packages unless current source strongly suggests a better local
placement:

```text
io.quarkus.gradle.tasks.application
io.quarkus.gradle.tasks.application.execution
io.quarkus.gradle.tasks.application.image
io.quarkus.gradle.tasks.application.model
io.quarkus.gradle.tasks.application.planning
io.quarkus.gradle.tasks.worker
```

Suggested new package:

- `application.aot`: AOT image request/result helpers if the types are too
  specific for `application.image`.

Task classes remain in `io.quarkus.gradle.tasks.application`.

## Test Strategy

Use this order:

1. Pure unit tests for core AOT handler behavior, request/result records,
   receipt codec reuse, suffix/override planning, and missing-input
   validation.
2. ProjectBuilder tests for task registration, task types, dependency wiring,
   receipt file paths, and producer-task wiring.
3. TestKit tests with stubbed operations proving executable Gradle behavior and
   deterministic receipt writing without real container tooling.
4. Worker/operation mapping tests proving production AOT requests pass the
   expected custom augmentation context and forced properties.
5. Gated integration tests for real Docker/Podman/Jib AOT image build/push only
   as a deferred follow-up outside the default suite.

Do not use TestKit when a pure unit or ProjectBuilder test can prove the
contract.

## D0: Core AOT Result Extraction

D0 makes Quarkus' custom AOT image build observable to build tools. It must be
small and independent of Gradle task registration.

### D0.1 Keep Existing Core AOT Request Contract

Update:

- `core/deployment/src/main/java/io/quarkus/deployment/cmd/BuildAotEnhancedCustomizerProducer.java`

Behavior:

- do not add an enhanced-image target to
  `BuildAotOptimizedContainerImageRequestBuildItem`;
- do not change Jib, Docker, or Podman AOT-enhanced image build steps;
- pass the base image as `original-container-image`;
- force `quarkus.container-image.aot-image-suffix` from the named Gradle task;
- let existing container-image processors derive
  `originalContainerImage + effectiveAotImageSuffix()`.

Stop condition:

- Gradle can execute AOT-enhanced image builds through the existing core suffix
  contract without changing container-image extension APIs.

### D0.2 Implement AOT Command Result Handler

Update:

- `core/deployment/src/main/java/io/quarkus/deployment/cmd/BuildEnhancedAotContainerImageCommandHandler.java`

Behavior:

- inspect the `BuildResult` for produced
  `BuildAotOptimizedContainerImageResultBuildItem` instances;
- use the same system-classloader callback pattern as
  `DeployCommandResultHandler`: cast the handler context to
  `Consumer<Map<String, String>>` and call it with deterministic string keys;
- recommended keys:
  - `success`;
  - `container.image`;
- set `success=false` when no AOT result build item exists;
- set `success=true` and `container.image=<enhanced image>` when a result build
  item exists;
- sort or otherwise handle multiple result items deterministically. Prefer
  failing clearly unless there is a documented reason to select the first item.

Tests:

- pure unit test for one result item;
- pure unit test for no result item;
- pure unit test for multiple result items if the handler API can observe
  multiples; choose the first deterministic result or fail clearly, and
  document the behavior in the test name.

Stop condition:

- build-tool code can obtain the enhanced image reference from the custom build
  through a `Consumer<Map<String, String>>`, without parsing logs.

## D1: Gradle AOT Operation Model

D1 introduces modeled Gradle operation types and validation. It must not start
real workers yet.

### D1.1 Add AOT Image Request Type

Create a request record near the existing execution request types.

Suggested name:

- `AotEnhancedImageRequest`

Required fields:

- `BuildRequest build`;
- `ImageOperation operation`;
- `BuiltContainerImage baseImage`;
- `Path baseImageReceiptFile`;
- `Path aotFile`;
- `String enhancedImageReference`;
- `QuarkusApplicationImageBuilder builder`;
- `Path receiptFile`.

Constructor validation:

- build request is required;
- operation is required and only build/push values are accepted;
- base image is required;
- base image reference is present;
- base image working directory is present;
- AOT file path is required;
- enhanced image reference is required and non-blank;
- builder is required;
- property maps are copied defensively;
- receipt file is required.

Tests:

- valid request stores defensive copies;
- missing base image reference fails;
- missing working directory fails;
- missing enhanced image reference fails;
- missing receipt file fails.

Stop condition:

- AOT image operation inputs are represented without reading global metadata or
  project state at execution time.

### D1.2 Add AOT Image Result Helper

Prefer reusing `BuiltContainerImage` and `BuiltContainerImageResultCodec`.

Add a small factory/helper so every AOT task and operation constructs receipts
the same way:

- input: base image, builder, pushed flag, enhanced image reference;
- output: `BuiltContainerImage` with:
  - `resultType = "aot-container-image"`;
  - same builder;
  - pushed flag from operation;
  - reference from core AOT result;
  - empty digest/image ID initially;
  - base working directory copied when relevant;
  - base output directory copied when relevant.

Tests:

- build result uses `pushed=false`;
- push result uses `pushed=true`;
- reference is the enhanced image returned by core;
- base optional metadata is preserved only where intentionally designed.

Stop condition:

- receipt writing for AOT image tasks can use the existing image receipt codec.

### D1.3 Extend Operations Interface

Update:

- `BuildOperations`

Add methods:

```java
BuiltContainerImage buildAotEnhancedImage(AotEnhancedImageRequest request);

BuiltContainerImage pushAotEnhancedImage(AotEnhancedImageRequest request);
```

Update all production and test implementations/stubs.

Tests:

- compile-time coverage from existing stub operation tests;
- unit tests for task actions in later slices must prove the right method is
  called for build vs push.

Stop condition:

- AOT image tasks can execute through the same operations seam as build, image,
  and deploy tasks.

## D2: Worker-Backed Production Operation

D2 wires the named AOT operation to existing Quarkus bootstrap/custom build
machinery. Keep it modeled and testable.

### D2.1 Add Worker Parameters For Named AOT Operations

Add a named-operation-specific worker parameter interface. Do not reuse
`BuildAotEnhancedImageWorkerParams`; keeping a separate type avoids accidental
legacy behavior coupling.

Required worker inputs:

- build-system properties;
- forked/effective Quarkus properties;
- process-isolated flag;
- base name;
- target/output directory;
- application model;
- Gradle version;
- original/base container image;
- container working directory;
- AOT file;
- operation push flag or forced properties through the build request.

Do not pass `Project`, `Task`, or unfiltered ambient environment maps as new
modeled task inputs.

Tests:

- worker parameter mapping unit test where existing patterns allow it;
- otherwise cover through `WorkerBackedBuildOperations`
  mapping tests.

Stop condition:

- worker invocation can be configured entirely from
  `AotEnhancedImageRequest`.

### D2.2 Add Named AOT Worker Or Adapt Existing Worker

Preferred approach:

- add a named worker class if adapting legacy `BuildAotEnhancedImageWorker`
  would make legacy behavior harder to reason about.

The worker must:

- create the curated application context through existing worker helpers;
- create the augmentor with `BuildAotEnhancedCustomizerProducer`;
- pass context keys:
  - `original-container-image`;
  - `container-working-directory`;
  - `aot-file`;
- run `BuildEnhancedAotContainerImageCommandHandler`;
- pass a `Consumer<Map<String, String>>` context to the handler;
- read the enhanced image reference from the callback result map;
- throw `GradleException` with a precise message when custom build fails.

Keep legacy `BuildAotEnhancedImageWorker` unchanged unless extracting a shared
private helper is clearly simpler and low-risk.

Tests:

- no default test should run a real custom build;
- test the mapping and result handling with stubs or handler-level unit tests.

Stop condition:

- production operations can request an AOT-enhanced image without reading
  legacy metadata.

### D2.3 Implement Operations Methods

Update:

- `WorkerBackedBuildOperations`

Add:

- `buildAotEnhancedImage(...)`;
- `pushAotEnhancedImage(...)`;
- a private shared method used by both build and push.

Forced Quarkus properties:

- always set `quarkus.container-image.builder` from the named builder;
- for build:
  - set `quarkus.container-image.build=true`;
  - set `quarkus.container-image.push=false`;
- for push:
  - set `quarkus.container-image.build=true`;
  - set `quarkus.container-image.push=true`;
- set `quarkus.container-image.aot-image-suffix` or equivalent reference
  override properties according to D3 reference planning.

Result handling:

- read enhanced image reference from the core handler result;
- create a `BuiltContainerImage` AOT result;
- return it to the task;
- let the task write the receipt, matching normal image task pattern.

Tests:

- build operation submits expected base image, working directory, AOT file,
  builder, and forced build/push properties;
- push operation sets push true;
- missing core result fails with a precise `GradleException`;
- no test requires Docker/Podman/Jib.

Stop condition:

- production AOT operations are mapped behind `BuildOperations`.

## D3: Task Properties, Actions, And Reference Planning

D3 makes `QuarkusApplicationAotEnhancedImageBuildTask` and
`QuarkusApplicationAotEnhancedImagePushTask` executable.

### D3.1 Add Receipt And Base Receipt Properties

Update:

- `QuarkusApplicationAotEnhancedImageTask`

Add modeled file properties:

- `@OutputFile RegularFileProperty getReceiptFile()`;
- `@InputFile @PathSensitive(PathSensitivity.RELATIVE) RegularFileProperty getBaseImageReceiptFile()`.

Keep `getAotFile()` as `@InputFile`; remove `@Optional` only if Phase D
requires every executable AOT image task to have a file. If keeping it optional
is necessary for configuration-time flexibility, validate presence in the task
action before reading it.

Tests:

- ProjectBuilder checks both AOT image tasks have receipt and base receipt
  conventions;
- task validation or action tests cover missing AOT file with a clear message.

Stop condition:

- Gradle can see the base receipt input, AOT file input, and AOT receipt output.

### D3.2 Implement Shared AOT Image Task Action

Add a protected method on `QuarkusApplicationAotEnhancedImageTask`, for example:

```java
protected void executeAotEnhancedImageOperation(ImageOperation operation)
```

The method must:

- read base receipt with `BuiltContainerImageResultCodec`;
- require base image reference;
- require base working directory;
- require `aotFile` to be present and regular;
- compute or read the enhanced image reference;
- build `AotEnhancedImageRequest`;
- call `buildOperations().buildAotEnhancedImage(...)` or
  `buildOperations().pushAotEnhancedImage(...)`;
- write the returned `BuiltContainerImage` to `receiptFile` with
  `BuiltContainerImageResultCodec`.

Do not call `getProject()` inside the action.

Tests:

- build task calls build AOT operation and writes receipt;
- push task calls push AOT operation and writes receipt;
- missing base receipt fails clearly;
- missing image reference in base receipt fails clearly;
- missing working directory in base receipt fails clearly;
- missing AOT file fails clearly;
- no action test uses real container tooling.

Stop condition:

- AOT image task execution works through stubbed operations and deterministic
  receipts.

### D3.3 Implement Concrete Task Actions

Update:

- `QuarkusApplicationAotEnhancedImageBuildTask`;
- `QuarkusApplicationAotEnhancedImagePushTask`.

Each class should add a `@TaskAction`:

- build task calls shared method with build operation;
- push task calls shared method with push operation.

Set `@DisableCachingByDefault` unless a more specific existing base annotation
already covers external image side effects. Reason should mention external
container image state.

Tests:

- skeleton-execution tests must be updated or removed for these two tasks;
- task action tests verify operation selection.

Stop condition:

- invoking named AOT image tasks no longer throws skeleton
  "not implemented yet" errors.

### D3.4 Reference Planning: Suffix Plus Overrides

Align named reference behavior with settled direction:

- default enhanced image reference derives from base image reference plus
  `quarkus.container-image.aot-image-suffix`;
- full-reference and repository/tag overrides are modeled by the DSL/planner
  but are not executable in Phase D;
- task execution fails clearly when those overrides are configured.

Implementation guidance:

- prefer existing `AotEnhancedImagePlanner` and descriptor
  tests for static validation;
- task execution should use the actual base image reference from the base
  receipt, not a stale reference computed at registration time;
- pass suffix only and let core derive `originalContainerImage +
  suffix`.

Tests:

- default suffix derives from base receipt image;
- empty suffix means enhanced reference equals base image reference;
- full-reference override fails clearly at task execution;
- repository/tag override fails clearly at task execution;
- full-reference plus repository/tag remains a descriptor validation failure;
- task action uses base receipt reference, not only registration-time
  `imageReference`.

Stop condition:

- suffix and override behavior is deterministic and covered by pure unit tests.

## D4: Task Registration And Dependency Wiring

D4 updates `QuarkusPlugin` registration so named AOT tasks form the correct
Gradle graph.

### D4.1 Wire Base Image Dependencies And Receipts

Update the named AOT registration methods so:

- `quarkus<Name>AotEnhancedImageBuild` depends on
  `quarkus<Name>ImageBuild`;
- `quarkus<Name>AotEnhancedImageBuild.getBaseImageReceiptFile()` points to
  `build/quarkus-builds/<name>/image/image-build-result.properties`;
- `quarkus<Name>AotEnhancedImageBuild.getReceiptFile()` points to
  `build/quarkus-builds/<name>/aot-image/aot-image-build-result.properties`;
- `quarkus<Name>AotEnhancedImagePush` depends on
  `quarkus<Name>ImagePush`;
- `quarkus<Name>AotEnhancedImagePush.getBaseImageReceiptFile()` points to
  `build/quarkus-builds/<name>/image/image-push-result.properties`;
- `quarkus<Name>AotEnhancedImagePush.getReceiptFile()` points to
  `build/quarkus-builds/<name>/aot-image/aot-image-push-result.properties`.

If `aotEnhancedImage {}` is configured without `image {}`, fail clearly during
registration or descriptor validation. AOT image tasks need a base image task.

Tests:

- ProjectBuilder verifies dependencies and receipt file conventions;
- missing `image {}` plus `aotEnhancedImage {}` fails with a clear message.

Stop condition:

- Gradle task graph builds normal image before AOT image.

### D4.2 Wire AOT Producer Dependencies

Support both manual AOT files and task-provided AOT files:

- if the `aotFile` provider carries built-by information, rely on Gradle's file
  provider dependency where possible;
- if `aotFileProducerTaskName` is present, call `dependsOn(...)` for that task
  on both AOT build and push tasks;
- do not depend on `quarkus<Name>AotTraining` when the user has configured a
  manual AOT file with no producer wiring.

If the current DSL always defaults `aotFileProducerTaskName` to the training
task, adjust the DSL/descriptor behavior so manual-file configuration can opt
out of the default producer. Prefer a minimal, explicit model:

- default producer is used only for the default AOT file convention;
- `aotFile.set(...)` without `producedBy(...)` means no producer dependency;
- `producedBy(...)` or `aotFileFrom(...)` means explicit producer dependency.

Tests:

- default configuration depends on `quarkus<Name>AotTraining` only if that is
  still the designed default placeholder;
- manual file without producer does not depend on `quarkus<Name>AotTraining`;
- `producedBy(tasks.named("myAotProducer"))` wires dependency;
- `aotFileFrom(...)` wires both file and dependency.

Stop condition:

- AOT image tasks can consume external files and task-produced files without
  requiring test-suite plumbing.

## D5: Deployment Image Source Enablement

D5 unblocks deployments that explicitly choose the AOT image push source.

### D5.1 Resolve AOT Push Receipts

Update:

- `DeploymentImageSourceResolver`

Behavior:

- `AOT_ENHANCED_IMAGE_PUSH` reads the AOT image push receipt exactly like
  `NORMAL_IMAGE_PUSH`;
- missing receipt fails with a message naming AOT enhanced image push;
- missing image reference fails with a message naming the receipt file.

Tests:

- pure unit resolver test reads AOT image push receipt;
- missing AOT receipt fails clearly;
- no real deployment is performed.

Stop condition:

- image-source resolver no longer throws "not implemented yet" for
  `AOT_ENHANCED_IMAGE_PUSH`.

### D5.2 Wire Deploy Task Dependency To AOT Push

Update deploy registration if not already fully wired:

- deployment with `imageSource = AOT_ENHANCED_IMAGE_PUSH` depends on
  `quarkus<Name>AotEnhancedImagePush`;
- `QuarkusApplicationDeployTask.getAotEnhancedImagePushReceiptFile()` points to
  `build/quarkus-builds/<name>/aot-image/aot-image-push-result.properties`.

Tests:

- ProjectBuilder verifies dependency for `AOT_ENHANCED_IMAGE_PUSH`;
- TestKit/stub deploy path proves deploy consumes AOT receipt and writes
  deployment receipt.

Stop condition:

- AOT-enhanced image deploy path works through stubbed AOT image push and
  deploy operations.

## D6: Documentation Updates

D6 keeps docs-wip consistent with implementation.

Update:

- `phase-d-aot-enhanced-image-investigation.md` if implementation decisions
  refine the settled direction;
- `phase-b-task-topology.md` if task dependencies or receipt names change;
- `design.md` deferred follow-ups if the implementation pulls any deferred
  work forward or leaves a new follow-up.

Do not write final user-facing docs under `docs/src/main/asciidoc/` in Phase D
unless the user explicitly asks. The existing design keeps final docs deferred
until the DSL and task set are more complete.

Stop condition:

- docs-wip accurately describes implemented Phase D behavior and remaining
  deferred work.

## Acceptance Gates

Phase D is complete when all of these are true:

- `quarkus<Name>AotEnhancedImageBuild` executes through
  `BuildOperations.buildAotEnhancedImage(...)`;
- `quarkus<Name>AotEnhancedImagePush` executes through
  `BuildOperations.pushAotEnhancedImage(...)`;
- build task depends on normal image build and reads its receipt;
- push task depends on normal image push and reads its receipt;
- AOT producer dependency works for explicit task-provided files;
- manual AOT file without producer works and does not require test-suite
  plumbing;
- missing AOT file, missing base image reference, and missing working directory
  fail clearly;
- AOT image receipts are deterministic and use `BuiltContainerImageResultCodec`
  or an explicitly documented compatible codec;
- `AOT_ENHANCED_IMAGE_PUSH` deploy source consumes the AOT push receipt;
- legacy `buildAotEnhancedImage` behavior is unchanged;
- default tests do not require Docker, Podman, Jib, a registry, or Kubernetes;
- all focused unit, ProjectBuilder, and stubbed TestKit tests pass.

## Suggested Test Commands

Run focused Gradle plugin tests first:

```bash
./gradlew :gradle-application-plugin:test --tests '*Aot*' --no-scan
./gradlew :gradle-application-plugin:test --tests '*ApplicationTaskRegistrationTest' --no-scan
./gradlew :gradle-application-plugin:test --tests '*Deployment*' --no-scan
```

Run the broader Gradle application-plugin unit test suite before declaring the
slice done:

```bash
./gradlew :gradle-application-plugin:test --no-scan
```

If core deployment handler code changes, run a focused Maven compile:

```bash
./mvnw compile -f core/deployment -DskipTests
```

Do not add or run real Docker/Podman/registry integration tests as part of the
default Phase D acceptance unless the user explicitly asks for that gated
follow-up.

## Deferred Follow-Ups After Phase D

Durable follow-ups live in the cross-phase deferred follow-ups section of
`design.md`. Do not keep a second copy here.
