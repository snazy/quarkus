# P1-EP-01 Deployment Project Plugin Plan

Date: 2026-07-03

Status: implemented plan; archived. Documentation and migration follow-up
remain tracked through `public-dsl-shape-changes.md` and the active phase
review docs.

Owner / audience: Gradle configuration-cache and isolated-projects workstream

Finding: `P1-EP-01`

## Problem

`QuarkusExtensionPlugin` is applied to the runtime project of a Quarkus
extension. For split runtime/deployment extensions, it currently waits until
`afterEvaluate`, finds the deployment project, and mutates that deployment
project:

- applies the Java plugin;
- initializes Quarkus deployment classpath configurations;
- configures annotation processors;
- creates a runtime-project configuration that depends on the deployment
  project;
- builds deployment-project descriptors and classpaths;
- registers generated application-model tasks on the deployment project;
- configures deployment-project `Test` tasks.

That shape is a direct isolated-projects blocker. The runtime project should
not configure another project's plugins, tasks, configurations, dependencies,
or extensions.

## Direction

Use a deployment-project-side Gradle plugin for Quarkus extension deployment
modules.

The runtime project should continue to apply the existing
`io.quarkus.extension` plugin. The deployment project should apply a new plugin
that configures only the deployment project. The runtime project should consume
deployment information through ordinary Gradle dependencies, outgoing variants,
or stable artifact metadata instead of mutating the deployment project.

This is acceptable for the current workstream because the target is Quarkus 4.0,
so a deliberate plugin-shape change is on the table.

## Plugin Packaging Decision

Selected approach: add a separate Gradle/Maven project for the deployment-side
plugin.

Rationale:

- both plugins are part of the same user-facing Quarkus extension Gradle
  feature;
- the existing `gradle-extension-plugin` Maven/POM shape is deliberate and
  should not be complicated to host a second plugin marker coordinate;
- the deployment plugin should be different enough from the runtime plugin that
  sharing an implementation module is not required for the first version;
- a separate project gives the deployment plugin its own Maven wrapper POM,
  artifact prefix, plugin marker, tests, and publication validation path;
- any shared helper can be extracted later only if implementation proves that it
  removes real duplication.

Possible plugin IDs:

- `io.quarkus.extension.deployment`
- `io.quarkus.extension-deployment`

Decision: use `io.quarkus.extension.deployment`. Implemented in the rewritten
branch by `a3e685d7172` (`Add Gradle extension deployment plugin`).

## Maven / Publication Considerations

The new deployment plugin project should follow the existing Maven-wrapper
shape used by the current Gradle plugin modules. The parent Maven build runs
Gradle and attaches the implementation jar, javadoc jar, and sources jar from
`build/libs`.

Adding a new project should be validated against both publication paths:

- local plugin resolution in TestKit via `withPluginClasspath()`;
- Maven-launched build output under the new deployment plugin module;
- deploy profile behavior for `-Ddeploy-gradle-plugins`.

Decision: keep the current `gradle-extension-plugin/pom.xml` shape unchanged.
Add a new deployment-plugin Maven module/POM instead of teaching the existing
module to publish a second plugin marker coordinate.

The first implementation PR must prove that the new module works with the
Maven-driven build and local plugin resolution before functional wiring starts.

## Publication Investigation Notes

The rejected shared-module option was investigated first:

- `devtools/gradle/gradle-extension-plugin/build.gradle.kts` applies the shared
  Gradle-plugin convention and declares the current `io.quarkus.extension`
  plugin ID.
- Gradle's plugin development machinery generates
  `META-INF/gradle-plugins/io.quarkus.extension.properties` and a marker
  publication for the current plugin ID.
- `devtools/gradle/pom.xml` delegates Maven builds to Gradle and attaches the
  Gradle-built implementation jar, sources jar, and javadoc jar by
  `artifactFilePrefix`.
- `devtools/gradle/gradle-extension-plugin/pom.xml` currently publishes one
  Maven coordinate: `io.quarkus.extension:io.quarkus.extension.gradle.plugin`.
  That coordinate matches the existing plugin marker coordinate, while Gradle's
  generated implementation publication uses
  `io.quarkus.extension:gradle-extension-plugin`.

Implications:

- adding a second plugin ID in that existing project would likely generate the
  descriptor and Gradle marker publication, but it would put pressure on a
  Maven/POM shape that exists for a specific publication reason;
- a separate project avoids that publication ambiguity and keeps the current
  runtime extension plugin module stable.

## Target Shape

### Runtime project plugin: `io.quarkus.extension`

Responsibilities that stay on the runtime project:

- create `quarkusExtension` extension;
- apply/configure Java for the runtime project;
- register `extensionDescriptor`;
- register `validateExtension`;
- configure runtime annotation processor;
- package runtime extension descriptor into runtime jar;
- validate runtime/deployment relationship using modeled values or resolved
  artifacts;
- declare a dependency on the deployment project or deployment artifact without
  configuring that target project.

Responsibilities to remove:

- no `afterEvaluate` that finds and mutates the deployment project;
- no `deploymentProject.getPlugins().apply(...)`;
- no deployment-project task registration;
- no deployment-project test configuration;
- no deployment-project `ApplicationDeploymentClasspathBuilder` construction;
- no deployment-project annotation processor configuration.

### Deployment project plugin: new plugin ID

Responsibilities of the new deployment-side plugin:

- apply/configure Java for the deployment project;
- initialize deployment classpath configurations on its own project;
- configure annotation processor dependency on its own project;
- register generated application-model tasks for deployment tests;
- wire deployment-project `Test` tasks to the generated `TEST` application
  model;
- provide any outgoing metadata or variant needed by the runtime project;
- optionally validate that the project has a runtime-project dependency if that
  check can be done without cross-project mutable access.

The deployment plugin should not reach back into the runtime project to mutate
runtime tasks or extensions.

## Runtime-To-Deployment Contract Options

### Option A: Explicit User Wiring

Users apply both plugins:

```groovy
// runtime/build.gradle
plugins {
    id 'io.quarkus.extension'
}

quarkusExtension {
    deploymentModule = 'deployment'
}

dependencies {
    // existing runtime dependencies
}
```

```groovy
// deployment/build.gradle
plugins {
    id 'io.quarkus.extension.deployment'
}

dependencies {
    implementation project(':runtime')
    implementation 'io.quarkus:quarkus-arc-deployment'
}
```

Decision: this is the target shape.

Pros:

- clean isolated-projects boundary;
- no cross-project plugin application;
- build scripts clearly state each project's role;
- best Quarkus 4.0 long-term shape.

Cons:

- breaking change for existing Gradle extension builds;
- needs migration docs and clear error messages.

### Option B: Runtime Plugin Requests Deployment Plugin Through a Convention

The runtime plugin does not mutate the deployment project, but it can expose a
clear diagnostic when the configured deployment module does not apply the new
deployment plugin.

Pros:

- better user guidance than silent missing setup;
- avoids cross-project mutation.

Cons:

- runtime project still needs to discover enough about the deployment module to
  issue diagnostics. Under isolated projects, that discovery must be limited to
  safe identity/path metadata or ordinary dependency resolution.

### Option C: Compatibility Bridge

Keep legacy `afterEvaluate` deployment-project mutation behind an opt-out /
compatibility mode for Quarkus 3.x-style builds, and make the new deployment
plugin path the Quarkus 4.0 default.

Decision: do not plan a compatibility bridge.

Pros:

- lower migration pain if Quarkus wants transitional behavior.

Cons:

- leaves the old isolated-projects blocker alive;
- complicates testing and messaging;
- may not be worth it if Quarkus 4.0 is allowed to require the new plugin.

Recommendation: use Option A for the Quarkus 4.0 path and fail clearly when a
split deployment project does not apply the deployment plugin.

## Runtime-To-Deployment Metadata Proposal

Use a dedicated outgoing Gradle marker variant to mark that the project is a
Quarkus extension deployment module configured by the new deployment plugin.

Candidate attribute:

```java
Attribute<Boolean> QUARKUS_EXTENSION_DEPLOYMENT_ATTRIBUTE =
        Attribute.of("io.quarkus.extension.deployment", Boolean.class);
```

The deployment plugin would publish a small consumable marker configuration with
this attribute set to `true`. The runtime plugin would create a local
resolvable configuration for its configured deployment dependency and require or
inspect that marker variant.

Preferred check shape:

- runtime plugin creates a resolvable, non-consumable configuration in the
  runtime project;
- runtime plugin adds a dependency using the configured deployment module path
  or explicit deployment artifact coordinates;
- runtime plugin requests the marker variant by setting the same attribute on
  the consumer configuration;
- `ValidateExtensionTask` or a small validation task receives resolved artifact
  metadata / scalar validation inputs and checks that the selected artifact came
  from a variant with the deployment attribute;
- missing attribute yields a clear message: apply
  `io.quarkus.extension.deployment` to the deployment project.

Why this fits `P1-EP-01`:

- the runtime project does not need to apply plugins or register tasks on the
  deployment project;
- Gradle variant matching carries the role information through dependency
  resolution;
- checking selected variant attributes is compatible with configuration cache
  when modeled as task inputs or provider-backed validation values;
- the same mechanism can support published external deployment artifacts later,
  if those artifacts expose equivalent metadata.

Decision: use a dedicated deployment marker variant instead of attaching the
attribute to Java runtime elements.

Rationale:

- it keeps the marker semantic separate from runtime/deployment classpaths;
- it gives variant selection a precise target;
- it avoids changing normal Java component variants more than necessary;
- it should produce clearer diagnostics when the deployment plugin is missing.

The marker artifact can be a small generated file or another stable artifact
owned by the deployment plugin. It should not need to be on any runtime or test
classpath.

Implementation note from 2026-07-03:

- the marker variant is only used to prove that the configured deployment
  project applied `io.quarkus.extension.deployment`;
- deployment classpath validation uses a normal project dependency from the
  runtime project to the configured deployment project path and reads Gradle's
  resolved component IDs, not resolved deployment files;
- a custom deployment-classpath variant was considered and tried, but rejected
  because its custom attributes leaked into transitive external dependency
  selection and made normal Maven/Java variants incompatible;
- when `quarkusExtension.deploymentArtifact` is explicitly set, local
  deployment-project content validation is skipped. Runtime classpath misuse is
  still checked.

## Current Local Result

The implementation is now split across the rewritten branch stack:

- `bc099e63e87` (`Share Gradle TestKit fixtures across plugin tests`)
  introduces the shared TestKit fixture cleanup used by the plugin tests.
- `a3e685d7172` (`Add Gradle extension deployment plugin`) makes
  `QuarkusExtensionConfiguration` a Gradle-managed abstract extension, removes
  JavaBean setter aliases, adds the deployment plugin module, moves
  deployment-project setup to `io.quarkus.extension.deployment`, and changes
  runtime validation to use the deployment marker variant instead of
  deployment-project model access.
- `3c5d783cf73` (`Update Gradle integration tests for plugin rewiring`) updates
  integration-test fixtures for the new plugin shape.

Public DSL/API follow-up is tracked in `public-dsl-shape-changes.md`.

Validation recorded for the committed local stack:

- `./gradlew :gradle-extension-plugin:test --configuration-cache`
- `./mvnw process-sources -f devtools/gradle`
- `./gradlew :gradle-extension-deployment-plugin:test :gradle-extension-plugin:test --configuration-cache --rerun-tasks`
- `./gradlew :gradle-application-plugin:test :gradle-extension-deployment-plugin:test :gradle-extension-plugin:test --configuration-cache`

## Implementation Phases

### `P1-EP-01A`: Empty deployment plugin project spike

Goal: prove the new plugin ID can live in its own Gradle/Maven module before
adding behavior.

Scope:

- add a new `devtools/gradle/gradle-extension-deployment-plugin` Gradle
  project;
- add the new module to `devtools/gradle/settings.gradle.kts`;
- add the matching Maven module and POM under `devtools/gradle`;
- add a no-op deployment plugin class in the new project;
- declare `io.quarkus.extension.deployment` in the new project's
  `build.gradle.kts`;
- add a TestKit fixture proving the deployment plugin ID can be resolved from
  `withPluginClasspath()`;
- inspect Maven-launched output for plugin descriptors and publications.

Success gates:

- `./mvnw -f devtools/gradle/pom.xml process-sources -DskipTests`;
- `cd devtools/gradle && ./gradlew :gradle-extension-deployment-plugin:validatePlugins`;
- focused deployment-plugin TestKit test for the new plugin ID;
- manual or automated check that
  `build/pluginDescriptors/io.quarkus.extension.deployment.properties` exists;
- Maven-local verification using a repository under `target/` that checks
  whether
  `io.quarkus.extension.deployment:io.quarkus.extension.deployment.gradle.plugin`
  exists and can resolve the implementation.

Do not add marker-variant behavior or move existing behavior in this phase.

Status:

- completed in the rewritten branch by `a3e685d7172`;
- implemented the `gradle-extension-deployment-plugin` module;
- verified plugin descriptor generation for
  `io.quarkus.extension.deployment`;
- verified TestKit plugin-classpath resolution;
- verified Gradle marker and implementation publications into a repository
  under `target/`;
- verified a temporary consumer build can resolve the plugin by ID from that
  target repository;
- verified the Maven-driven `devtools/gradle` `package -DskipTests` build.

Note: redirecting `maven.repo.local` to an empty target repository is not a
useful verification here because the Quarkus `999-SNAPSHOT` BOM and dependency
set are not present there. The publication check used an explicit temporary
Gradle publish repository under `target/` instead, without writing to the
normal local Maven repository.

### `P1-EP-01B`: Marker variant spike

Goal: prove the dedicated deployment marker variant can be published and
resolved inside one Gradle build without cross-project mutation.

Scope:

- add the dedicated outgoing marker variant to the no-op deployment plugin;
- add a runtime-side resolvable marker configuration in a focused test fixture;
- prove the marker variant can be selected by the Quarkus deployment attribute;
- validate the failure shape when the deployment plugin is missing.

Success gates:

- focused marker-variant resolution check;
- focused missing-marker diagnostic check;
- configuration-cache run for the marker-resolution fixture.

Status:

- completed in the rewritten branch by `a3e685d7172`;
- added `quarkusExtensionDeploymentMarkerElements` as a dedicated consumable
  marker variant on projects applying `io.quarkus.extension.deployment`;
- added a cacheable `quarkusExtensionDeploymentMarker` task that writes the
  marker artifact;
- verified a Java deployment project can publish the marker while a runtime
  project resolves it by requiring the dedicated marker category and the
  `io.quarkus.extension.deployment=true` attribute;
- verified that marker resolution fails when the deployment project does not
  apply the deployment plugin; the dedicated category is required because a
  missing custom Boolean attribute alone does not make normal Java variants
  incompatible;
- verified the marker-resolution fixture with configuration cache;
- verified the Maven-driven `devtools/gradle` `package -DskipTests` build.

### `P1-EP-01C`: Move deployment-project self-configuration

Goal: move deployment-project setup currently done from runtime
`afterEvaluate` into the new deployment plugin.

Scope:

- deployment plugin applies/configures Java on its own project;
- deployment plugin initializes deployment classpath configurations;
- deployment plugin configures annotation processor dependency;
- deployment plugin registers generated `TEST` application-model task;
- deployment plugin wires deployment `Test` tasks to the generated model;
- deployment plugin publishes the dedicated marker variant with
  `io.quarkus.extension.deployment=true`;
- runtime plugin stops configuring deployment-project tests/tasks when the
  deployment plugin is present.

Success gates:

- existing deployment test generated-model tests pass with the new plugin
  applied in deployment fixtures;
- configuration-cache tests still pass;
- no `Project` instance from the deployment project is captured by runtime
  project task configuration.

Status:

- completed in the rewritten branch by `a3e685d7172`;
- the deployment plugin now applies/configures Java on its own project;
- the deployment plugin initializes deployment classpath configurations;
- the deployment plugin configures the Quarkus extension annotation processor;
- the deployment plugin registers and wires the generated `TEST` application
  model for deployment project `Test` tasks;
- the deployment plugin publishes the dedicated marker variant with
  `io.quarkus.extension.deployment=true`;
- the annotation-processor helper and shared constants were moved into
  `gradle-model` so the runtime and deployment plugins do not carry duplicated
  implementation code;
- focused deployment-plugin tests cover annotation processor wiring, generated
  test-model wiring, positive marker selection, and missing-marker failure;
- existing extension-plugin tests still pass.

### `P1-EP-01D`: Replace runtime validation deployment-project mutation

Goal: remove the runtime plugin's remaining need to create local configuration
state from a found deployment project.

Scope:

- model runtime-to-deployment validation through a runtime-project dependency
  declaration, resolved artifact metadata, or outgoing deployment metadata;
- make `ValidateExtensionTask` consume modeled deployment artifact/classpath
  inputs without the runtime plugin configuring the deployment project;
- preserve existing validation behavior for separate runtime/deployment modules.

Candidate shapes:

- runtime plugin creates a local resolvable configuration depending on the
  deployment project path supplied by user DSL, but does not access or mutate
  the deployment `Project` instance;
- deployment plugin publishes the dedicated marker variant with a Quarkus
  extension deployment attribute that runtime validation can resolve or inspect;
- runtime validation relies on explicit `deploymentArtifact` when the
  deployment project is not resolvable through Gradle.

Success gates:

- `validateExtension` works for split runtime/deployment projects;
- validation fails clearly when the resolved deployment artifact does not carry
  the Quarkus extension deployment attribute;
- `validateExtension --configuration-cache` reuses configuration cache;
- isolated-projects smoke test gets past this blocker or reports only remaining
  known blockers outside `P1-EP-01`.

Status:

- completed in the rewritten branch by `a3e685d7172`;
- runtime validation derives the deployment project path from the runtime
  project's path and the configured `deploymentModule`, without accessing the
  deployment `Project`;
- runtime validation resolves a dedicated marker configuration that requests
  the marker category plus `io.quarkus.extension.deployment=true`;
- the validation deployment classpath is a normal project dependency so
  transitive external dependencies keep their normal Java/Maven attributes;
- `ValidateExtensionTask` records deployment module contents from Gradle
  resolution-result component IDs. That is enough for its group/artifact
  comparison and avoids resolving deployment files just to identify modules;
- extension-plugin TestKit tests use a combined plugin-under-test classpath so
  fixtures can apply the separate deployment plugin without adding a production
  dependency between the two plugin modules;
- focused extension-plugin tests pass with configuration cache.

### `P1-EP-01E`: Remove legacy `afterEvaluate` cross-project mutation

Goal: delete the old runtime-project `afterEvaluate` path.

Scope:

- remove `findDeploymentProject(...)` if no longer needed for safe diagnostics;
- remove runtime-side deployment project mutation;
- add migration error messages when a split extension project does not apply the
  deployment plugin;
- update tests and fixtures to apply the new plugin in deployment modules.

Success gates:

- no `QuarkusExtensionPlugin` code path calls `deploymentProject.getPlugins()`,
  `deploymentProject.getTasks()`, `deploymentProject.getConfigurations()`, or
  constructs deployment-project `ApplicationDeploymentClasspathBuilder`;
- runtime-side validation checks the deployment plugin marker through Gradle
  dependency/variant metadata, not through deployment `Project` access;
- multi-project extension build works with
  `-Dorg.gradle.unsafe.isolated-projects=true` as far as current Gradle-model
  blockers allow;
- existing extension-plugin tests pass.

Status:

- completed in the rewritten branch by `a3e685d7172`;
- `QuarkusExtensionPlugin` no longer uses `afterEvaluate` to find or mutate the
  deployment project;
- deployment-project Java/plugin/configuration/test-model setup moved to
  `io.quarkus.extension.deployment`;
- runtime-side validation checks the deployment plugin marker through Gradle
  dependency/variant metadata;
- generated deployment test models now have a focused isolated-project
  local-output path, tracked in `extension-deployment-test-model-isolation.md`;
- migration documentation and any isolated-projects smoke gate remain for
  follow-up.

### `P1-EP-01F`: Documentation and migration

Goal: document the Quarkus 4.0 Gradle extension plugin shape.

Status: open follow-up.

Scope:

- update user/developer docs for Gradle extension projects;
- document when to apply `io.quarkus.extension` versus
  `io.quarkus.extension.deployment`;
- describe migration for split runtime/deployment extensions;
- note compatibility behavior if maintainers require a transition period.

## Test Plan

Unit / plugin tests:

- plugin descriptor exists for the deployment plugin ID in the new deployment
  plugin project;
- deployment plugin configures annotation processor on deployment project;
- deployment plugin wires deployment `Test` tasks to generated `TEST` model;
- runtime plugin no longer configures deployment project when the deployment
  plugin is applied;
- missing deployment plugin produces a clear Quarkus 4.0 migration error.
- deployment plugin publishes the dedicated marker variant carrying the Quarkus
  extension deployment attribute.
- runtime validation can detect the deployment attribute on the selected
  artifact/variant.

Integration / TestKit scenarios:

- two-project extension (`:runtime`, `:deployment`) with both plugins applied;
- custom deployment module name;
- direct runtime child project named `runtime` still requires explicit
  `deploymentArtifact` or new supported shape;
- `validateExtension --configuration-cache` first and second run;
- deployment `test --configuration-cache` first and second run;
- isolated-projects smoke test for the split extension fixture.

Publication checks:

- `:gradle-extension-deployment-plugin:validatePlugins`;
- descriptor file for the new plugin ID in the new deployment plugin Gradle
  build output;
- Maven-launched `devtools/gradle` build still attaches the implementation jar,
  sources jar, and javadoc jar for the new deployment plugin module;
- deploy-profile dry-run or local publish check for the new deployment plugin
  marker coordinate.

## Resolved Questions

- Plugin ID: `io.quarkus.extension.deployment`.
- Compatibility bridge: do not plan one for Quarkus 4.0.
- Maven module/POM: keep the current `gradle-extension-plugin/pom.xml` shape
  unchanged and add a separate deployment plugin module/POM.
- Runtime-to-deployment metadata direction: use a dedicated outgoing marker
  variant carrying a Gradle attribute that the runtime project can require or
  inspect.
- Runtime validation should require the marker attribute through variant
  matching. Inspecting selected attributes can still be useful in tests or for
  richer diagnostics, but variant selection should be the compatibility
  contract.
- Missing deployment-plugin diagnostics should be implemented from the runtime
  project's own marker-configuration resolution result. The runtime plugin can
  report the configured deployment dependency/path and tell the user to apply
  `io.quarkus.extension.deployment`, without reading or mutating the deployment
  `Project` model.

## Current Recommendation

Treat the implementation as locally fixed and focus the next public-facing work
on documentation/migration:

- document that split extension deployment projects apply
  `io.quarkus.extension.deployment`;
- document the runtime/deployment plugin split for Quarkus 4.0;
- update examples and tests that previously relied on runtime-side deployment
  project mutation;
- keep broader isolated-projects smoke and integration testing as follow-up
  coverage after the focused generated test-model local-output fix.
