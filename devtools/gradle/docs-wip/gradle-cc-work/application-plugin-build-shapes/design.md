# P1-AP-02 Build Shape Split Design

Status: current detailed task-model design for `io.quarkus.application`
Last reviewed: 2026-07-07

## Context

`P1-AP-02` currently tracks hidden build inputs flowing through
`ForcedPropertieBuildService`. The concrete remaining cases are image build and
image push tasks mutating shared state during task execution, while build tasks
read that state through an `@Internal` service property.

The same pattern exists conceptually for graph-selected native intent:
`buildNative` / `testNative` currently cause shared extension state to change
when those tasks are in the task graph. That is more visible than the old
`extraProperties` mutation, but it still means one build task can have different
semantic inputs depending on the requested task graph.

There is an additional compatibility wrinkle: users use `quarkusBuild` both as
the well-known task to execute and as a public configuration object of type
`QuarkusBuild`. Any split has to untangle those roles without forcing existing
build scripts to change immediately.

## Problem

From Gradle's perspective, an executable task should have a stable semantic
meaning and a stable input/output model.

The current monolithic-ish shape violates that direction:

- `quarkusBuild` is the public/default build task.
- Native aliases can make the build task mean "native build".
- Image tasks can make the build task mean "container image build" or
  "container image push" through late service mutation.
- Different package shapes share output directories under `build/`, so switching
  shapes requires cleanup/deletion code to remove stale outputs from previous
  shapes.

This makes build-cache correctness hard because Gradle cannot reliably snapshot
which semantic build shape is being requested when that meaning is determined by
task graph state or execution-time mutation.

## Current Work Inventory

The current application-build path already has a partial split, but the split is
based on artifact fragments rather than on user-visible build shapes.

- `quarkusBuildAppModel` (`QuarkusApplicationModelTask`) produces the serialized
  normal-mode `ApplicationModel` used by build, image, info, update, and offline
  tasks.
- `quarkusDependenciesBuild` (`QuarkusBuildDependencies`) extracts dependency
  jars from the serialized application model into `build/quarkus-build/dep`.
  It is deliberately not build-cacheable because caching dependency jars would
  pollute the Gradle build cache, but it still has up-to-date outputs.
- `quarkusAppPartsBuild` (`QuarkusBuildCacheableAppParts`) runs the Quarkus
  build and extracts the non-dependency app fragments into
  `build/quarkus-build/app`. It is cacheable for the default small-output cases
  and disabled for large/package-shape cases unless the user opts into caching
  large artifacts.
- `quarkusBuild` (`QuarkusBuild`) is the public compatibility/finalization task.
  It depends on `quarkusDependenciesBuild` and `quarkusAppPartsBuild`, then
  combines their outputs into the legacy/default Quarkus output locations. For
  some shapes, such as uber-jar, mutable-jar, and native sources, it falls back
  to running the full Quarkus build itself.
- `quarkusImageExtensionChecks` (`ImageCheckRequirementsTask`) validates the
  selected container-image extension and writes the selected builder name to
  `build/quarkus/image-name`.
- `imageBuild` (`ImageBuild`) depends on `quarkusImageExtensionChecks`, reads the
  builder name file, mutates `ForcedPropertieBuildService`, and is finalized by
  `quarkusBuild`.
- `imagePush` (`ImagePush`) depends on `quarkusImageExtensionChecks`, mutates
  `ForcedPropertieBuildService`, and is finalized by `quarkusBuild`.
- `buildNative` and `testNative` are public deprecated lifecycle aliases. When
  either task appears in the task graph, `QuarkusPlugin.configureBuildNativeTask`
  mutates extension state by setting `quarkusExt.nativeBuild` to `true`.
- `deploy` also uses the `QuarkusBuildTask` wiring and is finalized by
  `quarkusBuild`.
- `buildAotEnhancedImage` uses the same `QuarkusBuildTask` wiring, but does not
  currently drive the image/native hidden-state path described here.
- `quarkusRun` (`QuarkusRun`) also extends `QuarkusBuildTask`, depends on
  `quarkusBuild`, and then performs RUN-mode augmentation through
  `QuarkusBootstrap` / `StartDevServicesAndRunCommandHandler`.
- `quarkusDev` (`QuarkusDev`) does not extend `QuarkusBuildTask`, but it
  consumes serialized development and test application models and builds the
  dev-mode command line from Quarkus bootstrap/dev-mode APIs.

This means the current work is split by cache size and finalization needs, but
not by semantic build intent. The same public build task can still mean
"normal package", "native package", "container image build", or "container
image push" depending on selected tasks and execution-time service mutation.

`quarkusBuild` also currently has two user-facing meanings:

- executable entry point: users run `./gradlew quarkusBuild` to build the
  currently configured Quarkus application shape;
- configuration surface: users configure `tasks.named("quarkusBuild",
  QuarkusBuild)` or type-based `QuarkusBuild` task configuration to affect the
  build.

That dual role is the hardest compatibility constraint. A Gradle-native split
should not require users to immediately stop running `quarkusBuild`, and it
should not silently ignore configuration that existing builds apply to the
`QuarkusBuild` task.

## Current Input Inventory

`QuarkusBuildTask` models the common build inputs used by
`QuarkusBuildDependencies`, `QuarkusBuildCacheableAppParts`, `QuarkusBuild`,
`ImageBuild`, `ImagePush`, `Deploy`, `BuildAotEnhancedImage`, and
`QuarkusRun`:

- `@InputFile getApplicationModel()`: serialized normal-mode application model
  from `quarkusBuildAppModel`.
- `@Classpath getClasspath()`: main source set compile classpath, runtime
  classpath, annotation processor path, and resources.
- `@Input getCachingRelevantInput()`: selected Quarkus/project/config values
  derived from `quarkus.cachingRelevantProperties`.
- `@Input getJarEnabled()`: whether a JVM jar package is enabled for this build
  shape.
- `@Input getNativeEnabled()`: whether native build mode is enabled.
- `@Input getNativeSourcesOnly()`: whether only native-image sources should be
  generated.
- `@Input getJarType()`: effective Quarkus jar type, such as fast-jar,
  legacy-jar, mutable-jar, uber-jar, or AOT jar.
- `@Internal getRunnerSuffix()`, `getRunnerName()`, and `getOutputDirectory()`:
  path/name shaping values used to compute outputs. These are currently not
  modeled as direct task inputs, but they influence output paths and build
  semantics through derived methods.
- `@Internal getAdditionalForcedProperties()`: the shared
  `ForcedPropertieBuildService` used to provide late forced properties.
- `@Internal getFileSystemOperationsProvider()`: shared filesystem operations
  service.

`QuarkusPluginExtensionView` adds a nested view of extension state consumed by
build and code-generation tasks:

- `nativeBuild`, `cacheLargeArtifacts`, `cleanupBuildOutput`, and `finalName`.
- `cachingRelevantProperties`, `quarkusBuildProperties`,
  `quarkusRelevantProjectProperties`, `forcedProperties`, and
  `nativeArguments`.
- profile inputs from `quarkus.profile` system property and `QUARKUS_PROFILE`
  environment variable.
- manifest attributes and sections copied separately into build and codegen
  tasks.
- build/codegen fork option actions. These are nested task inputs, but they are
  Java actions rather than structured data, so they remain awkward from a cache
  and isolation perspective.

The current image-specific modeled inputs are narrow:

- `quarkusImageExtensionChecks` has the serialized application model, optional
  `quarkus.container-image.builder` system property, and output file
  `build/quarkus/image-name`.
- `ImageBuild` and `ImagePush` have `@InputFile getBuilderName()`, wired to that
  output file.

The current hidden or late inputs are the central problem:

- `ImageBuild` adds `quarkus.container-image.build=true` and
  `quarkus.container-image.builder=<builder>` to `ForcedPropertieBuildService`
  at execution time.
- `ImagePush` adds `quarkus.container-image.build=true` and
  `quarkus.container-image.push=true` to `ForcedPropertieBuildService` at
  execution time.
- `QuarkusBuildTask.additionalForcedProperties()` merges
  `quarkus.nativeArguments` with whatever is currently in
  `ForcedPropertieBuildService`.
- `buildNative` / `testNative` selection mutates `quarkusExt.nativeBuild` from
  `TaskExecutionGraph.whenReady`.
- Worker execution still receives execution-time process details such as the
  forked process environment and selected fork options. Those details are
  outside the build-shape split itself, but they are part of the broader cache
  compatibility surface.

Once named native/image/package outputs own structured inputs,
`ForcedPropertieBuildService` should no longer be needed for the new task
hierarchy. The current service exists to pass execution-time forced properties
from one legacy task to another:

- `ImageBuild` writes container-image build and builder properties.
- `ImagePush` writes container-image build/push properties.
- `QuarkusBuildTask` reads the service and merges it with
  `quarkus.nativeArguments`.

In the new hierarchy, those become ordinary inputs on the selected named output
or derived task. A normal/default named output has no image build/push intent
unless the user configured those properties explicitly. Image build/push tasks
select their named output with stable image inputs. Native named outputs have
stable native inputs and can be built alongside other named outputs.

Do not make removing the service from the old hierarchy a prerequisite for the
new split. The old tasks may keep `ForcedPropertieBuildService` and today's
dynamic behavior unchanged as compatibility behavior. Users who migrate to the
new task names get the Gradle-friendly model; users who stay on the old task
names keep the old semantics and warnings.

## Current Output Inventory

The Quarkus build is still executed with Gradle's project `build/` directory as
the Quarkus target directory for compatibility. Existing user builds may rely on
Quarkus artifacts appearing in those legacy locations.

Current generated/intermediate outputs:

- `build/quarkus-build/gen`: complete copied result of the Quarkus build for
  the selected shape, filtered to the relevant files. This directory is
  shape-specific in practice because it contains the raw Quarkus build result
  for the currently requested package/native/image semantics.
- `build/quarkus-build/app`: cacheable non-dependency app fragments for
  fast-jar-like, legacy-jar, AOT, and native runner shapes. This is also
  shape-specific in practice: fast-jar-like outputs exclude `quarkus-app/lib`,
  legacy-jar outputs exclude most `lib/` content but retain modified jars, and
  native outputs may include native runner/native-image source support files.
- `build/quarkus-build/dep`: dependency jars copied from the application model
  for fast-jar-like, legacy-jar, AOT, and native runner shapes. This directory
  is different from `gen/` and `app/`: for fast-jar, AOT, and non-sources native
  builds it should be shareable for the same application model and effective
  dependency-filtering/class-loading config. Legacy-jar needs a different
  layout, and mutable/uber/native-sources currently do not use this fragment.
- `build/quarkus/image-name`: selected container-image builder marker file.
- serialized application model files produced by the application model tasks.

Current legacy/default final outputs:

- fast-jar/AOT: the configured output directory, normally `build/quarkus-app`;
  Quarkus also emits `quarkus-artifact.properties` into that output target.
- legacy-jar: `build/lib`, the runner jar, and
  `build/quarkus-artifact.properties`.
- mutable-jar and uber-jar: runner jar and
  `build/quarkus-artifact.properties`; these currently fall back to full build
  behavior in `quarkusBuild`.
- native runner: native executable, native-image source jar directory, fast-jar
  support directory, and `build/quarkus-artifact.properties`.
- native sources only: native-image source jar directory and
  `build/quarkus-artifact.properties`.

Current local state and cleanup behavior:

- `QuarkusBuildCacheableAppParts` marks generated build output, legacy output
  directories, runners, native-source directories, and artifact properties as
  `@LocalState`.
- `QuarkusBuildTask.generateBuild()` deletes `build/quarkus-build/gen` and
  selected legacy output locations before running the Quarkus build.
- `QuarkusBuild.finalizeQuarkusBuild()` deletes selected final output files
  before re-assembling the legacy/default layout.
- `QuarkusBuildDependencies` and `QuarkusBuildCacheableAppParts` delete their
  fragment output directories before repopulating them.

That cleanup is not accidental. It compensates for shared legacy output roots:
switching from one package shape to another can otherwise leave stale files that
look like valid outputs for the next shape.

## Design Direction

Split the monolithic build meaning into explicit named Quarkus build outputs.

The preferred new public model is a Gradle `NamedDomainObjectCollection` on the
`quarkusApplication` extension from the standalone `io.quarkus.application`
plugin. Each registered build output owns one output type and its own
structured configuration:

```kotlin
quarkusApplication {
    buildProperties.put("common", "xyz")
    builds {
        fastJar("app") {
      buildProperties.put("property-foo", "bar")
            manifest { ... }
            outputName = "my-fast-jar"
        }
        native("native1") {
            nativeConfig { ... }
            outputName = "my-native"
        }
        fastJar("imageApp") {
            image {
                repository = "quay.io/acme/my-app"
                tag = "1.0"
                builder = JIB
            }
            aotEnhancedImage {
                aotFile.set(layout.buildDirectory.file("aot/app.aot"))
                producedBy(tasks.named("myIntTest"))
            }
            deployments {
                kubernetes("dev") { ... }
                openshift("prod") { ... }
            }
        }
    }
}
```

The exact DSL names are not final. The important shape is that output type is
part of the registered object type/factory, while the registered name gives the
output identity. This makes it possible to build several Quarkus outputs in one
Gradle invocation, for example a fast jar, an uber jar, a mutable jar, native
sources, and a native executable, without overloading one global
`quarkus.package.jar.type` task input.

Registered output names are global within `quarkusApplication.builds`, not
scoped by output type. A project can register `fastJar("app")` or
`native("app")`, but not both. The registered descriptor name is the stable
identity used for output roots, derived task names, diagnostics, and report
entries.

The descriptor-name uniqueness and task-name collision checks should operate on
registered descriptors, not by realizing/configuring every task. Perform the
check no later than the first derived task execution, and preferably during
task registration when Gradle has enough descriptor information. A late check
must fail before any output-producing action runs.

Derived task names should use the registered output name before the action
suffix, not a `quarkusBuild*` prefix. For example:

- `quarkusAppBuild`;
- `quarkusNative1Build`;
- `quarkusUberBuild`;
- `quarkusAppImageBuild`;
- `quarkusAppImagePush`;
- `quarkusNative1NativeTest`.

This keeps the new task namespace visually distinct from legacy `quarkusBuild`,
matches Gradle's common thing/action naming style, and avoids collisions with
old unprefixed tasks such as `imageBuild`, `imagePush`, `buildNative`, and
`testNative`. Registered output names must be validated or normalized with
clear collision detection, so names such as `native-main` and `nativeMain`
cannot silently derive the same task name.

The public `quarkusBuild` task should remain for compatibility. Existing user
build scripts often refer to `tasks.named("quarkusBuild", QuarkusBuild)` or
configure the `QuarkusBuild` task type. Do not casually remove or replace that
public surface.

Preferred transitional shape:

- keep `quarkusBuild` as the well-known compatibility build task;
- when users execute `quarkusBuild`, keep today's behavior: build the currently
  configured Quarkus application shape and materialize the legacy/default
  outputs;
- keep accepting existing configuration applied directly to the `quarkusBuild`
  task where possible;
- host the new task-type hierarchy in the standalone `gradle-app-plugin`
  module, separate from the existing `QuarkusBuildTask` / `QuarkusBuild` /
  `QuarkusBuildCacheableAppParts` / `QuarkusBuildDependencies` hierarchy;
- put new task classes under `io.quarkus.gradle.application.tasks`, using the
  `QuarkusApplication*` class-name prefix;
- model new task types as stable named-output tasks with structured inputs,
  shape-owned outputs, and no dynamic task-graph/service mutation;
- make image/native public lifecycle tasks depend on the corresponding named
  output task instead of mutating `quarkusBuild` inputs;
- propagate common managed configuration from the extension and, where needed,
  from `quarkusBuild` conventions into named-output tasks through
  providers/conventions;
- do not automatically propagate arbitrary task actions, dependencies,
  finalizers, or imperative task mutations from `quarkusBuild` to new
  named-output tasks;
- avoid task-graph-conditional input mutation.

Do not deprecate the `quarkusBuild` task name as part of this work. It is the
known command users run to build their application. The longer-term concern is
the `QuarkusBuild` task type as a configuration surface. That can only be phased
out once replacement extension-level or explicit build-shape configuration
surfaces exist.

Prefer a parallel hierarchy over incrementally rewriting the existing hierarchy.
The existing types carry compatibility behavior that is hard to make clean:
shared output roots, broad inherited inputs, fallback full-build behavior,
`QuarkusBuild` as a public configuration surface, and execution-time shared
state. A separate hierarchy lets the old tasks continue to work as compatibility
tasks while the new tasks are designed around Gradle's input/output model from
the start.

Mirror that split at the plugin-module boundary. Avoid weaving new task
registration through the existing legacy build-task setup. Keep clearly named
registration/configuration paths, for example:

- legacy application build registration: current `quarkusBuild`,
  `quarkusAppPartsBuild`, `quarkusDependenciesBuild`, `imageBuild`,
  `imagePush`, deprecated native aliases, and compatibility finalizers;
- new `QuarkusApplicationPlugin` named-output registration: explicit named
  build outputs, derived task names, isolated output roots, structured inputs,
  new dependency fragments, named native test tasks, and optional
  materialization tasks.

If useful, introduce a scoped registration context object to hold intermediate
state inside `QuarkusApplicationPlugin`: the extension instance, app-model task
providers, classpath builders, shared services, custom filesystem service, and
task providers that later wiring needs. Keep the context scoped to this
workstream and avoid turning it into a broad plugin framework.

Potential new hierarchy:

- named build-output model objects under `quarkusApplication.builds`;
- typed output definitions for fast jar, legacy jar, mutable jar, uber jar,
  native executable, native sources, and image-capable outputs including
  AOT/container-related variants;
- common internal base for named build-output execution: app model, classpath,
  effective config, worker execution, output root, and structured forced
  properties;
- build tasks derived from named outputs, such as `quarkusAppBuild` and
  `quarkusNative1Build`;
- optional image build/push tasks derived from named outputs, such as
  `quarkusAppImageBuild` and `quarkusAppImagePush`;
- native test tasks derived from named native outputs, such as
  `quarkusNative1NativeTest`;
- dependency-fragment task keyed by dependency layout, for example
  fast-jar-like vs legacy-jar;
- optional materialization/finalization task that copies one selected named
  output into legacy compatibility outputs such as `build/quarkus-app/`.

Task-class names should be distinct from public Gradle task names and from the
legacy task hierarchy. Use `QuarkusApplication*` for the new implementation
types, for example:

```text
io.quarkus.gradle.application.tasks
  QuarkusApplicationTask
  QuarkusApplicationBuildTask
  QuarkusApplicationPackageTask
  QuarkusApplicationNativeTask
  QuarkusApplicationImageBuildTask
  QuarkusApplicationImagePushTask
  QuarkusApplicationAotEnhancedImageBuildTask
  QuarkusApplicationAotEnhancedImagePushTask
  QuarkusApplicationDeployTask
  QuarkusApplicationLaunchTask
  QuarkusApplicationRunTask
  QuarkusApplicationDevTask
  QuarkusApplicationRemoteDevTask
  QuarkusApplicationContinuousTestTask
```

Keep pure value objects and planners close to that hierarchy, but do not add
deep packages before they are useful. A reasonable initial split is:

```text
io.quarkus.gradle.application.model
  QuarkusApplicationBuildDescriptor
  QuarkusApplicationBuildType
  OutputLayout
  QuarkusApplicationImageDescriptor
  QuarkusApplicationDeploymentDescriptor
  QuarkusApplicationLaunchDescriptor

io.quarkus.gradle.application.internal.planning
  TaskNamePlanner
  OutputLayoutPlanner
  ImagePlanner
  DeploymentPlanner
```

Avoid `NewQuarkus*` as a lasting name, and avoid extending the overloaded
legacy `QuarkusBuild*` naming family. Keep legacy classes in their current
packages and behavior.

`quarkusRun`, `quarkusDev`, `quarkusRemoteDev`, and `quarkusTest` need
separate consideration:

- `quarkusRun` currently depends on `quarkusBuild` before performing RUN-mode
  augmentation. The remaining design question is whether `quarkusRun` should
  keep depending on the compatibility `quarkusBuild` task or depend on a
  dedicated run/build-shape output that provides the files RUN mode needs.
- `quarkusDev` does not package the app in the same way, but it still relies on
  serialized app models, Quarkus bootstrap, dev-mode dependency resolution, and
  generated classes/resources. It belongs to the launch-session family, not the
  package-output hierarchy.
- `quarkusRemoteDev` and `quarkusTest` share the `QuarkusDev` base shape and
  should be treated as part of the same launch/dev-mode family.

Do not force run/dev/test mode into the same package-shape hierarchy if their
contracts are different. Dev, remote-dev, and continuous-test tasks should use
a launch-session base that is a sibling of the named build-output base. Any
shared code with package-output tasks should be limited to explicit app-model,
classpath, or augmentation helpers.

Longer term, the new task hierarchy should keep package/image/native build
outputs and long-lived launch sessions as siblings, not parent/child types:

```text
QuarkusApplicationTask
  QuarkusApplicationBuildTask
    named package/native/image/deploy output tasks
  QuarkusApplicationLaunchTask
    QuarkusApplicationRunTask
    QuarkusApplicationDevTask
    QuarkusApplicationRemoteDevTask
    QuarkusApplicationContinuousTestTask
```

`QuarkusApplicationBuildTask` owns named output descriptors, output roots,
package or image intent, augmentation results, layout inference, and
materialization planning. `QuarkusApplicationLaunchTask` owns launch
descriptors, dev/test app models, source-set/classpath metadata, generated
outputs, and `DevModeCommandLine` construction.

Model launch variants with an explicit launch kind, for example:

- `DEV`;
- `REMOTE_DEV`;
- `CONTINUOUS_TEST`.

`quarkusTest` is not "dev with tests" and not a Gradle `Test` task. It is a
Quarkus continuous-test launch mode using the dev-mode infrastructure, setting
`QuarkusBootstrap.Mode.CONTINUOUS_TEST` and `IsolatedTestModeMain`.

For the new launch-session model, prefer explicit continuous-test task names
such as:

- `quarkusContinuousTest` for a default launch descriptor;
- `quarkus<LaunchName>ContinuousTest` for named launch descriptors.

Keep legacy `quarkusTest` as the compatibility task name.

`quarkusIntTest` is different: it is a Gradle `Test`-style integration-test
task, not a Quarkus continuous-test launch session. Prefer integrating new
Quarkus integration-test behavior with Gradle's JVM Test Suite model instead
of inventing another Quarkus-owned test-suite DSL:

```kotlin
testing {
    suites {
        register<JvmTestSuite>("myIntTest") {
            forQuarkusBuild("app")
        }
    }
}
```

Gradle should own test-suite source sets, dependencies, targets, and the
underlying `Test` task. Quarkus should add the named build-output relationship
and Quarkus-specific test wiring such as app-model inputs, `BeforeTestAction`,
test system properties, Dev Services/compose inputs, native runner or
container-image metadata, and `@QuarkusIntegrationTest` conventions.

For Quarkus-owned built-in suites, especially native-test suites derived from
named native outputs and AOT-training suites derived from `aotEnhancedImage {}`
declarations, Quarkus should register the suite instead of asking build authors
to do so. This avoids the common Gradle ordering trap where a build script must
know whether to call `register(...)` or `named(...)`. For example,
`native("native1")` can register a suite named `quarkusNative1NativeTest`, and
`fastJar("app") { aotEnhancedImage { ... } }` can register a suite named
`quarkusAppAotTraining`. Build authors customize Quarkus-created suites with
`named(...)`; they use `register(...)` only for additional user-owned suites:

```kotlin
quarkus {
    builds {
        native("native1") {
            // native build config only
        }

        fastJar("app") {
            aotEnhancedImage {
                // Declaring this block registers quarkusAppAotTraining by default.
            }
        }
    }
}

testing {
    suites {
        named<JvmTestSuite>("quarkusNative1NativeTest") {
            // customize the Quarkus-created suite
        }

        named<JvmTestSuite>("quarkusAppAotTraining") {
            // customize the Quarkus-created AOT training suite
        }

        register<JvmTestSuite>("myIntTest") {
            forQuarkusBuild("native1")
        }
    }
}
```

Quarkus should not require a `nativeTest { enabled = true }` flag just to make
the default native-test suite exist. Registering a native output is enough to
create the matching Quarkus-owned native-test suite/task, but that suite should
remain explicit-by-default and not be wired into `check` unless a later opt-in
model chooses to do so. If built-in Quarkus suites require Gradle's
`jvm-test-suite` infrastructure, the application plugin should apply/configure
it as part of the normal Quarkus application-plugin model instead of requiring
users to apply it manually. User-defined extra suites remain user-owned and can
be attached to named Quarkus outputs with `forQuarkusBuild(...)`.

The same ordering rule applies to AOT-enhanced images: declaring
`aotEnhancedImage {}` should register the deterministic Quarkus-owned training
suite before users need to customize it with `testing.suites.named(...)`. Do
not require users to pre-register the suite before the `quarkus {}` block.

Design the new hierarchy for testability from the start. Most behavior should
live in small services or value objects that can be unit-tested without running
a Gradle build. Task classes should mainly declare Gradle inputs/outputs and
delegate to those services.

Testing strategy is part of the design, not an afterthought. Prefer the cheapest
test level that can prove the contract:

1. Pure unit tests for value objects, planners, normalization, validation,
   output-layout decisions, forced-property planning, image/deploy/AOT
   descriptors, and compatibility materialization plans.
2. Cheap Gradle `ProjectBuilder`-style tests for plugin registration,
   extension/model object creation, task providers, conventions, and direct
   task relationships that do not require executing Gradle.
3. Gradle TestKit only for behavior that needs a real Gradle build: task graph
   execution, provider realization boundaries, configuration-cache reuse,
   up-to-date/cache behavior, and Kotlin/Groovy DSL interaction.
4. Heavy `integration-tests/gradle` coverage only when the behavior requires a
   real Quarkus application build, container-image extension behavior,
   native-image behavior, dev-mode process behavior, or other end-to-end
   Quarkus integration.

Do not add a TestKit or integration test when a pure unit test or cheap
`ProjectBuilder` test can prove the same contract. If a higher-level test is
added, keep a lower-level test for the underlying decision whenever practical so
failures identify whether the break is in planning, Gradle wiring, or Quarkus
execution.

Recommended split:

- pure model/value objects for named output identity, output type, package
  shape, output layout, dependency layout, forced-property intent, and
  materialization plan;
- pure planners that map structured inputs to expected `gen/`, `app/`, `dep`,
  legacy output, and cleanup operations;
- a clearly isolated package-layout inference component for outputs that are
  not fully represented in `AugmentResult` metadata today;
- small filesystem executors that apply a planned copy/sync/delete operation;
- thin Gradle task classes that wire providers, declare annotations, and call
  the planner/executor.

This should allow most edge cases to be covered by plain unit tests:

- package-shape to output-layout mapping;
- whether dependency fragments can be shared;
- image/native forced-property mapping;
- legacy materialization plan for `build/quarkus-app/`;
- cleanup decisions for compatibility output roots;
- error cases such as incompatible native+jar shape combinations.

Use cheap Gradle `ProjectBuilder`-style tests for plugin/model wiring that does
not require Gradle execution:

- extension and named-container registration;
- typed factory and class-based registration behavior;
- task provider registration and direct task dependencies;
- convention propagation that can be inspected without executing tasks;
- separation of legacy and new `QuarkusPlugin` registration paths.

Use Gradle TestKit for Gradle execution-specific contracts:

- input/output annotations and cache/up-to-date behavior;
- configuration-cache compatibility;
- provider/convention propagation of managed extension and compatibility
  `quarkusBuild` properties into new named-output tasks;
- proof that arbitrary `quarkusBuild` task actions, dependencies, finalizers,
  and imperative task mutations do not silently attach to new named-output
  tasks;
- public task names and diagnostics behavior;
- Kotlin and Groovy DSL behavior that cannot be proven with `ProjectBuilder`.

Reserve the heavy `integration-tests/gradle` tests for end-to-end packaging
behavior that really needs a full Quarkus application build, container-image
extension behavior, or native-image related behavior.

The delegated investigation pass is complete; see
`archive/phase-a/investigation-results.md`. The implementation plan below
incorporates those results. Remaining follow-ups are tracked in the settled
direction/follow-up section and phase-specific test gates rather than as a
separate pre-implementation investigation step.

## Conditional Inputs

The tempting local fix is to make an input file for `quarkusBuild` conditional
on whether `ImageBuild` or `ImagePush` is in the task graph. That would make the
cache key change when image tasks execute.

This is not the preferred long-term shape.

Reasons:

- Gradle's configuration-cache/build-cache model works best when task inputs
  are configured independently from the selected task graph.
- `TaskExecutionGraph.whenReady`-style mutation is late and graph-sensitive.
- A single task with graph-dependent meaning remains hard to reason about and
  hard to extend to multiple package shapes.

Prefer making the condition a named-output/task-selection decision instead:

- running `quarkusBuild` keeps the legacy compatibility path for the currently
  configured default output;
- running `quarkusAppBuild` selects the named app output with stable inputs;
- running `quarkusAppImageBuild` selects the named app output plus stable image
  build inputs;
- running `quarkusAppImagePush` selects the named app output plus stable image
  push inputs;
- running `quarkusNative1Build` selects the named native output with stable
  native inputs.

## Output Layout Constraint

Multiple build shapes cannot safely write to the same output directories.

Today, different shapes produce output under shared `build/` locations. That is
why some cleanup/deletion code exists: switching shapes can leave stale files
from the previous shape in a directory now owned by a different semantic output.

The split design should introduce shape-specific output roots for new explicit
shape-owned fragments. This does not necessarily mean duplicating every current
fragment for every shape. `gen/` and `app/` need per-shape ownership. `dep/`
should be treated as a reusable dependency fragment where the dependency layout
and filtering semantics match.

Possible layout:

- `build/quarkus-build/dep/...` for legacy-compatible or reusable dependency
  fragments where the layout and filtering semantics match;
- `build/quarkus-builds/app/gen/...`
- `build/quarkus-builds/app/app/...`
- `build/quarkus-builds/uber/gen/...`
- `build/quarkus-builds/uber/app/...`
- `build/quarkus-builds/native1/gen/...`
- `build/quarkus-builds/native1/app/...`

Exact dependency-fragment names are not decided here. The default output root
for a new named output should be `build/quarkus-builds/<registered-name>/`,
with user-overridable output-directory conventions on the descriptor where that
is useful.

Compatibility fallback:

- `quarkusBuild` keeps the existing/default output layout for now;
- `build/quarkus-app/` remains a materialized legacy/default final output, not
  a symlink or pointer;
- new named-output tasks relocate `quarkus-artifact.properties` from the
  Quarkus output target to
  `build/quarkus-build-results/<registered-name>/quarkus-artifact.properties`;
- `build/quarkus/application-model/` remains separate because it is keyed by
  launch mode and application model inputs, not by package shape;
- cleanup/deletion behavior remains on the compatibility/default path as needed;
- new explicit named-output tasks with new task names use isolated output
  directories and should not sync to legacy output directories by default;
- new explicit named-output tasks should not need cross-shape cleanup;
- an explicit compatibility materialization task may expose one selected shape
  at the legacy location if needed.

This makes concurrent multi-output builds possible:

```bash
./gradlew quarkusAppBuild quarkusUberBuild quarkusNative1Build
```

without output clobbering.

The important rule is: legacy output materialization belongs to `quarkusBuild`
or an explicitly named compatibility materialization task, not to every new
named-output task. New task names should make it clear when they produce
named-output-owned outputs only.

## Container Image Outputs

Container-image publication should be configured on the registered build output
that owns the image. Do not introduce a separate global image publication graph
for the first slice.

Example:

```kotlin
quarkus {
    builds {
        fastJar("app") {
            image {
                repository = "quay.io/acme/my-app"
                // Defaults to project.version when not set.
                tag = "1.0"
                builder = JIB
                quarkusBuildProperties.put("quarkus.jib.platforms", "linux/amd64,linux/arm64")
            }
            aotEnhancedImage {
                aotFile.set(layout.buildDirectory.file("aot/app.aot"))
                producedBy(tasks.named("quarkusAppAotTraining"))
                // Defaults to the normal image reference plus
                // quarkus.container-image.aot-image-suffix, currently "-aot".
                imageSuffix = "-aot"
            }
        }
        native("native1") {
            // No image tasks are meaningful unless this output opts in too.
        }
    }
}
```

The image block defines the image target and image-specific inputs. It should
not contain `build = true` or `push = true` flags. The selected Gradle task is
the command:

- `quarkusAppImageBuild` builds the configured image target;
- `quarkusAppImagePush` builds and pushes the configured image target.

This keeps task intent explicit and avoids reintroducing configured task
meaning through DSL booleans. A plain `quarkusAppBuild` should not publish a
container image just because the output has image configuration.

Use an enum for the image builder attribute rather than a free-form string, so
common values are hard to misspell. Start with enum values for known Quarkus
container-image builders such as Jib, Docker, Podman, OpenShift, and Buildpack.
Add S2I only if Quarkus still exposes it as a distinct builder in the relevant
version.

Image-specific Quarkus build properties belong on the image block as a
`MapProperty<String, String>`. These properties are passed to the Quarkus build
machinery only for image-producing tasks. They are the right place for
builder-specific container-image properties such as Docker buildx platforms,
Podman platforms, Jib platforms, and other image-only Quarkus configuration.
They should be merged after common output-level `quarkusBuildProperties`, with
task intent (`quarkus.container-image.build` and
`quarkus.container-image.push`) supplied by the selected task.

Image tags should default from `Project.getVersion().toString()`. The
implementation should handle Gradle's default `unspecified` version explicitly,
either by failing with a clear message for image tasks or by documenting a
stable fallback; do not silently publish `:unspecified` unless that is an
intentional compatibility decision.

Effective image references must be deterministic. If multiple registered
outputs enable image publication with the same effective image reference and
more than one corresponding image build/push task is selected in the same
Gradle invocation, fail before any image-producing action runs unless the tasks
are part of one explicit ordered owner/flow, such as an AOT-enhanced image
replacing the base image for the same registered output. This allows one
project-wide tag to be used by one selected output, while preventing two output
types from silently racing to publish the same tag. Do not add a broad
overwrite/alias model in the first slice.

### AOT Enhanced Images

AOT-enhanced image support should be modeled as an optional nested image
feature on the registered output, not as a global task. It is a
current-platform convenience flow, not a multi-platform AOT orchestration
system:

```kotlin
quarkus {
    builds {
        fastJar("app") {
            image {
                repository = "quay.io/acme/my-app"
                tag = "1.0"
                builder = JIB
            }
            aotEnhancedImage {
                aotFile.set(layout.buildDirectory.file("aot/app.aot"))
                producedBy(tasks.named("quarkusAppAotTraining"))
                // Defaults to the normal image reference plus
                // quarkus.container-image.aot-image-suffix, currently "-aot".
                imageSuffix = "-aot"
            }
        }
    }
}
```

The `aotFile` should be a `RegularFileProperty`. If the file provider is backed
by a task output property, Gradle can carry the producer dependency through the
provider. If the producer task does not expose a typed output property, the DSL
should provide explicit producer wiring, for example `producedBy(...)` or a
helper such as `aotFileFrom(producer, fileProvider)`. The producer task is
dependency wiring only; the cache/input model should come from the declared
`aotFile` and other structured image inputs.

The default enhanced image target should follow current Quarkus behavior:
derive from the normal image reference plus
`quarkus.container-image.aot-image-suffix`, whose current default is `-aot`.
Phase D production execution supports this suffix contract only, because the
existing Quarkus container-image processors derive the enhanced target from the
original image plus suffix. Users may model repository, tag, or full-reference
overrides in the DSL/planner, but executing those overrides needs a later
core/container-image API or SPI that does not force ad hoc processor changes.
Until that exists, executable AOT image tasks should fail clearly when such
overrides are configured.

AOT-enhanced image tasks should exist as a build/push pair, analogous to the
normal image pair:

```text
quarkusAppImageBuild -> myIntTest -> quarkusAppAotEnhancedImagePush
```

The derived AOT-enhanced image tasks should be output-specific, for example:

- `quarkusAppAotEnhancedImageBuild`;
- `quarkusAppAotEnhancedImagePush`.

Keep the existing global `buildAotEnhancedImage` task unchanged as legacy
compatibility behavior. The new path should fail clearly when required
`aotEnhanced` inputs are missing, instead of silently skipping because a global
metadata file is absent.

Do not attempt to support multi-platform AOT-enhanced images as an automatic
Quarkus Gradle workflow. AOT files are platform/JDK-sensitive, so a true
multi-platform AOT image requires users to train and build per platform and
assemble/publish the manifest externally. Normal image build/push tasks may
still support multi-platform output through existing Quarkus container-image
properties. AOT-enhanced image tasks should document that they operate only for
the current platform; they should not try to prove whether the base image is
single-platform or multi-platform in the first execution slice.

AOT training should be modeled as an optional producer, not hidden inside the
AOT image task. Support these cases:

- default Quarkus-owned AOT-training suite registered by `aotEnhancedImage {}`;
- external/manual `aotFile` with no producer task, replacing the default
  producer;
- `aotFileFrom(...)` or `producedBy(...)` wiring to an existing integration-test
  task or suite, replacing or augmenting the default producer.

Declaring `aotEnhancedImage {}` should register a deterministic, namespaced
Gradle JVM Test Suite for the named output, for example
`quarkusAppAotTraining`. This matches the planned native-test suite model:
Quarkus registers built-in suites, users customize them with
`testing.suites.named(...)`, and users call `register(...)` only for additional
user-owned suites. The application plugin may apply/configure Gradle's
`jvm-test-suite` infrastructure as needed for these built-in suites.

If a manual `aotFile` is configured without producer wiring, the Quarkus-owned
suite may still exist as the deterministic customization point, but the
AOT-enhanced image tasks must not depend on it. The selected producer is the
explicit `aotFileFrom(...)`/`producedBy(...)` wiring when present; otherwise it
is the default training suite; otherwise the task consumes the manual file as a
plain input.

The Quarkus-owned AOT-training suite should inject the required Quarkus build
properties for that suite, such as `quarkus.package.jar.aot.enabled=true` and
the integration-test AOT phase, and expose the produced AOT file as a typed
Gradle output. Reusing existing integration tests should remain possible, but
AOT training is a distinct intent from correctness testing and may deserve a
separate suite.

## Deployment Outputs

Deploy should remain a legacy global task initially. The current `deploy` task
is a generic Quarkus deploy-command wrapper: it selects a deploy target,
validates deployer and container-image extensions, optionally requests image
build properties, runs Quarkus deploy command handlers, and relies on
`quarkusBuild` as the compatibility backend/finalizer.

In the new model, deploy should be an optional named deployment container under
the registered build output:

```kotlin
quarkus {
    builds {
        fastJar("app") {
            image {
                repository = "quay.io/acme/my-app"
                tag = "1.0"
                builder = JIB
            }
            deployments {
                kubernetes("dev") {
                    // target = KUBERNETES implied by the factory
                    imageSource = ImageSource.NORMAL_IMAGE_PUSH
                }
                kind("local") {
                    // target = KIND implied by the factory
                    imageSource = ImageSource.EXISTING_IMAGE
                    imageReference = "localhost/acme/my-app:dev"
                }
                openshift("prod") {
                    // target = OPENSHIFT implied by the factory
                    imageSource = ImageSource.AOT_ENHANCED_IMAGE_PUSH
                }
            }
        }
    }
}
```

Derived deploy task names should make the deployment/environment identity
explicit:

- `quarkusAppDeployToDev`;
- `quarkusAppDeployToLocal`;
- `quarkusAppDeployToProd`.

Do not add a simple single-deployment `deploy { ... }` sugar block initially.
That sugar would derive a task such as `quarkusAppDeploy`, but it becomes
ambiguous once a second deployment is added. Require named deployment
descriptors from the start.

Deployment names are unique within a named output. Derived task-name collision
checks must fail before any deploy action runs.

New deploy tasks should mirror the legacy behavior functionally, but with
structured inputs and explicit dependencies:

- select or validate the deploy target;
- validate the required deployer extension is present;
- validate required container-image extension/configuration when deployment
  needs an image;
- consume an explicit image source rather than carrying a `push` boolean;
- depend on the named output image task required by the selected image source,
  such as normal image push or AOT-enhanced image push, rather than setting
  image-build forced properties;
- support deployment using an already existing image reference without adding
  image build/push prerequisites;
- use a deploy-specific named operation behind the application build operations
  boundary;
- prefer structured result data from `DeploymentResultBuildItem`, with a
  limited successful receipt fallback when only the generic deploy command path
  reports success;
- pass deploy target/configuration as structured task/build-system properties
  rather than mutating JVM-global system properties;
- fail when user-supplied Quarkus deployment or image configuration
  contradicts the named deployment descriptor;
- remain non-cacheable because deployment mutates external state, while still
  modeling inputs for configuration-cache correctness and diagnostics.

Keep the existing global `deploy` task unchanged as legacy compatibility
behavior until output-specific deploy tasks are available.

## Migration Diagnostics

The best user experience for Quarkus 4.0 is to preserve `quarkusBuild`
execution behavior and make legacy-task diagnostics available as an opt-in
migration aid. Quarkus 4.1 can enable those diagnostics by default once the new
named-output model exists and the replacement path is documented.

When enabled, warn for legacy application task usage, including plain
`quarkusBuild` execution. Running `quarkusBuild` remains valid; the diagnostic
is about compatibility-model usage, not immediate task-name removal.

Warn when the requested task path uses the legacy application model or depends
on task-graph-selected or execution-time-mutated build intent:

- `quarkusBuild`, because it remains the compatibility/default package task;
- `imageBuild` and `imagePush`, because they currently mutate
  `ForcedPropertieBuildService` and finalize `quarkusBuild`;
- `buildNative` and `testNative`, because they currently flip native build
  intent through task graph state;
- `deploy`, because it is the global legacy deployment task;
- `buildAotEnhancedImage`, because it is the legacy AOT-enhanced image path;
- direct legacy task APIs that are being replaced by extension-level or
  explicit named-output configuration, where already covered by the broader
  Gradle DSL deprecation work.

Expose diagnostics as a nested composite on the `quarkus` extension, with
conventions fed from Gradle project properties:

```kotlin
quarkus {
    diagnostics {
        legacyTaskUsage = WARN // OFF, WARN, FAIL
    }
}
```

Suggested property:

```bash
./gradlew build -Pquarkus.diagnostics.legacy-task-usage=warn
```

Recommended levels:

- `OFF`: no legacy-task warning, report, or failure;
- `WARN`: log a warning and write a report entry when legacy usage is used,
  whether directly requested or reached transitively;
- `FAIL`: fail when legacy usage is used, whether directly requested or reached
  transitively, with a report entry if possible.

Report generation should be part of the diagnostics model, for example under
`build/reports/quarkus/diagnostics.txt` or a more specific legacy-task report
file. Keep report generation enabled when `legacyTaskUsage` is `WARN` or
`FAIL`; consider a separate report file property if users need to redirect it.

Define "usage" narrowly enough that `FAIL` is usable, but do not distinguish
direct from transitive use once the legacy path is actually used:

- legacy task explicitly requested;
- legacy task executes;
- later enhancement: legacy task or extension shape is configured through old
  APIs that have documented replacements, even if no legacy task executes.

Do not fail merely because legacy tasks are registered or because
`quarkusBuild` is wired into `assemble`.

Suggested warning message shape:

```text
The Quarkus Gradle plugin currently uses quarkusBuild as the shared execution backend for this build shape.
This legacy compatibility path is planned to change after Quarkus 4.0.
Future versions will use named build-output tasks for native/image/package variants to improve Gradle build-cache and configuration-cache correctness.
See: <versioned docs URL>
```

The warning should be about the shared execution backend and side-effect based
build-shape selection, not about `quarkusBuild` disappearing.

Potential release shape:

- Quarkus 4.0: keep old behavior, introduce the new named-output build
  hierarchy, add opt-in legacy-task diagnostics with `OFF` as the default, and
  document the replacement path.
- Quarkus 4.1: deprecate old Java task types for removal, including the old
  `QuarkusBuildTask` / `QuarkusBuild` / `QuarkusBuildCacheableAppParts` /
  `QuarkusBuildDependencies` family where replacement tasks exist. Enable
  legacy-task diagnostics at `WARN` by default. Keep the `quarkusBuild` task
  name as the compatibility entry point.
- Quarkus 4.1 or later: route native/image/package variants through dedicated
  named-output tasks while preserving `quarkusBuild` as the well-known command
  for the currently configured default/compatibility shape.

## Expected Benefits

- Correct build-cache keys for native/image/package-shape intent.
- Less execution-time shared-state mutation.
- Fewer task-graph-sensitive inputs.
- Multiple package shapes can be built in one Gradle invocation.
- Multiple named native outputs can have their own matching native test tasks.
- Shape-specific output ownership reduces cleanup/deletion heuristics.
- Future task cacheability reviews become simpler because each task owns one
  input/output contract.
- Better testability: most output-layout, build-shape, and materialization
  behavior can be covered by pure unit tests, with TestKit reserved for Gradle
  wiring/cache/configuration-cache contracts.

## Compatibility Constraints

- Keep `quarkusBuild` as a public task name.
- Prefer keeping `quarkusBuild` as type `QuarkusBuild` for now.
- Do not deprecate the `quarkusBuild` task name before there is a clearly
  accepted replacement story for the well-known build command.
- Consider phasing out direct `QuarkusBuild` type-based configuration only
  after replacement managed configuration surfaces exist.
- Do not attempt to make the old task hierarchy perfectly Gradle-native while
  also preserving all compatibility semantics. Let old tasks remain
  compatibility tasks and put the clean model in new task types.
- Keep legacy and new task registration/configuration paths visibly separated
  by module: legacy compatibility registration remains in `QuarkusPlugin`, and
  new named-output registration lives in `QuarkusApplicationPlugin`.
- Use a small scoped registration context if it reduces parameter threading and
  makes the split clearer; do not use this as an excuse for a broad plugin
  refactor.
- Keep the new hierarchy thin at the Gradle task boundary. Put shape decisions,
  output planning, and materialization planning into unit-testable services.
- Existing normal-build customizations on `quarkusBuild` should continue to
  apply to normal builds.
- Existing `quarkusBuild` execution should continue to build the currently
  configured package/native shape during the warning/migration phase.
- Do not assume arbitrary `quarkusBuild.doFirst { ... }` actions should apply
  to internal image/native or named-output tasks. That is a compatibility risk
  and needs an explicit migration decision if `quarkusBuild` ever becomes
  lifecycle-only.
- Preserve legacy/default output locations for `quarkusBuild`.
- Introduce isolated output locations only for new explicit named-output tasks
  or internal image/native execution tasks.

## Named-Output Execution Principles

### AugmentResult And Managed Outputs

`AugmentResult` is useful but not sufficient as the only source for managed
Gradle output properties. Gradle `@OutputFile` and `@OutputDirectory`
properties must be configured before task execution, while `AugmentResult` only
exists after augmentation has run. Named-output tasks therefore declare their
managed outputs from the registered descriptor and planned output directory,
then consume `AugmentResult` after execution to validate facts, write receipts,
and drive compatibility materialization.

Current authoritative facts available from `AugmentResult`:

- `getJar()` exposes `JarResult.path`, `originalArtifact`, `libraryDir`,
  `mutable`, `classifier`, and `isUberJar()`;
- `getNativeResult()` exposes the native executable path;
- `getResults()` exposes `ArtifactResult` path, type, and metadata produced
  from `ArtifactResultBuildItem`;
- jar artifact metadata includes `library-dir` when Quarkus produced one;
- native artifact metadata includes GraalVM information;
- native-sources artifact metadata currently does not provide a complete,
  reliable output-directory manifest.

Implications by named output type:

| Output type | `AugmentResult` status | Gradle modeling consequence |
| --- | --- | --- |
| fast-jar | Primary jar path and library directory are available. | Declare the named output root from the descriptor; use result facts to validate/copy the fast-jar layout and receipts. |
| mutable-jar | Primary jar facts and mutable flag are available. | Still keep layout/support-directory rules in the Gradle package-layout planner. |
| uber-jar | Primary jar path is available; `JarResult.isUberJar()` can identify lack of library dir. | Good enough for the primary `@OutputFile`; descriptor/planner still owns the pre-execution file location. |
| legacy-jar | Primary jar path and library directory are available. | Compatibility layout, `build/lib`, and materialization rules remain Gradle-side planner knowledge. |
| native | Native executable path is available through `getNativeResult()` and native `ArtifactResult` metadata. | Good enough to validate/write a native executable receipt; the task still declares the expected output from descriptor/planner state. |
| native-sources | `ArtifactResult` type exists, but the result path can point at the source jar path rather than the final copied `native-sources` directory. | Not enough for pure result-driven outputs; keep native-sources directory inference isolated until Quarkus exposes a richer output manifest. |

Keep the boundary explicit in code: a Gradle-plugin-local helper may translate
`AugmentResult` into authoritative augmentation facts, but package-layout
inference must remain separate and clearly replaceable. A future Quarkus
build-tool output manifest could remove most of this inference by returning
primary artifacts, support directories, dependency directories, generated
metadata, and compatibility materialization targets directly.

### Existing Task Reuse Boundary

Named-output execution should reuse existing source-generation wiring, but it
should not directly reuse the legacy app-fragment task classes as replacement
tasks.

`QuarkusGenerateCode` is an upstream source-generation task. It is not tied to a
single package shape or output root, and it already participates in the normal
Java/classes/resources pipeline. New named-output build tasks should depend on
the existing generated-source and compilation pipeline rather than duplicate
code-generation worker behavior.

`QuarkusBuildCacheableAppParts` and `QuarkusBuildDependencies` are legacy
package-layout tasks. They extend `QuarkusBuildTask`, use the legacy effective
configuration/build-service shape, hard-code legacy fragment roots such as
`build/quarkus-build/app`, `build/quarkus-build/gen`, and
`build/quarkus-build/dep`, and expose conditional outputs based on
`nativeEnabled()`, `nativeSourcesOnly()`, and `jarType()`. They also encode
compatibility fallback behavior, including empty outputs for mutable jar, uber
jar, and native-sources cases.

For the new model, keep those task classes on the legacy `quarkusBuild` path.
Extract or mirror their reusable logic into testable helpers instead:

- dependency fragment layout and dependency-copy/filtering decisions;
- app/layout synchronization decisions;
- package-layout inference around `AugmentResult` facts;
- compatibility materialization rules.

The new `QuarkusApplication*` task classes should consume those helpers with
explicit managed properties and named output roots such as
`build/quarkus-builds/<name>/...`. This preserves behavior while keeping most
of the new logic covered by pure unit tests, with TestKit and integration tests
reserved for Gradle task wiring, cache/up-to-date behavior, worker invocation,
and end-to-end package behavior.

### Effective Configuration Behavior

The current `EffectiveConfigProvider` should be treated as legacy-shaped, but
its behavior is important and should be preserved when named-output tasks grow
their own descriptor-driven configuration path.

The detailed history, known regression tests, and named-output coverage gaps are
tracked in [Effective Config History And Reuse Notes](effective-config-history.md).

Current provider inputs:

- application model platform properties and app artifact coordinates;
- ignored jar entries from the extension;
- main resource directories used to load application configuration files;
- extension forced properties;
- Gradle project properties relevant to Quarkus;
- extension `quarkusBuildProperties`;
- manifest attributes and manifest sections exported as Quarkus package
  properties;
- global extension `nativeBuild`;
- profile inputs from system property, environment variable, extension build
  properties, and Gradle project properties.

Current merge behavior:

- task/manifest properties are exported through
  `quarkus.package.jar.manifest.*` keys;
- default `quarkus.application.name` and `quarkus.application.version` come
  from the application artifact;
- ignored jar entries become
  `quarkus.package.jar.user-configured-ignored-entries`;
- `additionalForcedProperties` are merged into forced properties at call time;
- global `nativeBuild=true` adds `quarkus.native.enabled=true`;
- profile resolution order is system property, environment variable,
  `quarkusBuildProperties`, Gradle project properties, then `prod`.

`EffectiveConfig` then constructs the SmallRye config using these effective
source ordinals:

| Ordinal | Source |
| --- | --- |
| 600 | forced properties |
| 500 | task/manifest properties |
| 400 | JVM system properties |
| 300 | environment variables |
| 290 | extension `quarkusBuildProperties` |
| 280 | Gradle project properties |
| 265/260 | file-system `config/application.{yaml,yml,properties}` |
| 255/250 | classpath `application.{yaml,yml,properties}` |
| 110/100 | classpath `microprofile.{yaml,yml,properties}` |
| 0 | platform/default fallback properties |

The resulting `EffectiveConfig` exposes both the full effective config map and
the smaller Quarkus worker propagation map. Worker propagation keeps
`quarkus.*` and `platform.quarkus.*` values from sources not already visible to
Quarkus in the worker, plus system properties, platform properties, and
`quarkus.test.*` values. It intentionally skips configuration-file values that
the worker can load itself and avoids propagating default `PackageConfig` and
`NativeConfig` values unless they were explicitly set.

For named-output tasks, do not reuse `EffectiveConfigProvider` directly as the
main model. It is coupled to `QuarkusPluginExtensionView`, global
`nativeBuild`, and call-time `additionalForcedProperties`, which are exactly the
legacy selection mechanisms the named-output model is replacing. Instead,
extract or mirror the merge/profile/worker-propagation semantics behind a pure
planner fed by an explicit immutable request:

- common extension `quarkusBuildProperties`;
- registered-output `quarkusBuildProperties`;
- operation-specific forced properties from package/native/image/deploy
  planners;
- manifest properties when the output type supports them;
- profile inputs;
- app-model platform properties and app coordinates;
- source directories for application config lookup;
- provider-backed, `configInputs`-filtered system, environment, and Gradle
  project properties;
- default properties such as application name/version and ignored jar entries.

Broad capture of all environment variables, JVM system properties, and Gradle
project properties is intentionally not the default named-output behavior.
Those sources can affect build-time Quarkus configuration, profile selection,
validation of the descriptor-owned output shape, and the Quarkus worker
propagation map, but capturing all of them makes task execution non-portable and
too sensitive to unrelated host state.

The named-output model therefore defaults to filtered ambient capture and
supports `-PquarkusBuildLegacyAmbientConfigCapture=true` as an explicit
compatibility escape hatch. When enabled, named application tasks warn, opt out
of configuration-cache reuse, disable build caching, and are never considered
up-to-date.

The filtered mapping is modeled through extension-level `configInputs` DSL:

```kotlin
quarkus {
    configInputs {
        projectProperties {
            prefixes.add("quarkus.")
            names.add("quarkus.some.exact.property")
        }
        systemProperties {
            prefixes.add("quarkus.")
            names.add("quarkus.some.exact.system-property")
        }
        environmentVariables {
            prefixes.add("QUARKUS_")
            names.add("QUARKUS_SOME_EXACT_ENV")
        }
        legacyAmbientConfigCapture.set(false)
    }
}
```

Default prefixes are `quarkus.`, `platform.quarkus.`, and
`smallrye.config.` for Gradle project and JVM system properties, and
`QUARKUS_`, `PLATFORM_QUARKUS_`, and `SMALLRYE_CONFIG_` for environment
variables. Exact names are also supported for each source type. The filtered
source maps are task inputs and are used for effective-config planning and
build-system property propagation. In normal mode, `buildSystemProperties`
starts from the Quarkus worker propagation map and merges explicitly supplied
build, task, Gradle project, and JVM system properties. In legacy ambient mode,
`buildSystemProperties` intentionally uses the full effective config map.

The detailed planner API, source ordering, propagation filters, default
exclusion rules, profile behavior, and worker-reset behavior should be specified
in [Effective Config History And Reuse Notes](effective-config-history.md) and
treated as the source of truth for Phase B implementation planning.

The named-output path must make native/image/package intent descriptor-driven
and operation-driven. It must not read global `nativeBuild` or late
build-service state to decide the effective build shape.

### Descriptor-Owned Shape Properties

Named-output identity must be owned by the registered descriptor and selected
task, not by arbitrary Quarkus config sources.

Application config files, environment variables, Gradle project properties, and
other Quarkus config sources may still influence ordinary build-time extension
configuration. They must not silently change the semantic output that Gradle
declared and wired before execution.

Shape-defining properties include at least:

- `quarkus.package.jar.type`;
- `quarkus.native.enabled`;
- `quarkus.package.jar.enabled`;
- `quarkus.package.output-directory`;
- `quarkus.package.output-name`;
- container-image build/push intent;
- selected container-image builder when modeled by the descriptor;
- AOT-enhanced image target/reference.

For example, a registered fast-jar output must force
`quarkus.package.jar.type=fast-jar` at the operation/forced-property layer. If
`application.properties` says `quarkus.package.jar.type=uber-jar`, the
configured fast-jar output still remains fast-jar. The same applies to image
tasks: `quarkus<Name>ImageBuild` owns image-build intent, and
`quarkus<Name>ImagePush` owns image-push intent.

Add a post-effective-config validation step for named-output tasks: after the
effective config is built, verify that the resolved package/native/image shape
still matches the descriptor and selected operation. A mismatch should fail with
a clear diagnostic. This protects against missed forced properties, deprecated
aliases, or Quarkus-side compatibility rewrites that would otherwise change the
declared Gradle task semantics.

The invariant is: the descriptor defines the output shape; effective config is
the execution environment within that shape.

## Roadmap And Phase Boundaries

For executable step-by-step work, use
`archive/phase-a/implementation-plan.md` for the completed Phase A record and
`phase-b-task-topology.md` for the Phase B topology, later-reference topology,
and B0/B1/B2 slice boundaries. This section records the broad phase boundaries;
phase-specific implementation plans record ordered tasks, guardrails, tests,
and stop conditions.

### `P1-AP-02A`: Completed Investigation And State Inventory

This phase is complete and captured in
`archive/phase-a/investigation-results.md`. It established the current task/mode
matrix, output-layout constraints, compatibility-only behavior, augmentation
metadata opportunities, and test gates.

Key outcomes already incorporated into this design:

- old image/native/deploy/AOT tasks remain compatibility-only initially;
- new package/native/image/deploy behavior should live in the
  standalone `io.quarkus.application` plugin's `QuarkusApplication*`
  hierarchy with named descriptors and isolated outputs;
- dev, remote-dev, and continuous-test tasks are launch-session tasks, not
  package-output tasks;
- `quarkusRun` remains the main unresolved launch/build boundary question;
- existing `AugmentResult` data should be consumed where available, with
  remaining filesystem/layout inference isolated in a planner;
- A1 established pure planners/value objects and focused unit tests before task
  registration changes.

If the investigation needs to be refreshed later, update
`archive/phase-a/investigation-results.md` using the same matrix/report structure
rather than reintroducing a separate pre-implementation checklist here.

### `P1-AP-02A1`: Named Output Model And Planner Skeleton

Implementation status: complete.

This slice added unit-testable planners/value objects before task wiring:

- named output identity and task-name derivation;
- task-class/package naming for the new hierarchy, originally under the
  temporary `io.quarkus.gradle.tasks.application.QuarkusApplication*` package
  and now moved to `io.quarkus.gradle.application.*`;
- typed output definitions for fast jar, legacy jar, mutable jar, uber jar,
  native executable, native sources, and image-capable outputs;
- output layout planner;
- dependency fragment layout planner;
- package-layout inference planner for filesystem/layout rules not yet exposed
  by Quarkus result metadata;
- forced-property planner for image/native/package shape;
- image target and duplicate effective-image-reference planner;
- AOT-enhanced image planner with `aotFile`, `producedBy(...)`, and
  `aotFileFrom(...)` modeling;
- deployment descriptor and `DeployTo` task-name planner;
- compatibility materialization planner.

This slice deliberately avoided changing old task behavior and did not require
the full task hierarchy to be registered. It answered: given a declared named
output, what task names, structured forced properties, output roots, dependency
fragments, and optional compatibility materialization plan should exist?

It did not delete or rewrite the old hierarchy.

### `P1-AP-02A2`: Named Output DSL And Task Skeleton

Implementation status: complete for the agreed skeleton scope. Gradle JVM Test
Suite integration is intentionally deferred to the native/test execution phase.

This slice originally added the new task-type hierarchy next to the old one
without changing existing task behavior. That hierarchy has since moved into
the standalone `io.quarkus.application` plugin:

- `quarkusApplication.builds` named-output collection on the extension;
- typed output registrations for the first implemented output types, while the
  A1 model already accounts for all planned output types;
- common named-output build execution base, using
  `QuarkusApplicationBuildTask` in
  `io.quarkus.gradle.application.tasks`;
- structured inputs for package/native/image intent;
- shape-owned output root properties;
- dependency-fragment task shape;
- materialization task shape.

This slice originally added separate registration/configuration paths for
legacy and new tasks. The final module split keeps that boundary as
`QuarkusPlugin` for compatibility tasks and `QuarkusApplicationPlugin` for the
named-output model.

### `P1-AP-02A3`: Opt-In Legacy Diagnostics

Implementation status: complete.

This slice added the Quarkus 4.0 diagnostics model:

- nested `quarkus.diagnostics` extension object;
- `legacyTaskUsage` level with `OFF`, `WARN`, and `FAIL`;
- Gradle project-property convention;
- report file generation at
  `build/reports/quarkus/legacy-task-usage.txt`;
- targeted diagnostics for legacy application task usage, including
  `quarkusBuild`, `imageBuild`, `imagePush`, `buildNative`, `testNative`,
  `deploy`, and `buildAotEnhancedImage`.

Keep diagnostics `OFF` by default in Quarkus 4.0. Plan to enable `WARN` by
default in Quarkus 4.1 when old Java task types can be formally deprecated for
removal. `quarkusBuild` diagnostics identify legacy model usage; they do not
deprecate the `quarkusBuild` task name.

### `P1-AP-02B`: Image Build/Push Named Output Tasks

Implement image-specific execution tasks in the new hierarchy with stable inputs
for image build/push intent.

Task names, task types, dependency edges, convenience-task decisions, and
cacheability stance for the named application model are tracked in
[P1-AP-02B Task Topology](phase-b-task-topology.md). Treat that document as
the Phase B task-topology and B0/B1/B2 implementation-slice source of truth.
Its later-phase topology sections are reference material unless explicitly
pulled into a later implementation plan.

Introduce derived image build/push task names, such as
`quarkusAppImageBuild` and `quarkusAppImagePush`, that depend on named outputs
instead of mutating `ForcedPropertieBuildService` during execution.

Image configuration lives on the registered output descriptor. The image DSL
defines the target repository/tag and builder enum; the selected task supplies
build versus push intent. Add duplicate effective-image-reference detection for
selected image-producing tasks before any image-producing action runs.

Keep old `imageBuild`, `imagePush`, and `quarkusBuild` unchanged initially if
that gives the safest migration path.

Removing `ForcedPropertieBuildService` from the old hierarchy is optional and
should not block introducing the new Gradle-friendly tasks.

Introduce a small execution boundary before wiring expensive Quarkus work into
the new tasks. The Gradle tasks should declare managed inputs/outputs, resolve
providers at execution time, create immutable request objects, and call a
Gradle-plugin-local operations interface. A production implementation uses the
existing app-model generation, effective-config code, and worker implementations
under `io.quarkus.gradle.tasks.worker`. Unit tests can use stubs that capture
requests and write cheap marker/result files. A smaller worker-oriented test set
then verifies the production implementation maps requests to the real worker
invocations.

Initial operations shape:

```java
interface BuildOperations {
    void build(BuildRequest request);
    BuiltContainerImage buildImage(ImageRequest request);
    BuiltContainerImage pushImage(ImageRequest request);
}
```

Keep the interface scoped to Phase B execution needs. Deploy can use a separate
operations interface in its own phase. AOT-enhanced image operations should be
added when that later phase is pulled forward, not to the initial Phase B
operations interface.

The Phase B operations package should live under
`io.quarkus.gradle.application.internal.execution`. Request objects should be
immutable Java records created by task actions after provider resolution. The
production implementation owns Quarkus worker/bootstrap invocation and returns
normalized results such as `BuiltContainerImage`; task actions own Gradle
receipt-file writing through `BuiltContainerImageResultCodec`. This keeps
external side effects behind the operations seam while keeping Gradle output
serialization deterministic and easy to unit test.

Minimum Phase B request contents:

- `BuildRequest`: build name/type, output root, app-model
  file/provider, application classpath inputs, source/resource directories,
  effective-config plan, build-system properties, operation forced properties,
  fork/isolation settings, and named package output layout.
- `ImageRequest`: the build request or build-request
  reference, operation kind (`BUILD` or `PUSH`), `ContainerImageTarget`,
  builder enum, common and image-scoped build properties, selected receipt file,
  and optional known builder side-file locations such as Jib digest/image-id
  files.

Operations should throw Gradle-facing exceptions with the registered build name,
task operation, and relevant descriptor values in the message. They should not
write Gradle outputs directly; task actions write receipts from returned
normalized results.

Image tasks should not model the container image itself as a Gradle file output.
Container images are external state. Use `@Nested` Gradle beans only for
declared inputs such as the intended image target:

```java
public abstract class ContainerImageTarget {
    @Input
    public abstract Property<String> getRepository();

    @Input
    public abstract Property<String> getTag();
}
```

The produced image result must cross the Gradle task boundary through a file,
not an in-memory nested bean. Image build/push tasks should write a small
Gradle-owned result/receipt file as an `@OutputFile`. The receipt is useful for
task wiring, diagnostics, and downstream consumers, but it is not proof that
Gradle owns the external image artifact. Dependent tasks, such as deploy tasks,
should consume the result file as an `@InputFile` and parse the normalized
receipt content.

Phase B therefore needs a small image-result support model. The receipt schema
and metadata evidence are defined in
[P1-AP-02B AugmentResult Image Metadata Investigation](phase-b-augment-result-image-metadata.md);
this section records the design-level ownership boundary:

- `ContainerImageTarget`: a `@Nested` task input bean for the intended image
  repository/tag and any future target identity fields;
- `BuiltContainerImage`: an immutable normalized result model used in Java code
  and serialized into the receipt file;
- `BuiltContainerImageResultCodec`: serializer/deserializer for the receipt
  format. Keep the format stable, explicit, reproducible, and tolerant of
  missing optional fields such as digest. The writer should use
  `io.quarkus.bootstrap.util.PropertyUtils.store(...)` rather than
  `java.util.Properties.store(...)`, so generated receipts have sorted keys and
  no timestamp comment;
- builder-specific result extractors/generators that convert Quarkus execution
  results into `BuiltContainerImage`, for example:
  - Jib extractor: combines `ArtifactResult` metadata with Jib digest/image-id
    files when present;
  - Docker/Podman extractor: reads `ArtifactResult` metadata from the common
    processor and includes working-directory/output-directory when present;
  - Buildpack extractor: reads the image reference from `ArtifactResult`
    metadata;
  - OpenShift extractor: emits only known requested/target data unless Quarkus
    exposes richer result metadata;
  - AOT-enhanced extractor: later-phase support; initially use the modeled
    enhanced image reference and any structured custom-build result if the
    production operation exposes one later.

Keep these extractors separate from Gradle task actions. Task actions should
assemble requests, invoke operations, and write/read receipts through the codec.
This keeps the builder-specific metadata quirks unit-testable without running
container tooling.

Extractor matching rules for Phase B:

- accept `ArtifactResult` entries with type `jar-container` or
  `native-container`;
- prefer the result whose `container-image` metadata equals the modeled target
  reference when multiple image results exist;
- for Jib, read digest and image-id side files only when the configured file
  paths are known and the files exist;
- for Docker/Podman common and Buildpack, use only metadata exposed through
  `ArtifactResult`;
- for OpenShift, fall back to the modeled target fields when metadata is empty;
- never synthesize `image.digest`.

Phase B receipt locations:

- `build/quarkus-build-results/<name>/image/image-build-result.properties`;
- `build/quarkus-build-results/<name>/image/image-push-result.properties`.

Phase D AOT-enhanced receipt locations follow their own `aot-image/`
subdirectory:

- `build/quarkus-build-results/<name>/aot-image/aot-image-build-result.properties`;
- `build/quarkus-build-results/<name>/aot-image/aot-image-push-result.properties`.

Current `AugmentResult` image metadata is documented in
[P1-AP-02B AugmentResult Image Metadata Investigation](phase-b-augment-result-image-metadata.md).
The important findings are:

- `AugmentResult` has no dedicated typed container-image result API;
- normal image details are available only through `ArtifactResult` metadata
  produced by container-image extensions;
- Docker/Podman common, Jib, and Buildpack expose image references for normal
  jar/native image builds, but OpenShift currently produces empty image
  metadata;
- Jib obtains an image digest and image ID, but currently writes them to
  configured files rather than adding them to `ArtifactResult` metadata;
- AOT-enhanced image custom builds currently expose only an enhanced image
  reference build item, and the legacy Gradle worker does not consume a
  structured result.

Therefore named image tasks treat image digests/SHA values as optional
enrichment: read them when Quarkus makes them available, especially from Jib
digest files, but make the receipt useful without them. Image build, image
push, AOT image build, AOT image push, and deploy tasks should remain
non-cacheable and should not try to infer correctness from Gradle up-to-date
checks, because their meaningful artifact is external container/deployment
state. The deterministic receipt is for downstream wiring and diagnostics, not
proof that the external image or deployment state can be restored from Gradle's
build cache.

### `P1-AP-02C`: Native Named Output And Native Tests

Move `buildNative` and `testNative` away from graph-selected mutation of shared
extension state.

Introduce stable native named-output inputs in the new hierarchy. Named native
outputs should own matching native test suites/tasks, for example
`quarkusNative1NativeTest`, configured from the named output rather than the
global legacy `extraNativeTest` setting. Quarkus registers these built-in
suites; build authors customize them with `testing.suites.named(...)` instead
of trying to `register(...)` the same names.

Keep existing `extraNativeTest` and `testNative` behavior unchanged as legacy
configuration. The new native test model should use Gradle JVM Test Suite
infrastructure for the test-suite/source-set/test-task shape, while Quarkus
adds the native-output relationship and Quarkus-specific test wiring. Do not
wire new native-test suites into `check` by default.

### `P1-AP-02D`: Additional Named Package Outputs

Add opt-in named outputs for fast jar, legacy jar, uber jar, mutable jar, and
native sources.

Each named output gets shape-specific output directories and does not write
`build/quarkus-app/`, root runner files, or `build/quarkus-artifact.properties`.
Build metadata such as `quarkus-artifact.properties` is kept under
`build/quarkus-build-results/<registered-name>/`.

Keep old `quarkusBuild` as the compatibility/default package task using the
current configured package type and legacy output location.

Use shared dependency fragments where the dependency layout and filtering
semantics match. Do not duplicate `dep/` per shape unless a shape needs a
different dependency layout.

### `P1-AP-02E`: Cleanup Simplification

Once explicit named-output tasks own isolated outputs, avoid adding
cleanup/deletion code to those new tasks except for their own output roots.
Cleanup/deletion of legacy shared outputs remains a compatibility/default-path
concern.

Do not remove or rewrite legacy cleanup until tests prove legacy
`quarkusBuild` behavior is preserved and there is an explicit decision to touch
the old hierarchy.

## Settled Direction And Remaining Follow-Ups

### Public Named-Output DSL

- Support both typed factory methods such as `fastJar("app")` and class-based
  registration where Gradle's managed model makes that practical. Typed factory
  methods should be the ergonomic path; class-based registration should exist
  for users who prefer Gradle's explicit type model.
- Include all known output types in the planning model from the start: fast jar,
  legacy jar, mutable jar, uber jar, native executable, native sources, and
  image-capable outputs. A1 already models all types so important output-layout
  and task-name cases are not missed.

### Name Validation And Task-Name Derivation

- Registered output names are non-empty Gradle names and must be globally unique
  within `quarkusApplication.builds`.
- Derive public task names by converting the registered name to a stable
  Gradle-style name segment and appending an action suffix, for example
  `app` -> `quarkusAppBuild`, `native1` -> `quarkusNative1Build`, and
  `native-main` -> `quarkusNativeMainBuild`.
- Treat normalized-name collisions as errors, for example `native-main` and
  `nativeMain` deriving the same task-name segment.
- Treat derived task-name collisions with existing legacy tasks, Gradle
  lifecycle tasks, or other derived Quarkus tasks as errors before any
  output-producing action runs.
- Keep the exact allowed character set conservative at first. Prefer rejecting
  names that cannot be converted predictably over inventing surprising escaping
  rules.

### `quarkusBuild` Configuration Propagation

- Propagate only relevant managed properties/conventions from the extension and
  from compatibility `quarkusBuild` configuration.
- Relevant means the property applies to the selected named output type and can
  be represented as a structured Gradle input. Examples include configured
  output names/directories, common Quarkus build properties, manifest settings
  for JVM package outputs, native arguments for native outputs, and fork options
  where the new task explicitly supports them.
- Do not propagate arbitrary task actions, `dependsOn`, `finalizedBy`,
  `mustRunAfter`, `doFirst`/`doLast`, or other imperative task mutation from
  `quarkusBuild` to named-output tasks.
- Keep a focused test requirement for the exact managed-property mapping,
  including both included and excluded propagation cases.

### Task Types, Dependency Fragments, And Run/Dev/Test Shape

- Use stable public task types with properly modeled outputs, for example
  `@OutputFile`, `@OutputDirectory`, `RegularFileProperty`, and
  `DirectoryProperty`, so other Gradle tasks can wire build dependencies to new
  Quarkus outputs naturally.
- Keep `build/quarkus-build/dep` as the reusable dependency-fragment location
  for now. Do not move dependency fragments per named output unless a shape needs
  a different dependency layout or filtering contract.
- Leave `quarkusRun` on the legacy path for now. Longer term, add new
  `QuarkusApplication*` launch/run tasks alongside the legacy run/dev/test tasks
  rather than forcing the existing `quarkusRun` into the first named-output
  build split.

### Image DSL

```kotlin
quarkus {
    builds {
        fastJar("app") {
            image {
                repository = "quay.io/acme/my-app"
                tag = "1.0" // defaults to project.version
                builder = JIB
                quarkusBuildProperties.put("quarkus.docker.buildx.platform", "linux/amd64,linux/arm64")
            }
        }
    }
}
```

- `image {}` is an opt-in block on the registered output that owns image
  publication.
- The image block defines target image identity and builder configuration only.
  It does not have `build = true` or `push = true`; derived tasks provide that
  intent.
- Add a `MapProperty<String, String>` for image-scoped Quarkus build
  properties. Use it for builder-specific and image-only properties, including
  multi-platform normal image builds. These properties are passed through to the
  Quarkus build machinery for image-producing tasks.
- Image tags default to `Project.getVersion().toString()`, with explicit
  handling for Gradle's default `unspecified` version.

### Image Builder Enum

- Start with enum values matching known Quarkus container-image builders:
  `JIB`, `DOCKER`, `PODMAN`, `OPENSHIFT`, and `BUILDPACK`.
- Map enum values to Quarkus builder names in one place, for example `jib`,
  `docker`, `podman`, `openshift`, and `buildpack`.
- If Quarkus still supports an S2I-specific builder distinct from OpenShift in
  the relevant version, add `S2I`; otherwise do not carry a stale enum value.

### Duplicate Image References

- Fail when two unrelated selected image-producing tasks resolve to the same
  effective image reference.
- Allow the same reference inside one declared owner/flow when the relationship
  is explicit and ordered, such as a base image build followed by an AOT-enhanced
  image push for the same registered output.
- Do not add a broad overwrite/alias model in the first slice. If users need
  intentional competing writers later, add an explicit opt-in with clear task
  ordering and diagnostics.

### AOT-Enhanced Image DSL

- Support both `producedBy(...)` and `aotFileFrom(producer, fileProvider)`.
- `aotFile` remains the modeled `RegularFileProperty` input.
- `producedBy(...)` is dependency wiring for cases where the file property is
  set separately.
- `aotFileFrom(...)` is convenience wiring for producers that do not expose a
  typed task output property.
- Quarkus should not require a specific `quarkusIntTest` task. The AOT producer
  can be any task or test-suite target that exposes or can be associated with an
  AOT file.
- If Quarkus contributes test-suite integration that can produce an AOT file,
  expose that file as a typed `RegularFileProperty` on the Quarkus-aware test
  task/target so users can wire
  `aotFile.set(myIntTest.flatMap { it.aotFile })` where the type supports it.
- AOT-enhanced image tasks are current-platform convenience tasks. Do not model
  automatic multi-platform AOT image assembly in the Quarkus Gradle plugin.
- Default AOT image reference is the normal image reference plus
  `quarkus.container-image.aot-image-suffix`, currently `-aot`.
- Allow AOT image repository, tag, or full reference overrides, with validation
  for contradictory settings.
- Provide `quarkus<BuildName>AotEnhancedImageBuild` and
  `quarkus<BuildName>AotEnhancedImagePush`.
- Declaring `aotEnhancedImage {}` registers a deterministic Quarkus-owned
  AOT-training JVM Test Suite for the named output, such as
  `quarkusAppAotTraining`.
- Users customize Quarkus-created AOT suites with
  `testing.suites.named<JvmTestSuite>("quarkusAppAotTraining")`; they register
  additional user-owned suites separately.
- The Quarkus-owned AOT suite configures test execution with the required AOT
  Quarkus build properties and exposes the produced AOT file as a typed output.
  Existing integration-test suites may still be reused when users opt in.

### Deployment DSL

- Deployments are nested named descriptors under the registered output.
- Required shape: `deployments { kubernetes("dev") { ... } }`,
  `deployments { openshift("prod") { ... } }`,
  `deployments { knative("prod") { ... } }`,
  `deployments { kind("local") { ... } }`, and
  `deployments { minikube("local") { ... } }`, with
  `quarkus<BuildName>DeployTo<DeploymentName>` derived task names.
- Do not expose a generic public `register(...)` deployment DSL in the first
  slice. The deployment target is selected by the factory and is not
  configurable inside the deployment block.
- Do not add `deploy { ... }` single-deployment sugar initially. The sugar would
  become confusing once a second deployment is added.
- Descriptor fields should cover the deploy target/factory, selected image
  source, deploy-command properties, and the subset of common managed Quarkus
  build properties relevant to deployment.
- Do not put `push` on deployment descriptors or deploy tasks. Pushing is
  modeled by image tasks such as `quarkusAppImagePush` and
  `quarkusAppAotEnhancedImagePush`.
- Deployment image sources should cover at least: an already existing image
  reference, the normal image push task, and the AOT-enhanced image push task.
- Do not add a local-image or build-without-push image source in the first
  deployment slice. Add a separate explicit source later if local-cluster
  workflows need it.

### Native-Test Suite DSL

- Quarkus registers built-in native-test suites with deterministic names such as
  `quarkusNative1NativeTest`.
- Declaring `aotEnhancedImage {}` registers a deterministic Quarkus-owned
  AOT-training suite such as `quarkusAppAotTraining`.
- Users customize Quarkus-created suites with
  `testing.suites.named<JvmTestSuite>("quarkusNative1NativeTest")` or
  `testing.suites.named<JvmTestSuite>("quarkusAppAotTraining")`.
- Users register additional suites themselves and attach them with
  `forQuarkusBuild("native1")`.
- Quarkus should add only Quarkus-specific wiring: selected named output,
  native runner or image metadata, app-model/test system properties,
  `BeforeTestAction`, Dev Services/compose inputs, and
  `@QuarkusIntegrationTest` conventions.

### Legacy Diagnostics

- `OFF`: no legacy-task report, warning, or failure.
- `WARN`: warn and write a report when legacy usage is used, whether directly
  requested or reached transitively.
- `FAIL`: fail when legacy usage is used, whether directly requested or reached
  transitively, with a report entry if possible.
- "Used" means executed or explicitly requested, including transitive task
  graph usage. Merely configuring old APIs is not treated as usage.
- Legacy application task usage includes `quarkusBuild`, `imageBuild`,
  `imagePush`, `buildNative`, `testNative`, `deploy`, and
  `buildAotEnhancedImage`.
- Legacy launch-session tasks include `quarkusRun`, `quarkusDev`,
  `quarkusRemoteDev`, and `quarkusTest`; these remain compatibility tasks, but
  their diagnostics may be staged separately from package/image/native/deploy
  usage.
- `quarkusBuild` remains the well-known compatibility build task; diagnostics
  for it are about legacy model usage, not name deprecation.

### Cleanup

- New named-output tasks should not need legacy cleanup code because each output
  owns isolated directories.
- Cleanup remains only for the legacy compatibility path and any explicit
  compatibility materialization task that writes to shared legacy locations.

### Follow-Up Documentation Task

- Defer full user-facing documentation until the new plugin's supported
  compatibility guarantees, migration scope, and first public release shape are
  settled.
- Add a dedicated docs follow-up covering new build-file examples, migration of
  existing build files, legacy/default `quarkusBuild` behavior, explicit named
  outputs, image and deploy use cases, native-test suites, AOT-enhanced image
  flow, diagnostics, and compatibility timelines.

### Cross-Phase Deferred Follow-Ups

These are higher-level cleanup or diagnostic tasks, not blockers for Phase B:

- Refine `configInputs` semantics after production worker wiring if needed.
  Environment-variable capture must not guess property-style names from
  environment-variable names. Users should declare exact environment-variable
  names/prefixes, exact property-style names/prefixes, or explicit Quarkus build
  properties. Any broader ambient capture remains an opt-in compatibility mode
  that disables cacheability as designed.
- Add gated integration coverage for real container image build/push behavior,
  including Docker/Podman/registry paths and AOT-enhanced image build/push
  paths, outside the default unit and TestKit suite. This needs opt-in test
  gating, a small fixture, deterministic cleanup, and assertions against image
  receipts or returned image identifiers rather than default-suite Docker work.
- Investigate richer AOT image metadata from core/container-image extensions if
  digest or image ID support becomes available for AOT-enhanced images.
- Implement Quarkus-owned AOT-training JVM test-suite wiring after the generic
  AOT-file producer contract is stable. This needs deterministic suite names
  such as `quarkus<Name>AotTraining`, required AOT Quarkus build properties,
  typed AOT-file output/provider wiring, and customization through
  `testing.suites.named(...)`.
- Add a command-line driven named-deployment convenience variant only if users
  need it after the build-script DSL exists.
- Consider a separate local-cluster image source such as `NORMAL_IMAGE_BUILD` if
  `kind` or `minikube` workflows need a build-without-push convenience that
  cannot be expressed well with `EXISTING_IMAGE`.
- Implement named native-test suites. This needs deterministic Quarkus-owned
  JVM test-suite registration, `forQuarkusBuild(...)` wiring for user-owned
  suites, native runner/image metadata inputs, and a clear decision on
  lifecycle wiring such as `check`.
- Implement named launch/dev/run/remote-dev/continuous-test behavior. This
  needs public task types, launch-mode-specific operation mapping, app-model
  and source-set inputs, and a design that leaves room for Gradle continuous
  build integration without reusing the legacy task internals blindly.
- Simplify existing `build-system.properties` writing to use
  `PropertyUtils.store(...)` where appropriate so generated Java-properties
  files stay deterministic without timestamp comments.
- Consider replacing optional `QuarkusShowEffectiveConfig` direct
  `Properties.store(...)` usage with deterministic diagnostic output. This is
  separate from the named-output task model because it affects an existing
  diagnostic task.
- Gradle-style package archive naming for the new application plugin: replace
  the current registered-build-name default for `quarkus.package.output-name`
  with a legacy-compatible archive base name, normally derived from the Gradle
  project name and version. The detailed proposed design lives in
  `package-output-naming-design.md`.
- Fast-jar launcher name configurability: `quarkus-run.jar` is currently a
  Quarkus core fast-jar layout constant, used by AOT/AppCDS and documented in
  many places. Treat renaming it as a separate core packaging capability, not
  as Gradle-plugin post-processing. If implemented, it must be surfaced through
  Quarkus packaging configuration and consumed consistently by fast-jar layout,
  AOT/AppCDS, container image, and deployment code.
- Replace package-layout inference with a richer Quarkus build-tool output
  manifest if core/deployment eventually exposes one.
- Replace native-sources output-directory inference with a richer Quarkus
  build-tool output manifest if core/deployment eventually exposes one.
- Revisit annotated package child output properties if the broad
  output-directory model becomes too coarse for Gradle incremental behavior.
- Revisit native executable cacheability or finer up-to-date behavior only
  after toolchain, OS, container runtime, and ambient native-image inputs are
  explicitly modeled.
- Add gated real integration coverage for named native-sources and native
  executable outputs. Native-sources should be the first target because it can
  avoid a local native-image requirement; native executable coverage should
  follow the existing native test gating conventions.
- Consider extracting reusable logic from legacy app/dependency fragment tasks
  only after named JVM package output execution proves which behavior is still
  needed.
- Consider moving conditional-dependency discovery into an explicit task that
  writes a small resolved-coordinate result, instead of doing the discovery as
  part of classpath input snapshotting for application-model/codegen/build
  tasks. This is an optimization/further isolation follow-up, not a correctness
  prerequisite: the current requirement is that conditional dependency
  resolution remains lazy and is triggered only by tasks that consume the final
  Quarkus runtime/deployment classpath.

## Success Gates

- Existing `quarkusBuild` behavior and output layout remain compatible.
- Running image build/push does not mutate hidden build-service state consumed
  by a cacheable build task.
- Native and image build intent are modeled as stable task inputs.
- At least one multi-output invocation can run without output clobbering.
- New task types expose stable typed Gradle output properties so downstream
  tasks can wire dependencies to Quarkus outputs through providers.
- A named native output can run its matching native test task without relying
  on global legacy `testNative` configuration.
- Image build/push intent is selected by derived task name, not by `build` or
  `push` booleans in the named-output image DSL.
- Two selected image-producing tasks cannot silently publish the same effective
  image reference.
- AOT-enhanced image tasks consume a modeled `RegularFileProperty` AOT file and
  optional producer task wiring rather than scanning a global metadata file.
- AOT-enhanced image tasks are scoped to the current platform. Multi-platform
  AOT-enhanced image workflows remain external/manual.
- AOT-enhanced image references default to the normal image reference plus the
  existing AOT image suffix, currently `-aot`. Repository/tag/full-reference
  overrides remain a modeled future capability until core/container-image
  exposes a proper target-selection API for custom AOT image builds.
- Output-specific deploy tasks use nested named deployment descriptors and
  derive task names such as `quarkusAppDeployToDev`.
- No single-deployment `quarkusAppDeploy` sugar is introduced in the first
  implementation slice.
- Output-specific deploy tasks select an image source rather than carrying
  runtime push behavior. Supported sources include an existing image reference,
  the normal image push task, and the AOT-enhanced image push task.
- Legacy-task diagnostics can be set to `OFF`, `WARN`, or `FAIL` through the
  extension and a Gradle project property.
- Legacy-task diagnostics treat both direct and transitive execution as usage,
  while mere task registration is not usage.
- Cache/up-to-date tests prove that changing native/package-shape inputs
  invalidates only the relevant named-output task. Image-related tasks remain
  non-cacheable side-effect tasks; tests should validate modeled inputs,
  deterministic receipts, and dependency wiring rather than pretending Gradle
  can prove external image state is current.
- Configuration-cache reuse works for normal and named-output task paths.
- The test suite follows the planned test pyramid: pure unit tests for planners
  and value objects, cheap Gradle `ProjectBuilder` tests for registration/model
  wiring, TestKit only for real Gradle execution contracts, and heavy
  integration tests only for true end-to-end Quarkus behavior.
- User-facing documentation follow-up is tracked for new build files, migration,
  use cases, diagnostics, and compatibility behavior.
