# Dry-Run Resolution Inventory

Date: 2026-07-05

Status: evidence
Current design:
[../../declared-dependencies-gradle-native-design.md](../../../declared-dependencies-gradle-native-design.md)

This inventory captures dry-run evidence and rejected intermediate production
branches from before the branch rewrite. The current branch folds the relevant
coverage and M1 containment into `f56491a335f` (`Rework Gradle application model
task wiring`).

Owner / audience: Gradle configuration-cache workstream

## Scope

This note tracks Gradle plugin patterns where `--dry-run` can resolve deployment
configurations while Gradle is only calculating the task graph.

The intended behavior must stay unchanged:

- dry-run/task graph calculation must not resolve deployment configurations;
- real task execution must still resolve and fingerprint the deployment
  classpath and generate the same application models;
- configuration-cache reuse after a dry-run must not reuse partial task state
  for a real build;
- production code should not branch on `StartParameter.isDryRun()` to produce a
  different task shape.

## Current Decision

The previous local containment fix used `StartParameter.isDryRun()` branches and
marked the partial dry-run graph as not compatible with the configuration cache.
That approach was rejected.

Current local state:

- regression tests remain and assert that `--dry-run` does not resolve the
  prod/test deployment configurations;
- production `StartParameter.isDryRun()` branches have been removed again from
  `ApplicationModelTaskConfigurator` and the removed
  `QuarkusDeclaredDependenciesTask`;
- no `isDryRun()` call sites remain under `devtools/gradle`;
- the implemented M1 containment fix removes the declared-dependencies producer task,
  removes the opt-in flag, and runs external Maven declared-dependency
  enrichment from `QuarkusApplicationModelTask` execution.

Additional local M1 spike evidence:

- Removing `QuarkusDeclaredDependenciesTask` and folding external declared
  POM/effective-model collection into `QuarkusApplicationModelTask.execute()`
  removes the external-Maven provider from configuration-cache storage.
- Project-declared dependency metadata also must not be backed by a provider
  that touches `classpath.getDeploymentConfiguration().getIncoming().getArtifacts()`;
  configuration-cache serialization of that provider resolves deployment
  configurations during dry-run.
- Even after that provider is removed, app-model task state still needs
  selected graph/artifact data. The supported configuration-cache-compatible
  shape is to expose Gradle's lazy `Provider<ResolvedComponentResult>`,
  `Provider<Set<ResolvedArtifactResult>>`, and file-collection inputs, not live
  `Configuration` or `ArtifactCollection` instances.
- Replacing those providers with live `Configuration` references is not viable:
  Gradle rejects `Configuration` objects as disallowed configuration-cache task
  state.
- Gradle documentation states that, when configuration cache is enabled,
  dependency graph and artifact resolution are completed while Gradle stores the
  task graph, because the resolved state is needed for serialization. Therefore
  a dry-run with configuration cache can still legitimately materialize modeled
  dependency-resolution task inputs. The open design question is whether the
  current Quarkus deployment configuration resolution is an avoidable side
  effect of the app-model task shape, not whether Gradle can generally defer
  modeled resolution-result inputs until `@TaskAction`.

## Inventory

### `DRYRUN-01`: Application model task wiring can resolve during dry-run

- Component: `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/tasks/ApplicationModelTaskConfigurator.java`.
- Pattern: application-model task properties are wired from Gradle resolution
  views such as deployment artifacts and resolution result roots.
- Risk: when Gradle realizes and fingerprints the task graph for dry-run and/or
  configuration-cache storage, these providers can resolve deployment
  configurations even though no task action will run.
- Regression coverage: plugin-side TestKit coverage asserts that `test
  --dry-run` skips model/codegen tasks and does not resolve the prod/test
  deployment configurations.
- Fix direction: keep the task boundary on Gradle-supported lazy
  resolution-result providers and file collections. Do not serialize
  `Configuration`, `ArtifactCollection`, or `Project` objects as task state.
  Keep a plain dry-run without configuration cache as the no-deployment-
  resolution gate; use configuration-cache dry-run only to prove the stored
  graph does not break a later real build.

### `DRYRUN-02`: Declared dependency producer task wiring can resolve during dry-run

- Component: `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/tasks/QuarkusDeclaredDependenciesTask.java`.
- Pattern: `configureFrom(Configuration)` wires a live `ArtifactCollection` and
  a POM artifact view from the deployment configuration.
- Risk: the producer task is skipped during dry-run, but the wiring can still
  give Gradle resolution-backed task state to inspect while calculating the
  graph or storing configuration cache.
- Status: resolved by current M1 containment, which removes
  `QuarkusDeclaredDependenciesTask`.
- Regression coverage: covered by the same dry-run deployment-resolution
  marker test plus the absence of `quarkusDeclaredDependencies` tasks.
- Follow-up direction: do not reintroduce a declared-dependencies producer task
  unless a later design gives it a clear Gradle-native role.

### `DRYRUN-03`: Deployment classpath snapshot provider resolves artifacts when queried

- Component: `ApplicationModelTaskConfigurator` wiring for
  `deploymentClasspathSnapshot` and
  `QuarkusApplicationModelTask.deploymentClasspathSnapshot(...)`.
- Pattern: an `@Input` provider computes a scalar deployment artifact snapshot
  from `ArtifactCollection.getArtifacts()`.
- Current behavior: useful for real local up-to-date behavior, but unsafe if
  queried during dry-run.
- Risk: removing the old dry-run branch exposes this provider during dry-run
  again. The production fix should avoid returning to dry-run-specific task
  shapes.
- Status: resolved by current M1 containment, which removes this scalar
  snapshot input and replaces it with a Gradle-native deployment classpath file
  input for local up-to-date checks.
- Follow-up direction: do not reintroduce a custom provider that calls
  `getArtifacts()` to produce scalar task inputs.

### `DRYRUN-04`: Existing provider-backed resolution findings are broader than dry-run

- Components:
  - `ApplicationDeploymentClasspathBuilder.resolvePlatformSpec()` /
    `resolvePlatformDependencies()`;
  - `QuarkusComponentVariants`;
  - project-dependency tooling model traversal.
- Pattern: provider-backed configuration resolution or cross-project model
  access.
- Current status: already tracked as active findings, especially `P1-GM-01` and
  `P1-GM-03`.
- Risk: these may still affect configuration-cache or isolated-projects
  compatibility, but they should not be hidden behind `isDryRun()` checks.
- Fix direction: keep the broader resolution redesign under the active finding
  IDs.

## Regression Tests

Current local regression coverage:

- plugin-side TestKit coverage under
  `TasksConfigurationCacheCompatibilityTest.dryRunDoesNotResolveDeploymentConfigurationsAndConfigurationCacheDryRunDoesNotPoisonRealBuild`;
- integration coverage under `JavaPlatformWithEagerResolutionTest`.

The tests should keep asserting:

- plain dry-run of `test` with configuration cache disabled skips generated
  model/codegen tasks;
- plain dry-run does not resolve prod/test deployment configurations;
- configuration-cache dry-run may store a cache entry;
- a later real `test` build with configuration cache still succeeds and runs
  the model-generation tasks when needed.

The tests should not assert that Gradle discards the configuration-cache entry.
That was the rejected containment behavior, not the desired final design.

## Open Design Work

See `declared-dependencies-gradle-native-design.md`.

Remaining M2 design work is broader than dry-run and should be tied to the
build-tool-agnostic dependency model effort. It should answer:

- how to avoid dependency graph resolution, artifact inspection, POM lookup,
  and Maven effective-model building during configuration;
- how to keep `--dry-run` as a regression gate without centering production
  design on dry-run-specific branches;
- how to preserve declared-dependency semantics required by modular packaging
  while moving selected graph/artifact state behind Gradle-native task
  boundaries.
- how that shared model can later support, but not include, a Gradle-native
  `quarkusDev` / continuous-build rewrite.

## Verification

Regression commands:

- `./gradlew :gradle-application-plugin:test --tests 'io.quarkus.gradle.tasks.TasksConfigurationCacheCompatibilityTest' --configuration-cache --stacktrace`
- `./mvnw -f integration-tests/gradle/pom.xml -Dtest=JavaPlatformWithEagerResolutionTest test`

Expected current status: these tests define the contract. If they fail after
the dry-run branches are removed, fix the task/resolution design rather than
reintroducing `StartParameter.isDryRun()` branching.
