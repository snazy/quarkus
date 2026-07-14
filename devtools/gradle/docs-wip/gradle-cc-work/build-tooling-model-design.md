# Build Tooling Model Design Seed

Status: starter design notes, not an implementation plan
Last reviewed: 2026-07-09

## Objective

Define what we need to know before designing the proper fix for the Gradle
build-tooling/application-model path after the standalone
`io.quarkus.application` plugin split.

This document intentionally does not propose a final implementation yet. It
captures the current problem, known consumers, constraints, plausible Gradle
contracts, and the investigation needed before writing an agent-followable
implementation plan.

## Why This Exists

The new standalone application plugin avoids the riskiest legacy
`io.quarkus` task wiring by using new task names, provider-backed task inputs,
and Gradle variants/artifacts for cross-project relationships.

That does not automatically fix the Gradle tooling-model path. Quarkus
bootstrap/devtools code and any external tool can still ask Gradle for a Quarkus
`ApplicationModel`. The current tooling builder answers that query by inspecting
live mutable Gradle model from the target project and from dependency projects.
That remains the main project-isolation problem once legacy task behavior is
intentionally left as compatibility behavior.

Primary finding:

- `P1-GM-03`: application-model and project-declared dependency paths read
  other projects' mutable model.

Related finding:

- `P1-GM-01`: legacy/shared component metadata mutation during dependency
  resolution. This may matter if the tooling-model fix continues to use
  `ApplicationDeploymentClasspathBuilder` and `QuarkusComponentVariants`.

## Current Entry Points

Tooling model registration:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
  registers `new GradleApplicationModelBuilder()`.

Direct utility callers:

- `ToolingUtils.create(Project, LaunchMode)`;
- `ToolingUtils.create(Project, ModelParameter)`;
- `AppModelGradleResolver.resolveModel(...)`.

Main tooling builder:

- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/GradleApplicationModelBuilder.java`

Project-declared dependency enrichment:

- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/dependency/DependencyDataCollector.java`
- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/dependency/GradleProjectDependencyDeclaredDependencyCollector.java`

Extension detection and deployment dependency mapping:

- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/dependency/DependencyUtils.java`
- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/dependency/ApplicationDeploymentClasspathBuilder.java`
- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/dependency/QuarkusComponentVariants.java`

Task-produced application model path:

- legacy: `QuarkusApplicationModelTask`;
- new plugin: `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/modelgen/GenerateModelTask.java`.

The task-produced model path and the tooling-model query path are currently
different enough that fixing one does not automatically fix the other.
The new plugin's task-produced package/build model now has its own
POM-closure-based enrichment path; that implementation deliberately leaves the
tooling-model query path and legacy task-produced model path unchanged.

## Current Mutable-Model Reads

The current tooling builder uses live Gradle model for several distinct
purposes. These should not be treated as one problem.

### Target Project Metadata

The target project itself is read for:

- group/name/version;
- project directory, build directory, and build file;
- Java source sets and output directories;
- compile and resource processing tasks;
- test tasks;
- Kotlin compile tasks when present;
- runtime, deployment, platform, and compile-only configurations.

Reading the target project is expected for a tooling model query about that
project. The project-isolation problem starts when the builder follows
dependencies into other projects or included builds and reads their mutable
state directly.

### Dependency Project Workspace Metadata

`GradleApplicationModelBuilder` follows `ProjectComponentIdentifier` values
back to live projects using `rootProject.findProject(...)` or included-build
internals. It then reads source sets, tasks, layout, and build files to create
workspace modules and resolved paths for dependency projects.

This currently supports dev/test workspace discovery and reloadable local
modules, but it violates isolated-project boundaries.

### Local Extension Metadata

`DependencyUtils` maps resolved artifacts back to local extension projects and
then reads:

- the extension project's `quarkusExtension` extension;
- the configured deployment module;
- resource source directories for `META-INF/quarkus-extension.properties`;
- conditional dependencies and dependency conditions.

The new extension runtime/deployment plugins already introduced Gradle variant
contracts that should replace this live project inspection for new-plugin
paths.

### Project-Declared Dependency Metadata

`GradleProjectDependencyDeclaredDependencyCollector` walks root-project or
dependency-project configurations and reads dependencies from `api`,
`implementation`, `runtimeOnly`, and test configurations.

This exists to feed `ResolvedDependency.getDirectDependencies()` semantics,
including configured-declared direct dependencies that may not be selected into
the application graph. Those semantics are documented in
`declared-dependencies-gradle-native-design.md` and cannot be replaced blindly
with only selected Gradle graph edges.

## Consumers And Compatibility Surface

Known consumers of the tooling/application model:

- Quarkus bootstrap/devtools helpers that ask Gradle for an
  `ApplicationModel`, especially `BuildToolHelper` through
  `QuarkusGradleModelFactory`;
- Quarkus' IDE launcher path: `QuarkusLauncher` loads `IDELauncherImpl`, and
  `IDELauncherImpl` calls `BuildToolHelper.enableGradleAppModelForDevMode(...)`
  for Gradle projects;
- `AppModelGradleResolver`;
- legacy tasks and utilities still using `ToolingUtils.create(...)`;
- Quarkus Gradle integration tests that request the model through Gradle
  Tooling API;
- modular packaging and SBOM paths that rely on direct-dependency metadata.

IDE investigation result:

- current evidence does not show IntelliJ IDEA, VS Code Quarkus tooling,
  Eclipse/JBoss Tools, CodeReady Studio, or Quarkus LS requesting Quarkus'
  `ApplicationModel` through Gradle Tooling API;
- those integrations appear to detect Quarkus from dependency/classpath/project
  labels and to execute Gradle tasks or Buildship launches for actions such as
  dev mode;
- Quarkus' own IDE launcher code can still indirectly use the tooling model for
  Gradle projects, so IDE-adjacent runtime behavior remains relevant even if the
  IDE plugin does not request `ApplicationModel` itself;
- IntelliJ Ultimate is proprietary, so this is an evidence-backed assessment,
  not a formal compatibility guarantee.

Compatibility questions that remain:

- Which Quarkus bootstrap/devtools callers require the exact existing
  `io.quarkus.bootstrap.model.ApplicationModel` type?
- Which consumers need full workspace source metadata for dependency projects?
- Which consumers only need selected dependency graph, deployment classpath,
  platform imports, and extension metadata?
- Which consumers need configured-declared dependency metadata with Maven-like
  scope/optional/missing semantics?

## Hard Gates

Any proper fix must preserve the same gates that motivated the new plugin:

- no mutable model reads from dependency projects during tooling-model
  construction;
- no `rootProject.getAllprojects()` project walk for dependency metadata;
- no included-build internal project lookup for dependency metadata;
- no cross-project source-set, task, extension, configuration, or layout reads;
- no provider callbacks that mutate component metadata while dependency
  resolution is in progress;
- no task or tooling-model path that relies on `Project` objects during
  execution-like work outside the target project boundary;
- behavior must be testable under isolated projects.

For IDE/import performance, the design should also avoid configuring or
realizing unrelated projects as much as possible.

## Design Direction

The likely proper fix is not to make the legacy project walk more careful. It
is to replace cross-project mutable reads with producer-owned Gradle contracts.

### Use Existing New-Plugin Contracts Where Possible

The new application and extension plugins already define useful contracts:

- local extension runtime marker on Java `runtimeElements`;
- runtime-to-deployment dependency variant;
- deployment project marker variant;
- application package elements variants.

The tooling-model design should reuse these contracts for extension and
deployment metadata instead of inventing a parallel lookup path.

### Add Producer Metadata Artifacts Where Needed

Some data currently read from dependency projects cannot be reconstructed from
normal Java variants:

- workspace module identity and source/resource/output roots;
- build file and build directory;
- declared dependency metadata with Gradle configuration-to-scope mapping;
- possibly Quarkus-specific source groups for dev/test tooling.

If the tooling model still needs that data, dependency projects should expose
it as explicit outgoing metadata artifacts or variants. The consuming tooling
model should resolve those artifacts through Gradle dependency resolution, not
by opening the producer project's mutable model.

Candidate variant families:

- `quarkusApplicationWorkspaceMetadataElements` for application/library
  workspace metadata;
- `quarkusApplicationDeclaredDependenciesElements` for configured-declared
  dependency metadata;
- extension-specific metadata variants only if the existing extension variants
  are insufficient.

Names above are placeholders. Do not treat them as final API.

### Split Production Model From Dev/IDE Workspace Model

The current `ApplicationModel` can contain both dependency graph data and rich
workspace/source metadata. The new plugin's production model intentionally
does not walk every dependency project for source folders.

The tooling fix should decide whether there are two model shapes:

- a production/build model with selected runtime/deployment graph, platform
  imports, compile-only metadata, extension metadata, and current-project
  workspace module;
- a dev/tooling workspace model with richer local project source/resource/output
  metadata, supplied by producer-owned metadata artifacts.

If the public `ApplicationModel` type cannot distinguish those shapes cleanly,
we may need a new tooling model type or a versioned parameter.

### Prefer Opt-In Through The New Plugin Where Possible

We cannot create a clean opt-in exactly like the standalone plugin ID for every
tooling consumer, because Tooling API callers request a model by type from
whatever provider is registered.

Practical compatibility options:

- keep the existing model type and internally use the new Gradle-native path
  when `io.quarkus.application` is applied;
- add a new tooling model type or version for Gradle-native consumers, while
  leaving the legacy model provider unchanged;
- add a feature flag for experimentation, but do not rely on it as the final
  user-facing contract;
- keep legacy behavior for projects using only `io.quarkus`.

The safest near-term path is likely: new model implementation path selected
for projects using `io.quarkus.application`, with compatibility tests against
known consumers. If model semantics must change, introduce a new model
type/version rather than silently changing the existing model contract.

## Risks

- Unknown third-party consumers may assume the existing model type and fields
  are populated in legacy-specific ways.
- Removing dependency-project source roots may break source-aware dev tooling
  unless a replacement workspace metadata contract exists.
- Declared dependency semantics are not equivalent to Gradle selected graph
  edges; modular packaging and SBOM-related consumers may need Maven-like
  metadata preservation.
- Metadata variants can improve isolation but add producer-plugin requirements.
  We need a fallback story for plain Java projects and included builds that do
  not apply Quarkus-specific plugins.
- Resolving metadata artifacts during IDE import can still be expensive if the
  variants force many producer tasks. The metadata must be cheap and should not
  require packaging jars unnecessarily.

## Investigation Needed

Before writing an implementation plan, answer these questions with source and
test evidence.

1. Which fields in `ApplicationModel` are actually consumed by
   `BuildToolHelper`, `QuarkusGradleModelFactory`, `AppModelGradleResolver`,
   legacy tasks, package/build/dev/test/runtime code, and tests?
2. Are there known non-Quarkus external consumers of
   `QuarkusGradleModelFactory` or `ApplicationModel` Tooling API requests?
3. For dependency project workspace modules, which source/resource/output
   fields are required for dev mode, continuous testing, codegen, normal
   packaging, and bootstrap/devtools helpers?
4. Can the existing new-plugin `GenerateModelTask` model builder be factored
   into a reusable pure builder for tooling-model queries, or should the
   tooling model resolve a task-produced serialized model artifact?
5. What exact failure appears when requesting the current tooling model with
   isolated projects enabled in a multi-project build?
6. Can Gradle tooling model builders safely resolve producer metadata variants
   under isolated projects, and what task graph/configuration behavior results?
7. What metadata does a plain Java project dependency need to expose, and can
   it be derived from normal Java variants without applying a Quarkus plugin?
8. Which parts of `DeclaredDependency` metadata can be derived from Gradle
   resolution result, and which still require Maven effective-model/POM
   parsing?
9. How should included builds expose and consume metadata without using
   `IncludedBuildInternal`?
10. What migration story should Tooling API consumers use: same
    `ApplicationModel`, new model type, or parameterized version?

## Early Test Targets

Create reproducers before changing the implementation:

- tooling-model request for a simple single-project application;
- tooling-model request for a multi-project app with a plain Java dependency
  under isolated projects;
- tooling-model request for a multi-project app with a local Quarkus extension
  runtime/deployment pair under isolated projects;
- included-build dependency case;
- declared-dependency metadata case that includes optional/provided/missing
  semantics if possible;
- comparison between legacy tooling model and new model for fields we intend
  to preserve;
- IDE-like import smoke test if there is already an existing Quarkus Gradle
  tooling integration test harness; current evidence says mainstream IDEs do
  not request `ApplicationModel`, so this is lower priority than Quarkus-side
  Tooling API tests.

## Possible Implementation Slices

These are not yet a plan.

1. Add isolated-project TestKit/tooling API reproducers for current
   `GradleApplicationModelBuilder` failures.
2. Extract a pure model assembly component shared by new-plugin
   `GenerateModelTask` and future tooling model code.
3. Add producer-owned metadata artifact(s) for workspace and declared
   dependency data where source evidence says they are required.
4. Teach the tooling builder to use Gradle resolution results and metadata
   variants for project dependencies when `io.quarkus.application` is applied.
5. Add fallback behavior for plain Java project dependencies that uses selected
   artifacts only and does not inspect the producer project.
6. Decide whether legacy `ApplicationModel` remains the returned model type or
   whether a new tooling model/version is required.

## Related Docs

- `phase-1-consolidated-review.md`
- `phase-1-gradle-model.md`
- `new-application-plugin-design.md`
- `application-model-and-codegen.md`
- `tooling-model-consumers-investigation.md`
- `declared-dependencies-gradle-native-design.md`
- `archive/p1-ap-01-codegen-project-walk-plan.md`
