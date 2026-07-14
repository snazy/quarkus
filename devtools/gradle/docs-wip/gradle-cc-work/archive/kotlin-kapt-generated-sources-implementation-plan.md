# Kotlin/KAPT Generated Sources Implementation Plan

Design: `../kotlin-kapt-generated-sources-design.md`
Status: implemented
Last reviewed: 2026-07-13

## Goal

Wire `io.quarkus.application` generated main/test sources into Kotlin/JVM and
KAPT tasks while preserving the design constraint that generated directories
are not added to shared `SourceSet`s.

## Phase 1: Kotlin API Feasibility Check

Status: complete.

The helper initially appeared able to use stable Kotlin Gradle Plugin API types
from `org.jetbrains.kotlin:kotlin-gradle-plugin-api`, but TestKit proved that
the application plugin cannot load those types from its own plugin classloader
when Kotlin is supplied by the consuming build.

Findings:

- `kotlin-gradle-plugin-api` exposes
  `org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool`, which has
  `source(Object...)`, `setSource(Object...)`, and `getSources()`.
- `org.jetbrains.kotlin.gradle.tasks.KaptGenerateStubs` is also in the API
  artifact and extends the same source-capable Kotlin compile path.
- A small compile probe succeeded with only the Gradle API jar and
  `kotlin-gradle-plugin-api` on the compile classpath.
- The legacy `gradle-application-plugin` and `gradle-model` modules already use
  `compileOnly(libs.kotlin.gradle.plugin.api)`, but that is not enough for the
  new plugin helper because the Kotlin task classes still are not visible to the
  application plugin classloader in the consuming TestKit build.
- The legacy plugin still keeps Kotlin-specific wiring generic and localized;
  the new plugin should improve containment with a package-private helper, not
  leak Kotlin types through public APIs.
- A direct typed helper failed in TestKit with
  `NoClassDefFoundError: org/jetbrains/kotlin/gradle/tasks/KotlinCompileTool`.

Decision for implementation:

- Do not add a Kotlin compile-time dependency to `gradle-app-plugin`.
- Keep `KotlinGeneratedSourceWiring` package-private and classloader-neutral.
- Use plugin-id hooks in `TaskRegistration`, task-name matching in the helper,
  and localized reflection to call the public `source(Object...)` shape on the
  matching Kotlin/KAPT tasks.
- Do not expose Kotlin types from public task APIs or `TaskRegistration` method
  signatures.

## Phase 2: Helper

Add a package-private helper next to `TaskRegistration`, for example
`KotlinGeneratedSourceWiring`.

Responsibilities:

- wire main generated output into `compileKotlin`;
- wire test generated output into `compileTestKotlin`;
- wire main generated output into `kaptGenerateStubsKotlin`;
- wire test generated output into `kaptGenerateStubsTestKotlin`;
- keep missing tasks a no-op;
- keep any reflection or Kotlin-specific type references local to the helper.

The helper should accept only Gradle-neutral inputs from `TaskRegistration`,
such as `Project`, task names, and `TaskProvider<QuarkusApplicationGenerateCodeTask>`.

Implemented approach after TestKit validation:

- use `tasks.matching(task -> task.getName().equals(...)).configureEach(...)`
  inside Kotlin/KAPT plugin-id hooks;
- call the task's `source(Object...)` method by localized reflection with
  `generateTask.flatMap(QuarkusApplicationGenerateCodeTask::getGeneratedOutputDirectory)`;
- add the matching `dependsOn(generateTask)`.

## Phase 3: Task Registration Wiring

In `TaskRegistration`, after generated-code task registration and existing Java
compile wiring:

- keep `wireGeneratedSourcesIntoJavaCompilation(project)` unchanged except for
  small extraction if it improves symmetry;
- add unconditional plugin hooks:
  `plugins.withId("org.jetbrains.kotlin.jvm", ...)`;
- delegate Kotlin compile wiring to the helper;
- add unconditional plugin hooks:
  `plugins.withId("org.jetbrains.kotlin.kapt", ...)`;
- delegate KAPT stub wiring to the helper.

The plugin hooks must work in both plugin orders:

- Kotlin/KAPT applied before `io.quarkus.application`;
- Kotlin/KAPT applied after `io.quarkus.application`.

Prefer live task configuration inside the helper:

- typed `tasks.withType(...).configureEach(...)` if stable typed APIs are used;
- otherwise `tasks.matching(name predicate).configureEach(...)`.

Avoid `tasks.named(...)` if investigation shows Kotlin registers the task after
the plugin callback.

## Phase 4: Tests

Add focused TestKit coverage under `QuarkusApplicationPluginTest` or a new
dedicated test class if that keeps fixtures smaller.

Required tests:

- Kotlin/JVM applied before `io.quarkus.application`: `compileKotlin` and
  `compileTestKotlin` see the generated main/test directories.
- Kotlin/JVM applied after `io.quarkus.application`: same assertions.
- KAPT applied before `io.quarkus.application`: `kaptGenerateStubsKotlin` and
  `kaptGenerateStubsTestKotlin` see the generated main/test directories.
- KAPT applied after `io.quarkus.application`: same assertions.
- Plain Java project with no Kotlin/KAPT still configures and runs existing
  new-plugin tests without Kotlin classloading failures.
- Existing assertion remains true: generated directories are not added to shared
  main/test source sets.
- KSP plus `sourcesJar` remains cycle-free. This is not KSP source-consumption
  support; it is the regression guard that generated directories were not added
  back to shared `SourceSet`s. This remains a follow-up for the new plugin test
  suite because `gradle-app-plugin` currently has a Kotlin version in its local
  catalog but no KSP version source; the legacy integration test receives
  `kspVersion` from the integration-test resource filtering path.

Each Kotlin/KAPT test should include source-input and behavioral proof:

- source-input proof: assert the relevant task inputs include the generated
  directory without reading the write-only Kotlin `source` property;
- behavioral proof: compile a fixture that can only succeed if the generated
  source is visible to the relevant phase.

Do not assert dependency wiring by calling `taskDependencies.getDependencies`.
That introspection is not configuration-cache/project-isolation friendly, and
with KAPT it can force task graph resolution before Kotlin task properties are
finalized.

Behavioral Kotlin fixture:

- use a stubbed Quarkus codegen provider to write a generated type under
  `quarkusApplicationGenerateCode` and/or
  `quarkusApplicationGenerateTestCode`;
- compile Kotlin main/test source that imports or references that generated
  type;
- run the Kotlin compile task or a task that depends on it;
- the build must fail without Kotlin generated-source wiring.

Behavioral KAPT fixture:

- use a generated type in an annotated Kotlin source in a way KAPT stub
  generation must resolve;
- run `kaptGenerateStubsKotlin` and/or `kaptGenerateStubsTestKotlin`, or a
  compile task path that executes them;
- the build must fail without KAPT stub-task wiring even if `compileKotlin`
  itself is wired.

Fixture recommendations from the legacy scan:

- Do not copy the full `kotlin-grpc-project`; it is too broad for this slice
  because it brings REST, GraphQL, tests, native-test sources, and dev-mode
  compiler options.
- Do not copy the full `kotlin-kapt-grpc-mapstruct` fixture unless the small
  KAPT fixture cannot prove stub resolution. The legacy fixture is strong, but
  heavier because it uses gRPC, protobuf Kotlin, MapStruct runtime, and
  MapStruct processor.
- Prefer the existing new-plugin stubbed codegen pattern in
  `QuarkusApplicationPluginTest`: configure
  `quarkusApplicationGenerateCode` / `quarkusApplicationGenerateTestCode` with
  `doLast` actions that write generated Java sources.
- Put new coverage in a dedicated class, for example
  `QuarkusApplicationKotlinGeneratedSourcesTest`, instead of growing
  `QuarkusApplicationPluginTest`.
- Use the legacy Kotlin version pattern:
  `System.getProperty("kotlin_version", "2.4.0")` and
  `pluginManagement { plugins { id 'org.jetbrains.kotlin.jvm' version ... } }`.
  Add the matching test system property in `gradle-app-plugin` if needed.
- For KSP cycle coverage, keep a separate minimal fixture with Kotlin JVM, KSP,
  `io.quarkus.application`, and `java { withSourcesJar() }` once the new plugin
  test module has a stable KSP version source.

Use the standard TestKit arguments for this module:

```bash
./gradlew :gradle-app-plugin:test --tests <test-name> --stacktrace
```

The TestKit helper already adds configuration-cache and isolated-projects
arguments by default; keep that behavior for these tests unless a specific
Kotlin plugin limitation requires documenting a narrower gate.

## Phase 5: Verification

Run targeted checks:

```bash
./gradlew :gradle-app-plugin:test --tests '*Kotlin*' --stacktrace
./gradlew :gradle-app-plugin:test --tests '*Kapt*' --stacktrace
# Deferred until gradle-app-plugin has a stable KSP version source:
# ./gradlew :gradle-app-plugin:test --tests '*Ksp*' --stacktrace
./gradlew :gradle-app-plugin:validatePlugins --stacktrace
```

Run the broader plugin test if the helper touches shared task registration:

```bash
./gradlew :gradle-app-plugin:test --tests io.quarkus.gradle.application.QuarkusApplicationPluginTest --stacktrace
```

## Review Checklist

- No generated directory is added to `SourceSet` source directories.
- No Kotlin Gradle Plugin type leaks into public task APIs.
- Plain Java projects do not need Kotlin plugin classes.
- Kotlin/KAPT wiring is plugin-conditional and order-independent.
- KAPT stubs are wired separately from `compileKotlin`.
- Tests prove behavior with generated types, not only that task inputs contain a
  directory.
- KSP coverage proves no `sourcesJar` cycle is reintroduced; it does not claim
  KSP processor generated-source consumption.
- No legacy generated-source compile-task `mustRunAfter` workaround is added
  unless new intermediate generated-source compile tasks are introduced.
