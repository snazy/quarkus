# P1-AP-01 Codegen Implementation Plan

Status: implementation plan; Phase 13 implemented
Last reviewed: 2026-07-08

## Current Progress

- Phase 0 is complete:
  - the worktree had only the docs changes that introduced this plan;
  - the new plugin still owns `quarkusApplication` and named build task
    registration;
  - legacy codegen defaults were confirmed as providers
    `grpc,avdl,avpr,avsc` and input names `proto,avro`.
- Phase 1 is implemented:
  - `QuarkusApplicationCodegen` was added as application-level DSL state;
  - `QuarkusApplicationExtension` exposes `codegen {}` and `getCodegen()`;
  - defaults and overrides are covered in `QuarkusApplicationPluginTest`;
  - the ProjectBuilder coverage asserts the new plugin does not register
    legacy `quarkusGenerateCode*` task names.
- Phase 1 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`.
- Phase 2 is implemented:
  - `CodegenRequest` and
    `CodegenOperations` were added under
    `io.quarkus.gradle.application.internal.codegen`;
  - `WorkerBackedCodegenOperations` and `CodegenWorker`
    mirror legacy `CodeGenWorker` execution through the new plugin's worker
    base;
  - `QuarkusWorker` exposes its bootstrap helpers as `protected` so dedicated
    worker packages can reuse them without moving test stubs into production;
  - request immutability and worker submission mapping are covered by focused
    tests.
- Phase 2 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests '*Codegen*' --stacktrace`.
- Phase 3 is implemented:
  - `QuarkusApplicationGenerateCodeTask` was added as a standalone
    application-level task type;
  - the task declares launch mode, test flag, application model, compile
    classpath, source parent directories, generated output directory, codegen
    providers/input names, effective-config inputs, and worker operation
    configuration;
  - the task action builds a `CodegenRequest` and delegates
    to `CodegenOperations`;
  - the task is deliberately `@DisableCachingByDefault` until codegen
    cacheability is reviewed in a later phase;
  - request construction and operation delegation are covered by focused tests.
- Phase 3 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.tasks.QuarkusApplicationGenerateCodeTaskTest --stacktrace`;
  - `./gradlew :gradle-app-plugin:validatePlugins --stacktrace`.
- Phase 4 is implemented at the model-task topology level:
  - `quarkusApplicationModel` remains the production model and still depends
    on `classes`;
  - `quarkusApplicationCodegenModel` was added for normal pre-codegen model
    generation and does not depend on `classes`;
  - `quarkusApplicationTestCodegenModel` was added for test pre-codegen model
    generation with `LaunchMode.TEST` and does not depend on `testClasses`;
  - all three model tasks write distinct serialized model files;
  - launch-mode-aware TEST classpath construction remains the explicit Phase 5
    task, because the current classpath builder is still normal-mode oriented.
- Phase 4 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`.
- Phase 5 is implemented:
  - `ClasspathBuilder` now exposes separate normal and test
    runtime classpath inputs;
  - the exposed runtime classpaths are final Quarkus runtime classpaths: they
    extend the raw Gradle runtime classpath and add condition-satisfied
    conditional runtime extensions discovered from extension descriptors;
  - it creates separate deployment classpath configurations for normal and
    test runtime artifacts;
  - deployment classpaths are derived from the corresponding final runtime
    classpaths, so condition-satisfied conditional runtime extensions bring
    their deployment artifacts with them;
  - it creates separate compile-only configurations for normal and test model
    generation;
  - model task registration now selects runtime, deployment, compile-only, and
    original classpath inputs from the requested launch mode;
  - ProjectBuilder coverage proves normal model tasks do not see a test-only
    runtime artifact, while `quarkusApplicationTestCodegenModel` does.
- Phase 5 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`.
- Phase 6 is implemented:
  - `quarkusApplicationGenerateCode` and
    `quarkusApplicationGenerateTestCode` are registered by the new plugin;
  - both tasks consume provider-backed pre-codegen model outputs;
  - main codegen uses `LaunchMode.NORMAL`, `test = false`,
    `build/generated/sources/quarkus-application/main`, main resource source parents, and
    the normal runtime/deployment classpath;
  - test codegen uses `LaunchMode.TEST`, `test = true`,
    `build/generated/sources/quarkus-application/test`, test resource source parents, and
    the test runtime/deployment classpath;
  - both tasks consume application-level codegen DSL settings and
    `configInputs`;
  - no dev-mode codegen task and no legacy `quarkusGenerateCode*` task names
    are registered.
- Phase 6 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`;
  - `./gradlew :gradle-app-plugin:validatePlugins --stacktrace`.
- Phase 7 is implemented:
  - the main Java source set does not include the
    `quarkusApplicationGenerateCode` generated source directory, so legacy
    codegen tasks do not consume the new-plugin output when both plugins are
    applied during migration;
  - `compileJava` explicitly depends on `quarkusApplicationGenerateCode` and
    directly includes that generated source directory;
  - the test Java source set does not include the
    `quarkusApplicationGenerateTestCode` generated source directory;
  - `compileTestJava` explicitly depends on both
    `quarkusApplicationGenerateCode` and
    `quarkusApplicationGenerateTestCode` and directly includes the test
    generated source directory;
  - ProjectBuilder coverage verifies source-set directories, compile task
    dependencies, and the Java plugin's `classes`/`testClasses` path.
- Phase 7 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`;
  - `./gradlew :gradle-app-plugin:validatePlugins --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --tests '*Codegen*' --stacktrace`.
- Phase 8 is deferred:
  - the `gradle-app-plugin` module has no cheap Kotlin/KAPT plugin fixture or
    module-local Kotlin Gradle plugin dependency to reuse;
  - implementing the wiring without tests would violate this plan's acceptance
    criteria;
  - adding real external Kotlin plugin TestKit setup here would be heavier and
    more brittle than the intended default-suite slice;
  - the follow-up is recorded in `new-application-plugin-design.md`.
- Phase 9 is implemented:
  - `QuarkusApplicationPluginTest` now has a TestKit smoke test that applies
    `io.quarkus.application` to a tiny Java app and runs `compileTestJava`;
  - the fixture appends deterministic generated-source writers to
    `quarkusApplicationGenerateCode` and
    `quarkusApplicationGenerateTestCode`, while focused unit tests continue to
    cover `CodegenOperations` delegation;
  - the generated main and test sources are referenced from handwritten main
    and test sources, proving Java compilation consumes both generated source
    directories;
  - the test runs with `--configuration-cache` and
    `-Dorg.gradle.unsafe.isolated-projects=true` and asserts a second
    up-to-date run.
- Phase 9 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests 'io.quarkus.gradle.application.QuarkusApplicationPluginTest.compilesGeneratedSourcesFromStubbedCodegenWithConfigurationCacheAndIsolatedProjects' --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --tests '*Codegen*' --stacktrace`;
  - `./gradlew :gradle-app-plugin:validatePlugins --stacktrace`.
- Phase 10 is implemented:
  - `QuarkusApplicationPluginTest` now has a TestKit fixture that applies
    `io.quarkus.application`, depends on `quarkus-avro`, defines a tiny Avro
    schema under `src/main/avro`, and runs `compileJava`;
  - the test proves the real Quarkus codegen worker path produces a generated
    source file and that Java compilation consumes the generated Avro type;
  - generated-source assertions intentionally search under
    `build/generated/sources/quarkus-application/main` instead of depending on the Avro
    provider's internal subdirectory layout;
  - codegen source parent directories mirror legacy behavior by passing the
    parent directories of Java source roots, so default projects expose
    `src/main/avro`, `src/main/proto`, `src/test/avro`, and `src/test/proto`
    to Quarkus codegen providers;
  - generated source directories are wired directly into Java compile tasks
    instead of the shared source sets, so codegen tasks do not accidentally
    consume each other's generated-output roots or cross-plugin generated
    outputs.
- Phase 10 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests 'io.quarkus.gradle.application.QuarkusApplicationPluginTest.compilesRealAvroGeneratedSourcesWithConfigurationCacheAndIsolatedProjects' --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --tests '*Codegen*' --stacktrace`;
  - `./gradlew :gradle-app-plugin:validatePlugins --stacktrace`.
- Phase 11 is implemented:
  - `QuarkusApplicationPluginTest` now has a multi-project TestKit fixture
    where only `:app` applies `io.quarkus.application`;
  - `:lib` is a plain `java-library` project with no Quarkus or Jandex plugin;
  - `:app` declares `implementation project(':lib')`, runs
    `:app:compileJava`, and appends a generated source writer to
    `:app:quarkusApplicationGenerateCode`;
  - the generated source imports and uses a `:lib` class, proving Gradle's
    normal project-dependency classpath wiring supplies dependency outputs
    without Quarkus configuring dependency-project tasks;
  - the test asserts the second nested build reuses the configuration cache and
    all relevant compile/codegen tasks are up-to-date.
- Phase 11 verification has run:
  - `./gradlew :gradle-app-plugin:test --tests 'io.quarkus.gradle.application.QuarkusApplicationPluginTest.compilesGeneratedSourcesWithPlainProjectDependencyAndIsolatedProjects' --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`.
- Phase 12 is implemented:
  - `QuarkusApplicationGenerateCodeTask` remains
    `@DisableCachingByDefault` because arbitrary Quarkus codegen providers,
    fork-option actions, source sensitivity, and effective-config inputs need a
    dedicated cacheability review before remote/local build-cache claims are
    made;
  - the cacheability review is tracked in
    `new-application-plugin-design.md`;
  - Gradle plugin validation is clean;
  - the forbidden-pattern scan has no matches in `gradle-app-plugin` main
    sources.
- Phase 12 verification has run:
  - `./gradlew :gradle-app-plugin:validatePlugins --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --stacktrace`;
  - `rg -n "getProject\\(|Task\\.getProject|afterEvaluate|subprojects|allprojects|rootProject|project\\(" devtools/gradle/gradle-app-plugin/src/main/java`.
- Phase 13 is implemented:
  - `p1-ap-01-codegen-project-walk-plan.md` now describes the implemented
    standalone-plugin codegen state instead of the pre-implementation gap;
  - `new-application-plugin-design.md` tracks the remaining Kotlin/KAPT wiring
    and codegen cacheability review follow-ups;
  - no user-facing Quarkus docs were changed because this remains docs-wip
    design work.

## Objective

Add Gradle-native Quarkus code generation to the standalone
`io.quarkus.application` plugin in `devtools/gradle/gradle-app-plugin`.

The implementation must follow `p1-ap-01-codegen-project-walk-plan.md`:

- no legacy `quarkusGenerateCode*` task names;
- no dependency-project task walk;
- no cross-project mutable model access;
- no dependency-project Jandex task wiring;
- generated sources are application-level, not named-output-level;
- all supported TestKit paths run with configuration cache and isolated
  projects enabled.

An agent should be able to execute this plan phase by phase. After each phase,
run the listed verification before moving on.

## Hard Gates

These are blockers, not preferences:

- Do not change legacy `io.quarkus` codegen behavior in this work.
- Do not register `quarkusGenerateCode`, `quarkusGenerateCodeDev`, or
  `quarkusGenerateCodeTests` from the new plugin.
- Do not call `Task.getProject()` from task actions.
- Do not capture live Gradle model objects in task actions, worker parameters,
  or provider callbacks.
- Do not inspect dependency projects, their tasks, source sets, extensions, or
  configurations.
- Do not use `afterEvaluate` for codegen registration or dependency-project
  ordering.
- Keep test stubs and recording implementations out of production sources.
- Every default-suite TestKit invocation for `gradle-app-plugin` must include:
  - `--configuration-cache`;
  - `-Dorg.gradle.unsafe.isolated-projects=true`.
- Use `--build-cache` for cacheability-sensitive codegen tests.

## Existing Source To Inspect First

Read these files before editing:

- `devtools/gradle/docs-wip/gradle-cc-work/archive/p1-ap-01-codegen-project-walk-plan.md`
- `devtools/gradle/docs-wip/gradle-cc-work/new-application-plugin-design.md`
- `devtools/gradle/gradle-app-plugin/AGENTS.md`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/plugin/QuarkusApplicationPlugin.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/plugin/TaskRegistration.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/dsl/QuarkusApplicationExtension.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/modelgen/GenerateModelTask.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/modelgen/ClasspathBuilder.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/config/EffectiveConfig*.java`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusGenerateCode.java`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/CodeGenWorker.java`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`

Useful test references:

- `devtools/gradle/gradle-app-plugin/src/test/java/io/quarkus/gradle/application/plugin/QuarkusApplicationPluginTest.java`
- `devtools/gradle/gradle-app-plugin/src/test/java/io/quarkus/gradle/application/execution/worker/WorkerBackedBuildOperationsTest.java`
- `devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/QuarkusPluginTest.java`

## Phase 0: Baseline And Source Shape Check

1. Confirm the worktree state:

   ```bash
   git status --short
   ```

2. Confirm that `gradle-app-plugin` still owns the new plugin and named build
   task registration.

3. Confirm whether `GenerateModelTask` can support a
   pre-classes variant:

   - it must allow empty or non-existing application class/resource directories;
   - it must not require `classes` or `testClasses`;
   - it must still serialize an application model with a project artifact and
     dependency metadata.

4. Confirm whether `ClasspathBuilder` is normal-mode only.
   At the time this plan was written, it uses:

   - `runtimeClasspath`;
   - `compileOnly`;
   - one deployment classpath derived from runtime artifacts;
   - one platform properties configuration.

5. Confirm the legacy defaults for codegen provider and input names from the
   legacy extension and `QuarkusGenerateCode` registration.

Acceptance:

- no code edits are required in this phase;
- any source-shape surprise is recorded as a note in this document before
  implementing Phase 1.

Verification:

```bash
git status --short
```

## Phase 1: Add Codegen DSL Inputs

Add codegen configuration to the new `quarkusApplication` extension.

1. Add a new managed DSL type in `gradle-app-plugin`, for example:

   `io.quarkus.gradle.application.dsl.QuarkusApplicationCodegen`

   Required properties:

   ```java
   public abstract ListProperty<String> getProviders();

   public abstract ListProperty<String> getInputNames();
   ```

   Use legacy-equivalent conventions. If legacy uses a single provider/input
   list, preserve that shape; do not invent per-build codegen configuration in
   this phase.

2. Add the nested object to `QuarkusApplicationExtension`. This is extension
   DSL state, not a task input, so do not add task input annotations here:

   ```java
   public abstract QuarkusApplicationCodegen getCodegen();
   ```

   The extension may expose a DSL method if the surrounding DSL uses methods
   for nested blocks:

   ```java
   void codegen(Action<? super QuarkusApplicationCodegen> action)
   ```

3. Keep codegen config application-level. Do not add codegen blocks under
   `builds {}`.

4. Add unit or ProjectBuilder coverage that verifies:

   - `quarkusApplication.codegen.providers` has the expected default;
   - `quarkusApplication.codegen.inputNames` has the expected default;
   - user values can override both;
   - applying legacy and new plugins together does not create legacy codegen
     task names from the new plugin.

Acceptance:

- the new extension has codegen provider/input-name properties;
- no tasks are registered yet;
- no legacy extension code is modified.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace
```

## Phase 2: Add Codegen Request And Operation Boundary

Add a testable operation boundary before adding task registration.

1. Add a request type under
   `io.quarkus.gradle.application.internal.codegen`, for example:

   `CodegenRequest`

   It should contain only immutable runtime values:

   - serialized application model path;
   - launch mode;
   - `boolean test`;
   - source parent directories;
   - generated output directory;
   - project build directory;
   - project coordinates or display name for error messages;
   - codegen providers;
   - codegen input names;
   - effective config properties;
   - compile classpath files if the production operation needs them.

2. Add an operation interface:

   ```java
   interface CodegenOperations {
       void generate(CodegenRequest request);
   }
   ```

3. Add a production implementation under production sources, for example:

   `io.quarkus.gradle.application.internal.codegen.worker.WorkerBackedCodegenOperations`

   The implementation may mirror legacy `CodeGenWorker`, but must be repackaged
   under `io.quarkus.gradle.application.*`.

4. The production implementation should invoke the same Quarkus codegen entry
   point as legacy codegen:

   `io.quarkus.deployment.CodeGenerator.initAndRun(...)`

5. Do not place stub operations in `src/main`.

6. Add test-only recording/stub operations under `src/test/java` or test
   fixtures. The stubs should record request values and optionally create a
   marker generated source file.

7. Add pure unit tests for request construction and operation invocation. These
   tests must not run Quarkus augmentation or real codegen.

Acceptance:

- production source has a clear codegen operation boundary;
- request values are immutable and contain no Gradle model objects;
- test stubs live only in test code.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests '*Codegen*' --stacktrace
```

If no `*Codegen*` tests exist before this phase, run the newly added test
classes explicitly.

## Phase 3: Add `QuarkusApplicationGenerateCodeTask`

Add one task type that supports main and test codegen through properties.

1. Add:

   `io.quarkus.gradle.application.tasks.QuarkusApplicationGenerateCodeTask`

2. The task should be cacheable only if all inputs and outputs are declared
   and the production operation is deterministic for those inputs. If there is
   doubt, start with `@DisableCachingByDefault` and add a follow-up. The legacy
   task is cacheable, but the new task must earn that annotation by declaring
   all inputs.

3. Required task properties:

   ```java
   @Input
   public abstract Property<LaunchMode> getLaunchMode();

   @Input
   public abstract Property<Boolean> getTest();

   @InputFile
   @PathSensitive(PathSensitivity.RELATIVE)
   public abstract RegularFileProperty getApplicationModel();

   @CompileClasspath
   public abstract ConfigurableFileCollection getClasspath();

   @InputFiles
   @PathSensitive(PathSensitivity.RELATIVE)
   public abstract ConfigurableFileCollection getSourceParentDirectories();

   @OutputDirectory
   public abstract DirectoryProperty getGeneratedOutputDirectory();

   @Internal
   public abstract DirectoryProperty getBuildDirectory();

   @Input
   public abstract ListProperty<String> getCodegenProviders();

   @Input
   public abstract ListProperty<String> getCodegenInputNames();
   ```

4. Add the effective-config task inputs using the same pattern already used by
   new application build/image/deploy tasks. Do not reuse legacy
   `EffectiveConfigProvider` directly.

5. The task action must:

   - deserialize no Gradle model objects;
   - construct `CodegenRequest`;
   - call `CodegenOperations.generate(...)`;
   - never call `getProject()`;
   - write only declared outputs.

6. Provide a production operation default without exposing public mutable task
   internals. Prefer the existing package-private/test-support injection pattern
   used by the new application tasks.

7. Add unit tests or ProjectBuilder tests that verify:

   - all required properties are present;
   - request construction includes launch mode, test flag, model path,
     source parents, generated output directory, codegen config, and effective
     config;
   - no test support classes are in `src/main`.

Acceptance:

- the task type exists;
- task action delegates through the operation boundary;
- task action has no `Task.getProject()` access;
- task type does not expose public internal helper methods.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests '*GenerateCode*' --stacktrace
./gradlew :gradle-app-plugin:validatePlugins --stacktrace
```

## Phase 4: Make Application Model Registration Codegen-Aware

Register pre-codegen model tasks that do not depend on compiled classes.

1. Refactor `TaskRegistration` so model-task registration can
   create explicit variants:

   - production model, current behavior:
     `quarkusApplicationModel`;
   - normal pre-codegen model:
     `quarkusApplicationCodegenModel`;
   - test pre-codegen model:
     `quarkusApplicationTestCodegenModel`.

2. The production model keeps:

   ```text
   dependsOn(classes)
   LaunchMode.NORMAL
   output: build/quarkus/application-model/quarkus-application-model.dat
   ```

3. The normal pre-codegen model:

   - has `LaunchMode.NORMAL`;
   - must not depend on `classes`;
   - should use main source/resource metadata and runtime/deployment
     classpaths;
   - may have empty application class/resource directories before compilation;
   - writes to a distinct path, for example:

     `build/quarkus/application-model/quarkus-application-codegen-model.dat`

4. The test pre-codegen model:

   - has `LaunchMode.TEST`;
   - must not depend on `testClasses`;
   - must use test launch-mode classpaths;
   - writes to a distinct path, for example:

     `build/quarkus/application-model/quarkus-application-test-codegen-model.dat`

5. If `GenerateModelTask` cannot represent empty
   classes/resources cleanly, refactor it narrowly:

   - keep class/resource directory inputs declared;
   - allow them to be empty;
   - keep existing production model behavior unchanged.

6. Add ProjectBuilder coverage that verifies task registration and dependency
   direction:

   - `quarkusApplicationCodegenModel` does not depend on `classes`;
   - `quarkusApplicationTestCodegenModel` does not depend on `testClasses`;
   - `quarkusApplicationModel` still depends on `classes`;
   - all three tasks write distinct model files.

Acceptance:

- three model tasks exist;
- pre-codegen model tasks have no compile-output task dependency;
- production build tasks still consume `quarkusApplicationModel`.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace
```

## Phase 5: Add Launch-Mode-Aware Classpaths

Make classpath construction distinguish main and test codegen.

1. Keep `ClasspathBuilder` as the new plugin adapter. Do not blindly reuse
   legacy classpath machinery that performs cross-project component-variant
   inspection, because the new plugin must stay compatible with Gradle isolated
   projects. The adapter API must provide at least:

   - normal runtime classpath input;
   - normal deployment classpath;
   - normal compile-only classpath;
   - test runtime classpath input;
   - test deployment classpath;
   - test compile-only classpath if needed by model generation.

2. Use Gradle configurations and artifact inference only. Do not inspect
   dependency projects.

3. Recommended configuration sources:

   - normal runtime: `runtimeClasspath`;
   - normal compile-only: `compileOnly`;
   - test runtime: `testRuntimeClasspath`;
   - test compile-only: `testCompileOnly` plus normal compile-only if Gradle
     does not already include the main compile-only relationship in the test
     source set;
   - deployment classpaths derived from the corresponding runtime artifacts.

4. Avoid name collisions by using explicit internal configuration names, for
   example:

   - `quarkusApplicationDeploymentClasspathConfiguration`;
   - `quarkusApplicationTestDeploymentClasspathConfiguration`;
   - `quarkusApplicationCompileOnlyConfiguration`;
   - `quarkusApplicationTestCompileOnlyConfiguration`.

   The runtime configurations must evolve the raw Gradle runtime classpath with
   Quarkus conditional runtime extensions before deployment artifacts are
   derived. Keep this implementation isolated-project compatible: read
   extension descriptors from artifact files, use resolution-result component
   ids for condition keys, and do not reuse legacy cross-project
   component-variant/project-inspection machinery.

5. Wire:

   - normal pre-codegen model and main codegen to normal classpaths;
   - test pre-codegen model and test codegen to test classpaths;
   - production `quarkusApplicationModel` remains normal-mode.

6. Add tests proving that the test codegen model does not accidentally use the
   normal runtime classpath.

Acceptance:

- test codegen has a TEST launch-mode model and classpath;
- normal codegen remains normal-mode;
- no cross-project project inspection is added.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests '*Classpath*' --stacktrace
./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace
```

## Phase 6: Register Main And Test Codegen Tasks

Register codegen tasks from the new plugin.

1. In `TaskRegistration`, register:

   - `quarkusApplicationGenerateCode`;
   - `quarkusApplicationGenerateTestCode`.

2. Task wiring:

   `quarkusApplicationGenerateCode`:

   - depends on or consumes the output of `quarkusApplicationCodegenModel`;
   - `launchMode = LaunchMode.NORMAL`;
   - `test = false`;
   - source parents from the main source set;
   - generated directory:
     `build/generated/sources/quarkus-application/main`;
   - codegen providers/input names from `quarkusApplication.codegen`;
   - effective config inputs from `quarkusApplication.configInputs`;
   - classpath from normal runtime/deployment needs as required by the
     production operation.

   `quarkusApplicationGenerateTestCode`:

   - depends on or consumes the output of
     `quarkusApplicationTestCodegenModel`;
   - `launchMode = LaunchMode.TEST`;
   - `test = true`;
   - source parents from the test source set;
   - generated directory:
     `build/generated/sources/quarkus-application/test`;
   - codegen providers/input names from `quarkusApplication.codegen`;
   - effective config inputs from `quarkusApplication.configInputs`;
   - classpath from test runtime/deployment needs as required by the
     production operation.

3. Do not register a dev-mode codegen task.

4. Add ProjectBuilder tests that verify:

   - both task names exist;
   - no legacy `quarkusGenerateCode*` task names exist;
   - task properties point to expected model files and output directories;
   - test task has `LaunchMode.TEST`;
   - main task has `LaunchMode.NORMAL`.

Acceptance:

- codegen task registration exists;
- task names do not collide with legacy tasks;
- task wiring uses provider-backed task outputs and properties.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace
```

## Phase 7: Wire Generated Sources Into Java Compilation

Wire Java compilation first. This is the minimum functional slice.

1. Use lazy task configuration in the new plugin. Do not use `afterEvaluate`.

2. Main Java wiring:

   - add `quarkusApplicationGenerateCode` output directory as a source
     directory for main Java compilation;
   - make `compileJava` depend on `quarkusApplicationGenerateCode`.

3. Test Java wiring:

   - add `quarkusApplicationGenerateTestCode` output directory as a source
     directory for test Java compilation;
   - make `compileTestJava` depend on `quarkusApplicationGenerateCode`;
   - make `compileTestJava` depend on `quarkusApplicationGenerateTestCode`.

4. Wire generated directories directly into `JavaCompile` tasks with
   provider-backed task outputs. Do not add them to the shared `main` or `test`
   Java source sets because that makes legacy `io.quarkus` codegen tasks see
   new-plugin generated outputs as their own source inputs when both plugins
   are applied.

5. Add ProjectBuilder tests verifying:

   - `compileJava` depends on main codegen;
   - `compileTestJava` depends on main and test codegen;
   - generated directories are not present in the shared source-set
     directories;
   - `classes` depends on generated main sources through `compileJava`;
   - `testClasses` depends on generated test sources through
     `compileTestJava`.

Acceptance:

- Java compilation sees generated main and test source directories;
- generated sources are produced before compilation.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace
```

## Phase 8: Add Kotlin And KAPT Conditional Wiring

Decision: deferred. Implement this phase later only if it can be tested cheaply
in the default suite without brittle external plugin setup. The deferral is
recorded in `new-application-plugin-design.md`; Java generated-source wiring is
the completed implementation for this slice.

1. Kotlin JVM wiring, behind `plugins.withId("org.jetbrains.kotlin.jvm", ...)`:

   - add main generated directory to `compileKotlin`;
   - make `compileKotlin` depend on `quarkusApplicationGenerateCode`;
   - add test generated directory to `compileTestKotlin`;
   - make `compileTestKotlin` depend on
     `quarkusApplicationGenerateTestCode`.

2. KAPT wiring, behind `plugins.withId("org.jetbrains.kotlin.kapt", ...)`:

   - add main generated directory to `kaptGenerateStubsKotlin`;
   - make `kaptGenerateStubsKotlin` depend on
     `quarkusApplicationGenerateCode`;
   - add test generated directory to `kaptGenerateStubsTestKotlin`;
   - make `kaptGenerateStubsTestKotlin` depend on
     `quarkusApplicationGenerateTestCode`.

3. Do not add Kotlin plugin classes to the production compile classpath unless
   unavoidable. Prefer task-name based lazy configuration and Gradle APIs.

4. Add tests only if they can run under:

   - `--configuration-cache`;
   - `-Dorg.gradle.unsafe.isolated-projects=true`.

Acceptance:

- Kotlin/KAPT wiring is explicitly deferred in the design doc with rationale.

Verification: documentation-only decision; no production wiring was added.

## Phase 9: Add Stubbed TestKit Codegen Smoke Tests

Before relying on real Quarkus codegen, prove Gradle wiring cheaply.

1. Add a TestKit fixture that applies `io.quarkus.application` to a tiny Java
   app.

2. Use a deterministic test-only generated-source writer to avoid real Quarkus
   codegen. Focused unit tests cover `CodegenOperations`
   delegation, so this TestKit smoke test should focus on Gradle source
   wiring and compilation.

3. The stub should write simple Java source files to:

   - `build/generated/sources/quarkus-application/main`;
   - `build/generated/sources/quarkus-application/test`.

4. The app should compile code that references generated main and generated
   test sources.

5. Run:

   - `compileJava`;
   - `compileTestJava`;
   - a second run for up-to-date behavior if the task is cacheable;
   - a build-cache restore test if the codegen task is marked cacheable.

6. All TestKit invocations must include:

   - `--configuration-cache`;
   - `-Dorg.gradle.unsafe.isolated-projects=true`;
   - `--build-cache` for cacheability checks.

Acceptance:

- Java compilation consumes generated source directories;
- configuration cache stores and reuses;
- no real Quarkus codegen is required for this wiring test.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests 'io.quarkus.gradle.application.QuarkusApplicationPluginTest.compilesGeneratedSourcesFromStubbedCodegenWithConfigurationCacheAndIsolatedProjects' --stacktrace
```

## Phase 10: Add Real Tiny Codegen Coverage If Cheap

Add real codegen coverage only if a cheap existing fixture or simple extension
can be used without containers, Docker, native-image, or external services.

1. Search existing Gradle integration fixtures for a tiny extension that
   performs code generation.

2. If a cheap fixture exists, add one TestKit test that:

   - applies `io.quarkus.application`;
   - runs `compileJava` or a named `quarkus<Name>Build`;
   - proves generated sources/classes are present;
   - runs under configuration cache and isolated projects.

3. If no cheap fixture exists, do not invent a large integration test in this
   phase. Add a follow-up to `new-application-plugin-design.md` for real
   codegen integration coverage.

Acceptance:

- real codegen is covered if cheap;
- otherwise the omission is explicit and tracked.

Verification when implemented:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests '*Codegen*' --stacktrace
```

## Phase 11: Add Multi-Project Isolated-Projects Coverage

Prove the P1-AP-01 target: no hostile project walk is needed for codegen.

1. Add a multi-project TestKit app:

   - `:app` applies `io.quarkus.application`;
   - `:lib` is a plain Java library;
   - `:app` has `implementation project(":lib")`;
   - no Quarkus or Jandex plugin is applied to `:lib`.

2. Run a task path that requires codegen and compilation in `:app`, for
   example:

   - `:app:compileJava`; or
   - `:app:quarkusAppBuild` if the fixture can build a tiny package cheaply.

3. Assert:

   - `:app:quarkusApplicationGenerateCode` executes or is up-to-date as
     expected;
   - `:lib` producer tasks are inferred by Gradle artifact dependencies;
   - no dependency-project task wiring is configured by the plugin;
   - configuration cache is stored and reused;
   - isolated projects is enabled.

4. Add a second multi-project test with an unrelated sibling project if not
   already covered by existing new-plugin tests.

Acceptance:

- codegen and compilation work with a project dependency under isolated
  projects;
- the plugin does not inspect dependency projects.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace
```

## Phase 12: Cacheability And Plugin Validation

Make the cacheability decision explicit and validated.

1. If `QuarkusApplicationGenerateCodeTask` is `@CacheableTask`:

   - run build-cache restore TestKit coverage;
   - ensure all inputs and outputs are declared;
   - ensure no broad ambient environment/system property capture is used unless
     the existing explicit escape-hatch behavior disables cacheability.

2. If it is `@DisableCachingByDefault`:

   - document why in the annotation;
   - add a follow-up to `new-application-plugin-design.md` if cacheability is
     still desired.

3. Run Gradle plugin validation:

   ```bash
   cd devtools/gradle
   ./gradlew :gradle-app-plugin:validatePlugins --stacktrace
   ```

4. Search for forbidden execution-time access:

   ```bash
   rg -n "getProject\\(|Task\\.getProject|afterEvaluate|subprojects|allprojects|rootProject|project\\(" \
     devtools/gradle/gradle-app-plugin/src/main/java
   ```

   Review every match. Constructor/configuration-time use may be acceptable;
   task-action or provider-callback use is not.

Acceptance:

- plugin validation is clean;
- cacheability annotation is deliberate;
- no forbidden project-walk pattern was introduced.

Verification:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:validatePlugins --stacktrace
./gradlew :gradle-app-plugin:test --stacktrace
```

## Phase 13: Documentation And Follow-Ups

1. Update `p1-ap-01-codegen-project-walk-plan.md` if implementation revealed a
   design adjustment.

2. Update `new-application-plugin-design.md` deferred follow-ups for anything
   deliberately not implemented, especially:

   - Kotlin/KAPT wiring if deferred;
   - codegen task cacheability if disabled;
   - optional Jandex-index diagnostics.

3. Do not add user-facing Quarkus docs in this phase unless requested. The
   docs-wip design set is still the working source for this effort.

Acceptance:

- docs reflect the implemented state;
- no stale open questions remain in this plan.

Verification:

```bash
git diff --check
git status --short
```

## Final Verification

Run these before declaring P1-AP-01 codegen complete:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test --stacktrace
./gradlew :gradle-app-plugin:validatePlugins --stacktrace
```

If the code touches shared `gradle-model` behavior, also run:

```bash
cd devtools/gradle
./gradlew :gradle-model:test --stacktrace
```

If the code touches Maven module metadata or source formatting-sensitive Java
files, also run:

```bash
./mvnw install -f devtools/gradle -DskipTests
```

## Completion Criteria

P1-AP-01 codegen is complete when:

- `io.quarkus.application` registers:
  - `quarkusApplicationCodegenModel`;
  - `quarkusApplicationTestCodegenModel`;
  - `quarkusApplicationGenerateCode`;
  - `quarkusApplicationGenerateTestCode`.
- The new plugin does not register legacy `quarkusGenerateCode*` task names.
- Main generated sources are compiled before `classes`.
- Test generated sources are compiled before `testClasses`.
- Named application builds see generated main classes through normal Java
  outputs.
- Test codegen uses `LaunchMode.TEST` and the test launch-mode classpath.
- Codegen operation execution goes through a testable operation boundary.
- Unit/ProjectBuilder tests cover task properties and wiring.
- TestKit coverage proves configuration-cache and isolated-project behavior.
- Multi-project TestKit coverage proves Gradle artifact inference replaces the
  legacy dependency-project walk.
- Any unimplemented Kotlin/KAPT wiring is explicitly tracked as a deferred
  follow-up.
