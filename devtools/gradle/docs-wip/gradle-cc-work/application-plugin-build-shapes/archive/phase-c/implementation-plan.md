# P1-AP-02C Implementation Plan

Status: implemented with structured deploy-result extraction and generic success fallback
Last reviewed: 2026-07-07

## Objective

Implement Phase C of `P1-AP-02`: named Quarkus application deployment tasks for
the Gradle application plugin.

Phase C must produce executable `quarkus<Name>DeployTo<Deployment>` tasks that:

- use the named-output model introduced in Phase A;
- consume Phase B image receipts or explicit existing-image references;
- expose stable Gradle inputs and deterministic deployment receipt outputs;
- run one explicit Kubernetes-family deployment target per task;
- avoid legacy forced-property mutation and JVM-global system-property mutation;
- keep legacy `deploy` behavior unchanged.

Phase C proper does not implement AOT-enhanced image build/push execution,
native-test suites, launch/dev/run/remote-dev/continuous-test behavior,
aggregate lifecycle tasks, or command-line-driven named deployment variants.

## Required Reading

Read these files before code changes:

1. `design.md`
2. `phase-b-task-topology.md`
3. `phase-c-deployment-investigation.md`
4. `phase-b-augment-result-image-metadata.md`
5. `effective-config-history.md`
6. `README.md`
7. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
8. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/Deploy.java`
9. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/QuarkusApplicationDeployTask.java`
10. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/execution/BuildOperations.java`
11. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/execution/WorkerBackedBuildOperations.java`
12. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/image/BuiltContainerImageResultCodec.java`
13. `core/deployment/src/main/java/io/quarkus/deployment/pkg/builditem/DeploymentResultBuildItem.java`
14. `core/deployment/src/main/java/io/quarkus/deployment/cmd/DeployCommandHandler.java`
15. `core/deployment/src/main/java/io/quarkus/deployment/cmd/DeployCommandDeclarationHandler.java`
16. `extensions/kubernetes/vanilla/deployment/src/main/java/io/quarkus/kubernetes/deployment/KubernetesDeployer.java`
17. `extensions/kubernetes/vanilla/deployment/src/main/java/io/quarkus/kubernetes/deployment/KubernetesDeployerPrerequisite.java`

Apply the Quarkus project instructions and relevant Java/Gradle/testing rules
before code changes.

## Current Code Inventory

Already implemented:

- named build descriptors and DSL under `application.dsl` and
  `application.model`;
- `kubernetes(name)` and `openshift(name)` deployment factories;
- `QuarkusApplicationDeployTask` input skeleton;
- deploy task-name planning and registration skeleton;
- Phase B image build/push tasks, image receipts, and worker-backed operations;
- config-input planning and descriptor-owned shape validation.

Missing before Phase C implementation:

- `knative(name)`, `kind(name)`, and `minikube(name)` deployment factories;
- corresponding deployment target enum values;
- deployment request/result records;
- deterministic deployment receipt codec;
- image-source resolution from explicit references and image receipt files;
- task dependency wiring by `imageSource`;
- executable deploy task action;
- production deploy operation behind `BuildOperations`;
- tests for all of the above.

## Non-Goals

Do not do these in Phase C:

- do not change legacy `deploy`, `quarkusBuild`, `imageBuild`, or `imagePush`
  behavior;
- do not delete or repurpose `ForcedPropertieBuildService`;
- do not call `System.setProperty(...)` from new named deploy task actions;
- do not read `Task.getProject()` during task execution;
- do not add `push`, `imageBuild`, or `imageBuilder` booleans to named deploy
  tasks;
- do not add command-line-driven named deployment variants;
- do not add `quarkus<Name>Deploy` single-deployment sugar;
- do not add aggregate deployment lifecycle tasks;
- do not add `LOCAL_IMAGE`, `NORMAL_IMAGE_BUILD`, or any build-without-push
  image source in Phase C;
- do not implement AOT-enhanced image build/push execution;
- do not require Docker, Podman, a registry, Kubernetes, OpenShift, Knative,
  Kind, or Minikube in the default test suite;
- do not mark deploy tasks cacheable.

## Invariants

- One named deploy task runs one explicit deployment target.
- Deployment target and image builder are distinct concepts.
- The deployment factory owns the target. Users cannot override target inside
  the deployment block.
- `imageSource` selects image consumption:
  - `EXISTING_IMAGE` consumes only `imageReference`;
  - `NORMAL_IMAGE_PUSH` consumes `quarkus<Name>ImagePush` receipt;
  - `AOT_ENHANCED_IMAGE_PUSH` is modeled but fails clearly until AOT image push
    execution exists.
- Descriptor-owned deployment/image values must not be silently overridden by
  user config.
- Deploy tasks are non-cacheable because they mutate external state, but they
  still expose modeled inputs and deterministic receipt outputs.
- Receipt files are deterministic `.properties` files written with
  `io.quarkus.bootstrap.util.PropertyUtils.store(...)`, never
  `java.util.Properties.store(...)`.
- Prefer pure unit tests first, then ProjectBuilder, then TestKit with stubs,
  then gated real-cluster integration tests only outside the default suite.

## Package Layout

Use existing packages unless current source strongly suggests a better local
placement:

```text
io.quarkus.gradle.tasks.application
io.quarkus.gradle.tasks.application.deployment
io.quarkus.gradle.tasks.application.dsl
io.quarkus.gradle.tasks.application.execution
io.quarkus.gradle.tasks.application.model
io.quarkus.gradle.tasks.application.planning
```

Suggested new package:

- `application.deployment`: deployment receipt/result types, receipt codec,
  image-source resolver, deployment operation result helpers.

Keep task classes in `io.quarkus.gradle.tasks.application`.

## Test Strategy

Use this order:

1. Pure unit tests for target/factory model, deployment receipt codec,
   image-source resolver, request/result records, and conflict validation.
2. ProjectBuilder tests for DSL registration, task types, conventions,
   dependency wiring, receipt file paths, and no singular deploy task.
3. TestKit tests for executable Gradle behavior with stubbed deploy operations.
4. Worker/operation mapping tests for production deploy request to Quarkus
   bootstrap/custom build parameters.
5. Gated integration tests only for real Kubernetes-family cluster mutation.

Do not use TestKit when a pure unit or ProjectBuilder test can prove the
contract.

Every supported deployment target must be exercised through the stubbed deploy
operation path at least once. The default suite must prove that `kubernetes`,
`openshift`, `knative`, `kind`, and `minikube` named deployments all produce the
expected operation request and deterministic deployment receipt without talking
to a real cluster.

## C0: Deployment Model, DSL, And Receipt Infrastructure

C0 is pure infrastructure and registration behavior. It must not perform real
deployment or require external infrastructure.

### C0.1 Add Kubernetes-Family Deployment Targets

Extend the named deployment target model:

- add `KNATIVE("knative")`;
- add `KIND("kind")`;
- add `MINIKUBE("minikube")`.

Update descriptor/planner tests so all five targets map to the expected
Quarkus target names:

- `KUBERNETES -> kubernetes`;
- `OPENSHIFT -> openshift`;
- `KNATIVE -> knative`;
- `KIND -> kind`;
- `MINIKUBE -> minikube`.

Stop condition:

- target enum and planner tests cover all five supported targets.

### C0.2 Add Deployment DSL Factories

Add deployment DSL types and factories:

- `QuarkusKnativeDeployment`;
- `QuarkusKindDeployment`;
- `QuarkusMinikubeDeployment`;
- `QuarkusApplicationDeployments.knative(String)`;
- `QuarkusApplicationDeployments.knative(String, Action<...>)`;
- `QuarkusApplicationDeployments.kind(String)`;
- `QuarkusApplicationDeployments.kind(String, Action<...>)`;
- `QuarkusApplicationDeployments.minikube(String)`;
- `QuarkusApplicationDeployments.minikube(String, Action<...>)`.

Follow the existing `QuarkusKubernetesDeployment` and
`QuarkusOpenShiftDeployment` pattern.

Tests:

- ProjectBuilder registration for each new factory;
- each factory sets the expected target;
- no generic public `register(...)` method is exposed;
- no `quarkus<Name>Deploy` singular task is registered;
- deployment name collision checks still work.

Stop condition:

- all five factory methods register `quarkus<Name>DeployTo<Deployment>` tasks
  with correct target inputs.

### C0.3 Add Deployment Receipt Model And Codec

Create a deterministic receipt model under `application.deployment`.

Suggested types:

```java
record DeploymentResult(
        String buildName,
        String deploymentName,
        QuarkusApplicationDeploymentTarget target,
        QuarkusApplicationDeploymentImageSource imageSource,
        String imageReference,
        Optional<String> quarkusDeployTarget,
        Optional<String> kubernetesDeploymentTarget,
        Optional<String> resultName,
        Map<String, String> resultLabels,
        boolean success) {}
```

```java
final class DeploymentResultCodec {
    static void write(Path path, DeploymentResult result);
    static DeploymentResult read(Path path);
}
```

Receipt fields:

```properties
schema.version=1
build.name=app
deployment.name=prod
deployment.target=openshift
image.source=NORMAL_IMAGE_PUSH
image.reference=quay.io/acme/app:1.0
quarkus.deploy.target=openshift
quarkus.kubernetes.deployment-target=openshift
result.name=my-app
result.labels.app.kubernetes.io/name=my-app
success=true
```

Rules:

- write sorted keys;
- use `PropertyUtils.store(...)`;
- do not write timestamps;
- do not write cluster URL, namespace, credentials, tokens, server versions, or
  other volatile/sensitive data;
- omit absent optional fields;
- reject unsupported `schema.version`;
- preserve label keys after `result.labels.` prefix.

Tests:

- round-trip all fields;
- round-trip with only required fields;
- deterministic sorted output;
- no timestamp comment;
- rejects unsupported schema version;
- rejects missing required fields.

Stop condition:

- receipt codec is fully tested without Gradle/TestKit.

### C0.4 Add Image-Source Resolution Model

Add a pure resolver that converts deployment image source into a deployable
image reference.

Suggested input:

```java
record DeploymentImageSourceRequest(
        QuarkusApplicationDeploymentImageSource imageSource,
        Optional<String> explicitImageReference,
        Optional<Path> normalImagePushReceipt,
        Optional<Path> aotImagePushReceipt) {}
```

Suggested behavior:

- `EXISTING_IMAGE`:
  - require `explicitImageReference`;
  - do not require image receipt paths;
  - return the explicit image reference;
  - force `quarkus.container-image.build=false`;
  - force `quarkus.container-image.push=false`.
- `NORMAL_IMAGE_PUSH`:
  - require the normal image push receipt file;
  - read it with `BuiltContainerImageResultCodec`;
  - return the receipt image reference;
  - fail if the receipt is missing or unreadable.
- `AOT_ENHANCED_IMAGE_PUSH`:
  - fail clearly with a message that AOT image push execution is not implemented
    yet, unless AOT push exists by the time this phase is implemented.

Tests:

- existing image succeeds with explicit reference;
- existing image fails without reference;
- normal image push reads an image receipt and returns its image;
- normal image push fails with missing receipt;
- AOT image source fails clearly until implemented;
- resolver does not read any file for `EXISTING_IMAGE`.

Stop condition:

- image-source resolution is pure and covered by unit tests.

### C0.5 Add Descriptor/Config Conflict Validation

Add pure validation for deploy operation properties before execution.

Validation must fail if user-supplied Quarkus config contradicts descriptor
owned values.

At minimum detect:

- descriptor target differs from `quarkus.deploy.target`;
- descriptor target differs from `quarkus.kubernetes.deployment-target`;
- descriptor target is disabled or contradicted by `quarkus.<target>.deploy`;
- resolved image reference differs from `quarkus.container-image.image`;
- `EXISTING_IMAGE` conflicts with `quarkus.container-image.build=true`;
- `EXISTING_IMAGE` conflicts with `quarkus.container-image.push=true`;
- image source expects a receipt image, but user config tries to select another
  image reference.

Error messages must name:

- build name;
- deployment name;
- descriptor-owned value;
- conflicting property name and value.

Tests:

- matching values pass;
- absent user values pass;
- each conflict fails with actionable message;
- unrelated Quarkus properties pass.

Stop condition:

- conflict validation is pure and covered by unit tests.

## C1: Task Wiring With Stubbed Execution

C1 wires Gradle tasks to the C0 model and proves executable task behavior with
stub operations. It must not perform real deployment.

### C1.1 Add Managed Task Properties

Extend `QuarkusApplicationDeployTask` with managed properties:

- deployment receipt output file;
- normal image push receipt input file, optional;
- AOT image push receipt input file, optional/future;
- resolved image reference input, optional before execution;
- deploy-result properties needed by the operation request.

Use Gradle annotations:

- `@OutputFile` for the deployment receipt;
- `@InputFile @Optional @PathSensitive(PathSensitivity.RELATIVE)` for image
  receipt inputs;
- `@Input` / `@Optional` for scalar properties;
- do not annotate external cluster state as an output.

Ensure task action does not call `getProject()`.

Stop condition:

- task properties are managed, annotated, and can be inspected in ProjectBuilder
  tests.

### C1.2 Wire Image-Source Dependencies And Receipt Paths

Update `QuarkusPlugin.registerNamedDeployTask(...)`.

Expected wiring:

- receipt output path:
  `build/quarkus-builds/<build-name>/deployments/<deployment-name>/deployment-result.properties`;
- `EXISTING_IMAGE`:
  - no image task dependency;
  - set deployment image reference from descriptor;
- `NORMAL_IMAGE_PUSH`:
  - require matching `image {}` block on the named build;
  - depend on `quarkus<Name>ImagePush`;
  - set normal image push receipt path from `quarkus<Name>ImagePush`;
- `AOT_ENHANCED_IMAGE_PUSH`:
  - if AOT image push is not executable yet, register task but fail at execution
    with a clear message;
  - if AOT image push exists, depend on `quarkus<Name>AotEnhancedImagePush` and
    set the AOT image push receipt path.

Tests:

- `EXISTING_IMAGE` deploy task has no image task dependency;
- `NORMAL_IMAGE_PUSH` deploy task depends on the matching image push task;
- `NORMAL_IMAGE_PUSH` fails before execution with a clear error if no image
  block exists, without forcing eager task realization;
- `AOT_ENHANCED_IMAGE_PUSH` behavior is clear and tested according to current
  AOT implementation state;
- receipt output path matches the expected layout;
- task registration remains lazy and does not execute/resolve deployment
  configurations during dry run.

Stop condition:

- ProjectBuilder tests prove dependency wiring and receipt paths for each image
  source.

### C1.3 Add Deploy Operation Request And Stub Execution

Extend the operations boundary.

Preferred shape:

```java
interface BuildOperations {
    ...
    DeploymentResult deploy(DeploymentRequest request);
}
```

Suggested request fields:

- build name;
- deployment name;
- deployment target;
- image source;
- image reference;
- build output directory;
- application model file;
- effective config plan;
- descriptor-owned forced properties;
- process/fork isolation inputs needed by existing operations.

Use the existing test stub pattern in `QuarkusApplicationStubBuildOperations`.

Task action behavior:

1. build effective config plan through existing named task config path;
2. resolve image source;
3. validate descriptor/config conflicts;
4. call `getOperations().deploy(request)`;
5. write receipt only after successful operation result;
6. do not write a success receipt after failure.

Stub tests:

- task action calls deploy operation exactly once;
- request contains build/deployment names, target, image source, and image
  reference;
- one stubbed executable deployment test covers each supported target:
  `kubernetes`, `openshift`, `knative`, `kind`, and `minikube`;
- receipt is written from stub result;
- failed stub operation does not write a success receipt;
- task action does not call `Task.getProject()`;
- task action does not mutate legacy forced-property service.

Stop condition:

- TestKit or ProjectBuilder-with-task-action coverage proves executable deploy
  task behavior without real Quarkus deployment.

### C1.4 Prove Config Conflict Behavior Through Tasks

Add task-level tests for descriptor/config conflict validation.

Scenarios:

- configured `openshift("prod")` with `quarkus.kubernetes.deployment-target=kubernetes`
  fails;
- configured `kubernetes("dev")` with
  `quarkus.container-image.image=other/app:1` conflicting with the image receipt
  fails;
- `EXISTING_IMAGE` with `quarkus.container-image.build=true` fails;
- matching or absent config values pass.

Prefer pure unit tests for validator details and one executable task-level test
for integration of the validator into the task action.

Stop condition:

- user config cannot silently override descriptor-owned deployment target or
  image reference.

## C2: Worker/Bootstrap-Backed Deploy Execution

C2 connects named deploy tasks to real Quarkus deploy behavior without requiring
real clusters in the default test suite.

### C2.1 Add Production Deploy Operation Mapping

Implement production deploy in `WorkerBackedBuildOperations`
or a small collaborator used by it.

Required behavior:

- use existing app-model generation and bootstrap machinery;
- do not embed legacy `Deploy` task action in `QuarkusApplicationDeployTask`;
- do not mutate legacy forced properties;
- do not call `System.setProperty(...)`;
- pass deployment intent through request/build-system properties:
  - `quarkus.deploy.target=<target>`;
  - `quarkus.<target>.deploy=true`;
  - `quarkus.kubernetes.deployment-target=<target>`;
  - resolved `quarkus.container-image.image=<image>`;
  - for existing-image deployments:
    `quarkus.container-image.build=false` and
    `quarkus.container-image.push=false`;
- preserve process/fork isolation behavior used by named build/image operations;
- preserve worker system-property reset semantics where applicable.

Implementation notes:

- Prefer extracting request-to-bootstrap/custom-build parameter mapping for unit
  tests.
- If an existing worker can be adapted without broad behavior changes, use it.
  Otherwise add the smallest deploy-specific worker/custom handler.
- Keep legacy `Deploy` behavior unchanged.

Tests:

- production mapping test verifies deploy properties are placed in the correct
  build-system/effective config maps;
- no full ambient environment/system/project property capture is introduced;
- `System.setProperty(...)` is not used in new deploy task action code;
- legacy `Deploy` remains unchanged.

Stop condition:

- production deploy request maps to Quarkus bootstrap/custom build parameters
  correctly without external cluster execution.

### C2.2 Extract Structured Deploy Results

Prefer `DeploymentResultBuildItem` as the structured result source through the
core `DeployCommandResultHandler`, which returns only system-classloader-safe
`Map<String, String>` values. If the deploy command succeeds without producing
`DeploymentResultBuildItem`, use the generic deploy command success fallback.

Behavior:

- if `DeploymentResultBuildItem` is produced, return a deployment result with
  result name and labels;
- if a generic deploy command result reports success but no
  `DeploymentResultBuildItem` is available, return a limited success result
  containing target and image facts;
- if neither structured result nor command success is available, fail;
- do not include volatile/sensitive cluster facts in the result.

Tests:

- synthetic structured result maps to receipt model;
- generic success fallback maps to limited receipt model;
- absence of both result and success fails;
- labels are serialized deterministically.

Stop condition:

- production operation can return a deterministic `DeploymentResult`
  without real cluster execution.

### C2.3 Execute Real Named Deploy Path With Safe Coverage

Enable `QuarkusApplicationDeployTask` to use production operations by default.

Required behavior:

- `quarkus<Name>DeployTo<Deployment>` invokes deploy intent for the descriptor
  target;
- deploy writes a normalized receipt after success;
- failed deploy does not write a misleading success receipt;
- legacy `deploy` behavior remains unchanged;
- default tests do not mutate real clusters.

Tests:

- TestKit or ProjectBuilder execution with stub operations remains the primary
  default executable coverage;
- production mapping tests cover real operation wiring without external cluster
  mutation;
- optional/gated integration fixture may be added later for real
  Kubernetes-family deployment.

Stop condition:

- named deploy tasks are executable through production operations and have safe
  default test coverage.

## Acceptance Gates

Phase C is complete only when all of these are true:

- `kubernetes`, `openshift`, `knative`, `kind`, and `minikube` factories exist.
- `quarkus<Name>DeployTo<Deployment>` tasks register for all five factories.
- No generic public deployment `register(...)` DSL is exposed.
- No `quarkus<Name>Deploy` sugar task is added.
- Deployment target is fixed by the factory and not configurable inside the
  deployment block.
- Deploy task dependencies match `imageSource`.
- `EXISTING_IMAGE` requires an image reference and has no image task dependency.
- `NORMAL_IMAGE_PUSH` depends on matching `quarkus<Name>ImagePush` and consumes
  its receipt.
- `AOT_ENHANCED_IMAGE_PUSH` has clear tested behavior according to current AOT
  implementation state.
- Deploy tasks have stable managed Gradle inputs and deterministic
  `@OutputFile` receipt outputs.
- Deploy tasks are not marked cacheable.
- Receipt files use `PropertyUtils.store(...)`, sorted keys, no timestamp
  comment, and schema version `1`.
- Deploy task actions do not call `Task.getProject()`.
- Named deploy code does not call `System.setProperty(...)`.
- New tasks do not mutate `ForcedPropertieBuildService`.
- Descriptor/config contradictions fail with actionable messages.
- Production deploy mapping has focused tests.
- Stubbed task execution has ProjectBuilder/TestKit coverage.
- Stubbed task execution covers all five supported deployment targets without
  real cluster access.
- No default test requires Docker, Podman, registry, Kubernetes, OpenShift,
  Knative, Kind, or Minikube.
- Legacy `deploy`, `quarkusBuild`, `imageBuild`, and `imagePush` behavior
  remains unchanged.

## Suggested Test Commands

Run focused tests first. Adjust exact test class names as implementation adds
them.

```bash
./mvnw test -f devtools/gradle -Dtest=QuarkusApplication*Test
```

For Gradle plugin TestKit tests, follow existing module conventions and run only
the relevant test classes. Do not run multiple Quarkus test modules in
parallel.

Before declaring completion, run the smallest module-level verification that
covers touched Gradle plugin code according to current project rules.

## Deferred Follow-Ups

Track deferred items in the design-level `Cross-Phase Deferred Follow-Ups`
section and the Phase C investigation `Deferred Follow-Ups` section rather than
duplicating a backlog here.

Phase C allows the generic deploy command success fallback to satisfy
production execution when Quarkus does not produce `DeploymentResultBuildItem`.
