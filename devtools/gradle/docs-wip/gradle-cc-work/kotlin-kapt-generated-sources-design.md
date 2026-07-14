# Kotlin/KAPT Generated Sources Design

Status: implemented design note
Last reviewed: 2026-07-13

## Objective

Record the implemented `io.quarkus.application` generated-source wiring for
Kotlin/JVM and KAPT without reintroducing the legacy source-set cycles that
affected KSP and `sourcesJar`.

The new plugin already generates main and test sources through:

- `quarkusApplicationGenerateCode`
- `quarkusApplicationGenerateTestCode`

and wires those outputs into Java compilation. The implemented design also wires
the generated directories directly into matching Kotlin and KAPT tasks.

## Context

The parity investigation records the relevant legacy behavior and history in
`gradle-app-plugin-parity-investigation.md`:

- legacy wires generated sources into `compileJava` / `compileTestJava`;
- legacy also wires generated sources into `compileKotlin` /
  `compileTestKotlin`;
- legacy wires generated sources into `kaptGenerateStubsKotlin` /
  `kaptGenerateStubsTestKotlin` because KAPT stub generation does not inherit
  the sources injected into `compileKotlin`;
- `#29698` showed that adding generated source directories to shared
  `SourceSet`s can create a KSP cycle:
  `kspKotlin -> quarkusGenerateCode -> processResources -> kspKotlin`;
- PR `#49811` fixed that class of cycle by wiring generated sources directly to
  compile tasks rather than shared source sets;
- `#45057` required generated-source compile task ordering only where
  generated-source compile tasks are part of the graph;
- `#50486` exposed the KAPT stub-generation gap after generated sources stopped
  being shared source-set roots.

The archived codegen plan captured the intended new-plugin direction in
`archive/p1-ap-01-codegen-project-walk-plan.md#java-kotlin-and-kapt-wiring`.

## Implemented Behavior

`io.quarkus.application` wires generated sources into Java, Kotlin, and KAPT
tasks without adding them to shared `SourceSet`s. The generated directories are:

- `build/generated/sources/quarkus-application/main`
- `build/generated/sources/quarkus-application/test`

Kotlin projects consume those directories through `compileKotlin` and
`compileTestKotlin`. KAPT projects consume them through
`kaptGenerateStubsKotlin` and `kaptGenerateStubsTestKotlin` because KAPT stub
generation does not inherit sources injected into Kotlin compilation tasks.

## Design

Keep generated-source ownership task-local:

- do not add Quarkus-generated directories to `SourceSet` source directories;
- do not create shared source roots that `sourcesJar`, KSP, IDE import, or other
  source-set consumers automatically traverse;
- add generated directories directly to the relevant compile/stub tasks.

Java wiring remains unchanged:

- `compileJava` consumes `quarkusApplicationGenerateCode` output;
- `compileTestJava` consumes `quarkusApplicationGenerateTestCode` output.

Kotlin/JVM wiring is plugin-conditional:

- when `org.jetbrains.kotlin.jvm` is applied, add main generated output to
  `compileKotlin`;
- make `compileKotlin` depend on `quarkusApplicationGenerateCode`;
- add test generated output to `compileTestKotlin`;
- make `compileTestKotlin` depend on `quarkusApplicationGenerateTestCode`.

KAPT wiring is plugin-conditional:

- when `org.jetbrains.kotlin.kapt` is applied, add main generated output to
  `kaptGenerateStubsKotlin`;
- make `kaptGenerateStubsKotlin` depend on `quarkusApplicationGenerateCode`;
- add test generated output to `kaptGenerateStubsTestKotlin`;
- make `kaptGenerateStubsTestKotlin` depend on
  `quarkusApplicationGenerateTestCode`.

Keep Kotlin-specific implementation details contained in a package-private
helper. `TaskRegistration` should install unconditional plugin-presence hooks,
but the actual Kotlin/KAPT task handling should live in a narrow helper class.

The implemented helper uses localized reflection to keep Kotlin Gradle Plugin
classes out of the Quarkus plugin runtime classpath unless the Kotlin/KAPT
plugins are actually present. Do not scatter reflection through
`TaskRegistration`.

## Ordering

The new plugin currently injects generated source directories directly into
consumer tasks. That should not need the legacy `mustRunAfter` workaround from
`#45057`.

Only reintroduce generated-source compile task ordering if the new plugin later
adds intermediate generated-source compile tasks analogous to legacy
`compileQuarkusGeneratedSourcesJava`.

## Non-Goals

- Do not add generated sources back to shared `SourceSet`s.
- Do not introduce KSP-specific integration in this slice. The primary KSP
  requirement is to avoid the old source-set cycle.
- Do not solve IDE generated-source root visibility here unless a concrete
  new-plugin IDE consumer requires it without recreating the KSP/source-set
  cycle.
- Do not broaden this into test-task model or continuous-testing work.

## Tests And Deferred Coverage

Focused new-plugin coverage lives under `devtools/gradle/gradle-app-plugin`.

Current coverage:

- applying Kotlin/JVM exposes generated main/test source directories to
  `compileKotlin` / `compileTestKotlin`;
- applying KAPT exposes generated main/test source directories to
  `kaptGenerateStubsKotlin` / `kaptGenerateStubsTestKotlin`;
- generated directories are still not added to shared main/test source sets;
- the TestKit invocation keeps the existing default gates:
  configuration cache and isolated projects.

Deferred useful regression shapes:

- a small Kotlin source references a class generated by a stubbed Quarkus
  codegen provider;
- a small KAPT fixture proves stub generation sees the generated source
  directory, ideally without requiring a heavyweight processor;
- a `sourcesJar` or source-set assertion confirms generated directories are not
  shared source-set roots.

## Implementation Notes

Likely implementation location: `TaskRegistration`, next to the existing Java
generated-source wiring.

Expected shape:

- keep the existing Java wiring helper;
- add unconditional lazy `plugins.withId("org.jetbrains.kotlin.jvm", ...)`
  wiring that delegates to the package-private helper;
- add unconditional lazy `plugins.withId("org.jetbrains.kotlin.kapt", ...)`
  wiring that delegates to the package-private helper;
- keep Kotlin Gradle Plugin types out of public task APIs and out of
  `TaskRegistration` method signatures;
- keep absent Kotlin/KAPT tasks a no-op.

The implementation plan verified the exact task API and classloader behavior:

- Kotlin plugin applied before `io.quarkus.application`;
- Kotlin plugin applied after `io.quarkus.application`;
- KAPT plugin applied before `io.quarkus.application`;
- KAPT plugin applied after `io.quarkus.application`;
- plain Java project without Kotlin/KAPT does not load or require Kotlin plugin
  classes.

If `tasks.withType(...)` can be used with stable Kotlin task types inside the
helper, prefer that because it is live for tasks registered later. Otherwise,
use `tasks.matching(name predicate).configureEach(...)` plus a localized source
injection helper.
