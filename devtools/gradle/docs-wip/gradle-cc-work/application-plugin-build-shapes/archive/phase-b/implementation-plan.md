# P1-AP-02B Implementation Plan

Status: archived, completed
Last reviewed: 2026-07-07

## Objective

Implement Phase B of `P1-AP-02`: normal named-output image build/push tasks for
the Quarkus Gradle application plugin.

Phase B must produce `quarkus<Name>ImageBuild` and `quarkus<Name>ImagePush`
tasks that:

- use the named-output model introduced in Phase A;
- expose stable Gradle inputs and receipt-file outputs;
- use descriptor-owned package/image shape instead of graph-selected mutation;
- write deterministic image receipts;
- invoke real Quarkus image build/push behavior by the end of B2;
- leave legacy tasks unchanged.

Phase B proper does not implement deployment, native tests, AOT-enhanced image
execution, dev/run/remote-dev/continuous-test behavior, or broad lifecycle
tasks. Those remain later-phase reference topology unless explicitly pulled
forward.

## Required Reading

Read these files before code changes:

1. `../../design.md`
2. `../../phase-b-task-topology.md`
3. `../../phase-b-augment-result-image-metadata.md`
4. `../../effective-config-history.md`
5. `../../README.md`
6. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
7. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/EffectiveConfig.java`
8. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/EffectiveConfigProvider.java`
9. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/QuarkusWorker.java`
10. Existing Phase A classes under
    `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/`

Apply the Quarkus project instructions and relevant Java/Gradle/testing rules
before code changes.

## Current Code Inventory

Phase A already added these packages:

- `io.quarkus.gradle.tasks.application`
- `io.quarkus.gradle.tasks.application.dsl`
- `io.quarkus.gradle.tasks.application.model`
- `io.quarkus.gradle.tasks.application.planning`

Behaviorally meaningful Phase A pieces:

- descriptor/model types under `application/model`;
- task-name, output-layout, build-intent, image-reference, AOT, deployment, and
  package-layout inference planners under `application/planning`;
- `quarkus.builds` DSL types and typed build/deployment factories;
- task registration skeleton for named build/image/AOT/deploy/native-test
  classes;
- opt-in legacy task usage diagnostics and report generation.

Skeleton or later-phase pieces:

- image build/push task actions and real execution;
- descriptor-driven effective-config planner;
- normalized image receipt model/codec/extractors;
- production operations seam around worker/bootstrap execution;
- AOT training and AOT-enhanced image execution;
- named deploy/native-test/dev/remote-dev/continuous-test/run behavior beyond
  class and naming skeletons.

Existing Phase A tests live under:

```text
devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/application/
```

Keep those tests green and extend the same package family for Phase B.

## Non-Goals

Do not do these in Phase B:

- do not delete, rewrite, or behaviorally replace legacy `quarkusBuild`;
- do not change legacy `imageBuild`, `imagePush`, `buildNative`,
  `testNative`, `deploy`, `buildAotEnhancedImage`, `quarkusRun`,
  `quarkusDev`, `quarkusRemoteDev`, or `quarkusTest`;
- do not remove `ForcedPropertieBuildService` from the legacy hierarchy;
- do not implement named deployment tasks;
- do not implement AOT-enhanced image build/push execution;
- do not implement native-test suite execution;
- do not add aggregate lifecycle tasks such as all-images or all-deployments;
- do not add `push` booleans to image/deploy descriptors;
- do not model container images as Gradle file outputs;
- do not mark image build/push tasks cacheable;
- do not broaden Docker/registry tests beyond narrow, explicitly gated
  integration coverage.

## Invariants

- Named-output descriptor and selected task own package/native/image shape.
- Application config, project properties, env vars, and other config sources may
  influence ordinary build-time config but must not silently change descriptor
  shape.
- `quarkus<Name>ImageBuild` owns image-build intent.
- `quarkus<Name>ImagePush` owns image-push intent and initially remains
  build-with-push-intent, not standalone push of an already-built image.
- Image tasks write Gradle-owned receipt files; the external image is not a
  Gradle-owned file artifact.
- Receipt files are deterministic `.properties` files written with
  `io.quarkus.bootstrap.util.PropertyUtils.store(...)`, never
  `java.util.Properties.store(...)`.
- The old image tasks may continue using the legacy forced-property service.
  New Phase B tasks must not mutate that service.
- Prefer pure unit tests first, then ProjectBuilder, then TestKit, then narrowly
  gated integration tests only when the behavior truly needs Quarkus/container
  execution.

## Package Layout

Use these package names unless current source strongly suggests a better local
placement:

```text
io.quarkus.gradle.tasks.application.config
io.quarkus.gradle.tasks.application.execution
io.quarkus.gradle.tasks.application.image
```

Suggested responsibilities:

- `application.config`: descriptor-driven effective-config request, planner,
  output plan, and shape validation.
- `application.execution`: operations interface, immutable execution request
  records, production implementation, and test-stub seam.
- `application.image`: `ContainerImageTarget`, `BuiltContainerImage`,
  `BuiltContainerImageResultCodec`, and builder-specific result extractors.

Keep task classes in `io.quarkus.gradle.tasks.application`.

## Test Strategy

Use this test pyramid:

1. Pure unit tests for config planning, shape validation, receipt codec,
   image-result extraction, and request objects.
2. ProjectBuilder tests for plugin registration, task providers, task types,
   conventions, and property wiring.
3. TestKit tests for executable Gradle behavior with stubbed operations.
4. Worker-oriented or integration tests only for real Quarkus worker/image
   behavior in B2.

Do not use TestKit when a pure unit or ProjectBuilder test can prove the
contract.

## B0: Config, Shape, And Receipt Infrastructure

B0 is pure infrastructure. It must not change task behavior, require Docker, or
run a full Quarkus application build.

### B0.1 Add Descriptor-Driven Effective Config API

Create request/output records in `application.config`.

Suggested records:

```java
record EffectiveConfigRequest(
        Map<String, String> platformProperties,
        String applicationName,
        String applicationVersion,
        Set<File> sourceDirectories,
        Map<String, String> commonBuildProperties,
        Map<String, String> outputBuildProperties,
        Map<String, String> operationForcedProperties,
        Map<String, ?> taskProperties,
        Map<String, ?> projectProperties,
        Map<String, String> environment,
        Map<String, String> systemProperties,
        Map<String, String> defaultProperties,
        String profile) {}

record EffectiveConfigPlan(
        Map<String, String> fullValues,
        Map<String, String> quarkusWorkerValues,
        Map<String, String> buildSystemProperties,
        Map<String, String> descriptorShapeValues) {}
```

Implementation notes:

- Keep names and types idiomatic for current source; do not preserve this exact
  spelling if a local convention is already better.
- Preserve current effective source ordering from `effective-config-history.md`.
- Do not make the named-output path depend on `EffectiveConfigProvider` as its
  public API.
- It is acceptable to reuse/extract internal helper behavior from
  `EffectiveConfig` when that keeps behavior identical and testable.
- Keep full config values, Quarkus worker system properties, build-system
  properties, and descriptor-shape values as separate maps.
- Build-system properties must not blindly mirror full effective config in
  normal mode. Start with Quarkus worker propagation values, then merge
  explicitly modeled build/task/project/system values captured through
  `configInputs`. The legacy ambient escape hatch may deliberately use the full
  effective config map.

Tests:

- source ordering matches existing effective config behavior;
- `quarkusBuildProperties` outrank Gradle project properties;
- config-file values can influence full config;
- defaults from `PackageConfig`/`NativeConfig` are excluded unless explicitly
  set;
- `quarkus.test.*` values are propagated;
- worker propagation skips config-file/env values that Quarkus can read itself;
- profile resolution follows current behavior.
- unrelated ambient Gradle project properties, JVM system properties, and
  environment variables do not enter build-system properties unless matched by
  configured prefixes/names or legacy ambient capture is enabled.

Stop condition:

- The planner can produce deterministic plans from synthetic inputs without
  Gradle task execution.

### B0.2 Add Descriptor-Owned Shape Validation

Create a shape validator in `application.config` or `application.planning`.

Required descriptor-owned keys for Phase B:

- `quarkus.package.jar.type`
- `quarkus.native.enabled`
- `quarkus.package.jar.enabled`
- `quarkus.package.output-directory`
- `quarkus.package.output-name`
- `quarkus.container-image.build`
- `quarkus.container-image.push`
- `quarkus.container-image.builder`

Behavior:

- Force descriptor/operation-owned keys at higher precedence than ordinary
  config sources.
- Validate after effective config creation and before worker submission.
- Fail with a message that includes registered build name, task/operation,
  expected descriptor value, and resolved value.

Example error shape:

```text
Named Quarkus output 'app' is registered as FAST_JAR but resolved
quarkus.package.jar.type=uber-jar while executing quarkusAppImageBuild.
Descriptor-owned output shape must not be changed by application config.
```

Tests:

- fast-jar descriptor wins over `application.properties` jar type;
- image build task forces build intent and push false;
- image push task forces push intent;
- mismatched resolved values fail before any operation is invoked.

Stop condition:

- Shape validation is pure-unit tested and does not require Gradle task
  realization.

### B0.3 Add Image Receipt Model

Create image receipt model classes in `application.image`.

Required types:

- `ContainerImageTarget`
- `BuiltContainerImage`
- `BuiltContainerImageResultCodec`

`ContainerImageTarget` is a Gradle nested input bean for intended image identity.
Minimum properties:

- repository/reference component as currently modeled by the Phase A image
  descriptor;
- tag;
- optional future identity fields only when needed.

`BuiltContainerImage` is an immutable Java result object. Minimum fields:

- schema version;
- result type;
- builder;
- pushed;
- optional image reference;
- optional digest;
- optional image ID;
- optional pull-required;
- optional working directory;
- optional output directory.

Receipt schema:

- file format: UTF-8 Java-properties-compatible text;
- writer: `io.quarkus.bootstrap.util.PropertyUtils.store(...)`;
- never use `java.util.Properties.store(...)`;
- stable sorted keys;
- no timestamp/date comment;
- omit absent optional fields;
- tolerate unknown fields when reading;
- reject malformed booleans and unknown required enum values;
- never infer or fabricate `image.digest`.

Required receipt keys:

- `schema.version=1`
- `result.type`
- `image.builder`
- `image.pushed`

Conditionally required:

- `image.reference`, when descriptor or Quarkus result provides an effective
  image reference.

Optional:

- `image.digest`
- `image.id`
- `image.pull-required`
- `image.working-directory`
- `image.output-directory`

Tests:

- writes stable lexical order;
- no timestamp/comment line is emitted;
- round-trips all known fields;
- optional fields remain absent;
- unknown fields are ignored on read;
- invalid booleans fail with field/file context;
- digest is not invented.

Stop condition:

- Codec tests prove deterministic output and parsing without container tooling.

### B0.4 Add Image Result Extractors

Create extractor interfaces/classes in `application.image`.

Suggested shape:

```java
interface BuiltContainerImageExtractor {
    Optional<BuiltContainerImage> extract(ImageExtractionRequest request);
}
```

The request should include:

- modeled target reference/tag;
- builder enum;
- operation kind build/push;
- `AugmentResult` or `List<ArtifactResult>` facts;
- optional Jib digest/image-id side-file paths.

Extractor matching rules:

- accept `ArtifactResult` types `jar-container` and `native-container`;
- prefer result whose `container-image` metadata equals the modeled target
  reference when multiple image results exist;
- Jib reads digest/image-id side files only when configured file paths are known
  and files exist;
- Docker/Podman common and Buildpack use only `ArtifactResult` metadata;
- OpenShift falls back to modeled target fields when metadata is empty;
- AOT-enhanced extraction is later-phase only;
- never synthesize image digest.

Tests:

- Docker/Podman metadata with `container-image`, `pull-required`,
  `working-directory`, and `output-directory`;
- Jib metadata plus digest/id side files;
- Jib metadata when side files are absent;
- Buildpack image reference metadata;
- OpenShift empty metadata fallback;
- multiple result entries choose the matching target;
- no digest is guessed for non-Jib builders.

Stop condition:

- Extractors can turn synthetic augmentation facts into `BuiltContainerImage`
  without running Quarkus or image tooling.

### B0.5 Add Operations Interface And Request Records

Create operations types in `application.execution`.

Required interface shape:

```java
interface BuildOperations {
    void build(BuildRequest request);
    BuiltContainerImage buildImage(ImageRequest request);
    BuiltContainerImage pushImage(ImageRequest request);
}
```

Minimum `BuildRequest` contents:

- build name/type;
- output root;
- app-model file/provider or resolved application model input;
- application classpath inputs;
- source/resource directories;
- effective-config plan;
- build-system properties;
- operation forced properties;
- fork/isolation settings;
- named package output layout.

Minimum `ImageRequest` contents:

- build request or build request reference;
- operation kind: `BUILD` or `PUSH`;
- `ContainerImageTarget`;
- builder enum;
- common and image-scoped build properties;
- selected receipt file;
- optional builder side-file locations.

Behavior:

- Operations return normalized result objects.
- Operations do not write Gradle output files directly.
- Task actions write receipt files through `BuiltContainerImageResultCodec`.
- Exceptions should be Gradle-facing and include build name, operation, and
  relevant descriptor values.

Tests:

- request records defensively copy maps/collections;
- required fields are validated;
- operation kind is explicit and cannot be inferred from task name inside the
  operations implementation.

Stop condition:

- Operations interface and request records compile and are pure-unit tested, but
  no production worker implementation is required yet.

## B1: Task Wiring With Stubbed Execution

B1 wires Gradle tasks to the B0 infrastructure using stubbed operations. It must
not run real image tooling.

### B1.1 Wire Image Task Managed Properties

Update `QuarkusApplicationImageTask`,
`QuarkusApplicationImageBuildTask`, and
`QuarkusApplicationImagePushTask`.

Required managed properties:

| Property | Annotation | Task(s) | Required | Source |
| --- | --- | --- | --- | --- |
| build name | `@Input` | both | yes | registered build descriptor |
| build type | `@Input` | both | yes | registered output descriptor |
| named build output | `@InputDirectory` / `@InputFile` as shape requires | both | yes | `quarkus<Name>Build` |
| application model | `@InputFile` | both | yes | existing app-model task output |
| runtime/deployment classpath inputs | `@Classpath` | both | yes | existing production build worker path |
| `ContainerImageTarget` | `@Nested` | both | yes | descriptor `image {}` |
| image builder | `@Input` | both | yes | descriptor builder enum |
| image build properties | `@Input` map | both | optional | common plus image-scoped properties |
| operation kind | `@Input` | both | yes | `BUILD` or `PUSH` |
| receipt file | `@OutputFile` | both | yes | image result path |
| config input prefixes/names | `@Input` sets | all named application tasks | yes | extension `configInputs {}` |
| legacy ambient config capture | `@Internal` property controlling cache behavior | all named application tasks | yes | extension `configInputs {}` / `-PquarkusBuildLegacyAmbientConfigCapture=true` |
| operations/test seam | `@Internal` | both | yes | Gradle service or injected helper |

Receipt path conventions:

- build: `build/quarkus-builds/<name>/image/image-build-result.properties`
- push: `build/quarkus-builds/<name>/image/image-push-result.properties`

Behavior:

- no `@CacheableTask` on image build/push tasks;
- no direct `ForcedPropertieBuildService` mutation;
- no `push` property on descriptor;
- selected task supplies build/push intent.
- normal mode captures only configured ambient config prefixes/exact names;
- legacy ambient capture warns, opts out of configuration-cache reuse, disables
  build caching, and makes outputs never up-to-date.

Tests:

- ProjectBuilder verifies task types, names, receipt path conventions, and
  managed properties;
- image build task has `BUILD` operation kind;
- image push task has `PUSH` operation kind;
- receipt paths are under `build/quarkus-builds/<name>/image/`.
- `configInputs` DSL defaults and configured exact/prefix entries are wired into
  tasks;
- normal filtered capture excludes unrelated ambient values from build-system
  properties, while legacy ambient capture includes them.

Stop condition:

- Tasks are configured with stable inputs/outputs but still use stubbed
  operations.

### B1.2 Add Stub Operations For Tests

Provide a test seam that lets TestKit fixtures run image tasks without Quarkus
image tooling.

Acceptable implementation options:

- a Gradle shared service selected by task property/convention in tests;
- a package-private operations provider replaceable from tests;
- another repository-consistent injection seam.

Constraints:

- no test-only API in the public DSL;
- no global mutable singleton that can leak across tests;
- task action code path should be the same except for operation implementation;
- stub returns `BuiltContainerImage`; task writes the receipt.

Stub result:

```properties
schema.version=1
result.type=jar-container
image.builder=<builder>
image.pushed=<true-or-false>
image.reference=<modeled-reference>
```

Tests:

- stub receives the expected request values;
- task writes receipt via `BuiltContainerImageResultCodec`;
- receipt is deterministic and has no timestamp comment.

Stop condition:

- TestKit can execute image build/push tasks without Docker, registry access, or
  full Quarkus image execution.

### B1.3 Wire Task Registration

Update named-output registration in `QuarkusPlugin`.

Required behavior:

- register `quarkus<Name>ImageBuild` only when registered output has
  `image {}`;
- register `quarkus<Name>ImagePush` only when registered output has
  `image {}`;
- both tasks depend on `quarkus<Name>Build`;
- `quarkus<Name>ImagePush` does not depend on `quarkus<Name>ImageBuild`;
- both tasks receive descriptor image target, builder enum, common properties,
  image-scoped properties, operation kind, and receipt path;
- duplicate effective image references are detected before any image-producing
  action runs;
- legacy image tasks remain unchanged.

Tests:

- no image tasks are registered for outputs without `image {}`;
- image tasks are registered for outputs with `image {}`;
- image build/push depend on named build output;
- image push does not depend on image build;
- multiple named outputs do not share mutable forced-property state;
- duplicate selected effective image references fail before stub operation runs.

Stop condition:

- ProjectBuilder and TestKit prove task registration and graph behavior using
  stubs.

### B1.4 Prove Config And Shape Behavior Through Tasks

Add executable stub tests that exercise named-output config behavior.

Tests:

- common output `quarkusBuildProperties` merge with image-scoped
  `quarkusBuildProperties` in the intended order;
- config files can influence ordinary build-time config;
- config files cannot change descriptor-owned shape;
- project properties cannot turn image build into image push or change builder;
- operation-specific forced properties are visible in the request;
- task failure includes the named output and operation.

Stop condition:

- Stub task execution proves the Gradle wiring and config planner interaction
  before real worker execution is introduced.

## B2: Worker-Backed Image Build/Push Execution

B2 connects image tasks to real Quarkus build/image behavior.

### B2.1 Add Production Operations Implementation

Implement production `BuildOperations`.

Required behavior:

- use existing app-model generation and worker/bootstrap machinery;
- feed descriptor/operation-owned forced properties into the effective-config
  planner;
- preserve the B1 `configInputs` model: normal execution captures only declared
  Gradle property, system-property, and environment-variable names/prefixes,
  while `quarkusBuildLegacyAmbientConfigCapture=true` is the explicit
  non-cacheable escape hatch for broad ambient capture. Do not derive
  property-style names from environment-variable names;
- avoid `ForcedPropertieBuildService` mutation;
- submit existing or minimally adapted worker actions under
  `io.quarkus.gradle.tasks.worker`;
- preserve fork/process-isolation behavior and stale worker system-property
  reset semantics;
- return `BuiltContainerImage` for image build/push operations.

Implementation notes:

- Prefer extracting reusable request-to-worker parameter mapping instead of
  copying large task-action blocks.
- Keep worker parameter changes minimal and focused.
- Workers cannot return `AugmentResult` directly to the task process. If image
  metadata is needed after worker execution, add the smallest deterministic
  worker result-file handoff and serialize only the facts needed by the
  extractor.
- Do not change legacy worker behavior for legacy tasks.

Tests:

- worker-oriented unit tests for request-to-worker parameter mapping;
- effective-config maps are passed to worker/build-system properties correctly;
- normal mode passes the filtered effective-config maps produced from
  `configInputs`; legacy ambient capture mode passes the deliberately broad
  map and disables configuration-cache/build-cache/up-to-date behavior;
- forked worker system properties receive the Quarkus worker map, not the full
  map when that distinction matters.

Stop condition:

- Production operations can be invoked from tests without broad container
  integration and map requests to worker parameters correctly.

### B2.2 Extract Real Image Results

Connect production operations to image extractors.

Required sources:

- `AugmentResult.getResults()` / `ArtifactResult` metadata;
- Jib digest/image-id files when configured and present;
- modeled target fallback for OpenShift when metadata is empty.

Behavior:

- normal jar/native image results use `jar-container` or `native-container`;
- builder-specific metadata keys follow
  `../../phase-b-augment-result-image-metadata.md`;
- digest is optional;
- absence of digest must not fail the task;
- missing image reference should fail only when neither descriptor nor Quarkus
  result provides a usable reference.

Tests:

- synthetic `ArtifactResult` extraction remains covered by B0 unit tests;
- production operation test proves `AugmentResult` facts are passed to the
  extractor;
- Jib side-file enrichment is covered without requiring a registry.

Stop condition:

- Real operations return `BuiltContainerImage` from Quarkus result facts when
  available.

### B2.3 Execute Real Named Image Build/Push

Enable image build/push tasks to use production operations by default.

Required behavior:

- `quarkus<Name>ImageBuild` invokes build-with-image-build intent;
- `quarkus<Name>ImagePush` invokes build-with-image-push intent;
- both write normalized receipts after successful operation;
- failed operations do not write misleading successful receipts;
- legacy `imageBuild` and `imagePush` behavior remains unchanged.

Tests:

- TestKit or integration fixture for `quarkusAppImageBuild` with the lightest
  viable builder path;
- TestKit or integration fixture for `quarkusAppImagePush` only when registry
  requirements can be gated safely;
- verify receipt contents after success;
- verify legacy image tasks still exist and keep their old wiring.

Gating:

- Docker/Podman/registry/network tests must be narrowly scoped and skipped
  unless the existing Quarkus Gradle test infrastructure opts into them.
- Prefer Jib/local metadata tests where they avoid external infrastructure.

Stop condition:

- Named image build/push tasks can run through the real Quarkus path and write
  normalized receipts, while legacy tasks remain compatible.

## Acceptance Gates

Phase B is complete only when all of these are true:

- `quarkus<Name>ImageBuild` and `quarkus<Name>ImagePush` are registered only for
  named outputs with `image {}`.
- Image build and image push tasks have stable managed Gradle inputs and
  `@OutputFile` receipt outputs.
- Image tasks are not marked cacheable.
- Image push does not depend on image build.
- New tasks do not mutate `ForcedPropertieBuildService`.
- Descriptor-owned shape is forced and validated before worker submission.
- Receipt files use `PropertyUtils.store(...)`, sorted keys, no timestamp
  comment, and schema version `1`.
- Receipt codec has direct unit tests.
- Builder extractors have direct unit tests.
- Stubbed task execution has ProjectBuilder/TestKit coverage.
- Production worker mapping has focused tests.
- Real image behavior has the narrowest viable integration coverage.
- Legacy `quarkusBuild`, `imageBuild`, and `imagePush` behavior remains
  unchanged.

## Suggested Test Commands

Run focused tests first. Adjust exact test class names as implementation adds
them.

```bash
./mvnw test -f devtools/gradle -Dtest=QuarkusApplication*Test
```

For Gradle plugin integration/TestKit tests, follow existing module conventions
and run only the relevant test classes. Do not run multiple Quarkus test modules
in parallel.

Before declaring completion, run the smallest module-level verification that
covers touched Gradle plugin code according to current project rules.

## Deferred Follow-Ups

Track deferred items in the design-level
`Cross-Phase Deferred Follow-Ups` section rather than duplicating them in the
Phase B implementation plan.
