# P1-AP-01 New-Plugin Codegen Plan

Status: implemented for the standalone new plugin
Last reviewed: 2026-07-08

## Objective

Add Gradle-native Quarkus code generation to the standalone
`io.quarkus.application` plugin without reintroducing the legacy
dependency-project task walk.

This supersedes the earlier legacy-plugin framing of this document. The
remaining useful idea is still the same: code generation should rely on
resolvable Gradle classpaths and artifact/task inference, not live
dependency-project task lookup, `afterEvaluate` callbacks, manual `jar`
dependencies, or cross-project Jandex ordering.

## Implemented State

The legacy `io.quarkus` plugin owns these tasks:

- `quarkusGenerateCode`;
- `quarkusGenerateCodeDev`;
- `quarkusGenerateCodeTests`.

The standalone `io.quarkus.application` plugin registers:

- `quarkusApplicationModel`, a normal-mode model task;
- `quarkusApplicationCodegenModel`, a normal-mode pre-codegen model task;
- `quarkusApplicationTestCodegenModel`, a test-mode pre-codegen model task;
- `quarkusApplicationGenerateCode`, a normal/main codegen task;
- `quarkusApplicationGenerateTestCode`, a test codegen task;
- named application build/image/AOT/deploy/native-test/launch task surfaces
  from `quarkusApplication.builds`.

The new-plugin extension exposes code-generation provider and input-name
properties through `quarkusApplication.codegen`, with defaults equivalent to
the legacy extension defaults.

Implemented TestKit coverage proves:

- generated main/test Java sources are compiled under configuration cache and
  isolated projects;
- real Avro code generation works for a tiny app;
- a multi-project app can compile generated sources that depend on a plain
  `java-library` project through normal Gradle project-dependency classpath
  inference, without applying Quarkus or Jandex to the producer project.

## Legacy Behavior To Preserve Functionally

The legacy `QuarkusGenerateCode` task:

- is cacheable;
- consumes a launch-mode application model file;
- declares a compile classpath with `@CompileClasspath`;
- declares source parent directories;
- writes a generated-sources directory;
- builds effective Quarkus config;
- executes `CodeGenWorker`, which invokes
  `CodeGenerator.initAndRun(...)`;
- passes source parent directories, generated-sources directory, build
  directory, application model, properties, launch mode, and the test flag to
  Quarkus deployment code.

Legacy plugin registration also wires generated sources into:

- `compileJava`;
- `compileTestJava`;
- `compileKotlin`;
- `compileTestKotlin`;
- KAPT stub generation tasks when the KAPT plugin is present.

The new plugin should preserve the functional behavior, not the legacy task
names or the legacy registration shape.

## New-Plugin Decisions

### Task Names

Do not reuse legacy task names. The new plugin must not register
`quarkusGenerateCode`, `quarkusGenerateCodeDev`, or
`quarkusGenerateCodeTests`, because those collide with the legacy plugin and
make coexistence ambiguous.

Use new plugin-owned names:

- `quarkusApplicationGenerateCode` for normal/main source generation;
- `quarkusApplicationGenerateTestCode` for test source generation.

Do not add a new dev-mode codegen task in this slice. The new plugin's dev and
continuous-test tasks are still failing stubs, and real behavior needs a
separate Gradle-native dev/continuous-build design.

### Scope

Codegen is application-level, not named-output-level.

Generated sources are inputs to Java/Kotlin compilation and therefore to all
named outputs. Registering codegen per named build would duplicate work and
create inconsistent generated classes. One normal codegen task and one test
codegen task are enough for the initial new-plugin slice.

### Application Model Ordering

Codegen needs an application model, but `compileJava` also needs codegen
outputs. Therefore do not make codegen consume the existing
`quarkusApplicationModel` task if that task depends on `classes`.

Introduce pre-codegen model tasks:

- `quarkusApplicationCodegenModel` with `LaunchMode.NORMAL`;
- `quarkusApplicationTestCodegenModel` with `LaunchMode.TEST`.

These model tasks must not depend on `classes` or `testClasses`. They should
consume source/resource/classpath metadata, resolvable runtime/deployment
classpaths, platform metadata, compile-only metadata, and project coordinates.

The existing production `quarkusApplicationModel` may continue to depend on
`classes`, so named build tasks see compiled generated sources through normal
Java outputs.

Implementation note: pre-codegen model tasks serialize distinct model files
under `build/quarkus/application-model/` and do not depend on `classes` or
`testClasses`.

Expected ordering:

```text
quarkusApplicationCodegenModel
  -> quarkusApplicationGenerateCode
  -> compileJava / compileKotlin
  -> classes
  -> quarkusApplicationModel
  -> quarkus<Name>Build
```

For test codegen:

```text
quarkusApplicationTestCodegenModel
  -> quarkusApplicationGenerateTestCode
  -> compileTestJava / compileTestKotlin
  -> testClasses
```

### Classpath Model

Each codegen task must depend on Gradle artifact inference through declared
classpath inputs:

- normal codegen uses the normal launch-mode runtime/deployment classpath;
- test codegen uses the test launch-mode runtime/deployment classpath.

The implementation should use `ClasspathBuilder` as the new plugin adapter. The
new plugin must not inspect dependency projects, so it must not blindly reuse
legacy classpath machinery that performs cross-project component-variant
inspection. Local project and included-build producer tasks should be inferred
by Gradle from selected artifacts on resolvable configurations.

The adapter may derive deployment artifacts from resolved runtime artifacts, but
it must first evolve the raw Gradle runtime classpath with Quarkus conditional
runtime extensions. The durable rationale, laziness contract, and expected
algorithm are tracked in
[`new-application-plugin-design.md`](../new-application-plugin-design.md#conditional-dependencies).
This plan implements that design for codegen and application-model classpath
construction without the legacy cross-project component-variant/project
inspection path.

The current `ClasspathBuilder` is effectively normal-mode
oriented. The implementation must make classpath construction launch-mode aware
for at least `NORMAL` and `TEST`, or introduce separate clearly named builders
for main and test codegen. Do not fake test codegen with the normal runtime
classpath.

The codegen task should expose a managed classpath input, preferably:

```java
@CompileClasspath
public abstract ConfigurableFileCollection getClasspath();
```

Avoid the legacy mutable `FileCollection` field plus
`setCompileClasspath(Configuration)` pattern in new code.

Implementation note: `ClasspathBuilder` is launch-mode aware for normal and
test codegen. Test codegen uses test runtime/deployment inputs instead of the
normal runtime classpath. It creates raw, conditional candidate, and final
runtime configurations for each launch-mode classpath, and deployment
configurations are derived from the final runtime configurations.

### Generated Output Directories

Use explicit Gradle build directories rather than hidden source sets named
`quarkus-generated-sources` and `quarkus-test-generated-sources`.

Recommended outputs:

- `build/generated/sources/quarkus-application/main`;
- `build/generated/sources/quarkus-application/test`.

These directories are intentionally distinct from the legacy plugin's
`build/generated/sources/quarkus/...` outputs so `io.quarkus` and
`io.quarkus.application` can be applied to the same project during migration
without overlapping generated-source ownership.

The codegen task should expose:

```java
@OutputDirectory
public abstract DirectoryProperty getGeneratedOutputDirectory();
```

Wire these directories into compilation tasks as source directories. Do not
add them to the shared `main` or `test` Java source sets. When the legacy
`io.quarkus` plugin is also applied during migration, legacy codegen tasks
inspect the shared Java source sets; adding new-plugin generated directories
there makes the legacy tasks consume new-plugin task outputs and triggers
Gradle implicit-dependency validation failures.

Do not create extra source sets unless a concrete Gradle or IDE integration
issue requires it.

### Effective Config And Worker Boundary

Do not reuse legacy `EffectiveConfigProvider` as an API. Use the new plugin's
descriptor-driven effective-config planner shape from
`application-plugin-build-shapes/effective-config-history.md`.

The codegen task should:

- resolve provider-backed Gradle/system/environment config inputs at execution
  time through declared task properties;
- construct an immutable request object;
- invoke a testable operation boundary;
- write only declared outputs.

Add a production operation for code generation, for example:

```java
interface CodegenOperations {
    void generate(CodegenRequest request);
}
```

The production implementation may mirror `CodeGenWorker`, but it must live in
the new plugin module under `io.quarkus.gradle.application.*`, not in the
legacy plugin package. Test stubs must live in test sources or test fixtures,
not production sources.

### Java, Kotlin, And KAPT Wiring

Required Java wiring:

- add `quarkusApplicationGenerateCode` output directory as a source directory
  for `compileJava`;
- make `compileJava` depend on `quarkusApplicationGenerateCode`;
- add `quarkusApplicationGenerateTestCode` output directory as a source
  directory for `compileTestJava`;
- make `compileTestJava` depend on both
  `quarkusApplicationGenerateCode` and
  `quarkusApplicationGenerateTestCode` when test sources may reference main
  generated sources.

Kotlin wiring should be added when `org.jetbrains.kotlin.jvm` is applied:

- add the main generated directory to `compileKotlin`;
- make `compileKotlin` depend on `quarkusApplicationGenerateCode`;
- add the test generated directory to `compileTestKotlin`;
- make `compileTestKotlin` depend on `quarkusApplicationGenerateTestCode`.

KAPT wiring should be added when `org.jetbrains.kotlin.kapt` is applied:

- add the main generated directory to `kaptGenerateStubsKotlin`;
- make it depend on `quarkusApplicationGenerateCode`;
- add the test generated directory to `kaptGenerateStubsTestKotlin`;
- make it depend on `quarkusApplicationGenerateTestCode`.

Keep this wiring lazy and plugin-conditional. Do not require Kotlin or KAPT
classes on the new plugin compile classpath.

### Jandex Direction

Jandex ordering is orthogonal to code generation task dependencies.

The new plugin must not apply Jandex plugins to dependency projects and must
not manually wire dependency-project Jandex tasks. Projects that want indexed
artifacts should configure that on the producer project. The consumer should
see the result through normal Gradle artifact selection.

Optional future diagnostics may inspect resolved artifacts for
`META-INF/jandex.idx` and warn, but that diagnostic must not require live
project traversal.

## Implementation Slices

The detailed implementation plan is
`p1-ap-01-codegen-implementation-plan.md`. Its completed slices are summarized
below.

### Slice 1: Model And Task Types

Implemented new plugin codegen request/model/task types:

- `CodegenRequest`;
- `CodegenOperations`;
- worker-backed production operation;
- `QuarkusApplicationGenerateCodeTask`.

The task type supports both normal and test launch modes through a mode
property, not through separate implementation classes.

### Slice 2: Pre-Codegen Application Models

Implemented pre-codegen application model tasks:

- `quarkusApplicationCodegenModel`;
- `quarkusApplicationTestCodegenModel`.

They use the same new-plugin model-generation machinery as
`quarkusApplicationModel`, but without depending on compiled classes.

### Slice 3: Main/Test Codegen Registration

Implemented:

- `quarkusApplicationGenerateCode`;
- `quarkusApplicationGenerateTestCode`.

Each task is wired to:

- the corresponding pre-codegen model task;
- source parent directories:
  - main source-set parents for `quarkusApplicationGenerateCode`;
  - test source-set parents for `quarkusApplicationGenerateTestCode`;
- generated output directory;
- launch mode;
- codegen provider/input names;
- effective config inputs;
- build directory;
- classpath input.

### Slice 4: Compile Task Wiring

Java generated-source wiring is implemented. Kotlin and KAPT wiring remain
deferred until they can be covered by cheap default-suite tests.

Implemented Java wiring uses lazy task configuration for:

- `compileJava`;
- `compileTestJava`.

Deferred Kotlin/KAPT wiring should use lazy plugin hooks:

- `plugins.withId("org.jetbrains.kotlin.jvm", ...)`;
- `plugins.withId("org.jetbrains.kotlin.kapt", ...)`.

Avoid eager task realization and avoid holding live task or project objects in
task actions.

### Slice 5: Tests And Gates

Implemented tests cover:

1. Pure unit tests for request construction and worker submission mapping.
2. ProjectBuilder tests for task registration, launch-mode classpaths, and Java
   compile wiring.
3. TestKit tests for stubbed main/test generated sources.
4. TestKit tests for real Avro code generation.
5. Multi-project TestKit smoke test with `:app` depending on plain `:lib`.

Every default-suite TestKit invocation for the new plugin must use:

- `--configuration-cache`;
- `-Dorg.gradle.unsafe.isolated-projects=true`.

Use `--build-cache` for cacheability-sensitive codegen tests.

## Acceptance Criteria

- The new plugin registers `quarkusApplicationGenerateCode` and
  `quarkusApplicationGenerateTestCode`.
- The new plugin does not register legacy `quarkusGenerateCode*` task names.
- The new `quarkusApplication` extension exposes code-generation provider and
  input-name properties with legacy-equivalent defaults.
- Codegen tasks do not inspect dependency projects, register cross-project
  callbacks, or manually wire dependency-project tasks.
- Codegen classpath dependencies are inferred by Gradle through resolvable
  classpath inputs.
- Test codegen uses the TEST launch-mode classpath, not the normal runtime
  classpath.
- Generated main sources are compiled before `classes`.
- Generated test sources are compiled before `testClasses`.
- `quarkusApplicationModel` and named build tasks see generated classes through
  normal Java outputs.
- Java compile wiring is covered by ProjectBuilder or TestKit tests.
- Kotlin/KAPT wiring is either implemented with tests or explicitly deferred
  with a follow-up entry in the design docs.
- A multi-project TestKit app proves a project dependency is handled through
  Gradle artifact inference under configuration cache and isolated projects.
- Jandex task ordering is not part of the codegen dependency model.

## Non-Goals

- Do not change legacy `io.quarkus` `quarkusGenerateCode*` behavior in this
  slice.
- Do not implement new dev-mode or continuous-test codegen behavior.
- Do not add automatic Jandex plugin application to dependency projects.
- Do not make broad cacheability claims for arbitrary fork-option actions or
  broad ambient environment capture.
- Do not make the serialized application model build-cacheable as part of this
  work.

## Follow-Up Candidates

Moved to
[`new-application-plugin-design.md`](../new-application-plugin-design.md#deferred-follow-ups)
so the durable backlog survives implementation-plan archival.

## Open Questions

No blocking design questions remain.

The implementation verified:

- `GenerateModelTask` can represent pre-classes model
  variants;
- `ClasspathBuilder` is launch-mode aware for normal and
  test codegen;
- Avro is a cheap real-codegen fixture for default TestKit coverage;
- Kotlin/KAPT wiring does not currently have cheap default-suite coverage and
  remains deferred.
