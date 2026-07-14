# P1-EP-02 Application Model Generation Plan

Status: historical
Superseded by: ../../tracker.md

This document tracks the remaining work for `P1-EP-02` after commit
`048c22e11b3` (`Gradle: Generate extension test application model with task
output`).

That commit removed deployment `Test.doFirst` application-model generation and
system-property mutation. Deployment `Test` tasks now consume a generated model
file from `GenerateApplicationModelTask`, a shared task type in `gradle-model`
with a required launch-mode input and a launch-mode-derived output convention.

Commit `02a4622902f` (`Gradle: Model extension generated application model
inputs`) moved the modeled
`QuarkusApplicationModelTask` contract into `gradle-model`, made
`GenerateApplicationModelTask` extend it, and shared task wiring through
`ApplicationModelTaskConfigurator`. The extension-plugin generated model task
now uses modeled task inputs instead of calling
`ToolingUtils.create(Project, LaunchMode)` from its task action.

The current follow-up slice extracted Project-free model assembly helpers into
`ApplicationModelBuilderSupport`, so the live tooling builder and modeled task
path now share extension descriptor processing, direct file dependency
modeling, source output path collection, and dependency-walk flag helpers.

The application plugin now wires `QuarkusGoOffline` to the existing generated
`NORMAL`, `DEVELOPMENT`, and `TEST` application-model task outputs. The offline
task no longer calls `ToolingUtils.create(...)` or the Quarkus extension during
execution.

The dev-mode tasks now also consume generated model outputs. `quarkusDev` and
`quarkusRemoteDev` use the generated `DEVELOPMENT` model as their primary model
and the generated `TEST` model for test bootstrap. `quarkusTest` uses the
generated `TEST` model for both inputs.

The remaining application-plugin task-execution consumers, `QuarkusInfo` and
`QuarkusUpdate`, now consume the generated `NORMAL` model. The live
`QuarkusPluginExtension.getApplicationModel(...)` methods remain only as
deprecated compatibility API for callers that intentionally need in-memory live
model construction.

## Goal

Make Gradle application-model generation a modeled, launch-mode-aware task
contract that can be wired by providers instead of live `Project` access during
task execution.

The near-term target is the extension plugin's deployment-test path. The design
should not be `TEST`-only, because Gradle plugin code still has direct
`ToolingUtils.create(...)` call sites for application models in multiple launch
modes.

## Current State

- `GenerateApplicationModelTask` produces the `TEST` model at
  `build/quarkus/application-model/quarkus-app-test-model.dat`.
- Deployment `Test` tasks depend on that task and receive
  `BootstrapConstants.SERIALIZED_TEST_APP_MODEL` from the task output provider.
- The generated model task no longer reports configuration-cache serialization
  problems from its own implementation in the focused TestKit scenario.
- A local probe of `:deployment:test --configuration-cache` still exposes other
  extension-plugin blockers, including `ExtensionDescriptorTask` and
  cross-project setup. This plan should not claim full extension-plugin
  configuration-cache or isolated-projects compatibility by itself.

## Open Questions

- Can the lower-level model assembly in `QuarkusApplicationModelTask` and
  `GradleApplicationModelBuilder.buildAll(...)` be extracted without changing
  tooling model behavior?
- Which remaining `ToolingUtils.create(...)` call sites are tooling/user API
  paths that should keep live in-memory model construction instead of generated
  task outputs?
- Which blockers must be fixed first to make a two-run
  `:deployment:test --configuration-cache` assertion meaningful?

## Required Inspection

1. Dependency boundaries:
   - Inspect `devtools/gradle/gradle-extension-plugin/build.gradle*`.
   - Confirm whether it can depend on task types from
     `gradle-application-plugin`, or whether shared code must live in
     `gradle-model` or a new support package.
   - Avoid introducing an application-plugin dependency cycle.

2. Existing application-model task:
   - Inspect `QuarkusApplicationModelTask` inputs, nested classpath snapshots,
     declared-dependency handling, and output contract.
   - Inspect `QuarkusPlugin.configureApplicationModelTask(...)` and identify
     which pieces are generic vs application-plugin-specific.
   - Check whether extension deployment projects have equivalent classpath,
     project descriptor, platform, and declared-dependency inputs at task
     registration time.

3. Call-site inventory:
   - Inventory direct `ToolingUtils.create(...)` calls under
     `devtools/gradle`.
   - Classify each as task execution, task configuration, tooling API,
     extension DSL/API, dev-mode/runtime path, or test-only path.
   - Record launch mode and consumer: serialized model file, in-memory model,
     worker input, test system property, or JVM argument.

4. Blocker isolation:
   - Run or reproduce `:deployment:test --configuration-cache` on the TestKit
     extension fixture.
   - Separate failures caused by model generation from failures caused by
     `ExtensionDescriptorTask`, `P1-EP-01` cross-project setup, or shared
     `gradle-model` behavior.
   - Use this evidence to decide what can be asserted in the next PR.

## Candidate Target Contract

Introduce a launch-mode-aware generated-model task or task family with:

- `@Input Property<LaunchMode>` or one task instance per launch mode.
- Mode-specific output file:
  - `quarkus-app-model.dat` for `NORMAL`
  - `quarkus-app-dev-model.dat` for `DEVELOPMENT`
  - `quarkus-app-test-model.dat` for `TEST`
- Modeled inputs equivalent to application-model construction:
  - project descriptor
  - runtime/app classpath
  - deployment classpath
  - platform/import metadata
  - compile-only classpath when relevant
  - declared-dependency collector state when enabled
  - launch mode
- No task action access to `Project`, `Configuration`, `SourceSet`, or other
  live Gradle model objects.

Prefer reusing `QuarkusApplicationModelTask` if dependency boundaries and input
semantics permit it. If reuse is not viable, build an extension-plugin task with
parallel inputs and an intentional convergence path.

## Proposed Phases

### `P1-EP-02B`: Inspection and Reproducer Boundary

Status: completed locally as an inspection/doc step.

Deliverables:

- Call-site inventory for `ToolingUtils.create(...)`.
- Feasibility note: reuse `QuarkusApplicationModelTask`, extract shared wiring,
  or create a parallel extension-plugin task.
- Focused TestKit scenario documenting current `:deployment:test
  --configuration-cache` blockers.

This phase may be documentation-only if the blocker split is not yet clean
enough for a committed failing/passing test.

Inspection results:

- Directly reusing `QuarkusApplicationModelTask` from the extension plugin is
  not a good module boundary. `gradle-extension-plugin` and
  `gradle-application-plugin` both depend on `gradle-model` through the shared
  `io.quarkus.devtools.gradle-plugin` convention; they do not depend on each
  other. Pulling an application-plugin task type into the extension plugin
  would couple the plugin artifacts in the wrong direction. Shared model-task
  code should move to `gradle-model` or to a new shared support package if
  reuse is desired.
- `QuarkusApplicationModelTask` is reusable in concept, but not as-is as a
  full compatibility fix. Its wiring in `QuarkusPlugin.configureApplicationModelTask(...)`
  depends on `ProjectDescriptorBuilder.buildForApp(project)`,
  `ApplicationDeploymentClasspathBuilder`, `DependencyDataCollector`, and
  configuration-backed `QuarkusResolvedClasspath`/`QuarkusPlatformInfo`
  properties. Some of those inputs are already modeled enough for task
  execution, while others still originate from live `Project`, `SourceSet`,
  `Jar`, `Test`, and `Configuration` state during configuration.
- `GradleApplicationModelBuilder.buildAll(...)` remains the live-Gradle-model
  entry point used by tooling callers through `ToolingUtils.create(...)`.
  It should not become task-oriented itself, because tooling callers still need
  to build an in-memory model from a live `Project`. However, it and the modeled
  task path should share the lower-level model assembly logic once both have
  collected equivalent project descriptor, classpath, platform, compile-only,
  declared-dependency, and launch-mode inputs.
- `ProjectDescriptorBuilder.buildForApp(project)` is a separate modeling
  problem. It uses `afterEvaluate`, `SourceSetContainer`, `Jar` tasks, and
  `Test` tasks to populate source/resource metadata. A launch-mode-aware
  generated-model task cannot be considered fully isolated-projects compatible
  until this descriptor contract is also modeled or replaced.
- Current direct `ToolingUtils.create(...)` call sites under `devtools/gradle`
  are:
  - `GenerateApplicationModelTask`: task execution, currently wired for
    `TEST` in the extension plugin, serialized model output. P1-EP-02C removed
    its live-`Project` task-action dependency.
  - `QuarkusPluginExtension.getApplicationModel(...)`: extension/API access,
    mode-dependent in-memory model. Treat this as API/tooling behavior, not a
    generated-model task replacement by default.
  - `AppModelGradleResolver.resolveModel(...)`: resolver/tooling API path,
    mode-dependent in-memory model. Treat separately from task execution.
  - `QuarkusGoOffline.resolveAllModels()`: task execution, resolves `NORMAL`,
    `DEVELOPMENT`, and `TEST`. This is a candidate for generated model
    providers, but it belongs to the application plugin and should be handled
    after the shared contract exists.
  - `QuarkusDev`: task execution/interactive dev mode. It uses `DEVELOPMENT`
    and `TEST` models and serializes them into JVM arguments. It is a candidate
    for launch-mode-aware generated model providers, but likely needs separate
    treatment because dev mode is intentionally interactive and not a build
    cache target.
- A one-off `:deployment:test --configuration-cache` fixture against the local
  composite plugin build still discards the configuration cache. The distinct
  blockers observed were:
  - `GenerateApplicationModelTask` serialized a live `DefaultProject` before
    P1-EP-02C.
  - Executing `GenerateApplicationModelTask` triggered Gradle's `Task.project`
    execution-time violation before P1-EP-02C.
  - `ExtensionDescriptorTask` still serializes a live
    `DefaultResolvableConfiguration`.
  - `ExtensionDescriptorTask` still serializes a live `DefaultProject`.
  This means a P1-EP-02 implementation can prove that its own generator no
  longer causes configuration-cache problems, but a full
  `:deployment:test --configuration-cache` reuse assertion also depends on
  descriptor-task and cross-project setup work.

Conclusion:

- Do not create a dependency from `gradle-extension-plugin` to
  `gradle-application-plugin`.
- P1-EP-02C moved `QuarkusApplicationModelTask` into `gradle-model`, kept the
  application-plugin API surface stable, and made the extension plugin use the
  same modeled task contract through `GenerateApplicationModelTask`.
- Treat full extraction of common model assembly from
  `GradleApplicationModelBuilder.buildAll(...)` as a follow-up after the
  generated-task modeling is reviewed.
- Keep `ToolingUtils.create(...)` API/resolver call sites out of this PR unless
  they are task execution paths that need serialized model artifacts.

### `P1-EP-02C`: Launch-Mode-Aware Generated Model Task

Status: fixed locally by `02a4622902f`.

Deliverables:

- Replace `GenerateApplicationModelTask` live-`Project` implementation with
  modeled inputs.
- Move the reusable modeled task implementation to `gradle-model` so both
  Gradle plugins can share it without depending on each other.
- Share task input wiring through `ApplicationModelTaskConfigurator`.
- Keep deployment `Test` wired from the `TEST` model output.
- Preserve the current behavior of the serialized test application model.
- Do not expand to unrelated consumers unless needed to avoid duplication.

Verification:

- Deployment test fixture still passes.
- Generated model task can run without configuration-cache serialization
  problems from its own implementation.
- If other blockers still prevent full `:deployment:test --configuration-cache`
  reuse, document them precisely instead of overclaiming.

Implemented result:

- `QuarkusApplicationModelTask` moved from `gradle-application-plugin` to
  `gradle-model` with its package/API preserved.
- `GenerateApplicationModelTask` now extends `QuarkusApplicationModelTask` and
  only adds the required launch-mode constructor, task-name helper, and
  launch-mode-derived output convention.
- `ApplicationModelTaskConfigurator` centralizes the modeled task wiring
  previously local to `QuarkusPlugin`.
- `QuarkusExtensionPlugin` configures the deployment `TEST` model task with
  `ProjectDescriptorBuilder`, `ApplicationDeploymentClasspathBuilder`, and
  `DependencyDataCollector` providers instead of relying on
  `ToolingUtils.create(...)` during task execution.
- A focused TestKit check verifies the generated model task no longer appears
  in configuration-cache problem output. Full `:deployment:test
  --configuration-cache` reuse is still blocked by `ExtensionDescriptorTask`.

Deferred from this phase:

- Extracting the common lower-level model assembly from
  `GradleApplicationModelBuilder.buildAll(...)` and
  `QuarkusApplicationModelTask` remains follow-up work. This should be handled
  separately because it changes the tooling model path as well as the task path.

### `P1-EP-02C2`: Shared Project-Free Model Assembly Helpers

Status: fixed locally by `5f75558a2fa`.

Deliverables:

- Extract helper logic that does not depend on live Gradle model objects from
  `GradleApplicationModelBuilder` and `QuarkusApplicationModelTask`.
- Keep `GradleApplicationModelBuilder` as the live `Project`/tooling adapter.
- Keep generated model tasks as provider/task-input adapters.
- Avoid changing graph traversal or project descriptor modeling in this slice.

Implemented result:

- Added `ApplicationModelBuilderSupport` in `gradle-model`.
- Shared extension descriptor processing for resolved Quarkus runtime
  dependencies.
- Shared direct file dependency modeling for file dependencies outside the
  resolved artifact graph.
- Shared source output path collection and dependency-walk flag helpers.

Verification:

- `./gradlew --no-scan :gradle-model:compileJava :gradle-application-plugin:compileJava :gradle-extension-plugin:compileJava`
- `./gradlew --no-scan :gradle-model:test`
- `./gradlew --no-scan :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.deploymentTestsShouldUseGeneratedApplicationModel --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest.generatedApplicationModelTaskShouldNotReportConfigurationCacheProblems`
- `./gradlew --no-scan :gradle-application-plugin:test --tests io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest`

Deferred from this phase:

- Full graph traversal and dependency assembly are still implemented separately
  by the live tooling builder and the modeled task path. Extract that only once
  both adapters expose equivalent collected inputs and the behavior change can
  be covered directly.

### `P1-EP-02D`: Broader Launch-Mode Consumers

Status: fixed locally for the known task-execution consumers.

Deliverables:

- Replace eligible task-execution `ToolingUtils.create(...)` call sites for
  `NORMAL`, `DEVELOPMENT`, and `TEST` with generated model providers.
- Keep interactive or tooling API paths separate if they are intentionally
  in-memory and not task-cache/configuration-cache candidates.

Verification:

- Mode-specific tests for any newly wired consumer.
- No new dependency cycles between generated model tasks and descriptor,
  validation, code generation, or build tasks.

Implemented result:

- `QuarkusGoOffline` now has three serialized model file inputs for
  `NORMAL`, `DEVELOPMENT`, and `TEST`.
- The task is wired to the existing `quarkusGenerateAppModel`,
  `quarkusGenerateDevAppModel`, and `quarkusGenerateTestAppModel` providers.
- The task action deserializes those files to validate the generated models
  and no longer reaches `extension().getApplicationModel(...)`.
- `TasksConfigurationCacheCompatibilityTest` now covers `quarkusGoOffline`
  in both regular configuration-cache and isolated-projects matrices.
- `QuarkusDev` now has generated model file inputs for its primary model and
  test model.
- `quarkusDev` and `quarkusRemoteDev` are wired to `DEVELOPMENT` plus `TEST`
  model providers. `quarkusTest` is wired to the `TEST` model provider for
  both inputs.
- `QuarkusDev.newLauncher()` deserializes the generated primary model for the
  in-memory setup it still needs and passes generated model file paths directly
  as the serialized app-model JVM arguments.
- `QuarkusInfo` and `QuarkusUpdate` now consume the generated `NORMAL`
  application model instead of calling `QuarkusPluginExtension.getApplicationModel()`
  during task execution.
- `QuarkusPluginExtension.getApplicationModel()` and
  `getApplicationModel(LaunchMode)` are deprecated for removal as compatibility
  API for callers that still need live model construction.

Verification:

- `./gradlew --no-scan :gradle-application-plugin:compileJava :gradle-application-plugin:compileTestJava`
- `./gradlew --no-scan :gradle-application-plugin:test --tests io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest`
- `./gradlew --no-scan :gradle-application-plugin:test --tests io.quarkus.gradle.QuarkusPluginTest`

Remaining P1-EP-02D work:

- No known task-execution `ToolingUtils.create(...)` consumers remain under
  P1-EP-02.
- Leave `AppModelGradleResolver.resolveModel(...)` and
  `QuarkusPluginExtension.getApplicationModel(...)` as tooling/API
  compatibility paths unless a concrete task-execution consumer requires
  generated model providers.

### `P1-EP-02E`: Cacheability Decision

Status: decided for now by `471875ecaf1`.

Deliverables:

- Generated model tasks remain `@DisableCachingByDefault` because serialized
  application models contain resolved file-system paths and are not relocatable.
- Do not make the tasks cacheable without either a relocatable serialized model
  contract or a deliberately complete path-locality fingerprint plus
  invalidation coverage for classpath, project descriptor, platform,
  declared-dependency, launch-mode, and path-bearing model changes.

Coordinate with `task-cacheability-follow-up.md`.

## Suggested Next Step

P1-EP-02 has no currently identified implementation work left for
task-execution consumers. Proceed to the next non-P1-EP-02 finding:

- Generated model tasks remain `@DisableCachingByDefault`; revisit only in the
  broader task-cacheability pass if the serialized model contract changes.
- Do not require full `:deployment:test --configuration-cache` reuse until the
  `ExtensionDescriptorTask` and cross-project setup blockers are removed.
