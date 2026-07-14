# P1-AP-02A Implementation Plan

Status: phase A complete; phase B ready
Last reviewed: 2026-07-07

## Objective

Implement the first safe slices of `P1-AP-02`: split graph-selected
package/native/image intent into an explicit named-output model for the Quarkus
Gradle application plugin, while keeping the legacy task hierarchy unchanged
until replacement paths and diagnostics exist.

Phase A is implemented: pure named-output planners, the `quarkus.builds` DSL
and skeleton task registration, and opt-in legacy-task diagnostics are present.
The next executable slice is `P1-AP-02B`: image build/push behavior for named
outputs. Do not delete or behaviorally replace the legacy hierarchy before
replacement execution paths are proven.

## Required Reading

Read these files before changing code:

1. `../../design.md`
2. `investigation-results.md`
3. `../../../phase-1-application-plugin.md`, finding `P1-AP-02`
4. `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
5. Existing task classes under
   `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/`

Apply the Quarkus project instructions and relevant Java/Gradle/testing rules
before code changes.

## Non-Goals

Do not do these in the first implementation slice:

- do not delete, rewrite, or behaviorally replace legacy `quarkusBuild`;
- do not deprecate the `quarkusBuild` task name;
- do not remove `ForcedPropertieBuildService`;
- do not change legacy `imageBuild`, `imagePush`, `buildNative`, `testNative`,
  `deploy`, `buildAotEnhancedImage`, `quarkusRun`, `quarkusDev`,
  `quarkusRemoteDev`, or `quarkusTest` behavior;
- do not wire new native-test suites into `check`;
- do not add `deploy { ... }` single-deployment sugar or `quarkusAppDeploy`;
- do not add a broad image overwrite/alias model;
- do not add user-facing docs yet beyond tracking the follow-up;
- do not use heavy TestKit or integration tests for contracts that can be
  proven with pure unit or cheap `ProjectBuilder` tests.

## Invariants

- Legacy tasks remain compatibility tasks until their replacements exist.
- New implementation classes use package
  `io.quarkus.gradle.tasks.application` and the `QuarkusApplication*` prefix.
- Public Gradle task names are derived from registered output names, for
  example `quarkusAppBuild`, `quarkusNative1Build`,
  `quarkusAppImageBuild`, and `quarkusNative1NativeTest`.
- Registered output names are global within `quarkus.builds`.
- New named outputs default to `build/quarkus-builds/<registered-name>/`.
- Legacy `build/quarkus-build/dep` may remain the reusable dependency-fragment
  location unless a shape needs a distinct dependency layout.
- New task types should expose stable typed Gradle outputs where applicable.
- Diagnostics remain opt-in and `OFF` by default for Quarkus 4.0.

## Test Strategy

Follow the test pyramid strictly:

1. Pure unit tests for value objects, planners, validation, normalization,
   output-layout decisions, forced-property planning, image/deploy/AOT
   descriptors, and compatibility materialization plans.
2. Cheap Gradle `ProjectBuilder` tests for plugin registration, extension/model
   object creation, task providers, conventions, and direct task relationships.
3. Gradle TestKit only for real Gradle execution contracts: task graph
   execution, provider realization boundaries, configuration-cache reuse,
   up-to-date/cache behavior, and Kotlin/Groovy DSL interaction.
4. Heavy `integration-tests/gradle` only for real Quarkus application build,
   container-image, native-image, dev-mode process, or end-to-end behavior.

Every higher-level test should have a reason. If a pure unit or
`ProjectBuilder` test can prove the contract, do not add only TestKit or heavy
integration coverage.

## Phase A1: Named Output Model And Planner Skeleton

This phase is the first implementation target.

### A1.1 Create Package Skeleton

Create the initial packages under:

```text
devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/
devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/model/
devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/planning/
```

Create matching unit-test packages under:

```text
devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/application/
devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/application/model/
devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/application/planning/
```

No task registration should happen in this step.

### A1.2 Model Output Identity

Add value objects for named output identity and output type.

Required concepts:

- raw registered name;
- normalized task-name segment;
- globally unique descriptor identity;
- output type: fast jar, legacy jar, mutable jar, uber jar, native executable,
  native sources, and image-capable output support;
- display label for diagnostics and error messages.

Suggested classes:

- `QuarkusApplicationBuildDescriptor`
- `QuarkusApplicationBuildName`
- `QuarkusApplicationBuildType`

Unit tests first:

- accepts simple names such as `app`, `native1`, `uber`;
- normalizes names such as `native-main` to task segment `NativeMain`;
- rejects empty names;
- rejects names that cannot be converted predictably;
- detects normalized collisions such as `native-main` and `nativeMain`;
- reports errors with both original names.

Stop condition:

- output-name identity and normalization are test-covered without Gradle task
  registration.

### A1.3 Plan Derived Task Names

Add a task-name planner.

Required derived task names:

- `quarkus<RegisteredName>Build`;
- `quarkus<RegisteredName>ImageBuild`;
- `quarkus<RegisteredName>ImagePush`;
- `quarkus<RegisteredName>AotEnhancedImageBuild`;
- `quarkus<RegisteredName>AotEnhancedImagePush`;
- `quarkus<RegisteredName>DeployTo<DeploymentName>`;
- `quarkus<RegisteredName>NativeTest`;
- `quarkusContinuousTest` and `quarkus<LaunchName>ContinuousTest` for launch
  descriptors.

Suggested class:

- `TaskNamePlanner`

Unit tests:

- derives each public name from representative descriptors;
- detects collisions between descriptors;
- detects collisions with legacy task names such as `quarkusBuild`,
  `imageBuild`, `imagePush`, `buildNative`, `testNative`, `deploy`, and
  `buildAotEnhancedImage`;
- detects deployment-name collisions within a named output;
- does not realize/configure Gradle tasks.

Stop condition:

- the planner can answer every derived public task name from descriptors alone.

### A1.4 Plan Output Layout

Add output-layout planners for new named outputs and compatibility
materialization.

Required layout defaults:

- new named output root: `build/quarkus-builds/<registered-name>/`;
- named `gen/` and `app/` under the output root;
- reusable legacy-compatible `build/quarkus-build/dep` where dependency layout
  and filtering match;
- no implicit writes to `build/quarkus-app/`, root runner files, or
  `build/quarkus-artifact.properties` for explicit named-output tasks.

Suggested classes:

- `OutputLayout`
- `OutputLayoutPlanner`
- `QuarkusApplicationDependencyFragmentLayoutPlanner`
- `CompatibilityMaterializationPlan`
- `CompatibilityMaterializationPlanner`

Unit tests:

- fast jar, legacy jar, mutable jar, uber jar, native executable, native
  sources, and image-capable output roots;
- `gen/` and `app/` are shape-owned;
- `dep/` is reused only for compatible dependency layouts;
- legacy materialization targets are produced only by the compatibility planner;
- new named-output layouts require no cross-shape cleanup.

Stop condition:

- layout and materialization plans can be tested without running Quarkus
  augmentation or Gradle.

### A1.5 Isolate Package-Layout Inference

Add a planner/helper that consumes available `AugmentResult` facts where
present and keeps remaining filesystem/layout inference explicit.

Required behavior:

- consume `AugmentResult.getResults()`, `getJar()`, and `getNativeResult()`
  where available;
- distinguish authoritative augmentation facts from Gradle-side inference;
- document inferred layout assumptions in tests;
- avoid spreading include/exclude copy rules across task classes.

Suggested class:

- `PackageLayoutInferencePlanner`

Unit tests:

- uses available jar/native result metadata;
- records when support files/directories still require inference;
- keeps inferred fast-jar, legacy-jar, native, and native-sources behavior
  separate from authoritative result facts.

Stop condition:

- task classes do not need to know package-layout inference rules directly.

### A1.6 Plan Structured Build Intent

Add planners for package/native/image forced properties without using
`ForcedPropertieBuildService`.

Required concepts:

- normal package intent;
- native executable intent;
- native sources intent;
- image build intent;
- image push intent;
- merge behavior with extension-level `quarkusBuildProperties`,
  `forcedProperties`, and `nativeArguments`.

Suggested class:

- `BuildIntentPlanner`

Unit tests:

- normal build has no image build/push intent unless explicitly configured;
- image build adds build intent and builder;
- image push adds build and push intent;
- native output adds native intent;
- native sources output adds native-sources intent;
- precedence with `quarkus.nativeArguments` is explicit and tested.

Stop condition:

- all build intent is represented as structured planner output, not mutable
  shared service state.

### A1.7 Plan Image Targets

Add image target descriptors and planner.

Required behavior:

- `image {}` is opt-in per registered output;
- image DSL has target identity and builder configuration, not `build = true`
  or `push = true`;
- builder enum starts with `JIB`, `DOCKER`, `PODMAN`, `OPENSHIFT`,
  `BUILDPACK`; add `S2I` only if current Quarkus still exposes it distinctly;
- duplicate effective image references fail when unrelated selected
  image-producing tasks collide;
- same reference is allowed only for one explicit ordered owner/flow, such as
  AOT-enhanced replacement for the same registered output.

Suggested classes:

- `QuarkusApplicationImageDescriptor`
- `QuarkusApplicationImageBuilder`
- `ImagePlanner`

Unit tests:

- builder enum maps to Quarkus builder names;
- missing image config prevents image task planning;
- duplicate unrelated image references fail;
- same-reference AOT flow is allowed only when explicit and ordered.

Stop condition:

- image build/push intent can be planned without selecting tasks from a Gradle
  execution graph.

### A1.8 Plan AOT-Enhanced Image Flow

Add AOT-enhanced image descriptor/planner.

Required behavior:

- `aotEnhancedImage {}` on the registered output declares the current-platform
  AOT-enhanced image flow;
- `aotFile` modeled as `RegularFileProperty` in task classes later, represented
  as an abstract file input in the A1 planner;
- support both `producedBy(...)` and `aotFileFrom(producer, fileProvider)`
  concepts;
- default enhanced image reference is the normal image reference plus the
  existing AOT image suffix, currently `-aot`;
- AOT image repository, tag, or full image reference may be overridden, but
  contradictory full-reference and structured settings fail clearly;
- declaring `aotEnhancedImage {}` registers a deterministic Quarkus-owned
  AOT-training suite such as `quarkusAppAotTraining`;
- producer may be any task or test-suite target, not a hard-coded
  `quarkusIntTest`;
- automatic multi-platform AOT image assembly is out of scope; users handle
  multi-platform AOT manually with external per-platform artifacts.

Suggested classes:

- `QuarkusApplicationAotEnhancedImageDescriptor`
- `AotEnhancedImagePlanner`

Unit tests:

- missing `aotFile` fails clearly;
- default `-aot` suffix reference is used;
- structured and full-reference overrides are honored;
- contradictory reference overrides fail clearly;
- producer wiring is represented separately from file input identity;
- both producer helper forms are modeled.

Stop condition:

- no planner behavior depends on `build/quarkus-container-it.properties`.

### A1.9 Plan Deployment Descriptors

Add deployment descriptor/planner.

Required behavior:

- deployments are nested named descriptors under registered outputs;
- public deployment DSL exposes `kubernetes("name")` and `openshift("name")`
  factories, not a generic public `register(...)`;
- deployment target is selected by the factory and is not configurable inside
  the deployment block;
- no `deploy { ... }` sugar and no `quarkusAppDeploy` task in the first slice;
- derived tasks use `quarkus<BuildName>DeployTo<DeploymentName>`;
- deployment names are unique within a named output;
- deployment descriptors model the selected image source rather than a `push`
  boolean;
- supported image-source planning covers an already existing image reference,
  normal image push output, and AOT-enhanced image push output.

Suggested classes:

- `QuarkusApplicationDeploymentDescriptor`
- `DeploymentPlanner`

Unit tests:

- task name derivation for `DeployTo`;
- collision detection;
- image-source defaults and explicit image-source selection;
- required deploy target/deployer/image validation modeled as planner errors.

Stop condition:

- deployment behavior is modeled without mutating JVM system properties or
  forced-property services.

### A1.10 Plan Native-Test And Launch Names

Add planner support for native-test suite/task names and continuous-test launch
names.

Required behavior:

- native output `native1` derives `quarkusNative1NativeTest`;
- Quarkus-created native-test suites are customized with `named(...)`;
- user-created suites attach with `forQuarkusBuild("native1")`;
- no `nativeTest { enabled = true }` gate;
- no automatic `check` dependency;
- continuous-test launch names derive `quarkusContinuousTest` or
  `quarkus<LaunchName>ContinuousTest`.

Suggested classes:

- extend `TaskNamePlanner`, or add
  `QuarkusApplicationLaunchDescriptor` plus launch planner if needed.

Unit tests:

- native-test names;
- launch continuous-test names;
- collisions with build-output task names;
- no default `check` attachment represented in plans.

Stop condition:

- test/launch naming is covered without applying Gradle JVM Test Suite yet.

### A1.11 A1 Verification

Run targeted unit tests for the new packages. If using Maven from the repository
root, keep it scoped to the Gradle application plugin module and do not run
parallel Maven tests.

Expected A1 result:

- new model/planner classes compile;
- pure unit tests pass;
- no changes to legacy task behavior;
- no `QuarkusPlugin` registration changes required unless purely mechanical and
  non-behavioral;
- no TestKit or integration-test requirement unless a contract genuinely cannot
  be proven with unit tests.

## Phase A2: Named Output DSL And Task Skeleton

Status: complete for the agreed A2 skeleton scope. JVM Test Suite integration
was intentionally deferred to the native/test execution phase because the A2
slice was limited to DSL/model/task skeleton registration.

Start A2 only after A1 model/planner tests are green.

### A2.1 Add Extension Model

Add `quarkus.builds` named-output collection on the application-plugin
extension.

Required behavior:

- typed factory methods such as `fastJar("app")`, `native("native1")`, and
  equivalent class-based registration where practical;
- all registered output names are global within the collection;
- descriptors expose typed managed properties for output directory, output
  name, Quarkus build properties, manifest settings where applicable, native
  config where applicable, image config, deployments, and AOT-enhanced image
  config;
- descriptors do not register/realize all tasks just to validate names.

Test with `ProjectBuilder` first:

- extension exists;
- typed factory and class-based registration work;
- duplicate and normalized-name collisions fail clearly;
- descriptor properties have correct conventions;
- no legacy task behavior changes.

### A2.2 Add Task Type Skeleton

Create task classes under `io.quarkus.gradle.tasks.application`:

- `QuarkusApplicationTask`
- `QuarkusApplicationBuildTask`
- `QuarkusApplicationPackageTask`
- `QuarkusApplicationNativeTask`
- `QuarkusApplicationImageBuildTask`
- `QuarkusApplicationImagePushTask`
- `QuarkusApplicationAotEnhancedImageBuildTask`
- `QuarkusApplicationAotEnhancedImagePushTask`
- `QuarkusApplicationDeployTask`
- `QuarkusApplicationLaunchTask`
- `QuarkusApplicationDevTask`
- `QuarkusApplicationRemoteDevTask`
- `QuarkusApplicationContinuousTestTask`

Keep skeleton task classes thin:

- declare typed inputs/outputs;
- expose `RegularFileProperty` / `DirectoryProperty` outputs where applicable;
- delegate all planning decisions to A1 planners;
- do not copy legacy cleanup heuristics into named-output tasks.

ProjectBuilder tests:

- tasks can be registered without realization failures;
- output properties and conventions are inspectable;
- direct relationships are as expected;
- old and new registration paths are visibly separated.

### A2.3 Add New Registration Path

In `QuarkusPlugin`, add a separate registration/configuration path for the new
named-output model.

Guardrails:

- do not weave new registration through existing legacy setup methods;
- use a small scoped registration context only if it reduces parameter
  threading;
- do not change old task names, dependencies, or finalizers;
- do not make `quarkusBuild` lifecycle-only;
- do not wire new native-test suites to `check`.

ProjectBuilder tests first, TestKit only where needed:

- `quarkus.builds.fastJar("app")` registers `quarkusAppBuild`;
- `native("native1")` registers `quarkusNative1Build` and
  `quarkusNative1NativeTest`;
- `image {}` registers output-specific image tasks;
- deployments register `DeployTo` tasks;
- no `quarkusAppDeploy` task is registered;
- legacy tasks still exist and keep their legacy types.

### A2.4 JVM Test Suite Integration

Status: deferred to the native/test execution phase.

If built-in Quarkus suites require Gradle's `jvm-test-suite` infrastructure,
the application plugin should apply/configure it for those built-in suites.

Required behavior:

- Quarkus registers built-in native-test suites with deterministic names;
- declaring `aotEnhancedImage {}` registers a deterministic Quarkus-owned
  AOT-training suite, for example `quarkusAppAotTraining`;
- users customize built-in suites with `testing.suites.named(...)`;
- users register additional suites and attach them with `forQuarkusBuild(...)`;
- duplicate `register(...)` attempts for Quarkus-owned suite names fail clearly;
- no `check` dependency by default.

Prefer ProjectBuilder tests for registration/model behavior. Use TestKit for
Kotlin/Groovy DSL behavior or actual suite task execution only.

### A2.5 A2 Verification

Expected A2 result:

- extension DSL and skeleton task registration work;
- legacy task behavior remains unchanged;
- no new task writes legacy output locations by default;
- unit and ProjectBuilder tests pass;
- any TestKit tests have a clear execution/configuration-cache/DSL reason.

## Phase A3: Opt-In Legacy Diagnostics

Status: complete.

A3 was implemented after the named-output DSL and task skeletons existed, so
the diagnostics can point users at concrete replacement task paths as those
paths become executable.

Add nested diagnostics model:

```kotlin
quarkus {
    diagnostics {
        legacyTaskUsage = WARN // OFF, WARN, FAIL
    }
}
```

Required behavior:

- project-property convention such as
  `-Pquarkus.diagnostics.legacy-task-usage=warn`;
- Quarkus 4.0 default `OFF`;
- planned Quarkus 4.1 default `WARN` once replacements exist;
- include all legacy application-task usage, including `quarkusBuild`;
- diagnose `quarkusBuild` as legacy model usage without deprecating the task
  name itself;
- do not deprecate the `quarkusBuild` task name;
- direct and transitive execution of legacy application tasks count as usage;
- mere task registration is not usage;
- report file generated for `WARN` and `FAIL`;
- `FAIL` fails when meaningful legacy usage occurs.

Implemented report path: `build/reports/quarkus/legacy-task-usage.txt`.

Tests:

- pure unit tests for level parsing and report model;
- ProjectBuilder tests for extension/property conventions;
- TestKit tests only for actual task execution paths and direct/transitive
  diagnostics behavior.

## Phase B And Later: Behavior Implementation

Only after A1/A2/A3 foundations are present:

- implement output-specific package/native execution;
- implement image build/push execution without `ForcedPropertieBuildService`;
- introduce a small operations/request boundary for new task execution so pure
  unit tests can use cheap stubs while a smaller worker-oriented test set
  verifies real worker invocation mapping;
- model image build/push outputs as Gradle-owned result/receipt files, not as
  the external image artifact itself;
- use `@Nested` beans only for declared image target inputs; produced image
  data crosses task boundaries through `@OutputFile` result/receipt files that
  dependent tasks consume as `@InputFile`;
- add the image-result support model needed by that boundary:
  `ContainerImageTarget`, `BuiltContainerImage`,
  `BuiltContainerImageResultCodec`, and builder-specific result extractors for
  Jib, Docker/Podman, Buildpack, OpenShift, and AOT-enhanced image results;
- use [P1-AP-02B Task Topology](../../phase-b-task-topology.md) as the source of
  truth for task names, task types, dependency edges, convenience-task
  decisions, and cacheability stance;
- use optional digest/SHA enrichment only when Quarkus exposes it. Current
  `AugmentResult` image metadata findings are documented in
  [P1-AP-02B AugmentResult Image Metadata Investigation](../../phase-b-augment-result-image-metadata.md);
- implement AOT-enhanced image execution using modeled `aotFile`;
- implement output-specific deploy execution without JVM-global mutation;
- add compatibility materialization if needed;
- consider routing legacy aliases to new tasks only after replacement behavior
  and diagnostics are proven.

Each behavior PR should:

- state which legacy task behavior remains unchanged;
- add or update typed task outputs;
- use planner output rather than duplicating layout logic in task actions;
- follow the test pyramid;
- include cache/up-to-date/configuration-cache verification when execution
  behavior changes.

## Follow-Up Documentation Task

After the DSL and task set are implemented, add user-facing docs covering:

- new build-file examples;
- migration of existing build files;
- legacy/default `quarkusBuild` behavior;
- explicit named outputs;
- image and deploy use cases;
- native-test suites;
- AOT-enhanced current-platform image flow and manual multi-platform boundary;
- diagnostics;
- compatibility timeline.

Do not write final user docs before the public DSL and task names are stable.

## Stop Conditions

Stop and ask for a design decision if implementation reveals any of these:

- an output type cannot be represented by the A1 planner without changing
  Quarkus core APIs;
- a legacy `QuarkusBuild` property cannot be mapped or intentionally excluded
  without affecting common existing builds;
- Gradle APIs make both typed factory and class-based registration impractical;
- applying/configuring `jvm-test-suite` for built-in suites creates unavoidable
  side effects in projects without tests;
- image builder enum values do not match current Quarkus supported builders;
- deploy image-source semantics cannot cover existing image references, normal
  image push outputs, and AOT-enhanced image push outputs without ambiguous
  task naming or runtime behavior;
- a TestKit or integration test is needed for a contract that appears unit
  testable but cannot be isolated.

When stopping, record the exact code path, the blocked invariant, options, and
recommended decision in this work folder before changing behavior.
