# P1-AP-02C Deployment Investigation

Date: 2026-07-07

Status: reference investigation; Phase C implementation completed and archived.
Current code lives in the standalone `io.quarkus.application` plugin under
`devtools/gradle/gradle-app-plugin`.

## Objective

Investigate what the named Gradle deployment slice needs to mirror from the
legacy `deploy` task and from Quarkus Kubernetes-family deployment
machinery.

This document was input for the Phase C implementation plan. The completed
implementation plan is archived under `archive/phase-c/implementation-plan.md`.

## Summary

Named deployment tasks were implemented after normal image build/push.

The existing legacy `deploy` task is not a Gradle dependency composition over
`quarkusBuild`, `imageBuild`, or `imagePush`. It bootstraps Quarkus directly,
runs custom deploy command handlers, mutates forced properties, and sets
`quarkus.deploy.target` as a JVM system property at execution time.

The named model should not copy that shape directly. It should use the Phase B
operations boundary, explicit task inputs, image receipts, and descriptor-owned
properties to run one deployment target per task:

- `deployments { kubernetes("dev") { ... } }` ->
  `quarkus<BuildName>DeployToDev`;
- `deployments { openshift("prod") { ... } }` ->
  `quarkus<BuildName>DeployToProd`.
- `deployments { kind("local") { ... } }` ->
  `quarkus<BuildName>DeployToLocal`.

Deployment tasks must remain non-cacheable because they mutate external cluster
state.

## Legacy Gradle `deploy` Behavior

The legacy Gradle task is `io.quarkus.gradle.tasks.Deploy`.

- It is marked `@DisableCachingByDefault`: `Deploy.java:35`.
- It extends `QuarkusBuildTask`: `Deploy.java:36`.
- It supports deployers `kubernetes`, `minikube`, `kind`, `knative`, and
  `openshift`: `Deploy.java:38`.
- It exposes task options:
  - `--deployer`: `Deploy.java:76`;
  - `--image-build`: `Deploy.java:81`;
  - `--image-builder`: `Deploy.java:86`.
- Its task action resolves the application model, builds effective Quarkus
  configuration, creates a `QuarkusBootstrap`, and bootstraps a
  `CuratedApplication`: `Deploy.java:97`.
- It first runs `DeployCommandDeclarationHandler` to discover command
  declarations: `Deploy.java:114`.
- It then runs `DeployCommandHandler` for the selected target:
  `Deploy.java:184`.

Important legacy behavior:

- It does not use `BuildWorker`. It invokes `AugmentAction.performCustomBuild`
  directly in the task action.
- It reads `quarkus.deploy.target` from JVM system properties:
  `Deploy.java:120`.
- It sets `quarkus.deploy.target` with `System.setProperty(...)` before the
  custom deploy build: `Deploy.java:182`.
- It mutates legacy extension forced properties:
  - `quarkus.<deployer>.deploy=true`;
  - `quarkus.container-image.build=<boolean>`;
  - optional `quarkus.container-image.builder=<builder>`;
  - `quarkus.ignore.legacy.deploy.build=true`.
- It validates required deployer and container-image extensions against the
  `ApplicationModel`: `Deploy.java:134`.
- It aborts when:
  - the required deployer extension is missing;
  - an explicitly selected image builder extension is missing;
  - image build is requested but no acceptable image extension is present;
  - multiple deploy command declarations exist and no target is selected;
  - a selected target is unknown.

Legacy task wiring in `QuarkusPlugin`:

- Legacy `imageBuild`, `imagePush`, and `deploy` are `finalizedBy(quarkusBuild)`,
  not `dependsOn(quarkusBuild)`: `QuarkusPlugin.java:349` and
  `QuarkusPlugin.java:363`.
- Legacy `deploy` does not depend on `quarkusImageExtensionChecks`:
  `QuarkusPlugin.java:363`.

## Quarkus Deploy Command Model

The deploy command mechanism is build-item based:

- `DeployCommandDeclarationBuildItem` declares that an extension supports a
  deploy command: `DeployCommandDeclarationBuildItem.java:9`.
- `DeployCommandDeclarationHandler` returns the declared command names from a
  custom build: `DeployCommandDeclarationHandler.java:9`.
- `DeployCommandActionBuildItem` records command execution result:
  `DeployCommandActionBuildItem.java:5`.
- `DeployCommandHandler` returns whether command execution produced any command
  actions: `DeployCommandHandler.java:8`.

Kubernetes-family deployment also participates in normal production
augmentation:

- `AugmentActionImpl.createProductionApplication()` includes
  `DeploymentResultBuildItem` as a final result type:
  `AugmentActionImpl.java:181`.
- `DeploymentResultBuildItem` contains the deployed primary resource name and
  labels: `DeploymentResultBuildItem.java:23`.

Implication: named deploy operations should prefer a structured operation that
can return a deployment receipt/report from `DeploymentResultBuildItem` where
available, instead of only returning a boolean from the legacy custom command
handler.

## Kubernetes And OpenShift Deployment Selection

Quarkus deployment has two related selection concepts:

- Generic custom command target: `quarkus.deploy.target`.
- Kubernetes target selection:
  - `quarkus.kubernetes.deployment-target`;
  - `quarkus.<target>.deploy=true`.

Relevant source:

- `DeployConfig` maps `quarkus.deploy.target`: `DeployConfig.java:12`.
- `DeploymentUtil` detects `quarkus.<target>.deploy` properties:
  `DeploymentUtil.java:17`.
- `KubernetesConfigUtil.getExplicitlyConfiguredDeploymentTargets()` parses
  `quarkus.kubernetes.deployment-target`: `KubernetesConfigUtil.java:52`.
- `KubernetesConfigUtil.isDeploymentEnabled()` checks known Kubernetes targets:
  `KubernetesConfigUtil.java:96`.
- Known Kubernetes-family targets include `kubernetes`, `openshift`, `knative`,
  `kind`, and `minikube`: `Constants.java:5`.

Internal target modeling:

- Extensions produce `KubernetesDeploymentTargetBuildItem`.
- `KubernetesProcessor.enabledKubernetesDeploymentTargets(...)` merges enabled
  targets into `EnabledKubernetesDeploymentTargetsBuildItem`:
  `KubernetesProcessor.java:71`.
- `KubernetesDeployer.determineDeploymentTarget(...)` selects one target:
  `KubernetesDeployer.java:159`.
- If `quarkus.kubernetes.deployment-target` contains multiple targets, only the
  first selected target is deployed: `KubernetesDeployer.java:178`.
- Selected target state is represented by
  `SelectedKubernetesDeploymentTargetBuildItem`.

Implication: a named Gradle deployment should run one Quarkus deployment target
per Gradle task invocation. It should not try to deploy multiple target names in
one augmentation.

## Image Identity And Image Build/Push

Image identity comes from `quarkus.container-image.*`:

- full `quarkus.container-image.image`;
- or registry/group/name/tag pieces.

Relevant source:

- `ContainerImageConfig` defines group, name, tag, registry, full image, build,
  push, and builder: `ContainerImageConfig.java:16`.
- `ContainerImageProcessor.publishImageInfo(...)` creates a
  `ContainerImageInfoBuildItem`: `ContainerImageProcessor.java:52`.
- A full `quarkus.container-image.image` overrides registry/group/name/tag:
  `ContainerImageProcessor.java:77`.
- `ContainerImageInfoBuildItem.getImage()` returns the resolved image reference:
  `ContainerImageInfoBuildItem.java:78`.
- Kubernetes resource generation applies the resolved image through
  `ApplyContainerImageDecorator`: `BaseKubeProcessor.java:810`.

Kubernetes deployment may request image build/push:

- `KubernetesDeployerPrerequisite` produces
  `ContainerImageBuildRequestBuildItem` when deployment is selected:
  `KubernetesDeployerPrerequisite.java:32`.
- It produces `ContainerImagePushRequestBuildItem` when a registry or fallback
  registry is present and implicit push is not prevented:
  `KubernetesDeployerPrerequisite.java:37`.
- For `kind` and `minikube`, implicit push is prevented:
  `KubernetesDeployer.java:89`.
- Build/push can be explicitly disabled with
  `quarkus.container-image.build=false` and
  `quarkus.container-image.push=false`: `ContainerImageConfig.java:103`.

Implication for named Gradle `ImageSource`:

- `EXISTING_IMAGE` should set `quarkus.container-image.image=<reference>`,
  `quarkus.container-image.build=false`, and
  `quarkus.container-image.push=false`.
- `NORMAL_IMAGE_PUSH` should consume the normal image-push receipt and set the
  deploy augmentation image reference from that receipt. It should also avoid
  letting the deploy augmentation independently choose a different image.
- `AOT_ENHANCED_IMAGE_PUSH` should consume the AOT image-push receipt once AOT
  image tasks are implemented. Until then, it should remain modeled but not
  executable.
- Do not add a separate `LOCAL_IMAGE` or `NORMAL_IMAGE_BUILD` source in Phase C.
  If local-cluster workflows need a no-push convenience later, add it as a
  separate explicit image source rather than overloading `NORMAL_IMAGE_PUSH`.

Deployment target and image builder must remain distinct:

- target: `kubernetes`, `openshift`, `knative`, `kind`, `minikube`;
- builder: `docker`, `podman`, `jib`, `openshift`, `buildpack`.

## Existing Named Deployment Surface

Before Phase C, Phase A had added a skeleton named deployment model. The
completed standalone-plugin model includes the Kubernetes-family deployment
factories and executable deployment tasks:

- `QuarkusApplicationDeployments` exposes `kubernetes(name)` and
  `openshift(name)` factory methods: `QuarkusApplicationDeployments.java:45`.
  Phase C added `knative(name)`, `kind(name)`, and `minikube(name)`.
- `QuarkusApplicationDeployment` stores deployment name, target, image source,
  and optional image reference: `QuarkusApplicationDeployment.java:31`.
- `QuarkusApplicationDeploymentDescriptor` validates required fields and
  requires an image reference for `EXISTING_IMAGE`:
  `QuarkusApplicationDeploymentDescriptor.java:25`.
- `QuarkusApplicationDeploymentImageSource` has `EXISTING_IMAGE`,
  `NORMAL_IMAGE_PUSH`, and `AOT_ENHANCED_IMAGE_PUSH`:
  `QuarkusApplicationDeploymentImageSource.java:21`.
- `QuarkusApplicationDeploymentTarget` includes the Kubernetes-family targets:
  `KUBERNETES`, `OPENSHIFT`, `KNATIVE`, `KIND`, and `MINIKUBE`.
- `QuarkusApplicationDeployTask` declares modeled inputs and executes through
  the new plugin's deployment operation boundary.
- `QuarkusApplicationPlugin` registers named deployment tasks and wires
  image-source dependencies.

Current tests cover registration, planning, image-source resolution, receipt
codecs, and mocked deployment execution:

- `QuarkusApplicationTaskRegistrationTest` asserts deployment task names and
  task inputs: `QuarkusApplicationTaskRegistrationTest.java:235`.
- `DeploymentPlannerTest` asserts task names, default image
  source, AOT source modeling, and existing-image validation:
  `DeploymentPlannerTest.java:39`.

## Recommended Phase C Shape

Phase C should implement normal named deployments for the Kubernetes-family
targets without adding single-deployment sugar. Start with factory methods for
`kubernetes`, `openshift`, `knative`, `kind`, and `minikube`.

The legacy task's command-line options are build-script modeled in the named
model:

- `--deployer` becomes the typed deployment factory.
- `--image-build` / `--image-builder` become descriptor/image-source and image
  builder configuration.

A command-line driven compatibility variant can be added later if it proves
useful, but it is not part of the first named deployment slice.

Suggested slices:

1. C0: Deployment Operation Model
   - Add deployment request/result records.
   - Add a deployment receipt model, likely under
     `build/quarkus-build-results/<build-name>/deployments/<deployment-name>/`.
   - Model selected deployment target, image source, resolved image reference,
     deployment result name, deployment labels, and operation success.
   - Extend or add operations interface methods for deploy.
   - Prefer `DeploymentResultBuildItem` as the structured result source. If a
     deploy command succeeds without producing a structured deployment result,
     write a limited successful receipt with target and image facts.
   - Keep deploy operations non-cacheable.

2. C1: Task Wiring And Image-Source Dependencies
   - `EXISTING_IMAGE`: no image task dependency; require `imageReference`.
   - `NORMAL_IMAGE_PUSH`: depend on `quarkus<BuildName>ImagePush` and consume
     its image receipt.
   - `AOT_ENHANCED_IMAGE_PUSH`: fail clearly until AOT image push exists, or
     depend on it once implemented.
   - Image build/push behavior is selected by task name and image source, not by
     command-line booleans on the deploy task.
   - Set task inputs from deployment descriptor and selected image receipt.
   - Fail when user-supplied deployment or image configuration contradicts the
     named deployment descriptor.
   - Preserve current no-`quarkus<BuildName>Deploy` sugar stance.

3. C2: Worker/Bootstrap Execution
   - Prefer routing through a named deployment operation rather than embedding
     legacy `Deploy` task logic in `QuarkusApplicationDeployTask`.
   - Avoid `System.setProperty(...)` in task actions. Pass
     `quarkus.deploy.target`, `quarkus.<target>.deploy=true`,
     `quarkus.kubernetes.deployment-target=<target>`, and image properties via
     the effective configuration/build-system properties path.
   - Validate required deployer extension and image-source requirements using
     the application model or Quarkus deployment result behavior.
   - Return a deterministic deployment receipt when Quarkus exposes
     `DeploymentResultBuildItem`.
   - Fail if neither a structured deployment result nor a successful deploy
     command result is available.

## Deployment Receipt

Use a small deterministic properties receipt. Do not include timestamps,
cluster URLs, namespaces, credentials, Kubernetes server versions, or other
volatile or sensitive data.

Suggested fields:

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

Include `manifest.file` only if the path is stable and useful for downstream
Gradle tasks. Store receipts with the same deterministic `PropertyUtils.store`
approach used for Phase B image receipts.

## Practical Test Strategy

Prefer the existing test pyramid:

- pure unit tests for deployment request/result mapping and receipt codec;
- ProjectBuilder tests for descriptor/task registration and dependency wiring;
- focused TestKit tests with stub operations for task execution and receipt
  writing;
- stubbed executable deployment coverage for every supported target:
  `kubernetes`, `openshift`, `knative`, `kind`, and `minikube`;
- worker-mapping unit tests for deploy request to Quarkus bootstrap/custom build
  parameters;
- gated integration tests only for real Kubernetes-family cluster mutation.

Do not require Docker, Podman, a registry, Kubernetes, OpenShift, Knative,
Kind, or Minikube in the default Phase C test suite.

## Settled Direction

- Add `kubernetes`, `openshift`, `knative`, `kind`, and `minikube` deployment
  factories.
- Model deployer/image-build/image-builder intent in the build script. Do not
  add command-line driven named deployment variants in Phase C.
- Let task names and image source select image behavior:
  - existing-image deployment consumes no image task;
  - normal-image deployment consumes the normal image push receipt;
  - AOT-image deployment consumes the AOT image push receipt once available.
- Use a deploy-specific named operation behind
  `BuildOperations`, not the legacy `Deploy` task action.
- Prefer `DeploymentResultBuildItem` for structured results, with a limited
  successful receipt fallback when only a generic deploy command success is
  available.
- Fail on descriptor/config contradictions instead of silently overriding
  values.
- Keep deployment tasks non-cacheable but model their inputs and receipt outputs
  for configuration-cache correctness, diagnostics, and downstream wiring.

## Deferred Follow-Ups

No Phase C-specific deferred follow-ups remain. Broader follow-ups are tracked
in the design-level `Cross-Phase Deferred Follow-Ups` section.
