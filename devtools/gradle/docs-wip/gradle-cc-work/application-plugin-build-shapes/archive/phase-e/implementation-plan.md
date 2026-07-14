# P1-AP-02E JVM Package Outputs Plan

Status: implementation plan draft
Last reviewed: 2026-07-07

## Objective

Make named JVM package output tasks production-ready for:

- fast-jar;
- mutable-jar;
- uber-jar;
- legacy-jar.

Phase E turns the existing named task/descriptor scaffolding into reliable
Gradle-modeled package outputs. It must not copy named outputs into legacy
shared locations; legacy output locations remain the responsibility of legacy
`quarkusBuild`.

## Required Reading

Before implementing this phase, read:

- `design.md`, especially `Named-Output Execution Principles`,
  `AugmentResult And Managed Outputs`, `Existing Task Reuse Boundary`, and
  `Testing Strategy`;
- `phase-b-task-topology.md`, for task naming and dependency expectations;
- `phase-b-augment-result-image-metadata.md`, for the existing
  augmentation-result side-file pattern and deterministic receipt guidance;
- `effective-config-history.md`, for effective-config behavior that must remain
  consistent with legacy Quarkus build config handling.

Relevant current code entry points:

- `io.quarkus.gradle.QuarkusPlugin`;
- `io.quarkus.gradle.tasks.application.QuarkusApplicationPackageTask`;
- `io.quarkus.gradle.tasks.application.QuarkusApplicationBuildTask`;
- `io.quarkus.gradle.tasks.application.execution.BuildOperations`;
- `io.quarkus.gradle.tasks.application.execution.WorkerBackedBuildOperations`;
- `io.quarkus.gradle.tasks.worker.BuildWorker`;
- `io.quarkus.gradle.tasks.worker.BuildWorkerParams`;
- `io.quarkus.gradle.tasks.application.planning.PackageLayoutInferencePlanner`;
- `io.quarkus.gradle.tasks.application.planning.OutputLayoutPlanner`;
- `io.quarkus.gradle.tasks.application.config.ShapeValidator`.

## Current State

The DSL and task registration already exist. `quarkus.builds.fastJar(...)`,
`mutableJar(...)`, `uberJar(...)`, and `legacyJar(...)` register
`QuarkusApplicationPackageTask` instances with names such as
`quarkusAppBuild`.

The task currently executes through `executeApplicationBuild()`, which calls the
generic `BuildOperations.build(...)` method and declares only
the broad package output directory. It forces basic descriptor-owned shape
properties:

- `quarkus.package.output-directory`;
- `quarkus.package.output-name`;
- `quarkus.package.jar.enabled`;
- `quarkus.native.enabled`;
- `quarkus.package.jar.type`.

The shape validator already checks the effective config after resolution, but
package tasks do not currently request augmentation facts and do not write a
package result receipt.

`BuildWorker` can already write an augmentation-result side file, but the
current `AugmentResultCodec` serializes only
`ArtifactResult` entries. It does not serialize `JarResult` facts such as the
primary jar path, original artifact, library directory, classifier, mutable
flag, or uber-jar status. JVM package output modeling must not rely on
`ArtifactResult` alone.

## Non-Goals

- Do not implement native executable or native-sources output execution here.
- Do not implement native-test suites.
- Do not implement dev/run/remote-dev/continuous-test tasks.
- Do not copy or materialize named outputs into legacy shared locations.
- Do not make image-related tasks cacheable or depend on image up-to-date
  checks.
- Do not directly reuse `QuarkusBuildCacheableAppParts` or
  `QuarkusBuildDependencies` as named-output tasks.

## Implementation Phases

### E0. Package Result Model And Codec

Add a Gradle-plugin-local package result model and deterministic receipt codec.

Create support types under a package such as
`io.quarkus.gradle.tasks.application.packaging` only if the existing package
layout has no better home.

Minimum model:

- `PackageResult`;
- `PackageResultCodec`;
- `PackageResultFactory` or extractor.

The result model must represent:

- build name;
- build type: `fast-jar`, `mutable-jar`, `uber-jar`, or `legacy-jar`;
- output root;
- output name;
- primary jar path;
- optional original artifact path;
- optional library directory;
- mutable flag;
- uber-jar flag;
- optional classifier;
- raw artifact result facts that are useful for diagnostics.

Receipt requirements:

- use `io.quarkus.bootstrap.util.PropertyUtils.store(...)`;
- use `schema.version=1`;
- omit unknown optional fields instead of writing guessed values;
- read unknown future keys without failing;
- fail malformed required fields with an error that names the file and field;
- keep paths stable and reproducible; prefer paths relative to the output root
  when they are inside the output root, otherwise write absolute paths.

Suggested common receipt fields:

```properties
schema.version=1
result.type=jvm-package
build.name=app
package.type=fast-jar
package.output-root=.
package.output-name=app
package.jar.path=quarkus-run.jar
package.mutable=false
package.uber=false
```

Suggested shape-specific fields:

- `package.library-dir`;
- `package.original-artifact`;
- `package.classifier`;
- `package.artifact.count`;
- `package.artifact.<n>.type`;
- `package.artifact.<n>.path`;
- `package.artifact.<n>.metadata.<key>`.

Keep the codec separate from the image receipt codec. Sharing low-level helper
code is fine, but package receipts should have a package-specific schema.

Acceptance for E0:

- pure unit tests cover writing and reading each JVM package shape;
- malformed receipts fail with useful messages;
- receipt output has no date comment and stable key ordering.

### E1. Augmentation Fact Capture For Package Builds

Extend the production operation boundary so package tasks can obtain full
augmentation facts.

Do not make package tasks parse broad log output or infer everything from file
existence. Use data available while the worker has the full `AugmentResult`.

Preferred shape:

- add `PackageResult buildPackage(BuildRequest request)`
  to `BuildOperations`;
- implement it in `WorkerBackedBuildOperations`;
- have package builds request an augmentation/result side file from the worker;
- write or derive the package result from the full `AugmentResult`, including
  `JarResult`, not only from the current artifact-only codec.

Two implementation options are acceptable:

- extend `AugmentResultCodec` into a richer augmentation-facts
  codec that includes `JarResult` fields, then derive the package result in
  `WorkerBackedBuildOperations`;
- add a package-specific worker result file that serializes the package result
  directly while `BuildWorker` still has the full `AugmentResult`.

Whichever option is chosen, keep the image extraction behavior unchanged unless
the change is purely additive and covered by existing image tests.

Acceptance for E1:

- package operations return a `PackageResult`;
- existing image, AOT image, and deployment operation tests still pass;
- package result extraction uses `JarResult` for jar facts;
- no task action calls `Task.getProject()`, captures live `Project`, or reads
  undeclared ambient state.

### E2. Package Task Output Modeling

Update `QuarkusApplicationPackageTask` and registration wiring so each package
task exposes stable Gradle outputs and a deterministic receipt.

Mandatory task properties:

- broad package output root as the current `@OutputDirectory`;
- `@OutputFile RegularFileProperty getPackageResultFile()` or equivalent
  receipt property.

Use a receipt path outside the runnable application tree if practical, for
example:

```text
build/quarkus-build-results/<build-name>/package-result.properties
```

If implementation simplicity favors keeping metadata under the named root, use:

```text
build/quarkus-builds/<build-name>/package-result.properties
```

but document in the code/test why the receipt is intentionally colocated with
the named output.

Typed downstream accessors should be added without creating overlapping Gradle
output snapshots. With the current broad `@OutputDirectory`, prefer `@Internal`
provider-style accessors for paths derived from the descriptor/planner and
receipt, such as:

- primary jar provider;
- library directory provider;
- original artifact provider;
- package receipt provider.

If annotated `@OutputFile` / `@OutputDirectory` child properties are added
inside the broad output directory, first replace the broad output declaration
for that task type so Gradle does not snapshot overlapping outputs.

Acceptance for E2:

- task registration wires package receipt conventions for all four DSL
  factories;
- downstream build logic can reference stable task properties without parsing
  task names;
- output declarations avoid overlapping Gradle output annotations;
- no named package output writes to `build/quarkus-build/app` or other legacy
  shared package roots.

### E3. Shape Intent And Validation Tightening

Centralize package shape intent so package tasks and operation requests cannot
drift.

Required behavior:

- descriptor-owned shape keys must win over user/common build properties;
- operation-specific forced properties must not allow changing the descriptor
  shape;
- the effective-config validator must continue to fail when config files or
  other sources try to change the registered package type;
- native-specific shape keys must remain unset or false for JVM packages.

Implementation should reconcile the current task-local
`descriptorShapeProperties()` with `BuildIntentPlanner`.
Avoid duplicating shape property rules in multiple places without tests.

Acceptance for E3:

- pure tests prove fast/mutable/uber/legacy descriptors force the expected
  `quarkus.package.jar.type`;
- tests prove a config file cannot turn a named `fastJar` output into an
  `uber-jar`, or a JVM package into a native output;
- tests cover common `quarkusBuildProperties` and per-build properties merge
  order.

### E4. Worker-Backed Package Execution

Wire `QuarkusApplicationPackageTask` to the package operation instead of the
generic void build operation.

Required behavior:

- execute the Quarkus worker-backed production build;
- produce the package result receipt;
- validate the result against the descriptor:
  - `fast-jar` result is not mutable and not uber;
  - `mutable-jar` result is mutable;
  - `uber-jar` result is uber and does not require a library directory;
  - `legacy-jar` result is not mutable and not uber;
- create the declared output root if Quarkus produced no files only when that
  behavior is already needed for Gradle output bookkeeping; do not hide failed
  Quarkus builds by creating empty successful outputs.

Do not add legacy compatibility materialization in this phase.

Acceptance for E4:

- running two named JVM package tasks in one Gradle invocation writes isolated
  output roots and isolated receipts;
- package task actions are configuration-cache friendly;
- stubs can exercise task behavior without running Quarkus augmentation;
- production operation still uses existing workers for real execution.

### E5. Tests And Verification

Use the established cheap-to-expensive test layering.

Pure unit tests:

- package result codec round trips;
- package result extraction from synthetic `AugmentResult` / `JarResult`;
- package shape property planning and merge precedence;
- output-layout planning for named roots and shared dependency fragment
  decisions;
- shape/result mismatch validation messages.

ProjectBuilder tests:

- DSL factories register package descriptors and tasks for all four shapes;
- receipt conventions are present;
- typed accessors are available;
- no legacy shared package roots are configured as named outputs.

TestKit/stub tests:

- one named package task writes a deterministic stub receipt;
- multiple named package tasks run in one build and do not clobber each other;
- descriptor-owned shape keys reach the operation request;
- ambient config capture remains opt-in and does not become the default.

Real integration tests:

- add a minimal parameterized build for fast-jar, mutable-jar, uber-jar, and
  legacy-jar only if pure/ProjectBuilder/TestKit tests cannot prove actual
  Quarkus layout behavior;
- keep these tests JVM-only, with no containers and no native-image tooling.

Suggested targeted command for the Gradle plugin module:

```bash
./mvnw test -f devtools/gradle/gradle-application-plugin -Dtest=QuarkusApplication*Package*,QuarkusApplicationTaskRegistrationTest
```

If the touched surface is broader, also run the existing application-task test
set that covers image/deploy/AOT operation mapping.

## Acceptance Criteria

Phase E is complete when:

- `fastJar`, `mutableJar`, `uberJar`, and `legacyJar` named outputs execute
  through the production worker-backed build path;
- each package task writes an isolated named output root and deterministic
  package receipt;
- package results are extracted from full augmentation facts, including
  `JarResult`;
- descriptor shape validation prevents accidental package type drift from
  config files or other property sources;
- multiple named JVM package outputs can run in one Gradle invocation without
  clobbering each other;
- downstream tasks can wire to stable task properties or receipt files;
- no new behavior copies named outputs into legacy shared output locations.
