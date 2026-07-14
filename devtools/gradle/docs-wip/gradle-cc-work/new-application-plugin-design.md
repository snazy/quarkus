# New Gradle-Native Application Plugin Design

Status: current design; initial implementation moved into `gradle-app-plugin`
Last reviewed: 2026-07-08

## Decision Summary

Introduce a new, separate Quarkus Gradle application plugin for the
Gradle-native task model instead of continuing to grow the current
compatibility plugin.

Current direction:

- keep the existing `io.quarkus` plugin as the compatibility plugin;
- create a new application plugin with explicit named outputs, no legacy task
  aliases, and hard Gradle compatibility gates;
- allow the new plugin and legacy plugin to be applied to the same project only
  as an intentional migration mode, with a clear warning from the new plugin;
- use the moved named application task model as the starting implementation
  surface;
- do not depend on the current `gradle-model` project traversal paths unless
  they are first made configuration-cache and isolated-project compatible.

This is not a recommendation to duplicate the old plugin under a new ID. The
new plugin only makes sense if it draws a hard boundary around the problematic
legacy behavior described in `phase-1-application-plugin.md`.

## Rationale

The existing application plugin has several structural compatibility blockers:

- `P1-AP-01`: live dependency-project traversal and cross-project task wiring;
- `P1-AP-02`: graph-selected image/native/package intent and late hidden
  build-service inputs;
- `P1-AP-05`: broad worker environment forwarding and opaque fork actions;
- inherited `gradle-model` blockers such as project metadata traversal and
  provider-triggered dependency metadata mutation.

The current plugin also has historical task names and behaviors that users rely
on:

- `quarkusBuild`;
- `buildNative` and `testNative`;
- `imageBuild` and `imagePush`;
- `deploy`;
- `quarkusDev`, `quarkusRemoteDev`, and `quarkusTest`;
- graph-selected package/image/native behavior.

Making all of that fully configuration-cache and isolated-project compatible
while preserving behavior is possible only by carefully unwinding many old
contracts. A new plugin provides a clean compatibility boundary: legacy behavior
continues in `io.quarkus`; Gradle-native behavior lives in the new plugin.

## Goals

- Provide a Gradle-native Quarkus application plugin that works with Gradle
  configuration cache and isolated projects from the first usable release.
- Make application outputs explicit and named.
- Avoid task-graph-selected behavior and hidden mutable state.
- Expose stable public task types and typed Gradle properties for downstream
  wiring.
- Preserve the new named-output design work already completed for JVM package,
  native, image, AOT image, and deployment tasks.
- Keep the implementation small enough that every feature can be tested with
  focused TestKit coverage.

## Non-Goals

- Do not provide every legacy `io.quarkus` task in the first version.
- Do not make the old `io.quarkus` plugin fully Gradle-native as part of this
  plugin's first implementation.
- Do not auto-apply Jandex plugins to dependency projects.
- Do not support implicit task-graph-selected package/image/native behavior.
- Do not mark side-effecting image/deploy tasks cacheable.
- Do not reuse shared `gradle-model` behavior that reads other projects'
  mutable model.
- Do not implement Gradle-native continuous testing in the first version.
  Reserved task names should fail explicitly until behavior exists.

## Hard Gates

The new plugin must meet these gates before it is considered usable. Treat
them as release blockers, not aspirational follow-ups. A feature that cannot
meet these gates should stay out of the new plugin or be marked unsupported
until it can.

Default test rule: every default-suite TestKit invocation for the new plugin
must run with:

- `--configuration-cache`;
- `-Dorg.gradle.unsafe.isolated-projects=true`.

Use `--build-cache` by default for build/cache-sensitive task paths, especially
cacheable package/codegen/model tasks. Do not require `--build-cache` for
side-effecting image push, deployment, or other non-cacheable tasks where the
test is intentionally about execution behavior rather than cache reuse.

### Gradle Configuration Cache

- Every supported task path must run with `--configuration-cache`.
- Repeated runs must reuse the configuration cache in focused TestKit tests.
- No task action may call `Task.getProject()`. Gradle documents this as
  unsupported when configuration caching is enabled.
- No task action may call task APIs that expose mutable build model state, such
  as task dependencies, task extensions, conventions, task containers, or
  project services.
- No task action may capture live Gradle model objects such as `Project`,
  `Configuration`, `SourceSet`, `Task`, extension instances, or task
  containers.
- No Gradle provider, `map`, `flatMap`, convention, or lazy callback may capture
  forbidden Gradle model objects or perform cross-project lookup as a side
  effect. Provider callbacks must transform declared provider values, not
  perform hidden model reads.
- No provider should be forced with `.get()` during configuration unless the
  value is intentionally finalized and safe to realize at that point.
- Worker parameters must be serializable, explicit, and provider-backed.
- Environment variables, system properties, and Gradle properties must be
  modeled as declared inputs or deliberately excluded by making the task
  non-cacheable/incompatible.
- Arbitrary `Action<JavaForkOptions>` hooks are not acceptable on cacheable
  tasks unless their effects are represented as stable inputs. Prefer typed
  fork-option properties.

### Gradle Isolated Projects

- Multi-project TestKit tests must run with
  `-Dorg.gradle.unsafe.isolated-projects=true`.
- The plugin must not traverse dependency projects.
- The plugin must not call `rootProject`, `subprojects`, `allprojects`, or
  `project(":x")` for dependency introspection.
- The plugin must not read another project's extensions, source sets,
  configurations, tasks, layout, group/version, or state.
- The plugin must not register `afterEvaluate` callbacks on another project.
- Cross-project relationships must be expressed through Gradle dependencies,
  variants, artifacts, capabilities, or explicit user wiring.
- Included-build behavior must be covered by at least one TestKit smoke test
  before claiming broad support.

### Task Modeling

- All cacheable tasks must declare complete inputs, outputs, local state, and
  worker parameters.
- Side-effecting tasks, especially image push and deploy tasks, must not be
  marked cacheable.
- Side-effecting tasks should still write deterministic receipts for downstream
  inspection.
- Task dependencies must be inferred through provider-backed properties or
  explicitly registered task relationships within the same project.
- Public task output properties must be stable enough for downstream build
  authors to wire custom tasks.
- Internal helper methods and properties on Gradle DSL-facing types must not be
  exposed with Java `public` visibility. Public members on DSL/task types are
  API surface and must be intentionally designed, documented, and tested.
- Task names must not collide with legacy `io.quarkus` task names, even when
  both plugins are applied during migration.

### Operation Boundaries

Expensive operations must be behind interfaces that can be replaced in tests:

- Quarkus augmentation;
- Quarkus application build execution;
- native-image execution;
- image build/push;
- deployment;
- model generation where practical.

Pure unit tests and ProjectBuilder tests must be able to use cheap stubs,
mocks, or recording implementations. Expensive real execution belongs in
focused TestKit or gated integration tests.

Test-supporting implementations must not live in the production source tree.
Use test fixtures or test sources.

### Legacy Isolation

- The new plugin must not register legacy tasks such as `quarkusBuild`,
  `buildNative`, `imageBuild`, `imagePush`, or `deploy`.
- The new plugin must not use `ForcedPropertieBuildService`.
- The new plugin must not depend on task-finalizer tricks to select package,
  image, native, AOT, or deploy intent.
- The new plugin must not run the old dependency-project task walk.
- If the legacy plugin is also applied to the same project, the new plugin
  should warn that coexistence is a migration mode and that legacy tasks do not
  inherit the new plugin's compatibility guarantees.

### Test Coverage

Required default-suite tests:

- prefer this order unless the behavior cannot be tested cheaply:
  1. pure unit tests;
  2. Gradle ProjectBuilder tests;
  3. Gradle TestKit tests;
  4. expensive integration tests;
- single-project TestKit configuration-cache reuse;
- multi-project TestKit isolated-projects smoke test;
- build-cache reuse/restore for cacheable task paths;
- named JVM package outputs built by a real tiny Quarkus app;
- downstream custom task consuming package result files through
  `TaskProvider.flatMap(...)`;
- named native-sources task coverage, at least stubbed initially;
- image/deploy task receipt wiring without requiring Docker/Podman/Kubernetes;
- failure tests for ambiguous names, conflicting image references, and
  unsupported legacy behavior.

Gated tests:

- real native executable;
- real Docker/Podman image build;
- registry push;
- local Kubernetes/OpenShift/Knative deployment.

## Advantages

- Clean Gradle compatibility contract without needing to preserve legacy task
  graph behavior.
- Smaller API surface for configuration-cache and isolated-project auditing.
- Clear migration story: users opt into explicit named outputs when they want
  Gradle-native behavior.
- Reduced risk of breaking existing `io.quarkus` users.
- New docs can teach the explicit model without constantly explaining legacy
  exceptions.
- Hard gates can be enforced by tests from the beginning instead of added after
  behavior has ossified.

## Downsides

- Two application plugins create user-facing complexity.
- Documentation, examples, and support answers must distinguish compatibility
  plugin behavior from Gradle-native plugin behavior.
- Some users will expect the new plugin to support all old tasks immediately.
- There may be duplicated setup code until shared Gradle-compatible
  infrastructure is extracted.
- Plugin IDs, extension names, task names, and migration recommendations need
  careful release-note treatment.
- Depending on an unfixed `gradle-model` layer would make the new plugin fail
  its own isolated-project gate, so this work may force deeper model changes.
- Coexistence with `io.quarkus` eases migration, but it can create confusing
  builds where legacy and Gradle-native tasks have different compatibility
  guarantees in the same project.

## Plugin Identity

Plugin ID:

- `io.quarkus.application`

Rationale:

- explicit enough to distinguish it from `io.quarkus`;
- matches the "application plugin" concept already used internally;
- avoids the overly abbreviated `io.quarkus.app`;
- leaves room for extension-oriented plugins such as
  `io.quarkus.extension.deployment`.

Gradle module:

- `devtools/gradle/gradle-application-native-plugin` is **not** recommended;
  "native" is overloaded with native executable output.
- `devtools/gradle/gradle-application-v2-plugin` is clear internally but poor
  as a long-term artifact name.
- `devtools/gradle/gradle-application-plugin2` only if the
  repository strongly prefers versioned internal names.
- Acceptable and shorter: `devtools/gradle/gradle-app-plugin`.
- Longer but more explicit: `devtools/gradle/gradle-quarkus-application-plugin`.
- Gradle module: `gradle-app-plugin`;
- Maven artifact: `io.quarkus.application.gradle.plugin`;
- implementation class:
  `io.quarkus.gradle.application.QuarkusApplicationPlugin`.

The new plugin should support coexistence with `io.quarkus` as a migration
mode, but only through clearly separated extension names and task names. It
should warn when both plugins are applied to the same project.

The repository already has precedent for adding a separate Gradle plugin module
in commit `16e0ca8fc5cd532674c221d5885828b7a66748da`
(`gradle-extension-deployment-plugin`). Follow that pattern:

- add the module to `devtools/gradle/settings.gradle.kts`;
- add the module to `devtools/gradle/pom.xml`;
- create a module `build.gradle.kts` using
  `id("io.quarkus.devtools.gradle-plugin")`;
- register the plugin under `gradlePlugin { plugins.create(...) { ... } }`;
- add reproducible jar settings;
- add focused TestKit tests in the new module.

## DSL Shape

### Extension Name Options

Option A: keep `quarkus {}`.

Pros:

- familiar to users;
- easier migration snippets;
- fewer concepts in docs.

Cons:

- collision risk if users accidentally apply both `io.quarkus` and
  `io.quarkus.application`;
- old mental model leaks into the new plugin;
- hard to explain why the same extension name has different semantics.

Option B: use a new extension name.

Recommended name:

- `quarkusApplication {}`.

Pros:

- makes the new model visibly distinct;
- avoids accidental extension-name collision;
- lets docs be explicit about named outputs;
- reduces pressure to support old `quarkus {}` properties;
- allows the old plugin and new plugin to coexist during migration if needed.

Cons:

- more verbose;
- users must learn a new DSL root;
- migration requires mechanical edits.

Recommendation: use `quarkusApplication {}` for the new plugin. Do not reuse
`quarkus {}` because the new plugin should be able to coexist with `io.quarkus`
as an intentional migration mode.

### DSL Proposal

Initial shape:

```kotlin
quarkusApplication {
    buildProperties.put("common", "xyz")

    builds {
        fastJar("app") {
            buildProperties.put("property-foo", "bar")
            manifest { }
            outputName = "my-fast-jar"
        }
        mutableJar("mutable") {
        }
        uberJar("uber") {
        }
        legacyJar("legacy") {
        }
        nativeExecutable("native1") {
            nativeArguments.put("quarkus.native.container-build", "true")
            outputName = "my-native"
        }
        nativeSources("nativeSources") {
        }
    }
}
```

Open naming choice:

- existing design uses `builds { ... }`;
- a new plugin could use `outputs { ... }` to avoid overloading "build" in
  Quarkus terminology and to emphasize result shapes.
- `outputs { ... }` is not perfect because image, AOT image, and deployment
  operations are also declared under each named entry.
- `applications { ... }` is semantically broader but may imply multiple
  independent applications rather than multiple output shapes of one
  application.
- `targets { ... }` captures build/deploy/image destinations but can be vague.
- `variants { ... }` is Gradle-native vocabulary, but it risks confusion with
  Gradle variants and Quarkus extension variants.

Recommendation: keep `builds { ... }` unless a better term emerges. It matches
the existing named-task design and avoids overloading Gradle's own "variant"
concept. `outputs { ... }` remains a reasonable alternative, but not clearly
better once image/deploy configuration is included.

Task names should retain the established new-task convention:

- `quarkus<App>Build`;
- `quarkus<App>ImageBuild`;
- `quarkus<App>ImagePush`;
- `quarkus<App>AotEnhancedImageBuild`;
- `quarkus<App>AotEnhancedImagePush`;
- `quarkus<App>DeployTo<Deployment>`;
- `quarkus<App>Run` for JVM package builds;
- project-level `quarkusApplicationDev`;
- `quarkus<App>ContinuousTest`, still reserved/deferred.

## Java Package Layout

Avoid reusing legacy implementation packages such as:

- `io.quarkus.gradle.tasks`;
- `io.quarkus.gradle.extension`;
- `io.quarkus.gradle.tooling` for new plugin-owned code.

Recommended root:

- `io.quarkus.gradle.application`

Recommended subpackages:

- `io.quarkus.gradle.application`
  - plugin class and package-private top-level registration;
- `io.quarkus.gradle.application.dsl`
  - extension and public DSL types;
- `io.quarkus.gradle.application.model`
  - public value objects, descriptors, and enums used by DSL/task APIs;
- `io.quarkus.gradle.application.tasks`
  - public task types;
- `io.quarkus.gradle.application.internal.*`
  - non-DSL implementation details, worker operations, planners, codecs,
    validation helpers, extraction helpers, and model-generation internals.

Implementation packages that must not be treated as public API include:

- `io.quarkus.gradle.application.internal.execution`
- `io.quarkus.gradle.application.internal.packaging`
- `io.quarkus.gradle.application.internal.nativeimage`
- `io.quarkus.gradle.application.internal.image`
- `io.quarkus.gradle.application.internal.deployment`
- `io.quarkus.gradle.application.internal.codegen`
- `io.quarkus.gradle.application.internal.modelgen`
- `io.quarkus.gradle.application.internal.planning`

The already-created named-task packages were moved from
`io.quarkus.gradle.tasks.application` into
`io.quarkus.gradle.application.*`. Public task types must stay out of the
legacy `tasks` package so the new plugin keeps a clean boundary.

## Module-Local `AGENTS.md`

Keep a module-local `AGENTS.md` in the new plugin module root:
`devtools/gradle/gradle-app-plugin/AGENTS.md`.

That file should repeat the hard gates in implementation-facing language:

- all TestKit tests use `--configuration-cache` and
  `-Dorg.gradle.unsafe.isolated-projects=true`;
- use `--build-cache` for cacheable task-path tests unless the task is
  intentionally side-effecting/non-cacheable;
- no `Task.getProject()` or equivalent Gradle model access from task actions;
- no capturing `Project`, `Task`, `Configuration`, `SourceSet`, extensions,
  task containers, or other live Gradle model objects in task actions, worker
  parameters, providers, or lazy callbacks;
- no cross-project mutable model access;
- no `afterEvaluate` cross-project wiring;
- no hidden `.get()` calls on providers during configuration unless explicitly
  justified and tested;
- no public internal methods/properties on DSL-facing types;
- no task-name collisions with legacy tasks;
- expensive Quarkus/container/deployment operations behind testable operation
  interfaces;
- test support stays out of `src/main`.

## Relationship To Existing `gradle-model`

Recommendation: depend on `gradle-model` only for pieces that are explicitly
audited as configuration-cache and isolated-project compatible. Add new
implementations for everything else.

Reason:

- `P1-GM-01` still has provider-triggered dependency metadata mutation during
  resolution.
- `P1-GM-03` still reads other projects' mutable model for application-model
  and dependency metadata enrichment.
- A new plugin that uses those paths cannot honestly claim isolated-project
  support.
- Fixing the shared `gradle-model` findings in a legacy-compatible way may be
  too intrusive for the existing plugin. The new plugin should not wait on a
  perfect legacy-compatible refactor if a separate, Gradle-native model path is
  cleaner.

Allowed reuse:

- small, audited value objects;
- serialization helpers that do not touch Gradle model state;
- dependency/classpath builders only after they are proven compatible with
  configuration cache and isolated projects;
- Quarkus bootstrap/core APIs outside Gradle-specific project traversal.

Preferred model direction:

- introduce a new application-model generation path for the new plugin that
  consumes resolved artifacts, variants, capabilities, and generated metadata
  files rather than live dependency-project objects;
- do not apply a Quarkus, Jandex, or metadata-producing Gradle plugin to all
  dependency projects; that is not feasible and would recreate the same
  isolated-project problem through plugin application instead of task lookup;
- consume normal Gradle dependency artifacts by default. If a producer project
  explicitly exposes Quarkus-specific metadata as a consumable variant or
  artifact, the new plugin may consume it through normal dependency resolution;
- included builds participate through normal Gradle dependency resolution;
- local project details needed by Quarkus are serialized by producer tasks and
  consumed as files, not read through `Project`.

This may be the hardest part of the new plugin. It should be designed before
claiming project-isolation support. The direction matches the archived
`archive/p1-ap-01-codegen-project-walk-plan.md`: codegen and build tasks should
rely on resolvable classpaths and Gradle's artifact/task inference, while
Jandex or other producer-side enhancements remain project-owned.

## Conditional Dependencies

Quarkus extensions can declare conditional dependencies: additional runtime
extension artifacts that should be present only when another dependency is
present in the application graph. The Gradle-native plugin must model these
dependencies before deriving the deployment classpath, otherwise augmentation
can miss deployment artifacts for conditionally-added runtime extensions.

A representative case is an extension family that adds an internal client
extension only when the matching client library is present. For example, when
`software.amazon.awssdk:apache-client` is present, the final runtime classpath
must include the matching Quarkus Amazon internal client extension, and the
deployment classpath must include that extension's deployment artifact.

The new plugin must implement this without the legacy cross-project
component-variant/project-inspection path:

- read conditional dependency declarations from extension descriptors found on
  the raw runtime artifact files;
- use Gradle resolution-result component ids, not produced jar metadata, to
  decide whether dependency conditions such as `group:artifact` are satisfied;
- resolve only the condition-satisfied conditional runtime extension artifacts;
- derive deployment artifacts from that final runtime classpath.

Dev mode uses the same Gradle-resolved conditional dependency mechanism, but it
needs a distinct DEVELOPMENT runtime configuration. That configuration starts
from the main compile and runtime classpaths, applies the normal
condition-satisfied `conditional-dependencies`, and then adds
`conditional-dev-dependencies` declared by extension descriptors. Runtime-dev
artifacts must be represented in that DEVELOPMENT application model; they must
not be manually injected into the launcher manifest because that bypasses
Quarkus bootstrap and can skew the deployment/runtime classloaders.

When a configuration is composed from multiple raw classpaths, condition
matching must walk each raw resolution graph independently. A project component
can appear in different variants across compile and runtime classpaths; sharing
one visited set across those graphs can cause runtime-only dependencies to be
missed after the compile variant was seen first.

The conditional configurations must remain lazy. Applying the plugin, realizing
tasks, listing tasks, or running unrelated tasks must not resolve the raw,
conditional-candidate, final runtime, or deployment configurations. Resolution
is expected only when a task that consumes the corresponding classpath is in the
execution graph, and Gradle snapshots that task's inputs or the task action
reads the modeled classpath.

The current implementation can run conditional dependency discovery as part of
task input snapshotting for application-model, codegen, and build tasks, not
during project configuration. This is acceptable for correctness, but it adds
work to task input snapshotting; a deferred optimization is to investigate
moving conditional dependency discovery into a separate task with explicit
outputs if that improves performance or diagnostics.

## Gradle Variant Contracts

The Gradle-native application plugin and the extension plugins use a small
number of explicit Gradle variant contracts. These contracts are the replacement
for the legacy application plugin's live dependency-project lookup.

Use this section as the reference for variant intent. Internal resolvable
configurations are documented separately below because they are not consumable
producer variants.

### Application Package Elements Variant

Producer: `io.quarkus.application` on an application project.

Configuration/variant, per registered JVM package build:

```text
quarkus<BuildName>PackageElements
```

Examples:

```text
quarkusFastPackageElements
quarkusFastJarPackageElements
quarkusUberPackageElements
```

Attributes:

```text
org.gradle.category = quarkus-application-package
org.gradle.usage = java-runtime
org.gradle.libraryelements = jar
org.gradle.jvm.environment = standard-jvm
artifactType = jar
io.quarkus.application.build-name = <registered-build-name>
io.quarkus.application.build-type = fast-jar | mutable-jar | uber-jar | legacy-jar
```

Outgoing artifact:

```text
<primary runnable JAR produced by quarkus<BuildName>Build>
```

The outgoing artifact is explicitly `builtBy` the named package build task.

Intent:

- give other Gradle projects and custom plugins a Gradle-native way to consume
  the runnable Quarkus application artifact;
- avoid reaching into another project's task container to query task properties;
- avoid reading `package-result.properties` during dependency resolution or
  task graph calculation;
- preserve producer task execution when a consumer resolves the package
  artifact.

Consumers:

- custom integration-test plugins that need to launch a Quarkus fast-jar or
  uber-jar produced by another project;
- build scripts that currently publish an ad-hoc outgoing configuration such
  as `quarkusRunner`.

Not intended for:

- normal Java runtime or compile classpaths;
- native executable outputs;
- container image outputs;
- publishing the full fast-jar directory layout. The variant publishes the
  primary runnable JAR. If a consumer needs the full package directory, add a
  separate directory variant instead of overloading this one.

### Runtime Extension Variant Marker

Producer: `io.quarkus.extension` on the extension runtime project.

Configuration/variant: the normal Java `runtimeElements` variant from the Java
plugin.

Additional attribute:

```text
io.quarkus.extension.runtime = true
```

Intent:

- mark a selected project component as a local Quarkus extension runtime
  project;
- let application-plugin classpath modeling recognize local runtime extension
  projects from Gradle resolution metadata;
- avoid reading the runtime project's produced jar file while calculating
  deployment dependencies, because that can query a project-produced artifact
  before the producing `jar` task has completed.

Consumers:

- the new `io.quarkus.application` plugin reads the runtime resolution result
  and looks for selected `ProjectComponentIdentifier` components whose selected
  variant carries this attribute.

Not intended for:

- selecting deployment artifacts directly;
- adding deployment classes to the application runtime classpath;
- identifying published external extension artifacts. External artifacts are
  still descriptor-driven through `META-INF/quarkus-extension.properties`.

### Runtime-To-Deployment Dependency Variant

Producer: `io.quarkus.extension` on the extension runtime project.

Configuration/variant:

```text
quarkusExtensionDeploymentDependencyElements
```

Attributes:

```text
org.gradle.category = quarkus-extension-deployment-dependency
io.quarkus.extension.deployment.dependency = true
```

Outgoing dependency:

```text
project(<quarkusExtension.deploymentModule>)
```

There is intentionally no outgoing runtime artifact on this variant. The
variant is a dependency handoff from the runtime extension project to the local
deployment project.

Intent:

- give consuming application builds a Gradle-native way to reach the local
  deployment project for a local runtime extension dependency;
- avoid resolving the descriptor's Maven-style `deployment-artifact` coordinate
  as an external module when the deployment module is part of the same Gradle
  build;
- keep the application project from inspecting sibling project extensions,
  tasks, source sets, configurations, or mutable model state.

Consumers:

- the new `io.quarkus.application` plugin creates an attribute-selected
  `project(path: <runtime-extension-project>)` dependency that requests
  `org.gradle.category = quarkus-extension-deployment-dependency`.

Not intended for:

- normal application runtime or compile classpaths;
- publication as a user-facing runtime artifact;
- replacing external descriptor-based deployment artifact resolution for
  already-published extensions.

### Deployment Project Marker Variant

Producer: `io.quarkus.extension.deployment` on the extension deployment
project.

Configuration/variant:

```text
quarkusExtensionDeploymentMarkerElements
```

Attributes:

```text
org.gradle.category = quarkus-extension-deployment-marker
io.quarkus.extension.deployment = true
```

Outgoing artifact:

```text
build/quarkus/extension-deployment-marker/io.quarkus.extension.deployment
```

Intent:

- identify a project as a Quarkus extension deployment module;
- let the runtime extension plugin validate that its configured local
  `deploymentModule` points at a project that applied the deployment-side
  plugin;
- produce a small cacheable marker artifact instead of inspecting another
  project's plugin container or extension state.

Consumers:

- the `io.quarkus.extension` runtime plugin resolves this marker through its
  internal `quarkusDeploymentMarker` configuration during validation.

Not intended for:

- application deployment classpath contents;
- application model dependency data;
- normal runtime/compile/test classpaths.

### Legacy Component-Metadata Variants

The legacy `gradle-model` path still contains `QuarkusComponentVariants` and
launch-mode-specific deployment dependency attributes such as
`io.quarkus.<project>.deployment-dependency.<mode>`.

Intent in the legacy plugin:

- retrofit Quarkus deployment and conditional-dependency behavior onto resolved
  external component metadata;
- support the existing `io.quarkus` compatibility plugin and older tooling
  paths.

New-plugin position:

- the new `io.quarkus.application` plugin must not depend on this mutable
  component-metadata path;
- external extension deployment dependencies are derived from resolved extension
  descriptors;
- local extension deployment dependencies use the explicit runtime/deployment
  variant contracts above.

This distinction matters because the legacy component-metadata path is tied to
the configuration-cache and isolated-project findings that motivated the new
plugin boundary.

### Internal Resolvable Configurations

The new application plugin also creates internal resolvable configurations such
as:

- `quarkusApplicationRuntimeClasspathConfiguration`;
- `quarkusApplicationTestRuntimeClasspathConfiguration`;
- `quarkusApplicationConditionalRuntimeClasspathConfiguration`;
- `quarkusApplicationTestConditionalRuntimeClasspathConfiguration`;
- `quarkusApplicationDeploymentClasspathConfiguration`;
- `quarkusApplicationTestDeploymentClasspathConfiguration`;
- `quarkusApplicationCompileOnlyConfiguration`;
- `quarkusApplicationTestCompileOnlyConfiguration`;
- `quarkusApplicationPlatformProperties`.

These are not outgoing variants. They are consumer-side classpath/modeling
configurations used by application-model, codegen, package, and test tasks.

The Java classpath-style configurations request standard Java runtime
attributes (`library`, `java-runtime`, `jar`, `external`, standard JVM) so they
select the normal Java runtime artifacts instead of legacy or plugin-specific
variants unless the plugin deliberately creates an attribute-selected dependency
for a Quarkus-specific variant.

## Local Extension Deployment Dependencies

The new application plugin must support local Quarkus extension projects
without reintroducing the legacy dependency-project walk.

External extensions remain descriptor-driven: the application plugin reads
`META-INF/quarkus-extension.properties` from resolved runtime artifacts and
adds the descriptor's `deployment-artifact` coordinate to the deployment
classpath.

Local extension projects are different. A local runtime extension descriptor
still contains a Maven-style `deployment-artifact` coordinate, but that
coordinate may not be published to any repository during a normal multi-project
build. Treating it as an external module breaks builds where the legacy plugin
would have found the sibling deployment project.

The Gradle-native contract is:

- the runtime extension project and deployment project expose the variants
  documented in [Gradle Variant Contracts](#gradle-variant-contracts);
- the application plugin uses the runtime resolution result to find selected
  `ProjectComponentIdentifier` components whose selected variant carries
  `io.quarkus.extension.runtime=true`. For those local runtime extension
  projects, the deployment classpath adds an attribute-selected
  `project(path: <runtime-project>)` dependency that requests the
  `quarkus-extension-deployment-dependency` category instead of using the
  descriptor's external coordinate;
- if the artifact came from an external module, the application plugin uses the
  descriptor coordinate as before.

This preserves isolated-project compatibility because the application project
does not inspect sibling project extensions, tasks, source sets, or mutable
state. The cross-project relationship is represented only as a Gradle project
dependency on a consumable variant.

## Migration Strategy

Do not auto-migrate existing builds.

Recommended phases:

1. Introduce `io.quarkus.application` as incubating/preview.
2. Document that it is explicit-output only and intentionally lacks legacy task
   aliases.
3. Keep `io.quarkus` as the compatibility plugin.
4. Add migration examples:
   - old `quarkusBuild` to
     `quarkusApplication { builds { fastJar("app") } }`;
   - old `buildNative` to
     `quarkusApplication { builds { nativeExecutable("native1") } }`;
   - old image tasks to `image { ... }` on the named output under
     `quarkusApplication.builds`;
   - old deploy task to `deployments { kubernetes("dev") { ... } }` on the
     named output.
5. Once stable, consider warning in `io.quarkus` when users enable
   configuration cache or isolated projects and point them to the new plugin.

## Implementation Status

The initial move plan has been completed and archived at
`archive/new-application-plugin-implementation-plan.md`.

Implemented baseline:

1. Standalone `gradle-app-plugin` module.
2. Plugin ID `io.quarkus.application`.
3. Extension root `quarkusApplication`.
4. Named `builds` DSL and task model under `io.quarkus.gradle.application`.
5. JVM package, native/native-sources, image, AOT-enhanced image, deployment,
   and failing launch/continuous-test task surfaces moved out of the legacy
   plugin.
6. Legacy plugin keeps compatibility tasks and diagnostics.

Active design gates still apply to all future implementation slices. In
particular, do not claim complete configuration-cache or isolated-projects
support for a feature until the corresponding TestKit coverage runs with
`--configuration-cache` and
`-Dorg.gradle.unsafe.isolated-projects=true`.

## Concerns

- The new plugin will be judged by its first public examples. If the first
  release quietly lacks project-isolation tests, it will lose credibility.
- The existing named-output implementation is valuable, but it started inside
  the old plugin and may still carry old assumptions. Keep tests tight when
  evolving it in the standalone module.
- Continuous testing and full Gradle-native dev lifecycle ownership are likely
  the hardest features to make both Gradle-native and user-friendly. They need
  native integration with Gradle continuous build and likely changes outside the
  Gradle plugin. Reserved tasks should stay explicit until their behavior is
  real enough to validate.
- Jandex should remain project-owned. Automatic cross-project Jandex ordering
  recreates the same isolated-projects problem under a different name.
  Same-project wiring is acceptable when a project explicitly applies a known
  Jandex plugin: application model tasks may depend on local `jandex` or
  `processJandexIndex` tasks, but the new plugin must not apply those plugins
  or inspect dependency projects for them.
- Full feature parity with `io.quarkus` is a trap. The new plugin should be
  explicit about what it supports.

## Settled Direction

- Plugin ID: `io.quarkus.application`.
- Module path: use `devtools/gradle/gradle-app-plugin`.
- Extension name: use `quarkusApplication`.
- Java package root: use `io.quarkus.gradle.application`.
- Public task types and DSL types live under
  `io.quarkus.gradle.application.*`, not the legacy
  `io.quarkus.gradle.tasks.application` package.
- The extension mirrors the legacy `buildForkOptions {}` and
  `codeGenForkOptions {}` hooks. These configure worker JVM fork options for
  application build/image/AOT operations and code generation respectively.
  Because arbitrary fork-option actions are opaque to Gradle, related tasks
  must remain conservative about build-cache claims until typed, stable
  fork-option inputs are available.
- Dependency projects are consumed through Gradle artifacts, variants, and
  dependency resolution. The new plugin must not apply plugins to all
  dependency projects or inspect their live mutable Gradle model.
- Run, project-level dev mode, and standalone remote dev have initial
  production implementations. Continuous testing remains deferred until Quarkus
  can integrate natively with Gradle's continuous build model and a usable
  stdin/control story.

## Remaining Design Work

- Exact application-model artifact contract for project dependencies and
  included builds.
- Exact user-facing behavior for continuous-test tasks and for any future
  interactive dev/remote-dev test controls.

## Deferred Follow-Ups

- Gradle-native continuous-test codegen once those tasks become real launch
  tasks instead of reserved entry points.
- Quarkus core/deployment extraction for Gradle-native dev/remote-dev protocol
  helpers. `BuildOutputChangesPolicy` and the plugin-local
  `RemoteDevPackageClient` should remain free of Gradle/plugin types so they
  can eventually move out of `gradle-app-plugin` and become Quarkus-owned
  core/deployment contracts shared by Gradle and any other external build-tool
  integrations. Do this after the new plugin has proven the contracts with
  focused tests; keep the legacy remote-dev path untouched until the extraction
  is mechanically clear.
- Real remote-dev backend integration coverage for the new
  `quarkusApplicationRemoteDev` task. Default-suite coverage should use fake
  or dry-run client seams so configuration-cache and isolated-project tests stay
  bounded and reliable. Add a deferred, slower integration test with a real
  mutable-jar remote side only after the plugin-local `RemoteDevPackageClient`
  and task/session contracts are stable. That test should verify the full
  client/server flow: initial connect, package-root diff upload, delete events,
  `appmodel.dat` restart/reconnect behavior, and secret redaction.
- KSP plus `sourcesJar` generated-source cycle regression test: Kotlin/JVM and
  KAPT generated-source wiring now attaches Quarkus generated source
  directories directly to the matching Kotlin/KAPT tasks instead of shared
  `SourceSet`s, so it should avoid the historical
  `kspKotlin -> quarkusGenerateCode -> processResources -> kspKotlin` cycle.
  Add a focused default-suite TestKit fixture with Kotlin JVM, KSP,
  `io.quarkus.application`, and `java { withSourcesJar() }` once
  `gradle-app-plugin` has a stable KSP version source. The test should run with
  `--configuration-cache` and
  `-Dorg.gradle.unsafe.isolated-projects=true`, and it should prove only that
  the source-set cycle is not reintroduced, not KSP generated-source
  consumption.
- Optional diagnostics for resolved artifacts without Jandex indexes. This must
  not become automatic cross-project Jandex task wiring or plugin application;
  it can only inspect resolved artifacts owned by the consuming classpath.
- Broader integration coverage for extension-deployment generated test models
  after the isolated-project/local-output fix. The focused
  `gradle-extension-deployment-plugin` TestKit coverage should remain the fast
  regression for `quarkusGenerateTestAppModel`, configuration cache, isolated
  projects, local output directories, and `test-fixtures` classifier separation.
  Add slower integration coverage for representative real Gradle shapes:
  composite extension builds, extension-to-extension dependencies, helper
  libraries, classifier artifacts, and Jandex/indexed local outputs. These tests
  should verify the serialized application model and selected runtime/deployment
  flags, not just task success.
- Split shared `gradle-model` changes into explicit new-plugin and legacy-plugin
  surfaces. Recent work moved or changed model/task helper types so the new
  `io.quarkus.application` plugin and extension plugins can share
  configuration-cache and isolated-project compatible behavior. Some of those
  changes are intrusive for the legacy `io.quarkus` application plugin and
  should not become accidental legacy behavior. Revisit the shared
  `gradle-model` types once the new plugin contracts are stable, extract the
  Gradle-native pieces behind new-plugin-owned or clearly neutral APIs, and
  restore/reduce legacy-facing code paths where possible so the compatibility
  plugin stays close to its pre-refactor behavior.
- Cross-module codegen input contract: define and test the supported behavior
  for codegen inputs, such as gRPC `.proto` files, that live in another Gradle
  module. The preferred boundary is that producer modules expose such inputs
  through normal Gradle artifacts or variants, and the consuming module resolves
  them like any other dependency. The new plugin must not reintroduce sibling
  project source-directory inspection to find those files.
- IDE model integration for generated source directories if simple compile-task
  source wiring is not enough for import/sync behavior.
- A shared operation boundary for codegen and package build worker setup if
  duplication becomes material.
- Codegen task cacheability review: `QuarkusApplicationGenerateCodeTask`
  starts as `@DisableCachingByDefault`. Before making it cacheable, review
  provider-specific inputs, fork-option actions, source-directory sensitivity,
  effective-config inputs, and the legacy ambient-config escape hatch. Add a
  build-cache restore TestKit test before changing the annotation.
- Dev-mode delivery lock split: real dev mode should not hold the session or
  deployment-handle monitors while synchronously sending build-output changes
  to the running Quarkus process. Split delivery into selection/snapshot,
  blocking transport send, and finalization phases so stop/cancel can proceed
  while Quarkus is slow or stuck. Preserve the current failed/not-applied
  delivery semantics: pending changes must not be lost, newer changes must
  coalesce correctly while a send is in flight, and stale delivery results must
  not clear newer pending state. Add focused tests for slow delivery plus
  stop/cancel responsiveness before enabling real dev-mode delivery.
- Gradle-native dev continuous-testing controls while
  `quarkusApplicationDev --continuous` is running, and equivalent support for
  mutable-jar remote-dev server runs started with
  `quarkusApplicationMutableJarRun --enable-remote-dev`. The current launcher
  disables Quarkus console input with `quarkus.console.disable-input=true` and
  disables continuous testing with
  `quarkus.test.continuous-testing=disabled`, because Gradle owns the
  long-running command and uses stdin for continuous-build cancellation. The
  mutable-jar remote-dev server run also sets those properties for now, because
  the new run task does not yet capture stdin or model remote-dev test support.
  Recent investigation suggests dev mode should be able to run tests, but users
  still need a way to enable/disable or rerun tests while dev mode is running,
  even when Dev UI is unavailable because the app is broken. Evaluate realistic
  control options:
  - preferred clean model: keep Quarkus stdin disabled and add a small
    Gradle/Quarkus control channel for test actions such as start, stop,
    run-all, run-failed, and toggle broken-only. Pros: explicit, testable, not
    coupled to terminal ownership, usable when Dev UI is broken. Cons: needs
    new protocol/receipt/client UX and has to be lifecycle-scoped to the
    running dev deployment;
  - Dev UI model: rely on existing continuous-testing actions where the app and
    Dev UI are reachable. Pros: already Quarkus-native. Cons: not sufficient
    when broken app state or deployment changes make Dev UI unavailable;
  - dirty stdin experiment: only when `System.in` is Gradle's internal
    `org.gradle.util.internal.DisconnectableInputStream`, experiment with a
    narrow `System.setIn(...)` wrapper/tee that preserves Gradle's
    continuous-build Ctrl-D/EOF cancellation while interpreting a small command
    set for test control. Pros: closest to interactive Quarkus-console UX
    inside the same `./gradlew ... --continuous` invocation. Cons: relies on
    Gradle internals, mutates global daemon JVM state, competes with Gradle's
    cancellation monitor, may break daemon/tooling API stdin forwarding, is
    likely version- and terminal-sensitive, and must be proven with real
    interactive continuous-build tests before being considered product-ready;
  - raw stdin duplication to the Quarkus child remains a last resort and should
    not be the default design. Java stdin is consumptive, so duplicating it
    reliably would require owning all terminal reads and forwarding structured
    commands rather than letting Gradle and Quarkus race on the same stream.
- Console color support for launch tasks: propagate the right
  `io.quarkus.force-color-support` setting for every new-plugin launch path,
  including run tasks and `quarkusApplicationDev`. The implementation should
  decide whether Gradle terminal capabilities, explicit user JVM/system
  properties, and Quarkus defaults should be modeled in task inputs or passed
  only as invocation-time launch arguments.
- Conditional/deployment descriptor discovery race-safety and task split: the
  current design allows descriptor discovery to run from `ValueSource` code
  paths while Gradle is resolving modeled classpaths. This includes
  `ConditionalDependencyCoordinatesValueSource`,
  `ConditionalDevDependencyCoordinatesValueSource`,
  `SatisfiedConditionalDependencyCoordinatesValueSource`, and
  `DeploymentArtifactsValueSource`. Those value sources can call
  `ExtensionDescriptorReader` on runtime artifacts while a continuous build is
  also rebuilding or replacing a same-build jar-only dependency. If the reader
  observes a project-produced jar before the producer has finished writing or
  atomically replacing it, `ZipFile` can fail with errors such as
  `zip END header not found` even though the final jar is valid. Investigate
  and fix this as an artifact-modeling problem, not primarily as a retry loop:
  descriptor and conditional-dependency discovery should inspect stable
  external module artifacts, use modeled Gradle variants for same-build
  extensions/deployment metadata, and treat project jar-only artifacts as
  coarse dependency/classpath-change diagnostics until an explicit
  rebootstrap/reaugment path exists. Evaluate whether descriptor discovery
  should move into one or more tasks with explicit inputs/outputs so producer
  task dependencies, diagnostics, and input snapshotting are correct. Any
  defensive retry in `ExtensionDescriptorReader` must be bounded and secondary
  to correcting the artifact modeling.

## References

- [Gradle configuration cache requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html)
- [Gradle isolated projects](https://docs.gradle.org/current/userguide/isolated_projects.html)
- [Gradle task configuration avoidance](https://docs.gradle.org/current/userguide/task_configuration_avoidance.html)
- [Gradle `Task` Javadoc](https://docs.gradle.org/current/javadoc/org/gradle/api/Task.html)
