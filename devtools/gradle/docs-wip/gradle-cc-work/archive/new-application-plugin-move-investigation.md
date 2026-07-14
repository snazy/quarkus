# New Application Plugin Move Investigation

Status: historical; investigation complete
Last reviewed: 2026-07-08

Historical note: this document captures the pre-move inventory when the named
application task model still lived in the legacy `gradle-application-plugin`.
The implementation has since moved the model into
`devtools/gradle/gradle-app-plugin` under `io.quarkus.gradle.application`.
Use `new-application-plugin-design.md` and current source for active work.

## Objective

Determine whether the named Quarkus application task model currently living
inside `devtools/gradle/gradle-application-plugin` can be moved into a new
`devtools/gradle/gradle-app-plugin` module, and identify the non-obvious
seams that an implementation plan must handle.

This investigation used two delegated explorer passes:

- source/class dependency inventory for
  `io.quarkus.gradle.tasks.application`;
- module/test wiring inventory for the proposed `gradle-app-plugin` split.

## Pre-Move State

The named application task model is currently under:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/application/**`
- `devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/application/**`

That module is the legacy `io.quarkus` Gradle plugin module:

- Gradle module: `gradle-application-plugin`
- Maven artifact: `io.quarkus.gradle.plugin`
- plugin id: `io.quarkus`
- implementation class: `io.quarkus.gradle.QuarkusPlugin`

The current named application DSL is exposed from the legacy extension:

- `io.quarkus.gradle.extension.QuarkusPluginExtension#getBuilds()`
- `io.quarkus.gradle.extension.QuarkusPluginExtension#builds(...)`
- `io.quarkus.gradle.extension.QuarkusPluginExtension#getConfigInputs()`
- `io.quarkus.gradle.extension.QuarkusPluginExtension#configInputs(...)`

The current named task registration is embedded in
`QuarkusPlugin.registerNamedApplicationTasks(...)`.

## Mostly Pure Move Inventory

These packages are new-task-domain code and can move to
`io.quarkus.gradle.application.*` with package/import rewrites:

- `model/*`
- `planning/*`
- `deployment/*`
- `image/*`
- `nativeimage/*`
- `packaging/*`
- most `execution/*` request/result/codec interfaces
- root task types such as `QuarkusApplicationTask`,
  `QuarkusApplicationBuildTask`, package/native/image/deploy/AOT tasks, and
  launch/dev/continuous-test task shells

Important caveat: root Gradle task types are movable, but their execution
backend and registration are not self-contained yet.

## Non-Pure Seams

### Effective Config

`EffectiveConfigPlanner` imports legacy
`io.quarkus.gradle.tasks.EffectiveConfig`.

The new plugin should not depend on the legacy plugin module. The
implementation plan resolves this by creating a dedicated new-module config
planner under `io.quarkus.gradle.application.internal.config` that preserves the
behavior needed by named application tasks.

The current named application code does not use `EffectiveConfigProvider`.

### Worker Backend

`WorkerBackedBuildOperations` imports legacy worker/tooling
classes:

- `io.quarkus.gradle.tasks.worker.BuildWorker`
- `io.quarkus.gradle.tasks.worker.DeployWorker`
- `io.quarkus.gradle.tasks.worker.BuildAotEnhancedImageForApplicationWorker`
- `io.quarkus.gradle.tooling.ToolingUtils`

The new plugin cannot depend on the legacy `gradle-application-plugin` module.
The implementation must move, extract, or replace the minimal production
operation backend needed by the new module.

Reverse references also exist: legacy workers currently import some named
application result/codec types. Moving those result/codec types requires
repackaging the named-application worker backend into the new module and
leaving legacy workers in the legacy module for legacy tasks.

### Registration And Extension Wiring

`QuarkusPlugin.registerNamedApplicationTasks(...)` owned:

- build name validation;
- task name collision validation;
- named build task registration;
- image build/push registration;
- AOT training/image registration;
- deployment registration;
- native-test registration;
- app model task wiring;
- source set/runtime classpath/source directory wiring;
- config input propagation;
- project-derived application name/version conventions.

That logic must move into a new plugin-owned registration component under
`io.quarkus.gradle.application.plugin`.

The legacy plugin should stop exposing/registering the named application DSL
and tasks. Users who want the new named task model should apply
`io.quarkus.application`.

### Application Model Generation

The named build tasks currently consume a serialized application model produced
by legacy `QuarkusApplicationModelTask`.

The new plugin needs its own application-model generation path or a reused path
that does not read dependency projects' mutable Gradle model and does not
depend on legacy plugin task wiring.

This remains the hardest compatibility seam because project-isolation support
depends on it.

## Configuration Cache And Isolation Hazards

No `Task.getProject()` calls were found in task actions under
`io.quarkus.gradle.tasks.application`.

Known hazards that must be addressed during the move:

- `legacyAmbientConfigCapture` can capture all Gradle properties, system
  properties, and environment variables. If kept, it must remain an explicit
  escape hatch that makes affected tasks configuration-cache incompatible,
  disables caching, and disables up-to-date checks.
- `WorkerBackedBuildOperations` copies all environment
  variables into worker fork options. This is not a declared task input and
  must be removed, narrowed, or modeled before claiming full cache correctness.
- `PATH`, `gradle.quarkus.gradle-worker.max-heap`, and arbitrary
  `buildForkOptions` affect execution but are currently `@Internal`.
- Existing DSL classes accept/capture `Project` in constructors and provider
  conventions. The new plugin DSL must be constructed from `ObjectFactory`,
  `ProviderFactory`, `ProjectLayout`, and explicit providers without retaining
  a live `Project`.
- Current registration sets application version with a provider that captures
  `Project`. The new plugin should use a provider supplied during
  configuration and avoid retaining `Project` in task actions or provider
  callbacks.

## Test Inventory

Move or rewrite with the new module:

- pure tests under `config/`, `deployment/`, `execution/`, `image/`, `model/`,
  `nativeimage/`, `packaging/`, and `planning/`;
- `QuarkusApplicationStubBuildOperations`, but keep it in test sources or test
  fixtures only;
- pure task execution tests currently mixed into
  `QuarkusApplicationTaskRegistrationTest`;
- real tiny-app package tests, rewritten to apply `io.quarkus.application`.

Keep in the legacy module or delete if no longer relevant:

- tests that assert `QuarkusPluginExtension` exposes `builds`;
- tests that assert `io.quarkus` registers named application tasks;
- tests that rely on build scripts importing test support from production
  classpaths.

The new module should use the existing `gradle-model` test fixtures for
`BaseGradleTest` if needed.

## Module Wiring Inventory

Precedent:

- `gradle-extension-deployment-plugin` shows the standalone plugin module
  shape.
- `gradle-extension-plugin` shows how TestKit plugin classpath can include
  another module's runtime classpath through `PluginUnderTestMetadata`.

Required new module wiring:

- add `gradle-app-plugin` to `devtools/gradle/settings.gradle.kts`;
- add `<module>gradle-app-plugin</module>` to `devtools/gradle/pom.xml`;
- create `devtools/gradle/gradle-app-plugin/build.gradle.kts`;
- create `devtools/gradle/gradle-app-plugin/pom.xml`;
- register plugin id `io.quarkus.application`;
- use implementation class
  `io.quarkus.gradle.application.QuarkusApplicationPlugin`;
- add module-local `AGENTS.md` before implementation work proceeds.

## Conclusion

The named application task model is movable, but it is not a simple package
rename. A blind implementation plan must explicitly handle:

1. new module skeleton and hard-gate `AGENTS.md`;
2. package move from `io.quarkus.gradle.tasks.application` to
   `io.quarkus.gradle.application`;
3. removal of named application DSL/registration from the legacy `io.quarkus`
   plugin;
4. replacement or movement of `EffectiveConfig`;
5. replacement or movement of the worker-backed production operations;
6. a new application-model generation path that does not depend on legacy
   project traversal;
7. TestKit coverage using `--configuration-cache` and isolated projects.
