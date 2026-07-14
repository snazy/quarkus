# Phase D AOT-Enhanced Image Investigation

Status: reference investigation; Phase D implementation completed and archived
Last reviewed: 2026-07-07

Current code lives in the standalone `io.quarkus.application` plugin under
`devtools/gradle/gradle-app-plugin`. The completed implementation plan is
archived under `archive/phase-d/implementation-plan.md`.

## Conclusion

P1-AP-02D was scoped to named AOT-enhanced image build/push execution. The named
DSL and task names already exist, and Phase D made
`quarkus<Name>AotEnhancedImageBuild` and
`quarkus<Name>AotEnhancedImagePush` execute through the same production
operation boundary used by named build, image, and deploy tasks.

Do not make Gradle `JvmTestSuite`-backed AOT training the critical path for
Phase D. The AOT image tasks first consume a modeled `aotFile` plus
optional producer wiring. A Quarkus-owned AOT-training suite can be a later
sub-slice once the generic producer contract is stable.

## Current Named Gradle State

`aotEnhancedImage {}` registers these named tasks:

- `quarkus<Name>AotTraining`;
- `quarkus<Name>AotEnhancedImageBuild`;
- `quarkus<Name>AotEnhancedImagePush`.

The task types expose modeled state:

- `QuarkusApplicationAotTrainingTask` exposes an `@OutputFile`
  `RegularFileProperty getAotFile()`;
- `QuarkusApplicationAotEnhancedImageTask` exposes an `@InputFile`
  `RegularFileProperty getAotFile()`, optional producer-task name, and optional
  image reference;
- `QuarkusApplicationAotEnhancedImageBuildTask` and
  `QuarkusApplicationAotEnhancedImagePushTask` execute through
  `BuildOperations` and write deterministic receipts.

The named registration follows the Phase-B topology requirements:

- the build and push tasks have deterministic AOT receipt files;
- the build task depends on `quarkus<Name>ImageBuild`;
- the push task depends on `quarkus<Name>ImagePush`;
- an explicit AOT file producer task name is wired as a dependency;
- deploy image source `AOT_ENHANCED_IMAGE_PUSH` consumes the AOT image push
  receipt.

## Legacy Behavior To Preserve Functionally

The legacy Gradle task `buildAotEnhancedImage` reads
`build/quarkus-container-it.properties` and silently skips when the metadata
file or `aot-file` key is absent. It warns if the configured AOT file path does
not exist. When the metadata is usable, it submits
`BuildAotEnhancedImageWorker`.

The metadata file is written by `DefaultDockerContainerLauncher` and contains:

- `original-container-image`;
- optional `container-working-directory`;
- `aot-file` when the file was produced.

The Maven `build-enhanced-artifact` mojo follows the same metadata convention.

The new named path should not scan the global legacy metadata file. It should
fail clearly when required modeled inputs are missing:

- base image receipt exists and contains an image reference;
- base image receipt contains a working directory, because core AOT image
  customization requires one;
- AOT file exists;
- unsupported enhanced image repository/tag/full-reference overrides are not
  configured.

## Core AOT Image Path

The core bridge is already present:

- `BuildAotEnhancedCustomizerProducer` adds and produces a
  `BuildAotOptimizedContainerImageRequestBuildItem`;
- the request carries original container image, container working directory,
  and the AOT file path;
- container-image processors return
  `BuildAotOptimizedContainerImageResultBuildItem`;
- that result build item currently carries only the enhanced container image
  string.

`BuildEnhancedAotContainerImageCommandHandler` converts the produced
`BuildAotOptimizedContainerImageResultBuildItem` into deterministic
build-tool-visible result metadata. The named Gradle task uses that metadata to
write `aot-image-*-result.properties` for downstream deploy tasks.

## Container-Image Processor Semantics

Current Quarkus AOT image processors derive the enhanced image as:

```text
originalContainerImage + quarkus.container-image.aot-image-suffix
```

The default suffix is `-aot`. An empty suffix means rebuilding the original
image reference with the AOT file included.

This is not exactly the same as deriving `repository:tag-aot` from the named
DSL. Phase D implemented the core suffix behavior without changing the
container-image processor API:

- use the base image receipt reference as `originalContainerImage`;
- derive the default enhanced image from the actual base image receipt and the
  modeled suffix;
- force `quarkus.container-image.aot-image-suffix` from the modeled suffix;
- fail clearly if explicit full-reference or repository/tag overrides are
  configured, because those need a later core/container-image target-selection
  API rather than a Gradle-only workaround.

Jib, Docker, and Podman processors all layer the AOT file into the container
working directory and set `JAVA_TOOL_OPTIONS=-XX:AOTCache=<aot-file-name>`.
They push only when `quarkus.container-image.push=true`; otherwise they build
locally. Therefore Phase D uses separate build and push task operations, with
push forcing `quarkus.container-image.push=true`.

## Image Receipts As The Named Boundary

Phase B already introduced `BuiltContainerImage` and
`BuiltContainerImageResultCodec`. The normal image receipts include:

- result type;
- image builder;
- pushed flag;
- optional image reference;
- optional digest/image ID;
- optional pull-required flag;
- optional working directory;
- optional output directory.

AOT build/push tasks should consume the normal image receipt rather than global
metadata:

- `quarkus<Name>AotEnhancedImageBuild` depends on and reads
  `quarkus<Name>ImageBuild`;
- `quarkus<Name>AotEnhancedImagePush` depends on and reads
  `quarkus<Name>ImagePush`;
- both tasks read `image.reference` as the original image;
- both tasks require `image.working-directory`;
- both tasks write a `BuiltContainerImage` receipt for the enhanced image.

Recommended receipt locations:

- `build/quarkus-build-results/<name>/aot-image/aot-image-build-result.properties`;
- `build/quarkus-build-results/<name>/aot-image/aot-image-push-result.properties`.

The AOT receipt can reuse `BuiltContainerImage` with:

- result type such as `aot-container-image`;
- same image builder enum as the base image task;
- `pushed=false` for build and `pushed=true` for push;
- reference from `BuildAotOptimizedContainerImageResultBuildItem`;
- digest/image ID empty initially unless future core/container-image metadata
  exposes them;
- working directory copied from the base image receipt when still relevant.

## Operation Boundary

Add AOT methods to `BuildOperations`, for example:

- `buildAotEnhancedImage(AotEnhancedImageRequest)`;
- `pushAotEnhancedImage(AotEnhancedImageRequest)`.

The production implementation should use a worker-backed custom augmentation,
mirroring legacy `BuildAotEnhancedImageWorker`, but with modeled request data:

- normal build request/effective config;
- base image reference from the receipt;
- container working directory from the receipt;
- AOT file path;
- image builder and image-specific Quarkus build properties;
- target enhanced image/suffix settings;
- receipt path for handler/result extraction.

Tests should use stub operations in pure unit or ProjectBuilder/TestKit tests,
not real Docker/Podman/registry work. Real image execution remains a gated
integration-test follow-up.

Phase D documents that AOT-enhanced image tasks are current-platform
convenience tasks. The task implementation does not try to prove whether the
base image is single-platform or multi-platform; doing that reliably would
require container-tooling and registry-specific inspection outside this slice.

## AOT File Producer Model

The generic producer contract should come before Quarkus-owned test-suite
plumbing:

- `aotFile` remains the modeled file input;
- if the file provider carries a producer task, Gradle can infer the dependency;
- `producedBy(...)` is still useful when the producer does not expose a typed
  file output;
- `aotFileFrom(producer, fileProvider)` remains useful convenience wiring.

Current code stores an optional producer task name. Phase D supports both an
externally provided file and a task-provided file by wiring
`dependsOn(producerTaskName)` when present. A later DSL refinement can retain a
typed task/provider relationship without exposing broad public task internals.

The deterministic `quarkus<Name>AotTraining` task remains registered as a
customization point. AOT image tasks do not depend on it unless the AOT image
configuration explicitly names it as the producer.

## AOT Training Suite Follow-Up

A Quarkus-owned AOT-training JVM Test Suite still makes design sense, but it is
not a blocker for named AOT image execution. The repository currently has no
Gradle `JvmTestSuite` pattern for Quarkus native/AOT tests; legacy native tests
are implemented with custom source-set and `Test` task wiring.

When implemented later, the suite should:

- use deterministic names such as `quarkus<Name>AotTraining`;
- be customized with `testing.suites.named(...)`;
- inject AOT-specific Quarkus build properties such as
  `quarkus.package.jar.aot.enabled=true`;
- expose the produced AOT file as a typed Gradle output;
- remain explicit and not automatically wire into `check` unless a later
  lifecycle decision says otherwise.

## Deployment Interaction

Once `quarkus<Name>AotEnhancedImagePush` writes a receipt,
`AOT_ENHANCED_IMAGE_PUSH` can be enabled in deployment image-source resolution:

- deploy depends on `quarkus<Name>AotEnhancedImagePush`;
- deploy reads the AOT image push receipt;
- deploy uses the AOT image reference exactly like normal image push receipts.

No deployment task should build or push images directly. The selected
`imageSource` controls the dependency on existing image reference, normal image
push, or AOT image push.

## Testing Strategy

Preferred coverage order:

1. Pure unit tests for AOT request/result types, receipt encoding/decoding,
   suffix/reference planning, missing working-directory validation, and
   operation mapping.
2. ProjectBuilder tests for task registration, receipt file locations,
   dependencies on image build/push tasks, and producer task dependency wiring.
3. TestKit tests using stub operations to verify task execution writes
   deterministic receipts without invoking real container tooling.
4. Deferred gated integration tests for real Docker/Podman/Jib AOT image
   build/push behavior.

## Implementation-Plan Inputs

The archived Phase D implementation plan used these slices:

1. Add a core AOT command result handler that exposes
   `BuildAotOptimizedContainerImageResultBuildItem`.
2. Add Gradle AOT image request/result operation types and extend
   `BuildOperations`.
3. Implement worker-backed AOT image build/push operations using modeled data
   instead of `build/quarkus-container-it.properties`.
4. Implement task actions and receipt outputs for
   `QuarkusApplicationAotEnhancedImageBuildTask` and
   `QuarkusApplicationAotEnhancedImagePushTask`.
5. Wire registration dependencies to normal image build/push tasks and the
   configured AOT file producer.
6. Enable deployment `AOT_ENHANCED_IMAGE_PUSH` source after the push receipt
   exists.
7. Add focused tests in the order above.

## Settled Direction

- Support suffix semantics in Phase D. The default enhanced image reference
  derives from the base image reference plus
  `quarkus.container-image.aot-image-suffix`. Full-reference or repository/tag
  overrides remain a later core/container-image API follow-up.
- Document current-platform behavior instead of trying to detect and reject
  multi-platform base images. Reliable detection would require additional
  Docker/Podman/registry plumbing and still would not cover every image source.
- Support both externally provided AOT files and task-provided AOT files in
  Phase D. The desired long-term producer is a Quarkus-owned test-suite-backed
  test task, but the `JvmTestSuite` plumbing can be deferred while the AOT
  image tasks consume a modeled file plus optional producer dependency.

## Source Pointers

- `BuildAotEnhancedImage`
- `BuildAotEnhancedImageWorker`
- `BuildEnhancedArtifactMojo`
- `DefaultDockerContainerLauncher`
- `BuildAotEnhancedCustomizerProducer`
- `BuildEnhancedAotContainerImageCommandHandler`
- `BuildAotOptimizedContainerImageRequestBuildItem`
- `BuildAotOptimizedContainerImageResultBuildItem`
- `ContainerImageConfig`
- `JibProcessor`
- `DockerProcessor`
- `PodmanProcessor`
- `QuarkusApplicationAotTrainingTask`
- `QuarkusApplicationAotEnhancedImageTask`
- `QuarkusApplicationAotEnhancedImageBuildTask`
- `QuarkusApplicationAotEnhancedImagePushTask`
- `BuiltContainerImage`
- `BuiltContainerImageResultCodec`
- `DeploymentImageSourceResolver`
