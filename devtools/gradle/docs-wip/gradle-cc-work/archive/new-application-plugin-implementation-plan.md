# New Application Plugin Implementation Plan

Status: implementation plan
Last reviewed: 2026-07-08

## Current Progress

- Phase 1 is implemented.
- Phase 2 is implemented at the source/module level:
  - pure domain/result/planning/codec packages moved to `gradle-app-plugin`;
  - `EffectiveConfigPlan` moved as a required value type;
  - the temporary `gradle-application-plugin` dependency on
    `gradle-app-plugin` was removed in Phase 8.
- Phase 3 is implemented at the source/module level:
  - `EffectiveConfigPlanner`, request, and shape validation
    types moved to `gradle-app-plugin`;
  - `gradle-app-plugin` now owns
    `io.quarkus.gradle.application.internal.config.EffectiveConfig`,
    a dedicated helper that mirrors the named-application subset of legacy
    `EffectiveConfig` behavior;
  - the transitional legacy named task imports were removed with legacy
    named-task ownership in Phase 8.
- Phase 4 is implemented:
  - root named task types moved to
    `io.quarkus.gradle.application.tasks`;
  - launch/dev/remote-dev/continuous-test task shells fail immediately with
    reserved-task messaging;
  - tests inject operations through test-only support rather than public task
    internals.
- Phase 5 is implemented:
  - the worker-backed production operation backend moved to
    `io.quarkus.gradle.application.internal.execution.worker`;
  - copied worker classes are repackaged in `gradle-app-plugin` without a
    production dependency on `gradle-application-plugin`;
  - moved build tasks default to the worker-backed backend when tests do not
    inject operations;
  - broad worker environment forwarding was removed; the only whole-environment
    capture left in the new module is the explicit `legacyAmbientConfigCapture`
    task escape hatch;
  - `QuarkusApplicationRealPackageBuildTest` is re-enabled.
- Phase 6 is implemented:
  - the named application DSL moved to
    `io.quarkus.gradle.application.dsl`;
  - `gradle-app-plugin` now creates the `quarkusApplication` extension;
  - `gradle-app-plugin` registers named build/image/AOT/deploy/native-test and
    reserved dev/continuous-test tasks from `quarkusApplication`;
  - the new plugin logs a migration warning when `io.quarkus` is also applied;
  - the legacy plugin no longer imports the moved DSL after Phase 8.
- Phase 2 through Phase 6 Gradle verification has run:
  - `./gradlew :gradle-app-plugin:test --stacktrace`;
  - `./gradlew :gradle-application-plugin:test --tests io.quarkus.gradle.application.tasks.QuarkusApplicationTaskRegistrationTest --stacktrace`;
  - `./gradlew :gradle-application-plugin:test --stacktrace`.
- Phase 7 is implemented:
  - `gradle-app-plugin` owns
    `io.quarkus.gradle.application.internal.modelgen.GenerateModelTask`;
  - named build/image/AOT/deploy tasks consume that model task's output through
    provider-backed `RegularFileProperty` wiring;
  - the new model task does not extend legacy `QuarkusApplicationModelTask` and
    does not use `Task.getProject()`;
  - single-project tiny-app TestKit coverage builds a real fast-jar with
    `--configuration-cache` and
    `-Dorg.gradle.unsafe.isolated-projects=true`;
  - the first implementation exposed a remaining multi-project execution
    blocker in reused `gradle-model` extension/deployment dependency detection.
- Phase 7B is implemented:
  - `gradle-app-plugin` owns
    `ClasspathBuilder`,
    `DeploymentArtifactsValueSource`, and
    `ExtensionDescriptorReader`;
  - the new classpath path scans only resolved runtime artifact files for
    `META-INF/quarkus-extension.properties` and adds deployment artifacts from
    those descriptors;
  - it does not attempt to classify project dependencies by reading dependency
    projects;
  - real multi-project TestKit coverage now runs `:app:quarkusAppBuild` with
    `--configuration-cache` and
    `-Dorg.gradle.unsafe.isolated-projects=true`, both with an unrelated sibling
    project and with `implementation project(":lib")` for a plain Java library.
- Phase 7B Gradle verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --stacktrace`;
  - `./gradlew :gradle-application-plugin:test --tests io.quarkus.gradle.application.tasks.QuarkusApplicationTaskRegistrationTest --stacktrace`.
- Phase 8 is implemented:
  - `QuarkusPluginExtension` no longer exposes `builds {}` or
    `configInputs {}` on the legacy `quarkus {}` extension;
  - `QuarkusPlugin` no longer registers named application tasks;
  - `gradle-application-plugin` no longer contains
    `io/quarkus/gradle/tasks/application/**`;
  - the temporary `implementation(project(":gradle-app-plugin"))` dependency
    was removed from `gradle-application-plugin`;
  - legacy tests that asserted the transitional named DSL on `quarkus {}` were
    removed from the legacy module.
- Phase 8 Gradle verification has run:
  - `./gradlew :gradle-application-plugin:test --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --stacktrace`.
- Phase 9 is implemented:
  - app-plugin TestKit coverage builds all four JVM package output types for a
    tiny Quarkus application;
  - a custom consumer task wires all package-result files through
    `tasks.named(...).flatMap(...)`;
  - second-run assertions cover `UP-TO-DATE` behavior for the package tasks,
    the application model task, and the consumer task with `--build-cache`;
  - package-result receipt assertions validate the expected fast-jar,
    mutable-jar, uber-jar, and legacy-jar output shapes;
  - ProjectBuilder coverage verifies image, AOT-image, and deployment receipt
    topology without executing Docker/Podman/Kubernetes/native work;
  - ProjectBuilder coverage verifies plugin-level named task collision
    handling;
  - existing coverage preserves coexistence warning behavior and failing
    dev/remote-dev/continuous-test stubs.
- Phase 9 Gradle verification has run:
  - `./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace`;
  - `./gradlew :gradle-app-plugin:test --stacktrace`.
- Phase 10 is implemented:
  - `gradle-app-plugin:validatePlugins` is clean after adding explicit
    cacheability annotations to the new task types and removing the invalid
    `@Internal` annotation from the injected `ProviderFactory` accessor;
  - full Gradle and Maven verification for `devtools/gradle` is green.
- Phase 10 verification has run:
  - `./gradlew :gradle-app-plugin:test --stacktrace`;
  - `./gradlew :gradle-application-plugin:test --stacktrace`;
  - `./gradlew build --stacktrace`;
  - `./mvnw install -f devtools/gradle -DskipTests`;
  - `./mvnw verify -f devtools/gradle -Dtest-containers -Dstart-containers`.
- All planned implementation phases in this document are complete.

## Objective

Create a separate Gradle-native Quarkus application plugin in
`devtools/gradle/gradle-app-plugin`, move the named application task model out
of the legacy `io.quarkus` plugin module, and make the new plugin the only
owner of the named `quarkusApplication { builds { ... } }` DSL and task
registration.

An agent following this plan must preserve the hard gates in
`new-application-plugin-design.md`:

- TestKit tests use `--configuration-cache`;
- TestKit tests use `-Dorg.gradle.unsafe.isolated-projects=true`;
- use `--build-cache` for cacheable task-path tests unless the task is
  intentionally side-effecting or non-cacheable;
- no `Task.getProject()` or equivalent mutable Gradle model access from task
  actions;
- no live `Project`, `Task`, `Configuration`, `SourceSet`, extension, or task
  container capture in task actions, worker parameters, or provider callbacks;
- no cross-project mutable model access;
- no task-name collisions with legacy tasks;
- test-supporting code stays out of `src/main`.

## Scope

In scope:

- create `devtools/gradle/gradle-app-plugin`;
- create plugin id `io.quarkus.application`;
- create extension `quarkusApplication`;
- move named application task/domain/result/planning code to
  `io.quarkus.gradle.application.*`;
- move or replace the reusable config and execution pieces needed by the new
  plugin;
- remove named application DSL and task registration from legacy `io.quarkus`;
- register initial failing stubs for dev and continuous-test tasks;
- move/rewrite tests so the new module owns named task coverage.

Out of scope:

- full Gradle-native dev mode;
- full Gradle-native continuous testing;
- automatic Jandex plugin application to dependency projects;
- side-effecting image/deploy cacheability;
- broad application-model/project-dependency metadata design beyond the
  minimal path needed to make the new plugin testable.

## Phase 0: Safety Baseline

1. Confirm the worktree status.
2. Do not revert unrelated changes.
3. Read:
   - `new-application-plugin-design.md`;
   - `archive/new-application-plugin-move-investigation.md`;
   - `p1-ap-01-codegen-project-walk-plan.md`;
   - module-local and repository `AGENTS.md` files.
4. Record the initial list of existing named application files:
   - `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/**`;
   - `devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/application/**`.

Acceptance:

- no code edits yet;
- the agent knows whether there are user changes in the touched files.

## Phase 1: Create The New Module Shell

1. Add `devtools/gradle/gradle-app-plugin/AGENTS.md`.

   It must state the module hard gates:

   - all TestKit tests use `--configuration-cache` and
     `-Dorg.gradle.unsafe.isolated-projects=true`;
   - use `--build-cache` for cacheable task-path tests;
   - no `Task.getProject()` from task actions;
   - no captured live Gradle model types;
   - no cross-project mutable model access;
   - no public internal helpers on DSL-facing types;
   - no legacy task-name collisions;
   - expensive operations behind testable interfaces;
   - test support outside `src/main`.

2. Create `devtools/gradle/gradle-app-plugin/build.gradle.kts` using the
   `gradle-extension-deployment-plugin` pattern:

   - apply `id("io.quarkus.devtools.gradle-plugin")`;
   - set `group = "io.quarkus.application"`;
   - register plugin id `io.quarkus.application`;
   - implementation class:
     `io.quarkus.gradle.application.QuarkusApplicationPlugin`;
   - add reproducible jar settings;
   - add `testImplementation(testFixtures(project(":gradle-model")))`;
   - add only dependencies required by moved code.

3. Create a minimal
   `io.quarkus.gradle.application.QuarkusApplicationPlugin` class that
   implements `Plugin<Project>` and does not register DSL or tasks yet. The
   class exists only so the new module shell can compile and expose the plugin
   id.

4. Create `devtools/gradle/gradle-app-plugin/pom.xml` using the
   `gradle-extension-deployment-plugin` POM as the template:

   - parent `io.quarkus.gradle.plugin.parent`;
   - artifact id `io.quarkus.application.gradle.plugin`;
   - packaging `pom`;
   - `artifactFilePrefix` = `gradle-app-plugin`;
   - deploy profile mirroring the other Gradle plugin modules.

5. Add the module to:

   - `devtools/gradle/settings.gradle.kts`;
   - `devtools/gradle/pom.xml`, before `gradle-application-plugin`.

Acceptance:

- `./gradlew :gradle-app-plugin:tasks` works from `devtools/gradle`;
- no named application source code has moved yet.

## Phase 2: Move Pure Domain, Result, Planning, And Codec Code

1. Move these production packages from
   `gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application`
   to `gradle-app-plugin/src/main/java/io/quarkus/gradle/application`:

   - `model` -> `io.quarkus.gradle.application.model`;
   - `planning` -> `io.quarkus.gradle.application.internal.planning`;
   - `deployment` -> `io.quarkus.gradle.application.internal.deployment`;
   - `image` -> `io.quarkus.gradle.application.internal.image`;
   - `nativeimage` -> `io.quarkus.gradle.application.internal.nativeimage`;
   - `packaging` -> `io.quarkus.gradle.application.internal.packaging`;
   - execution request/result/codecs that do not depend on legacy workers.

2. Move the immutable `EffectiveConfigPlan` value type to
   `io.quarkus.gradle.application.internal.config` because moved execution request
   types depend on it. Leave the effective-config planner and behavior in
   Phase 3.

3. Rewrite package declarations and imports from
   `io.quarkus.gradle.tasks.application` to `io.quarkus.gradle.application`.

4. Move matching pure tests into
   `gradle-app-plugin/src/test/java/io/quarkus/gradle/application/**` and
   rewrite imports.

5. Keep test-support classes in `src/test` or test fixtures only. Do not move
   `QuarkusApplicationStubBuildOperations` into production sources.

6. Add a temporary `implementation(project(":gradle-app-plugin"))` dependency
   from `gradle-application-plugin` to the new module so legacy named task
   classes that have not moved yet can compile against the moved pure types.
   This dependency is transitional and must be removed when Phase 8 removes
   named application task ownership from the legacy plugin.

Acceptance:

- pure tests for model/planning/result/codecs compile in `gradle-app-plugin`;
- no production class in `gradle-app-plugin` imports
  `io.quarkus.gradle.QuarkusPlugin`,
  `io.quarkus.gradle.extension.*`, or legacy task registration classes.
- `gradle-application-plugin` may depend on `gradle-app-plugin` during the
  transition, but `gradle-app-plugin` must not depend on
  `gradle-application-plugin`.

## Phase 3: Replace Or Move Effective Config Behavior

1. Remove the dependency on legacy
   `io.quarkus.gradle.tasks.EffectiveConfig` from
   `EffectiveConfigPlanner`.

2. Create
   `io.quarkus.gradle.application.internal.config.EffectiveConfig`
   inside `gradle-app-plugin`, containing only the behavior required by named
   application builds.

3. Do not move legacy `EffectiveConfig` wholesale unless this phase proves the
   new dedicated type cannot preserve required behavior. If that happens, stop
   and update this plan before continuing.

4. Preserve behavior covered by `EffectiveConfigPlannerTest`.

5. Keep `legacyAmbientConfigCapture` as an explicit escape hatch only if tests
   prove affected tasks:

   - are configuration-cache incompatible;
   - are not cacheable;
   - are never up-to-date.

Acceptance:

- `gradle-app-plugin` does not import `io.quarkus.gradle.tasks.EffectiveConfig`
  or `EffectiveConfigProvider`;
- config planner tests pass in the new module.

## Phase 4: Move Task Types And Make Dev/Test Stubs Explicit

1. Move root task types into `io.quarkus.gradle.application.tasks`.

   Include:

   - `QuarkusApplicationTask`;
   - `QuarkusApplicationBuildTask`;
   - `QuarkusApplicationPackageTask`;
   - `QuarkusApplicationNativeTask`;
   - `QuarkusApplicationImageTask`;
   - `QuarkusApplicationImageBuildTask`;
   - `QuarkusApplicationImagePushTask`;
   - `QuarkusApplicationAotEnhancedImageTask`;
   - `QuarkusApplicationAotEnhancedImageBuildTask`;
   - `QuarkusApplicationAotEnhancedImagePushTask`;
   - `QuarkusApplicationDeployTask`;
   - `QuarkusApplicationAotTrainingTask`;
   - `QuarkusApplicationNativeTestTask`;
   - launch/dev/remote-dev/continuous-test task shells.

2. Make initial unsupported tasks fail immediately and do no work:

   - `quarkus<App>Dev`;
   - `quarkus<App>ContinuousTest`;
   - any remote-dev task if registered;
   - any launch task that would imply old dev-mode behavior.

3. Error text must say the task is reserved by `io.quarkus.application` but
   Gradle-native dev/continuous-test integration is not implemented yet.

4. Review Java visibility:

   - public abstract Gradle properties are allowed when they are intended task
     API;
   - helper methods must be `protected`, package-private, or private;
   - no public internal methods on DSL-facing types.

Acceptance:

- task classes compile in `gradle-app-plugin`;
- unsupported dev/continuous-test tasks have focused unit or ProjectBuilder
  tests proving immediate failure;
- no moved task action calls `Task.getProject()`.

## Phase 5: Production Operation Backend

1. Keep the already-moved `BuildOperations` and request
   types in the new module. Move the production worker-backed implementation
   into `gradle-app-plugin`.

2. Keep expensive operations behind the operations interface:

   - package build;
   - native build;
   - normal image build/push;
   - AOT image build/push;
   - deployment.

3. Replace the legacy worker dependency. Do not make `gradle-app-plugin`
   depend on `gradle-application-plugin`.

4. Create a new production backend in `gradle-app-plugin` under
   `io.quarkus.gradle.application.internal.execution.worker`.

   Start from the behavior of the existing legacy workers, but do not import
   the legacy worker classes. Copy/repackage only the minimal non-legacy logic
   needed by named application operations. Leave the old worker classes in
   `gradle-application-plugin` for legacy tasks.

5. Remove or narrow broad worker environment forwarding:

   - do not call `providers.environmentVariablesPrefixedBy("").get()` to copy
     the entire environment into worker fork options;
   - pass only declared environment inputs or documented minimal values needed
     for process execution;
   - if an escape hatch is retained, mark affected tasks configuration-cache
     incompatible, non-cacheable, and never up-to-date.

6. Model execution-affecting values:

   - worker max heap;
   - PATH/JAVA_HOME handling where required;
   - build fork options, preferably as typed properties instead of arbitrary
     `Action<JavaForkOptions>`.

Acceptance:

- `gradle-app-plugin` has no production dependency on
  `gradle-application-plugin`;
- package/native/image/deploy task tests can inject test operations without
  production test stubs;
- worker-backed tests pass or are replaced by equivalent production-backend
  tests;
- `QuarkusApplicationRealPackageBuildTest` is re-enabled and passes.

## Phase 6: New DSL And Plugin Registration

1. Create
   `io.quarkus.gradle.application.QuarkusApplicationPlugin`.

2. Register extension:

   ```java
   quarkusApplication
   ```

   using a new extension type under
   `io.quarkus.gradle.application.dsl`.

3. Do not retain a live `Project` in DSL objects.

   Use constructor-injected Gradle services where needed:

   - `ObjectFactory`;
   - `ProviderFactory`;
   - `ProjectLayout`;
   - possibly `FileSystemOperations` or `ExecOperations` only when needed.

4. Register named tasks from the new extension:

   - `quarkus<App>Build`;
   - `quarkus<App>ImageBuild`;
   - `quarkus<App>ImagePush`;
   - `quarkus<App>AotEnhancedImageBuild`;
   - `quarkus<App>AotEnhancedImagePush`;
   - `quarkus<App>DeployTo<Deployment>`;
   - `quarkus<App>NativeTest` for native builds, if still part of the current
     new model;
   - `quarkus<App>Dev` as a failing stub;
   - `quarkus<App>ContinuousTest` as a failing stub.

5. Add coexistence warning:

   - if `io.quarkus` is also applied to the same project, log a warning from
     the new plugin;
   - do not fail;
   - warning must state coexistence is migration mode and legacy tasks do not
     inherit the new plugin's Gradle compatibility guarantees.

6. Generate task names only through the moved task name planner.

7. Validate collisions:

   - two build names with same task segment;
   - two deployment names with same task segment for a build;
   - generated task names colliding with legacy names.

Acceptance:

- applying only `io.quarkus.application` creates `quarkusApplication`;
- applying both `io.quarkus` and `io.quarkus.application` logs the migration
  warning and both plugins configure;
- generated task names do not include legacy `quarkusBuild`, `buildNative`,
  `imageBuild`, `imagePush`, or `deploy`.

## Phase 7: Application Model Generation Path

1. Add a new plugin-owned application model generation task or service.

2. It must not:

   - traverse dependency projects;
   - call `rootProject`, `subprojects`, `allprojects`, or `project(":x")` for
     dependency introspection;
   - read another project's extensions, source sets, configurations, tasks,
     layout, group/version, or mutable state;
   - apply plugins to dependency projects.

3. It should consume:

   - this project's declared resolvable classpaths;
   - resolved artifacts and variants;
   - generated metadata files only when explicitly exposed as artifacts.

4. Wire named build tasks to the generated model file through provider-backed
   properties.

5. Add both single-project and multi-project TestKit coverage for the
   application-model path.

6. If the multi-project isolated-project test cannot pass because the model
   path still needs deeper design, stop and update this plan before claiming
   the new plugin implementation complete.

Acceptance:

- named build tasks do not depend on legacy `QuarkusApplicationModelTask`;
- single-project real tiny-app package test passes;
- multi-project isolated-project configuration smoke test passes.

Status:

- Implemented with the scoped acceptance above.
- The initial multi-project execution blocker is addressed by Phase 7B.

## Phase 7B: Isolated Multi-Project Execution Classpath Path

This phase is required before claiming that `io.quarkus.application` can build
real multi-project applications with isolated-projects enabled.

1. Add a new app-plugin-owned classpath/deployment-dependency path, or split the
   reusable `gradle-model` path, so package execution no longer calls:

   - `ToolingUtils.findLocalProject(...)`;
   - `DependencyUtils.getProjectExtensionDependencyOrNull(...)`;
   - `DependencyUtils.getExtensionInfoOrNull(Project, Project)`;
   - `Project.getRootProject().getSubprojects()` or equivalent project graph
     scans.

2. Preserve external Quarkus extension detection by reading descriptors from
   resolved artifacts and artifact variants only.

3. Do not attempt to infer that an arbitrary project dependency is a Quarkus
   extension by inspecting the dependency project. If project extension support
   is needed, require explicit metadata exposed as a consumable artifact/variant
   by the dependency project.

4. Keep deployment dependency resolution compatible with `quarkus-arc` and other
   ordinary external extensions used by a tiny app.

5. Add TestKit coverage that applies only `io.quarkus.application` in `:app`,
   includes an unrelated `:lib` project, and runs `:app:quarkusAppBuild` with:

   - `--configuration-cache`;
   - `-Dorg.gradle.unsafe.isolated-projects=true`.

6. Add TestKit coverage where `:app` has `implementation project(":lib")` and
   `:lib` is a plain Java library, then run `:app:quarkusAppBuild` under the
   same flags.

Acceptance:

- real multi-project package execution passes with isolated-projects when an
  unrelated sibling project exists;
- real multi-project package execution passes with isolated-projects when the
  app depends on a plain Java project;
- no new app-plugin production code scans dependency projects or reads another
  project's mutable model.

Status:

- Implemented.
- The new plugin-owned classpath path intentionally supports external Quarkus
  extensions through resolved artifact descriptors only. Project dependencies
  are treated as ordinary runtime dependencies unless they expose explicit
  extension metadata as consumable artifacts or variants in a later phase.

## Phase 8: Remove Named Task Ownership From The Legacy Plugin

1. Remove named application DSL from `QuarkusPluginExtension`:

   - `getBuilds()`;
   - `builds(...)`;
   - `getConfigInputs()`;
   - `configInputs(...)`;
   - fields and constructor initialization for those types.

2. Remove named application task registration from `QuarkusPlugin`:

   - `registerNamedApplicationTasks(...)`;
   - helper methods used only by named application task registration;
   - named application imports.

3. Keep legacy tasks untouched:

   - `quarkusBuild`;
   - `buildNative`;
   - `testNative`;
   - `imageBuild`;
   - `imagePush`;
   - `deploy`;
   - `quarkusDev`;
   - `quarkusRemoteDev`;
   - `quarkusTest`.

4. Remove or move legacy tests that asserted named application DSL on
   `quarkus {}`.

Acceptance:

- `gradle-application-plugin` no longer contains
  `io/quarkus/gradle/tasks/application/**`;
- legacy `io.quarkus` plugin tests pass without named application DSL;
- new named tasks are available only from `io.quarkus.application`.

## Phase 9: Test Migration And Coverage

1. Move pure unit tests with their production packages.

2. Rewrite ProjectBuilder tests to apply `io.quarkus.application`.

3. Rewrite TestKit tests to apply `io.quarkus.application` and use:

   - `--configuration-cache`;
   - `-Dorg.gradle.unsafe.isolated-projects=true`;
   - `--build-cache` for cacheable package/codegen/model task paths.

4. Add or preserve tests for:

   - `quarkusApplication { builds { fastJar("app") } }`;
   - all four JVM package output types for a tiny Quarkus app;
   - custom task consuming package result files through
     `TaskProvider.flatMap(...)`;
   - second run `UP-TO-DATE` behavior for cacheable/package result paths;
   - image/deploy receipt wiring with stubbed operations;
   - AOT image receipt wiring with stubbed operations;
   - coexistence warning with `io.quarkus`;
   - dev and continuous-test failing stubs;
   - no legacy task-name collisions.

5. Keep expensive real Docker/Podman/Kubernetes/native tests gated or
   deferred.

Acceptance:

- no test build script imports production test stubs;
- test support lives in `src/test` or test fixtures;
- TestKit assertions verify configuration-cache reuse where applicable.

## Phase 10: Verification

Run commands sequentially.

From `devtools/gradle`:

```bash
./gradlew :gradle-app-plugin:test --stacktrace
./gradlew :gradle-application-plugin:test --stacktrace
./gradlew build --stacktrace
```

From repository root:

```bash
./mvnw install -f devtools/gradle -DskipTests
./mvnw verify -f devtools/gradle -Dtest-containers -Dstart-containers
```

Do not run test modules concurrently.

## Final Acceptance Checklist

- `devtools/gradle/gradle-app-plugin` exists and registers
  `io.quarkus.application`.
- `quarkusApplication` is the new DSL root.
- Named application production code lives under
  `io.quarkus.gradle.application.*`.
- The legacy plugin module no longer owns named application DSL or task
  registration.
- The new plugin has no production dependency on the legacy
  `gradle-application-plugin` module.
- Legacy and new plugins can be applied together, with a warning from the new
  plugin.
- Dev and continuous-test tasks are registered as immediate failing stubs.
- TestKit tests use configuration cache and isolated projects.
- Cacheable task-path tests use build cache where meaningful.
- No moved task action uses `Task.getProject()`.
- No test support implementation lives in production sources.
