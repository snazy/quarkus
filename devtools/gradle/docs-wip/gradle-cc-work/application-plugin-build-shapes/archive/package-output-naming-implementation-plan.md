# Package Output Naming Implementation Plan

Status: implemented
Last reviewed: 2026-07-09
Implemented: 2026-07-09

## Objective

Implement legacy-compatible default package/native artifact names in the
standalone `io.quarkus.application` Gradle plugin while preserving named output
directories.

After this plan, a build registered as `fastJar("fastJar")` in a project named
`nessie-quarkus` with version `0.108.2-SNAPSHOT` should produce a primary
artifact named like `nessie-quarkus-0.108.2-SNAPSHOT.jar`, not `fastJar.jar`.
The registered build name remains the task/output identity and directory name.

## Required Reading

Before editing code, read:

- `devtools/gradle/gradle-app-plugin/AGENTS.md`
- `devtools/gradle/docs-wip/gradle-cc-work/application-plugin-build-shapes/package-output-naming-design.md`
- `devtools/gradle/docs-wip/gradle-cc-work/application-plugin-build-shapes/design.md`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/dsl/QuarkusApplicationBuild.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/TaskRegistration.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/tasks/QuarkusApplicationBuildTask.java`
- `core/deployment/src/main/java/io/quarkus/deployment/pkg/PackageConfig.java`
- `core/deployment/src/main/java/io/quarkus/deployment/pkg/jar/UberJarBuilder.java`
- `core/deployment/src/main/java/io/quarkus/deployment/pkg/jar/LegacyThinJarBuilder.java`
- `core/deployment/src/main/java/io/quarkus/deployment/pkg/jar/NativeImageSourceJarBuilder.java`
- `core/deployment/src/main/java/io/quarkus/deployment/pkg/steps/NativeImageBuildStep.java`

Hard gates from `gradle-app-plugin/AGENTS.md` apply throughout:
configuration-cache and isolated-projects compatibility, no `Task.getProject()`
from task actions, no captured mutable Gradle model in task execution, no public
internal helpers on DSL-facing types, and no test stubs in `src/main`.

## Scope

In scope:

- Common archive naming properties on named build descriptors:
  - `archiveBaseName`
  - `archiveBaseNameSuffix`
  - `archiveVersion`
- Runner suffix properties only on shapes that consume
  `PackageConfig.computedRunnerSuffix()`:
  - legacy jar
  - uber jar
  - native executable
  - native sources
- `outputName` as the complete Quarkus output base-name property, with a
  convention assembled from the archive naming properties.
- Hard rejection of `project.version == "unspecified"` when the default
  `archiveVersion` convention is used.
- Tests for DSL shape, task wiring, forced Quarkus properties, result receipts,
  and tiny-app output names.

Out of scope:

- Renaming fast-jar `quarkus-run.jar`.
- Copying outputs into legacy shared directories.
- Image tag naming.
- Core packaging changes.

## Phase 0: Confirm Core Naming Surface

This phase should be quick and read-only. Record any surprise directly in
`package-output-naming-design.md` before implementation.

1. Confirm `PackageConfig.runnerSuffix()` is package-scoped and maps to
   `quarkus.package.runner-suffix`.
2. Confirm `PackageConfig.computedRunnerSuffix()` uses
   `jar().addRunnerSuffix()`, which maps to
   `quarkus.package.jar.add-runner-suffix`.
3. Confirm `computedRunnerSuffix()` is consumed by:
   - `UberJarBuilder`
   - `LegacyThinJarBuilder`
   - `NativeImageSourceJarBuilder`
   - `NativeImageBuildStep`
4. Confirm fast jar and mutable jar use fast-jar layout and do not consume
   runner suffix controls for their launcher jar.

Acceptance:

- No blocking naming decisions remain in the naming design.
- Any discrepancy is documented before code changes.

## Phase 1: Add Naming DSL Types

Edit the DSL under
`devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/dsl/`.

1. Add these abstract managed properties to `QuarkusApplicationBuild`:
   - `Property<String> getArchiveBaseName();`
   - `Property<String> getArchiveBaseNameSuffix();`
   - `Property<String> getArchiveVersion();`
2. Remove the current registered-name default:
   - delete `getOutputName().convention(name);`
3. Add a DSL-facing abstract base class or interface for runner-suffix-aware
   outputs. Recommended shape:
   - `QuarkusApplicationRunnerOutput extends QuarkusApplicationBuild`
   - abstract getters:
     - `Property<String> getArchiveRunnerSuffix();`
     - `Property<Boolean> getArchiveAddRunnerSuffix();`
4. Make only these descriptors extend or implement that runner-output type:
   - `QuarkusLegacyJarOutput`
   - `QuarkusUberJarOutput`
   - `QuarkusNativeOutput`
   - `QuarkusNativeSourcesOutput`
5. Do not expose runner suffix properties on:
   - `QuarkusFastJarOutput`
   - `QuarkusMutableJarOutput`
6. Keep any non-DSL helper package-private or under `internal.*`. Do not add
   public helper methods just for tests.

Acceptance:

- Common naming properties are visible on all named output types.
- Runner suffix properties are visible only on the four suffix-consuming output
  types.
- Existing build registration DSL still compiles.

## Phase 2: Add Internal Naming Helper

Add an internal helper, preferably under
`io.quarkus.gradle.application.internal.planning`, for deterministic name
assembly and validation.

Recommended type:

```java
final class PackageOutputName {
    static String assemble(String baseName, String baseNameSuffix, String version)
}
```

Rules:

- `baseName` must be non-null and non-blank.
- `baseNameSuffix` may be blank.
- `version` may be blank only when the user explicitly configured
  `archiveVersion` to blank.
- If the assembled convention sees `version == "unspecified"`, fail with a
  clear Gradle error before running augmentation. Any user-configured
  non-`unspecified` project version or `archiveVersion` is valid. The only
  supported way to intentionally produce a base name containing `unspecified`
  is to set `outputName` directly to that complete value, bypassing the
  assembled convention.
- Assembly:
  - start with `archiveBaseName`;
  - append `archiveBaseNameSuffix` exactly as configured;
  - append `-archiveVersion` when `archiveVersion` is not blank.

Implementation note:

- The helper should not depend on Gradle APIs unless necessary. Prefer pure
  Java for unit-testability.

Acceptance:

- Unit tests cover normal assembly, blank suffix, blank explicit version, blank
  base-name rejection, and `unspecified` rejection policy.

## Phase 3: Wire Conventions During Registration

Edit `TaskRegistration` and related object creation code.

1. Set conventions for every registered `QuarkusApplicationBuild`:
   - `archiveBaseName` convention: `project.getName()`
   - `archiveBaseNameSuffix` convention: `""`
   - `archiveVersion` convention: `project.getVersion().toString()`
   - `outputName` convention: provider assembled from the three archive
     properties
2. Set runner-output conventions only for `QuarkusApplicationRunnerOutput`:
   - `archiveRunnerSuffix` convention: `"-runner"`
   - `archiveAddRunnerSuffix` convention: `true`
3. Preserve explicit `outputName` assignment semantics:
   - use `convention(...)`, never `set(...)`, for the assembled default;
   - direct user assignment to `outputName` must override the convention.
4. Ensure the `unspecified` project-version rejection is evaluated when a
   package/native task is configured for execution, not while merely applying
   the plugin to a project that never executes a package/native task.
   Implement this as part of the provider that computes the `outputName`
   convention. Do not separately validate `archiveVersion` when a direct
   `outputName` value has replaced the convention.
5. Do not capture `Project`, `Task`, `SourceSet`, `Configuration`, or other
   mutable Gradle model objects in task actions or worker parameters.

Acceptance:

- Applying the plugin with `project.version = "unspecified"` does not fail by
  itself.
- Executing a package/native task that uses the default `archiveVersion`
  convention fails clearly.
- Setting `outputName = "explicit-name"` avoids the default archive-version
  policy because the assembled convention is not used.

## Phase 4: Wire Task Inputs And Forced Quarkus Properties

Edit task classes under
`devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/tasks/`.

1. Add task input properties for the common naming pieces to
   `QuarkusApplicationTask` or `QuarkusApplicationBuildTask`, depending on the
   smallest task surface that needs them.
2. Add declared task inputs for runner suffix only where package/native build
   tasks need to force those Quarkus properties. Because the current package
   and native build tasks share one base class, prefer a generic additional
   descriptor-shape property map populated only for suffix-consuming shapes
   over public runner-suffix getters on all task instances.
3. In `TaskRegistration.configureNamedTask(...)`, wire common naming properties
   from the DSL object to task inputs.
4. For runner-output descriptors, wire runner suffix properties into that
   additional descriptor-shape property map for package and native build tasks.
5. In `QuarkusApplicationBuildTask.descriptorShapeProperties()`:
   - force `quarkus.package.output-name` from `getOutputName()`;
   - force `quarkus.package.runner-suffix` only for legacy jar, uber jar,
     native executable, and native sources;
   - force `quarkus.package.jar.add-runner-suffix` only for those same shapes;
   - keep existing output-directory, jar-enabled, jar-type, native-enabled, and
     native-sources-only shape properties.
6. Do not add runner suffix properties to fast-jar or mutable-jar DSL or task
   public API.

Acceptance:

- Forced Quarkus properties reflect the new naming model.
- Fast jar and mutable jar do not expose ignored runner suffix DSL.
- Package/native result files continue to record the effective `outputName`.

## Phase 5: Update Unit And ProjectBuilder Coverage

Prefer cheap tests before TestKit.

Add or update focused tests for:

- `PackageOutputName` assembly and validation.
- DSL object conventions:
  - common archive properties exist for every output type;
  - runner suffix properties exist only for legacy jar, uber jar, native
    executable, and native sources.
- Task registration:
  - a registered build name that differs from the project name produces an
    `outputName` convention based on project name/version, not build name;
  - explicit `outputName` wins over archive-piece conventions;
  - `archiveBaseNameSuffix` affects `outputName`.
- Forced Quarkus properties:
  - fast jar: `quarkus.package.output-name`, fast-jar shape properties, no
    runner suffix forced properties;
  - mutable jar: same as fast jar for suffix controls;
  - uber jar and legacy jar: include runner suffix forced properties;
  - native executable and native sources: include runner suffix forced
    properties.
- `project.version = "unspecified"` rejection when the default archive version
  convention is used.

Likely test files:

- `QuarkusApplicationPluginTest.java`
- new `PackageOutputNameTest.java`
- package/native result factory tests if expected output-name assertions need
  updating.

Acceptance:

- Pure unit and ProjectBuilder tests cover all naming logic that does not need
  real Quarkus augmentation.

## Phase 6: Update Tiny-App TestKit Coverage

Update the existing tiny Quarkus app TestKit coverage in
`QuarkusApplicationPluginTest.java`.

Required TestKit flags:

- `--configuration-cache`
- `-Dorg.gradle.unsafe.isolated-projects=true`
- `--build-cache` where the test path is compatible with build-cache use

Test at least:

1. Fast jar output:
   - primary app jar path uses `<project-name>-<version>.jar`;
   - `quarkus-run.jar` remains fixed;
   - package result file lives under
     `build/quarkus-build-results/<registered-name>/package-result.properties`;
   - package result `outputName` is `<project-name>-<version>`.
2. Mutable jar output:
   - primary app jar naming matches the archive naming convention;
   - fast-jar layout launcher remains fixed.
3. Uber jar output:
   - runner jar path uses `<project-name>-<version>-runner.jar`;
   - `archiveBaseNameSuffix` changes the base name as expected.
4. Legacy jar output:
   - runner jar path uses `<project-name>-<version>-runner.jar`.
5. Explicit override:
   - `outputName = "explicit-app"` produces `explicit-app` based artifacts and
     result metadata.
6. Up-to-date/build-cache regression:
   - the existing consumer task that reads package result files through
     `RegularFileProperty` continues to work;
   - a second invocation reports the package build tasks and their required
     codegen/model/dependency tasks as `UP-TO-DATE` where they already had that
     behavior.

Native executable and native-sources output naming should be covered with cheap
factory/request tests unless existing TestKit coverage can exercise them
without requiring GraalVM/native-image work.

Acceptance:

- TestKit proves real tiny-app JVM package outputs match the expected layout.
- Tests keep configuration-cache and isolated-projects enabled.

## Phase 7: Documentation And Cleanup

1. Update `package-output-naming-design.md` if implementation reveals a
   concrete deviation.
2. Update `README.md` in this directory to list this implementation plan while
   it is active.
3. When implementation is complete, move this plan to the archive directory
   only after the user asks or confirms the phase is done.
4. Review public DSL Javadocs/comments if any are added; keep comments limited
   to non-obvious contracts.
5. Run a self-review for:
   - accidentally public internal helpers;
   - task APIs that expose ignored properties;
   - task actions capturing mutable Gradle model;
   - missing `@Input` annotations for naming properties;
   - stale assertions expecting registered build names as artifact names.

## Verification Commands

Run targeted tests first:

```bash
./mvnw -pl devtools/gradle/gradle-app-plugin -Dtest=PackageOutputNameTest test
./mvnw -pl devtools/gradle/gradle-app-plugin -Dtest=QuarkusApplicationPluginTest test
```

Then run the module test suite:

```bash
./mvnw -pl devtools/gradle/gradle-app-plugin test
```

If formatting/import ordering changes are needed, let the Maven build plugins
handle them. Do not manually reorder imports as a standalone edit.

## Done Criteria

- Default output names no longer use the registered build name.
- `outputName` remains a direct complete override for
  `quarkus.package.output-name`.
- Fast jar and mutable jar do not expose runner suffix controls.
- Legacy jar, uber jar, native executable, and native sources can configure
  runner suffix behavior.
- `project.version == "unspecified"` fails clearly only when a default-derived
  package/native output name is needed.
- Result metadata files remain under `build/quarkus-build-results/<name>/`.
- All new or updated TestKit tests use configuration cache and isolated
  projects.
- Targeted module tests pass.

## Verification

Completed on 2026-07-09:

```bash
./gradlew :gradle-app-plugin:test
```
