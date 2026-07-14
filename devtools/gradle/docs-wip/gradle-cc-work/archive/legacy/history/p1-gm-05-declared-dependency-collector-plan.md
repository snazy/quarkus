# P1-GM-05 Declared-Dependency Collector Plan

Status: historical
Superseded by: ../../tracker.md

## Scope

This document expands the umbrella finding `P1-GM-05` from
`phase-1-gradle-model.md`.

The collector was introduced by Quarkus PR #52226 / commit
`28a082273f1ff60da9af7eb7576b3ff6910dff06` to add declared dependency tracking
to Gradle application models. The implementation reconstructs Maven effective
models for resolved external artifacts, then records their direct declared
dependencies in the Quarkus application model.

## Current Shape

- `DependencyDataCollector` orchestrates collection for a Gradle
  `Configuration`, gathers resolved external module artifacts, invokes
  project-dependency collection, prefetches known external POMs, builds Maven
  effective models, caches declared-dependency results, and converts Maven
  dependencies into Quarkus dependency metadata.
- `GradlePomResolver` implements Gradle-backed POM lookup. It batch-resolves
  known external module POMs and, in the current local work, also batch-prefetches
  parent POMs and imported BOMs discovered during Maven effective-model
  building.
- `MavenEffectiveModelResolver` owns Maven `DefaultModelBuilder` use and adapts
  `PomResolver` to Maven's `ModelResolver`.
- The current local work removed `DeclaredDependencyExecutionStrategy`. Both
  tooling-model and Gradle-build collection now use the same `PomResolver`-
  based collector path; `GradlePomResolver` implements the prefetch/cache
  contract directly.
- The current local task path no longer has a separate declared-dependencies
  producer task. Application-model tasks keep Gradle-supported lazy
  resolution-result providers for the selected graph/artifacts, then run
  external Maven POM lookup and effective-model enrichment inside the
  application-model task action.
- `GradleProjectDependencyDeclaredDependencyCollector` isolates current Gradle
  project model inspection for root-project and project-dependency declared
  dependencies. It is structurally separate, but not yet
  isolated-projects-compatible.

## Why Maven Model Resolution Exists

Gradle's resolved dependency graph is not enough to reconstruct the direct
declared dependencies of each external Maven artifact. The collector needs
Maven effective-model semantics, including parent POMs, dependency management,
imported BOMs, properties, optional flags, scopes, and exclusions. The current
implementation therefore uses Maven's `DefaultModelBuilder` for correctness and
uses Gradle only to obtain POM files from the build's configured repositories.

## Main Problems

- Maven model building still receives broad provider-backed system properties
  during declared-dependency computation. Maven does not define a generally
  safe narrow set of model-relevant system properties, but exposing the full raw
  system-property map as a stable Gradle task/cache input would make cache
  artifacts non-portable and could record sensitive values. The goal is to use
  the computed declared-dependency snapshot as the stable modeled boundary.
- Parent POMs and imported BOM POMs discovered while building Maven effective
  models are batch-resolved through Gradle during application-model task
  execution, but are not modeled as first-class build-cache key inputs because
  `QuarkusApplicationModelTask` remains deliberately not build-cacheable.
- Gradle project-dependency inspection still reads mutable project model state.
- Tooling-model and task paths now share more collector logic than before. The
  remaining compatibility boundary is task wiring and project-dependency
  metadata, not a separate async execution strategy.

## Phase Split

### `P1-GM-05A`: Document and test current behavior

Status: completed locally by consolidated commit `1cf23bfe07a` (`Gradle:
Rework declared dependency collection`).

Add focused coverage for the behavior the collector must preserve before
changing architecture.

Suggested scope:

- Parent POM inheritance.
- Imported BOM dependency management.
- Property-substituted dependency versions.
- Optional dependencies and scopes.
- Unresolved POM fallback behavior.
- Project dependency handling, if a lightweight test can cover it without
  TestKit.

This phase should avoid architecture changes.

This phase is no longer intended as a standalone test-only PR. Its coverage is
part of the consolidated collector refactor PR.

### `P1-GM-05B`: Isolate Maven effective-model building from Gradle POM lookup

Status: completed locally by consolidated commit `1cf23bfe07a` (`Gradle:
Rework declared dependency collection`).

Introduce small abstractions so Maven model building can be tested without
Gradle.

Possible interfaces:

- `PomResolver`: resolves POM sources by GAV.
- `EffectiveModelResolver`: builds Maven effective models from POM sources.

The Maven `ModelResolver` adapter should sit behind these interfaces instead of
being mixed directly into `DependencyDataCollector`.

### `P1-GM-05C`: Batch Gradle-backed POM resolution

Status: completed locally by consolidated commit `1cf23bfe07a` (`Gradle:
Rework declared dependency collection`).

Let the Gradle-backed POM resolver resolve as many known POMs as possible in
one operation.

Suggested behavior:

- Batch-resolve the initial module GAVs collected from resolved artifacts.
- Cache successful and missing POM lookups.
- Historical first slice: keep a single-GAV fallback for parent POMs or
  imported BOMs discovered while Maven model building is already in progress.
- Committed local follow-up: `P1-GM-05E4` replaced that fallback path with
  iterative batch lookup for newly discovered parents/imports.

### `P1-GM-05D`: Abstract execution strategy

Status: completed locally by consolidated commit `1cf23bfe07a` (`Gradle:
Rework declared dependency collection`), then superseded by the current local
`P1-GM-05E4/E5` cleanup which removed `DeclaredDependencyExecutionStrategy`.

Remove the direct dependency on `CompletableFuture.runAsync` from collection
logic.

Suggested shape:

- Tooling-model execution strategy: local synchronous or controlled executor
  execution, with clear limits on Gradle API access.
- Gradle-native execution strategy: a placeholder that can later delegate to
  Worker API once inputs are modeled.

This phase should not claim configuration-cache compatibility by itself.

Current state: there is no execution-strategy abstraction. Batched POM lookup
made the previous tooling/build split unnecessary, and the Gradle-build path
uses normal caller-thread task execution until a later Worker API decision has
serializable parameters and no Gradle API calls behind the boundary.

### `P1-GM-05E`: Model task-path inputs and Gradle-compatible execution

Status: implemented locally by commit `1e8ed6feb14` (`Remove Gradle declared
dependency producer tasks`). This M1 containment work supersedes the earlier
committed producer-task experiment. Commits `baa96d0ffda`, `d567695bf6b`, and
`4871f87cecb` were useful stepping stones for modeling external Maven inputs,
batching parent/import POM lookup, and proving that producer-task boundaries
can make Maven work explicit. Follow-up dry-run/configuration-cache regression
work showed that the producer task still exposed resolution-backed task state
to graph calculation and that the desired end state does not need a separate
declared-dependencies producer task.

Implemented local direction:

- remove `QuarkusDeclaredDependenciesTask`;
- remove `enableDeclaredDependencyCollector`;
- keep Gradle-selected graph/artifact access modeled with Gradle-supported
  lazy providers, especially `ResolutionResult.getRootComponent()` and
  `ArtifactCollection.getResolvedArtifacts()`;
- track deployment artifacts for local up-to-date checks with a Gradle-native
  classpath file input rather than a custom timestamp/size snapshot provider;
- run external Maven POM lookup and Maven effective-model enrichment inside
  `QuarkusApplicationModelTask` task execution;
- precompute project-dependency declared metadata without resolving the
  deployment configuration, leaving the cross-project model-read problem under
  `P1-GM-03`;
- keep `QuarkusApplicationModelTask` non-build-cacheable and do not expose
  broad Maven model system properties as cache-key inputs.

The previous goal of moving task-path declared-dependency collection onto a
serializable producer-task input boundary is no longer the active plan. M1 keeps
declared enrichment inside application-model task execution. M2 is now tracked
as broader build-tool-agnostic dependency model work rather than as another
Gradle-only declared-dependencies producer task.

Preserved constraints:

- Do not pass `Project`, `Configuration`, or live Gradle model objects into
  execution.
- Model the resolved artifact/POM input set explicitly.
- Use Worker API or another Gradle-compatible execution boundary only after
  inputs are serializable and complete.
- Treat broad Maven model system properties as possible inputs to Maven
  effective-model computation, because Maven profile activation and model
  interpolation can depend on arbitrary system properties.
- Do not expose the full raw system-property map as a stable Gradle task/cache
  input. Use the computed declared-dependency snapshot as the stable modeled
  boundary unless a concrete, well-known Maven property needs direct modeling
  later.
- See the focused [P1-GM-05E modeled task inputs plan](p1-gm-05e-modeled-task-inputs.md).

Remaining scope:

- Reassess Worker API only if a future task boundary has serializable
  parameters and no worker code needs Gradle API access.
- Do not model the full recursive effective-model POM closure as a cache key
  while `QuarkusApplicationModelTask` remains non-cacheable.
- Preserve the distinction between a plain dry-run, which must not resolve
  deployment configurations, and a configuration-cache dry-run, where Gradle
  may materialize supported resolution-result task inputs while storing the
  task graph.
- Do not solve graph-calculation regressions with `StartParameter.isDryRun()`
  branches. See
  [declared dependencies Gradle-native design](../../../declared-dependencies-gradle-native-design.md).
- Keep M2 explicit as a follow-up tied to the broader build-tool-agnostic
  dependency model. Gradle-native `quarkusDev` should reference that work, but
  remains a separate larger dev-mode/continuous-build effort and is not part of
  `P1-GM-05`.

### `P1-GM-05F`: Separate project-dependency declared dependency collection

Status: completed locally by consolidated commit `1cf23bfe07a` (`Gradle:
Rework declared dependency collection`) for the structural separation step. The
isolated-projects-compatible replacement for cross-project mutable model reads
remains a follow-up under `P1-GM-03`.

External Maven artifact collection and Gradle project dependency collection
have different compatibility constraints.

Completed scope:

- Moved root-project and project-dependency declared dependency collection into
  a dedicated `GradleProjectDependencyDeclaredDependencyCollector`.
- Kept external Maven artifact POM/effective-model processing in
  `DependencyDataCollector`.
- Added ProjectBuilder coverage for a root project consuming a subproject and
  preserving declared `api`, `implementation`, and `runtimeOnly` dependencies.

Remaining scope:

- Replace cross-project mutable model reads with variant metadata or another
  isolated-projects-compatible contract.
- Coordinate with `P1-GM-03`, because both findings involve project dependency
  traversal.

## Suggested Ordering

Submit `P1-GM-05A-D/F` together as one PR-facing collector refactor. The
previous split was useful for local risk reduction, but the public review story
is clearer as one change that adds behavior coverage and then performs the
mechanical structural separation.

Treat the earlier `P1-GM-05E1/E2`, `P1-GM-05E3`, and `P1-GM-05E4/E5`
producer-task slices as local learning. The active reviewable slice is commit
`1e8ed6feb14`, the M1 containment change that removes the
declared-dependencies producer task and folds enrichment into application-model
task execution.

Keep Worker API, recursive POM cache-key modeling, and Gradle-native selected
graph replacement as later follow-up. The semantic M2 follow-up should be owned
by the broader build-tool-agnostic dependency model design and should build on
the `PomResolver` prefetch/cache contract only where Maven-declared enrichment
is still explicitly required.
