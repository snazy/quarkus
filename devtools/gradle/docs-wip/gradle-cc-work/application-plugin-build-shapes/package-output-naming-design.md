# Package Output Naming Design

Status: proposed design for the Gradle-native application plugin
Last reviewed: 2026-07-09

## Problem

The new `io.quarkus.application` plugin currently defaults a named build's
`outputName` to the registered build name:

```kotlin
quarkusApplication {
    builds {
        fastJar("fastJar") { }
    }
}
```

That produces names such as:

```text
build/quarkus-builds/fastJar/app/fastJar.jar
build/quarkus-builds/fastJar/quarkus-run.jar
```

The isolated output root is intentional, but the primary artifact name is too
synthetic. It uses a task/model identity (`fastJar`) where users expect the
application archive identity (`<project-name>-<project-version>`).

Legacy Gradle and Maven behavior normally produces names such as:

```text
build/quarkus-app/app/<project-name>-<version>.jar
build/<project-name>-<version>-runner.jar
```

The new plugin should keep isolated named-output directories while restoring
legacy-compatible artifact file naming by default.

## Existing Quarkus Naming Knobs

Quarkus core packaging already has these relevant config keys:

- `quarkus.package.output-name`
  - base name used by the `OutputTargetBuildItem`;
  - falls back to the build-system base name when not configured;
  - used by JVM and native packaging.
- `quarkus.package.runner-suffix`
  - package-level runner suffix, default `-runner`.
- `quarkus.package.jar.add-runner-suffix`
  - jar-level boolean, default `true`;
  - controls whether `computedRunnerSuffix()` returns the runner suffix or an
    empty string.

Relevant core behavior:

- fast jar still contains a fixed launcher jar named `quarkus-run.jar`;
- uber jar, legacy jar, native executable, and native image sources use
  `outputName + computedRunnerSuffix()`;
- native-image source jar directory uses
  `outputName + computedRunnerSuffix() + "-native-image-source-jar"`;
- Maven docs describe the uber-jar runner suffix as configurable through a
  jar-scoped runner-suffix property, while current core code exposes
  `PackageConfig.runnerSuffix()` at package scope. The new plugin must wire the
  current package-scoped key that core consumes:
  `quarkus.package.runner-suffix`.

The new plugin should feed these Quarkus config keys. It should not rename
Quarkus artifacts after augmentation.

## Goals

- Preserve isolated named output roots under
  `build/quarkus-builds/<registered-name>/`.
- Preserve result metadata roots under
  `build/quarkus-build-results/<registered-name>/`.
- Make default primary artifact names match legacy Gradle expectations.
- Keep registered build names as task/output identities, not archive names.
- Provide Gradle-style managed naming properties for users who need explicit
  naming.
- Avoid pretending Quarkus package names are normal Gradle archive tasks when
  Quarkus core owns parts of the layout.
- Keep the first implementation inside the Gradle plugin; do not require core
  changes unless renaming `quarkus-run.jar` is in scope.

## Non-Goals

- Do not copy named outputs into legacy shared locations.
- Do not post-process or rename Quarkus-generated files after the build.
- Do not make `quarkus-run.jar` configurable in the Gradle plugin.
- Do not change image tag defaults in this design.
- Do not change package-output result metadata formats except for updated
  expected names.

## Proposed DSL

Add managed naming properties to `QuarkusApplicationBuild` only when their
semantics apply to every output type:

```java
Property<String> getArchiveBaseName();
Property<String> getArchiveBaseNameSuffix();
Property<String> getArchiveVersion();
```

These three properties compute the Quarkus package output base name and are
valid for JVM package outputs, native executable outputs, and native-sources
outputs because all of those shapes ultimately use `quarkus.package.output-name`
as their base identity.

Add runner-specific properties only to output types that produce Quarkus runner
artifacts:

```java
Property<String> getArchiveRunnerSuffix();
Property<Boolean> getArchiveAddRunnerSuffix();
```

These should live on the descriptor layer that actually maps to
`computedRunnerSuffix()`: legacy jar, uber jar, native executable, and native
sources. Do not expose them on fast-jar or mutable-jar descriptors, because
those use the fixed fast-jar launcher layout and would ignore these properties.

Keep `getOutputName()` for compatibility with the already-created DSL, but
make it the provider-backed assembled Quarkus output base name:

```java
Property<String> getOutputName();
```

This should mirror Gradle's `AbstractArchiveTask.getArchiveFileName()` style:
`outputName` has a convention computed from the naming pieces, and users may
still set it directly when they want a complete explicit base name.

Recommended user-facing Kotlin DSL:

```kotlin
quarkusApplication {
    builds {
        fastJar("app") {
            archiveBaseName = "nessie-quarkus"
            archiveVersion = project.version.toString()
        }
        uberJar("cli") {
            archiveBaseNameSuffix = "-cli"
            archiveRunnerSuffix = "-runner"
        }
    }
}
```

Do not add a generic `archiveClassifier` in the first implementation. Quarkus
does not expose a generic classifier slot for all package shapes. For JVM
runner artifacts, the classifier-like behavior is the runner suffix. Adding a
generic classifier property would invite a false Gradle analogy and ambiguous
mapping.

Do not add `archiveAppendix`, `archiveClassifier`, or `archiveExtension`
initially unless a concrete output type consumes them. Native executable naming
is not archive-like, and exposing unused Gradle-style archive properties would
make the DSL misleading.

## Defaults

Default conventions:

```text
archiveBaseName = project.name
archiveBaseNameSuffix = ""
archiveVersion = project.version.toString()
archiveRunnerSuffix = "-runner"
archiveAddRunnerSuffix = true
```

Gradle's default project version value, `unspecified`, is rejected. The new
plugin must fail with a clear message when archive naming would derive
`archiveVersion` from `project.version == "unspecified"`. Do not silently omit
the version segment, and do not produce files containing `unspecified` unless a
user explicitly configures that value through an output-name override.

Computed Quarkus output base name:

```text
archiveBaseName
+ archiveBaseNameSuffix
+ optional("-" + archiveVersion)
```

Where `optional(...)` is omitted when the source value is blank.

The registered build name is not part of the default archive name. It remains
the stable identity for:

- DSL registration;
- task names;
- result metadata;
- isolated output root;
- diagnostics.

## Mapping To Quarkus Properties

For every package/native output task, the descriptor-owned forced properties
should include:

```text
quarkus.package.output-name = outputName
```

For legacy jar, uber jar, native executable, and native-sources output tasks,
the descriptor-owned forced properties should also include:

```text
quarkus.package.runner-suffix = archiveRunnerSuffix
quarkus.package.jar.add-runner-suffix = archiveAddRunnerSuffix
```

The current shape-owned properties remain forced:

```text
quarkus.package.output-directory
quarkus.package.jar.enabled
quarkus.package.jar.type
quarkus.native.enabled
quarkus.native.sources-only
```

The archive naming properties should be explicit task inputs. The computed
Quarkus output name should also be captured in package/native result receipts
through the existing `PackageResult` / `NativeResult` output-name field.

## Output Examples

For project `nessie-quarkus`, version `0.108.2-SNAPSHOT`, registered build
`fastJar`:

```text
build/quarkus-builds/fastJar/app/nessie-quarkus-0.108.2-SNAPSHOT.jar
build/quarkus-builds/fastJar/quarkus-run.jar
build/quarkus-build-results/fastJar/package-result.properties
```

For registered build `uber` with defaults:

```text
build/quarkus-builds/uber/nessie-quarkus-0.108.2-SNAPSHOT-runner.jar
build/quarkus-build-results/uber/package-result.properties
```

For registered build `cli` with `archiveBaseNameSuffix = "-cli"`:

```text
build/quarkus-builds/cli/nessie-quarkus-cli-0.108.2-SNAPSHOT-runner.jar
```

For registered native executable `native1` with defaults:

```text
build/quarkus-builds/native1/nessie-quarkus-0.108.2-SNAPSHOT-runner
```

The native default keeps the current core Quarkus runner suffix behavior.
Current core native image naming uses
`outputTargetBuildItem.getBaseName() + packageConfig.computedRunnerSuffix()`,
so the same runner suffix controls apply to native outputs.

## `outputName` Compatibility

Existing design examples use:

```kotlin
fastJar("app") {
    outputName = "my-fast-jar"
}
```

Keep this as the complete Quarkus output base-name property.

Rules:

- `outputName` has a convention assembled from `archiveBaseName`,
  `archiveBaseNameSuffix`, and `archiveVersion`;
- setting `outputName` directly overrides that convention;
- `outputName` does not include the runner suffix;
- `outputName` does not change `quarkus-run.jar`.

With this model there is no conflicting pair of APIs: the archive-style
properties feed the `outputName` convention, and an explicit `outputName` is
the normal Gradle-style way to replace the convention.

## Fast-Jar `quarkus-run.jar`

Do not rename `quarkus-run.jar` in the Gradle plugin.

Reasons:

- it is a core constant in `FastJarFormat`;
- it is used by AOT/AppCDS logic;
- it appears in many docs and deployment examples;
- renaming it after build would desynchronize metadata, scripts, image
  generation, and deployment assumptions.

If users need this, add a separate core packaging design:

- new core config property for fast-jar launcher jar name;
- fast-jar builder support;
- AOT/AppCDS support;
- container-image and deployment support;
- docs updates.

The Gradle plugin should consume that future core property if it exists, not
invent its own post-build rename.

## Implementation Notes

Likely implementation steps:

1. Add common archive naming properties to `QuarkusApplicationBuild`.
2. Add runner suffix properties to a dedicated runner-output DSL base type or
   interface implemented only by legacy-jar, uber-jar, native executable, and
   native-sources descriptors.
3. Convention them from `Project` values during DSL object creation or task
   registration.
4. Add a small internal `ArchiveName`/`PackageOutputName` value object or
   provider helper that assembles the `outputName` convention.
5. Replace the current `getOutputName().convention(name)` registered-name
   default with the archive-style convention.
6. Update `QuarkusApplicationBuildTask.descriptorShapeProperties()` to use
   `outputName`, and add runner properties only on task/descriptor paths whose
   output type consumes them.
7. Keep explicit `outputName` assignment as a normal override of the convention.
8. Update package/native result factory tests.
9. Update TestKit package-output tests to assert legacy-like file names for
   fast jar, mutable jar, uber jar, legacy jar, native executable, and native
   sources where practical.
10. Add a test where registered build name differs from archive name.
11. Add a test proving `project.version = "unspecified"` is rejected when a
    package or native output uses the default archive version convention.
12. Add a test for `archiveBaseNameSuffix`.
13. Add a test for `outputName` override preserving current behavior.

## Settled Decisions

- Keep the Quarkus-specific `outputName` property. It maps directly to
  `quarkus.package.output-name`, and the new plugin should follow Quarkus
  naming conventions here instead of inventing a more Gradle-like name.
- Keep fast-jar launcher naming stable. `quarkus-run.jar` is a documented
  Quarkus fast-jar layout constant and is not configurable as part of this
  design.
- Keep common archive naming pieces on `QuarkusApplicationBuild`, because every
  package/native output type consumes `quarkus.package.output-name`.
- Keep runner suffix controls off descriptors that do not consume
  `computedRunnerSuffix()`, especially fast jar and mutable jar.
- Reject `project.version == "unspecified"` when the default archive naming
  convention derives `archiveVersion` from the Gradle project version.

## Follow-Up Work

- If users need to rename fast-jar `quarkus-run.jar`, design that as a core
  packaging change and have the Gradle plugin consume the future core
  capability.
- If future Quarkus core adds shape-specific native naming properties, map
  those explicitly instead of overloading the current runner suffix controls.

## Recommendation

Implement the archive-style default and `outputName` override in the new Gradle
plugin. Do not wait for core changes. Treat fast-jar launcher renaming as a
separate, deferred core packaging feature.
