# P1-AP-02A Investigation Results

Status: initial delegated investigation complete
Last reviewed: 2026-07-06

## Purpose

Consolidate the read-only P1-AP-02A investigation before implementing the new
Gradle build-shape task hierarchy.

Source design note:
[P1-AP-02 Build Shape Split Design](../../design.md)

## Executive Summary

The current Gradle application build is already split by artifact fragments, but
not by stable semantic build shape:

- `quarkusBuildAppModel` / `quarkusGenerateAppModel` produce serialized
  application models.
- `quarkusDependenciesBuild` produces dependency fragments under
  `build/quarkus-build/dep`.
- `quarkusAppPartsBuild` runs augmentation into the project `build/` directory,
  copies selected outputs into `build/quarkus-build/gen`, and extracts app
  fragments into `build/quarkus-build/app`.
- `quarkusBuild` remains the public compatibility/finalization task that
  assembles legacy output locations or falls back to a full Quarkus build for
  shapes such as `uber-jar`, `mutable-jar`, and native sources.

The main hidden-state paths are confirmed:

- `imageBuild` and `imagePush` mutate `ForcedPropertieBuildService` during task
  execution and rely on `finalizedBy(quarkusBuild)`.
- `buildNative` and `testNative` mutate `quarkusExt.nativeBuild` from
  `TaskExecutionGraph.whenReady`.
- `quarkusBuild`, `quarkusAppPartsBuild`, and related old hierarchy tasks read
  dynamic semantic state through broad shared task wiring.

The augmentation investigation found an important opportunity: Quarkus
augmentation can technically target an isolated directory because
`QuarkusBootstrap.Builder.setTargetDirectory(Path)` feeds
`BuildSystemTargetBuildItem`. The blocker is compatibility with existing
Gradle users expecting artifacts under `build/`, not an obvious core API
limitation.

## Delegated Workstreams

### Task Graph And Wiring

Key findings:

- Task registration and wiring live mostly in
  [QuarkusPlugin.java:159](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java).
- `quarkusBuild` depends on `quarkusDependenciesBuild` and
  `quarkusAppPartsBuild`.
- `imageBuild`, `imagePush`, and `deploy` currently use `finalizedBy(quarkusBuild)`.
- `testNative` depends on `quarkusBuild`.
- `buildNative` is a deprecated `DefaultTask` finalized by `quarkusBuild`.
- Native alias behavior is selected by `configureBuildNativeTask()` mutating
  extension state from `TaskExecutionGraph.whenReady`.
- `quarkusRun` extends `QuarkusBuildTask`, depends on `quarkusBuild`, then
  performs RUN-mode bootstrap.
- `quarkusDev`, `quarkusRemoteDev`, and `quarkusTest` are separate dev-mode
  launch tasks using serialized app models and dev/test classpath/source state.

Implication:
The new hierarchy should avoid using `finalizedBy` plus shared state as the
normal build-shape selection mechanism. It should keep `quarkusBuild` as a
compatibility command while adding explicit named-output tasks with direct
dependencies and structured inputs.

### Output Layout And Package Shapes

Key findings:

- `gen/` is shape-specific: it is a copied raw Quarkus build result for the
  selected package/native/image semantics.
- `app/` is shape-specific: fast-jar-like outputs exclude `quarkus-app/lib`,
  legacy-jar keeps modified jars differently, and native outputs may include
  native runner/native-image support files.
- `dep/` is different: for fast-jar, AOT, and non-sources native builds it is
  shareable for the same application model and effective dependency filtering /
  class-loading config. Legacy-jar needs a different layout. Mutable, uber, and
  native-sources currently do not use this fragment.
- `quarkusBuild` materializes final compatibility outputs under `build/`, such
  as `build/quarkus-app`, `build/lib`, runner files, native runner,
  `native-sources`, native-image source directories, and
  `build/quarkus-artifact.properties`.
- Cleanup is intentional compatibility behavior for shared legacy output roots.

Relevant code:

- [QuarkusBuildTask.java:52](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildTask.java)
- [QuarkusBuildTask.java:242](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildTask.java)
- [QuarkusBuildCacheableAppParts.java:29](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildCacheableAppParts.java)
- [QuarkusBuildDependencies.java:52](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildDependencies.java)
- [QuarkusBuild.java:100](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuild.java)

### Existing Tests And User-Facing Use Cases

Key findings:

- Existing coverage is strong for broad package/cache behavior, but too much of
  it is TestKit or heavy integration-test coverage.
- Existing unit coverage covers task registration, additional forced property
  normalization, service snapshot semantics, and image builder marker behavior.
- Existing TestKit coverage covers cache/up-to-date behavior and
  configuration-cache-compatible task lists.
- Existing integration coverage covers package output behavior, native aliases,
  AOT image behavior, `quarkusRun`, and broad dev-mode flows.
- Major missing coverage:
  - no active end-to-end proof that `imageBuild` / `imagePush` forced
    properties are consumed by the finalized `quarkusBuild`;
  - no focused successful `imagePush` behavior coverage;
  - no direct `quarkusRemoteDev` coverage found;
  - native/image hidden-state behavior lacks cheap unit, `ProjectBuilder`, or
    focused TestKit task-graph/input gates;
  - stale-output shape-switch coverage is mostly indirect;
  - AOT image has heavy Docker IT coverage but little unit/TestKit input
    modeling.

Representative tests:

- [QuarkusPluginTest.java:34](../../../../../../../devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/QuarkusPluginTest.java)
- [AdditionalForcedPropertiesTest.java:11](../../../../../../../devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/AdditionalForcedPropertiesTest.java)
- [ForcedPropertieBuildServiceTest.java:40](../../../../../../../devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/services/ForcedPropertieBuildServiceTest.java)
- [ImageCheckRequirementsTaskTest.java:86](../../../../../../../devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/ImageCheckRequirementsTaskTest.java)
- [CachingTest.java:69](../../../../../../../devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/CachingTest.java)
- [TasksConfigurationCacheCompatibilityTest.java:81](../../../../../../../devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/TasksConfigurationCacheCompatibilityTest.java)
- [ImageTasksWithConfigurationCacheTest.java:43](../../../../../../../integration-tests/gradle/src/test/java/io/quarkus/gradle/ImageTasksWithConfigurationCacheTest.java)
- [BasicJavaNativeBuildIT.java:57](../../../../../../../integration-tests/gradle/src/test/java/io/quarkus/gradle/nativeimage/BasicJavaNativeBuildIT.java)
- [NativeIntegrationTestIT.java:170](../../../../../../../integration-tests/gradle/src/test/java/io/quarkus/gradle/nativeimage/NativeIntegrationTestIT.java)
- [JibAotTest.java:110](../../../../../../../integration-tests/gradle/src/test/java/io/quarkus/gradle/JibAotTest.java)

### Quarkus Augmentation, Run, And Dev-Mode Contracts

Key findings:

- Package builds use PROD augmentation through
  `QuarkusBootstrap -> CuratedApplication -> AugmentAction.createProductionApplication()`.
- Gradle currently targets the project `build/` directory deliberately because
  existing builds may rely on artifacts appearing there.
- RUN mode is separate: `quarkusRun` depends on `quarkusBuild`, then bootstraps
  with `QuarkusBootstrap.Mode.RUN` and uses
  `StartDevServicesAndRunCommandHandler`.
- DEV mode is not a package build: `quarkusDev`, `quarkusRemoteDev`, and
  `quarkusTest` build `DevModeCommandLine` from serialized app models, Gradle
  source/output paths, dev-mode dependencies, and module metadata.
- Quarkus core produces primary package artifacts and metadata, but Gradle owns
  the current `gen/app/dep` splitting and compatibility materialization.
- Current result APIs are not a full output manifest. `AugmentResult` exposes
  primary results, but Gradle still needs directory contents and split rules.
  Container-image metadata details are captured separately in
  [P1-AP-02B AugmentResult Image Metadata Investigation](../../phase-b-augment-result-image-metadata.md).
- `AugmentResult` exists after augmentation, so it cannot be the sole source of
  Gradle managed `@OutputFile` and `@OutputDirectory` declarations. Named
  output tasks must declare expected outputs from descriptor/planner state
  before execution and use `AugmentResult` afterward for validation, receipts,
  and compatibility materialization.
- For fast-jar, mutable-jar, uber-jar, legacy-jar, and native executable
  outputs, `AugmentResult` has the primary artifact facts we need: jar path,
  original artifact, library directory, mutable/classifier flags, native
  executable path, and artifact metadata. It is still not a complete layout
  manifest for support directories and compatibility copies.
- For native-sources, the current result metadata is insufficient as a complete
  managed-output description because the artifact path can refer to the native
  source jar path rather than the final copied `native-sources` directory.
  Keep native-sources layout inference isolated until Quarkus exposes a richer
  build-tool output manifest.

Relevant code:

- [QuarkusBuildTask.java:225](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildTask.java)
- [BuildWorker.java:58](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/BuildWorker.java)
- [QuarkusWorker.java:79](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/QuarkusWorker.java)
- [QuarkusBootstrap.java:455](../../../../../../../independent-projects/bootstrap/core/src/main/java/io/quarkus/bootstrap/app/QuarkusBootstrap.java)
- [AugmentActionImpl.java:180](../../../../../../../core/deployment/src/main/java/io/quarkus/runner/bootstrap/AugmentActionImpl.java)
- [JarResultBuildStep.java:68](../../../../../../../core/deployment/src/main/java/io/quarkus/deployment/pkg/steps/JarResultBuildStep.java)
- [NativeImageBuildStep.java:123](../../../../../../../core/deployment/src/main/java/io/quarkus/deployment/pkg/steps/NativeImageBuildStep.java)
- [QuarkusRun.java:71](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusRun.java)
- [QuarkusDev.java:360](../../../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusDev.java)

## Task And Mode Matrix

| Task / Mode | App Model Input | Augmentation / Launch Mode | Package / Build Output | `gen/` / `app/` / `dep/` Use | Legacy Output Use | Hidden Inputs / Shared Mutations | Cache / Up-to-date / CC Status | Tests / Missing Coverage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `quarkusBuild` | `quarkusBuildAppModel`, NORMAL | Package build via worker when fallback needed | Final jar/native/app output | Assembles from `app` + `dep`, or full `gen` fallback | Yes: `build/quarkus-app`, runner, native runner, artifact props | Reads `ForcedPropertieBuildService`; native aliases affect extension input | `@CacheableTask`; config-cache compatible; cache disabled unless `cacheLargeArtifacts` | Cache coverage good; image/native alias graph coverage weak |
| `quarkusAppPartsBuild` | `quarkusBuildAppModel`, NORMAL | Package build via worker | `build/quarkus-build/app` | Writes `gen`, syncs app fragments | Local-state cleanup touches legacy outputs | Reads same hidden forced service | Cacheable for fast/legacy/AOT by default; CC compatible | Cache coverage present |
| `quarkusDependenciesBuild` | `quarkusBuildAppModel`, NORMAL | No augmentation; dependency extraction | `build/quarkus-build/dep` | Writes dependency fragments | No final assembly | Ignores additional forced service in effective config | Never build-cacheable; up-to-date only; CC compatible | Up-to-date coverage present; filtering could use cheaper tests |
| `imageBuild` | Normal app model plus image check app model | No build itself; primes finalizer | None directly | Finalizes `quarkusBuild` | Through finalizer | Writes image build + builder into service | Non-cacheable; config-cache reuse covered by IT | No direct proof finalizer sees image props |
| `imagePush` | Normal app model plus image check app model | No build itself; primes finalizer | None directly | Finalizes `quarkusBuild` | Through finalizer | Writes image build + push into service; does not write builder | Non-cacheable | Only failure-path coverage found |
| `buildNative` | None directly | Deprecated alias selecting native build | Through finalized `quarkusBuild` | Finalizer path | Native runner | `whenReady` sets `nativeBuild` | Deprecated `DefaultTask`; no declared semantic input | Heavy native ITs only |
| `testNative` | Test task plus finalized/dependent build path | Native test execution | Native runner and test execution | Depends on `quarkusBuild` | Native runner path | Same `whenReady` native mutation | Test task; not in CC task list | Heavy native ITs only |
| `quarkusRun` | `quarkusBuildAppModel`, NORMAL | `QuarkusBootstrap.Mode.RUN` | Starts app command | Depends on `quarkusBuild` | Consumes packaged target dir | `quarkus.run.target`; runtime process | Non-cacheable; not CC-compatible | Run IT coverage exists; cheap wiring/input tests missing |
| `quarkusDev` | Dev + test app models | DEVELOPMENT dev mode | Launches dev process | No package fragments | No legacy package output | Captures `Project`; resolves dev deps at execution | Non-cacheable; not CC-compatible | Broad devmode IT coverage |
| `quarkusRemoteDev` | Dev + test app models | DEVELOPMENT with remote dev | Launches remote-dev process | No package fragments | No legacy package output | Inherits dev hidden runtime behavior | Non-cacheable | Direct coverage not found |
| `quarkusTest` | Test + test app models | TEST / continuous test | Launches isolated test mode | No package fragments | No legacy package output | Inherits dev hidden runtime behavior | Non-cacheable | Continuous-testing IT coverage; cheap wiring tests missing |
| `deploy` | `quarkusBuildAppModel`, NORMAL | Custom deploy augmentation | Deploy command or finalizer build | Finalizes `quarkusBuild` | May skip finalizer via forced property | Mutates extension forced properties and system property | Explicitly non-cacheable / non-compatible | Config-cache non-failure only; behavior thin |
| `buildAotEnhancedImage` | `quarkusBuildAppModel`, NORMAL | Custom AOT image worker | Enhanced container image | No app/dep split | Reads metadata under `build/` | Reads undeclared files and external AOT path | Non-cacheable / non-compatible | Docker IT exists; unit/TestKit input modeling missing |

## Must Preserve

- `quarkusBuild` remains a public executable task and public `QuarkusBuild`
  configuration surface for compatibility.
- `build` / `assemble` continue to reach `quarkusBuild`.
- `quarkusBuild` continues to materialize existing legacy outputs under
  `build/`.
- Fast-jar, AOT, legacy, native output layouts remain compatible for users,
  scripts, Dockerfiles, and tests.
- Dependency jars remain non-cacheable build outputs; app parts remain cacheable
  only for supported small-output shapes unless the user opts into large
  artifact caching.
- Package switching continues removing stale outputs from incompatible shapes.
- Image tasks continue validating installed image extensions, honoring
  `quarkus.container-image.builder`, and causing the final build to run with
  image build/push intent.
- Deprecated `buildNative` and `testNative` aliases continue selecting native
  build semantics until removal.
- `quarkusRun` continues depending on a completed production build and
  bootstrapping in RUN mode.
- `quarkusDev`, `quarkusRemoteDev`, and `quarkusTest` continue using dev/test
  serialized app models and remain dev-mode command-line flows rather than
  package-output split tasks.
- Future new dev/test tasks should use a launch-session base that is a sibling
  of the named build-output base, not a subclass of it. Launch variants should
  be modeled explicitly, for example `DEV`, `REMOTE_DEV`, and
  `CONTINUOUS_TEST`.
- Prefer explicit new continuous-test task names such as
  `quarkusContinuousTest` or `quarkus<LaunchName>ContinuousTest`, while keeping
  legacy `quarkusTest` as the compatibility task.
- Treat `quarkusIntTest` as Gradle `Test`-style integration testing, not as a
  Quarkus launch session. Prefer integrating new Quarkus integration-test
  behavior with Gradle JVM Test Suites through Quarkus-provided DSL such as
  `forQuarkusBuild("app")`.
- For Quarkus-owned built-in suites, such as native-test suites derived from
  named native outputs, Quarkus should register the suite and users should
  customize it with `testing.suites.named(...)`. Users should use
  `register(...)` only for extra user-owned suites. Example built-in suite name:
  `quarkusNative1NativeTest`.
- Do not require a `nativeTest { enabled = true }` switch for the default
  native-test suite. Registering the named native output is enough to create
  the matching Quarkus-owned suite/task, but it should not be wired into
  `check` by default.
- If built-in Quarkus suites require Gradle's `jvm-test-suite` infrastructure,
  the Quarkus application plugin should apply/configure it for those built-in
  suites instead of requiring users to apply it manually. User-defined extra
  suites remain explicit and attach with `forQuarkusBuild(...)`.

## Compatibility-Only Old Hierarchy Behavior

- Execution-time image/push mutation through `ForcedPropertieBuildService`.
- `TaskExecutionGraph.whenReady` mutation for deprecated native aliases.
- `quarkusBuild` as catch-all finalizer for image/deploy/native alias semantics.
- Full-build fallback in `quarkusBuild` for mutable, uber, and native-sources
  shapes.
- Running augmentation directly into Gradle project `build/` as the internal
  build-shape contract.
- Copy-filter inference from `build/` into `quarkus-build/gen/app/dep`.
- Broad cleanup of shared legacy roots such as `build/quarkus-app`,
  `build/lib`, runner files, `native-sources`, and
  `quarkus-artifact.properties`.
- `Deploy` mutating forced-property conventions and JVM system properties.

## New Hierarchy Requirements

- Use a parallel task hierarchy rather than rewriting the old compatibility
  hierarchy in place.
- Put new task classes in `io.quarkus.gradle.tasks.application` and use the
  `QuarkusApplication*` class-name prefix. Keep public Gradle task names and
  Java task class names separate; avoid permanent `NewQuarkus*` names and avoid
  extending the overloaded legacy `QuarkusBuild*` naming family.
- Prefer a `quarkus.builds` named-output model over a fixed public task matrix.
  Each registered build output owns one output type and derives tasks such as
  `quarkusAppBuild`, `quarkusNative1Build`, `quarkusAppImageBuild`, and
  `quarkusNative1NativeTest`.
- Named outputs should make it possible to build multiple package/native shapes
  in one Gradle invocation without output clobbering.
- Registered output names are global within `quarkus.builds`; descriptor-name
  uniqueness and derived-task-name collision checks should operate on
  registered descriptors without forcing all tasks to be configured, and must
  fail before any output-producing action runs.
- New named outputs should default to
  `build/quarkus-builds/<registered-name>/`. Keep `build/quarkus-build/` for
  legacy tasks and reusable/legacy-compatible dependency fragments such as
  `dep/`.
- Container-image configuration should live on the registered output that owns
  the image. Derived tasks such as `quarkusAppImageBuild` and
  `quarkusAppImagePush` select build versus push intent; the image DSL should
  not include `build = true` or `push = true` flags.
- Image builder selection should use an enum rather than a free-form string.
- If multiple selected image-producing tasks resolve to the same effective
  image reference, fail before any image-producing action runs unless the tasks
  are part of one explicit ordered owner/flow such as an AOT-enhanced image
  replacing the base image for the same registered output.
- AOT-enhanced image support should be modeled as an optional nested feature on
  the registered output's image configuration. The AOT file should be a
  `RegularFileProperty`, with optional producer task wiring through
  `producedBy(...)` or a helper such as `aotFileFrom(producer, fileProvider)`
  when the producer does not expose a typed output property.
- The default AOT-enhanced image target should be the parent image target; a
  separate enhanced tag/reference is optional only when users need base and
  enhanced images to coexist.
- Keep the existing global `buildAotEnhancedImage` task unchanged as legacy
  behavior. New derived tasks such as `quarkusAppAotEnhancedImageBuild` and
  `quarkusAppAotEnhancedImagePush` should fail clearly when required modeled
  inputs are missing instead of silently scanning/skipping a global metadata
  file.
- Deploy should remain a legacy global task initially. New deploy support should
  be modeled as a nested named deployment container under a registered output,
  deriving task names such as `quarkusAppDeployToDev` and
  `quarkusAppDeployToProd`.
- Do not add single-deployment `quarkusAppDeploy` sugar initially.
- Output-specific deploy tasks should mirror legacy deploy behavior
  functionally: select/validate deploy target, validate required deployer and
  image extensions/configuration, depend on the named output build/image task
  required by the deployer, default to building or referencing images rather
  than pushing them, call the Quarkus deploy command path, avoid JVM-global
  system-property mutation, and remain non-cacheable.
- Keep legacy and new task registration/configuration paths visibly separated
  in `QuarkusPlugin`, so removing old code later is localized.
- Consider a small scoped registration context object for `QuarkusPlugin` to
  hold the extension, app-model task providers, classpath builders, shared
  services, custom filesystem service, and task providers that are currently
  threaded through long setup methods.
- Keep that context scoped to this workstream; do not turn it into a broad
  plugin-framework refactor.
- Model normal/native/image/image-push/package-shape intent as structured task
  inputs.
- Prefer isolated augmentation output roots for new named-output tasks.
- Treat `gen/` and `app/` as shape-owned outputs.
- Treat `dep/` as a reusable dependency fragment where dependency layout and
  filtering semantics match.
- Keep legacy `build/` materialization as an explicit compatibility step for
  `quarkusBuild` or a deliberately named materialization task.
- Do not sync new explicit named-output task outputs into `build/quarkus-app/`,
  root runner files, or `build/quarkus-artifact.properties` by default.
- Do not require removing `ForcedPropertieBuildService` from the old hierarchy
  before adding the new hierarchy. The old tasks can keep their current
  non-Gradle-friendly behavior and warnings.
- Keep task classes thin and move output/fragment/materialization decisions to
  unit-testable planners.
- Do not force `quarkusRun` / dev-mode flows into package-output or
  named-output tasks unless an explicit contract requires it. Treat
  `quarkusDev`, `quarkusRemoteDev`, and `quarkusTest` as long-lived launch
  sessions with their own task base.

## Post-Investigation Plan Adjustment

After reviewing naming and DSL options, the first implementation slice should
start with named-output model/planner code rather than a public fixed task
matrix:

- named output identity and task-name derivation;
- task-class/package naming for the new
  `io.quarkus.gradle.tasks.application.QuarkusApplication*` hierarchy;
- typed output definitions for fast jar, legacy jar, mutable jar, uber jar,
  native executable, native sources, and image-capable outputs;
- support both typed factory DSL methods and class-based registration where
  Gradle's managed model makes that practical;
- stable public task types with typed Gradle output properties for downstream
  task wiring;
- output layout, dependency-fragment, forced-property, compatibility
  materialization, and package-layout inference planners;
- reuse `build/quarkus-build/dep` for dependency fragments unless a shape needs
  a distinct dependency layout;
- image target and duplicate effective-image-reference planning, including
  same-reference allowance only for an explicit ordered owner/flow such as
  AOT-enhanced image replacement;
- AOT-enhanced image planning, including `aotFile` input plus both
  `producedBy(...)` and `aotFileFrom(...)` producer wiring;
- deployment descriptor and derived `DeployTo` task-name planning, without
  single-deployment `quarkusAppDeploy` sugar initially;
- launch-session continuous-test task-name planning;
- Gradle JVM Test Suite integration planning for `forQuarkusBuild(...)`;
- pure unit tests before `QuarkusPlugin` task registration changes.

Legacy-path cleanup and migration diagnostics should come later. The preferred
diagnostics model is a nested `quarkus.diagnostics` extension object with
`legacyTaskUsage = OFF | WARN | FAIL`, conventioned from a Gradle project
property. Keep diagnostics off by default in Quarkus 4.0 and plan to enable
legacy-task diagnostics at `WARN` by default in Quarkus 4.1 once replacements
exist. Legacy-task usage includes direct and transitive execution; mere task
registration is not usage.

## Augmentation And Tooling Opportunities

Quarkus already supports arbitrary augmentation target directories through
`QuarkusBootstrap.Builder.setTargetDirectory(Path)`. Named Gradle outputs can
use that existing capability; no Quarkus core change is needed for isolated
named-output roots.

### Small

- Consume existing `AugmentResult.getResults()`, `getJar()`, and
  `getNativeResult()` wherever the new named-output execution path already has
  the augmentation result available.
- Add a small Gradle-plugin-local helper around `AugmentResult`,
  `JarResult.libraryDir`, `JarResult.path`, native result, and artifact
  metadata to separate authoritative augmentation facts from inferred package
  layout rules.
- Clearly document the remaining filesystem/layout inference that exists only
  because current Quarkus result metadata is not a complete output manifest.
  Keep that inference isolated so a future Quarkus output manifest can replace
  it without spreading changes across task classes.

### Medium

- Add a Quarkus build-tool API that returns an explicit package output plan:
  primary artifacts, support dirs, dependency dirs, generated metadata, and
  compatibility materialization targets.
- Expose native-source output directories accurately; current native-source
  result metadata appears to point at the original source jar path even after
  copying into `native-sources`.
- Add richer `ArtifactResult` metadata for directory artifacts and support
  files, not just primary artifact path/type/metadata.

### Large / Future

- Build-tool-agnostic output manifest/plan across JVM jar, mutable jar, native,
  container image, SBOM/AppCDS/AOT, and extension-produced artifacts.
- Decouple Quarkus package layout generation from build-system compatibility
  materialization.
- Make package builders able to produce split app/dependency fragments natively
  instead of Gradle reconstructing them from full output.

## Test Gates

Prefer the cheapest test level that can prove the contract:

1. Pure unit tests for value objects, planners, validation, normalization, and
   output/deploy/image/AOT/materialization decisions.
2. Cheap Gradle `ProjectBuilder`-style tests for plugin registration,
   extension/model creation, task providers, conventions, and direct task
   relationships that do not need Gradle execution.
3. Gradle TestKit only for real Gradle execution contracts such as task graph
   execution, provider realization, configuration-cache reuse,
   up-to-date/cache behavior, and Kotlin/Groovy DSL interaction.
4. Heavy Gradle integration tests only for end-to-end Quarkus application build,
   container-image, native-image, dev-mode, or process behavior.

Do not cover a planner or registration contract only with TestKit or a heavy
integration test when a pure unit or `ProjectBuilder` test can prove it.

### Pure Unit Tests

- Named-output/output-layout planner for fast-jar, legacy, uber, mutable, AOT,
  native runner, native sources, and container metadata cases.
- Descriptor identity and task-name derivation tests, including duplicate
  descriptor names and normalized task-name collisions.
- Dependency-fragment planner for fast-jar-like versus legacy layouts.
- Forced-property planner for normal, native, image build, and image push
  semantics, including precedence with `quarkus.nativeArguments`.
- Image target planner tests, including builder enum mapping and duplicate
  effective image reference detection, with explicit ordered same-reference
  AOT-enhanced image flow allowed.
- AOT-enhanced image planner tests, including same-reference defaulting,
  optional enhanced tag override, `RegularFileProperty` input modeling, and
  both `producedBy(...)` and `aotFileFrom(...)` producer task wiring.
- Deployment descriptor planner tests, including `DeployTo` task-name
  derivation, deployment-name collision detection, absence of single-deployment
  sugar, deploy target selection, default no-push behavior, and required
  image/deployer validation.
- Launch-session planner tests for `quarkus<LaunchName>ContinuousTest` naming.
- JVM Test Suite integration tests proving Quarkus-owned suites are registered
  with deterministic names, users can customize them with `named(...)`, duplicate
  `register(...)` attempts fail clearly, and user-owned suites can attach to a
  selected named output with `forQuarkusBuild(...)`.
- Compatibility materialization planner for `build/quarkus-app`,
  `build/lib`, runner files, native runner, native-source directories, and
  `quarkus-artifact.properties`.

### Gradle ProjectBuilder Tests

- Plugin registration tests for the new `quarkus.builds` model without executing
  Gradle.
- Typed factory and class-based registration tests.
- Task provider and direct relationship tests for registered descriptors,
  registration types, `dependsOn`, `finalizedBy`, and ordering relationships.
- Convention propagation tests that can be inspected without executing tasks.
- Tests proving old and new `QuarkusPlugin` registration paths stay separated
  and can be reasoned about independently.

### Gradle TestKit Tests

- Matrix tests for named fast, legacy, uber, mutable, AOT, and native-sources
  outputs that assert exact `gen/app/dep/final` presence and absence.
- Shape-switch tests in one project without `clean`, proving stale legacy
  outputs are removed only by compatibility materialization.
- `imageBuild` and `imagePush` tests with fake/minimal image extension setup
  asserting final build receives image build/push properties as declared inputs
  in the new path.
- Configuration-cache reuse tests for new named-output tasks under normal and
  isolated project modes.
- Native alias compatibility tests that do not require a native toolchain,
  proving deprecated aliases map to intended native shape behavior.
- `quarkusRun`, `quarkusRemoteDev`, `quarkusTest`, `deploy`, and
  `buildAotEnhancedImage` TestKit tests only where task execution,
  configuration-cache behavior, or Gradle DSL behavior cannot be proven with
  unit or `ProjectBuilder` tests.
- Diagnostics-level tests for `buildNative`, `testNative`, image build/push
  shared-backend usage, and direct old `QuarkusBuild` API usage.

### Heavy Gradle Integration Tests

- Existing package smoke tests for fast-jar, legacy, uber, mutable, AOT, native
  sources, and native runner.
- Existing native alias tests for real native execution.
- `quarkusRun` test proving RUN-mode augmentation still sees the expected
  packaged output.
- Dev-mode and continuous-testing smoke tests proving the package split does
  not alter dev command-line contracts.
- Container image metadata/build test using a deterministic non-pushing builder
  path where possible.
- AOT image IT remains heavy/non-cacheable until metadata inputs/outputs are
  explicitly modeled.

## Gaps, Risks, And Unknowns

- `imageBuild` / `imagePush` have no active direct proof that forced properties
  are consumed by finalized `quarkusBuild`.
- `imagePush` has no focused success coverage; only missing-extension style
  coverage was found.
- `ImagePush` declares `builderName` input but does not read it. Verify whether
  push should force builder too.
- `runnerSuffix`, `runnerName`, and `outputDirectory` affect output paths but
  are currently `@Internal` on `QuarkusBuildTask`.
- `BuildAotEnhancedImage` reads `build/quarkus-container-it.properties`,
  `aot-file`, image names, Docker state, and external AOT path without modeled
  inputs.
- `deploy` mutates extension forced properties and JVM system properties and
  has limited semantic test coverage.
- `quarkusRemoteDev` direct coverage was not found.
- Configuration-cache/project-isolation compatible tasks are tested, but the
  current model still contains `afterEvaluate`, graph `whenReady`, broad task
  container/project-derived setup, and provider lambdas that should be kept out
  of the new cache-compatible hierarchy.
- Some extensions may write files relative to `OutputTargetBuildItem` or
  `BuildSystemTargetBuildItem`; isolated targets should work, but compatibility
  materialization must copy all externally expected files.
- Container-image outputs can be metadata-only with null paths, so they cannot
  drive file output snapshots by themselves.
- It is unknown whether every package extension contributes enough artifact
  metadata to build a complete output manifest without new build items.
