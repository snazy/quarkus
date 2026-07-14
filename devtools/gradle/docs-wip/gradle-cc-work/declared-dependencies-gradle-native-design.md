# Declared Dependencies Gradle-Native Design

Date: 2026-07-05

Status: updated after M1 implementation; M2 is explicit follow-up under the
broader build-tool-agnostic dependency model work.

Owner / audience: Gradle configuration-cache workstream

Related:

- [declared-dependencies Gradle-native investigation](archive/legacy/evidence/declared-dependencies-gradle-native-investigation.md)
- [dry-run resolution inventory](archive/legacy/evidence/dry-run-resolution-inventory.md)
- [P1-GM-05 declared-dependency collector plan](archive/legacy/history/p1-gm-05-declared-dependency-collector-plan.md)
- [P1-GM-05E modeled task inputs](archive/legacy/history/p1-gm-05e-modeled-task-inputs.md)

## Problem Statement

The core problem is not `--dry-run`.

The Gradle-native goal is:

- Gradle plugin configuration stays declarative and cheap;
- dependency graph resolution, artifact inspection, POM lookup, and Maven
  effective-model building do not run during the configuration phase;
- task inputs and task actions own work that depends on resolved graphs or
  artifacts;
- `--dry-run`, `help`, `tasks`, and similar task-graph-only builds are
  regression gates proving configuration does not accidentally resolve
  deployment configurations.

Production code should not solve this with `StartParameter.isDryRun()` task
shape branches. Those branches hide the configuration-phase resolution problem
instead of fixing the task/provider boundary.

## 2026-07-06 M1 Result

Milestone 1 is implemented in the rewritten branch by commit `f56491a335f`
(`Rework Gradle application model task wiring`).

The implemented M1 removes `QuarkusDeclaredDependenciesTask` and
`enableDeclaredDependencyCollector`, then folds declared-dependency enrichment
into `QuarkusApplicationModelTask`.

The spike leading to this implementation showed that this was only viable once
the application-model task resolution boundary was redesigned at the same time:

- Moving external Maven POM/effective-model collection from task-input provider
  wiring into `QuarkusApplicationModelTask.execute()` removes one
  configuration-cache input problem. Broad Maven system properties are then read
  during task execution, not while Gradle stores the configuration-cache entry.
- The first remaining dry-run resolver stack came from
  `ApplicationModelTaskConfigurator` setting `getDeclaredDependencies()` from a
  provider that called
  `DependencyDataCollector.collectProjectDeclaredDependencies(project,
  classpath.getDeploymentConfiguration(), ...)`. Configuration-cache
  serialization of the `MapProperty` forced that provider and resolved the
  deployment artifact collection.
- Precomputing only project-declared metadata from the mutable project model
  avoids that specific provider-resolution stack, but it is not enough.
- The next dry-run resolver stack comes from configuration-cache serialization
  of `QuarkusApplicationModelTask` classpath state: Gradle encodes
  `ArtifactCollection` values held by the task's classpath wrappers. Resolving
  the compile-only configuration also asks Gradle for deployment-configuration
  consistency locks, which resolves the deployment configuration during
  dry-run.
- A spike that stored `Configuration` objects on the task and resolved them
  only in `@TaskAction` was rejected by Gradle configuration cache:
  `Configuration`/`DefaultResolvableConfiguration` is a disallowed serialized
  task state type.
- Gradle's own lifecycle documentation says that, with configuration cache
  enabled, dependency graph resolution and artifact resolution must be completed
  while Gradle stores the task graph because that state is needed for
  serialization. Gradle 7.5 added dependency resolution results as supported
  task inputs for exactly this reason: the supported configuration-cache shape
  is to model resolution results as task state, not to perform undeclared
  dependency resolution in `@TaskAction`.

Conclusion: removing the declared-dependencies producer task and flag can be
part of Milestone 1, but M1 also has to use Gradle's supported lazy
resolution-result inputs for the application-model task boundary. Gradle's
documented configuration-cache-compatible shape is:

- `ResolutionResult.getRootComponent()` for a
  `Provider<ResolvedComponentResult>`;
- `ArtifactCollection.getResolvedArtifacts()` for a
  `Provider<Set<ResolvedArtifactResult>>`;
- file collections from Gradle resolution views when file content is the
  relevant task input.

The supported shape is not to retain `Configuration`, `ArtifactCollection`,
`Project`, or already-realized resolved result objects in task state. The M1
implementation follows that boundary and changes the test contract accordingly:
a plain `--dry-run --no-configuration-cache` remains the "do not resolve
deployment configurations" gate, while a configuration-cache dry-run is allowed
to materialize Gradle-supported resolution-result inputs while storing the task
graph, as long as it does not poison a later real build.

M1 is therefore a containment and Gradle-phase-correctness milestone. It does
not solve the larger dependency-model semantic question.

## Historical Context

Relevant chronology:

- [PR #43139](https://github.com/quarkusio/quarkus/pull/43139) / commit
  [`94f0c3f0740`](https://github.com/quarkusio/quarkus/commit/94f0c3f07405e6d01e43f58b21c41e50cd145103):
  introduced Quarkus-specific CycloneDX SBOM generation. This established that
  standard dependency tooling does not fully represent Quarkus' effective
  build/runtime graph.
- [PR #44870](https://github.com/quarkusio/quarkus/pull/44870) / commit
  [`065d8cfde0c`](https://github.com/quarkusio/quarkus/commit/065d8cfde0c6e3583dfe9a8bd072052736757f6e):
  fixed a concrete SBOM relationship bug where the main application component
  did not record direct dependencies.
- [PR #52079](https://github.com/quarkusio/quarkus/pull/52079) / commit
  [`b6572c675ed`](https://github.com/quarkusio/quarkus/commit/b6572c675ed):
  introduced
  `ResolvedDependency.getDirectDependencies()`. The API distinguishes resolved
  direct dependencies present in the app model from configured declared direct
  dependencies, including optional/provided/excluded dependencies that may be
  absent from the application and marked `MISSING_FROM_APPLICATION`.
- [PR #52503](https://github.com/quarkusio/quarkus/pull/52503) / commit
  [`62efb111f3d`](https://github.com/quarkusio/quarkus/commit/62efb111f3d):
  ensured direct dependencies survive application-model serialization.
- [PR #52226](https://github.com/quarkusio/quarkus/pull/52226) / commit
  [`28a082273f1`](https://github.com/quarkusio/quarkus/commit/28a082273f1ff60da9af7eb7576b3ff6910dff06):
  added Gradle support for declared dependency tracking in the application
  model. It resolves POMs through Gradle, builds Maven effective models,
  collects declared dependencies, and maps them back onto the Gradle-built
  application model. The PR documents opt-in usage with
  `-PenableDeclaredDependencyCollector=true`.
- [PR #53140](https://github.com/quarkusio/quarkus/pull/53140) / commit
  [`c194c85b5b4`](https://github.com/quarkusio/quarkus/commit/c194c85b5b47b7ed6ce1c9ea06f1c39211badd63)
  and [issue #51583](https://github.com/quarkusio/quarkus/issues/51583):
  introduced extension-based modularity. Modular packaging consumes
  `getDirectDependencies()` to compute automatic module dependencies.
- Local work in this branch reworked the Gradle collector into more explicit
  and cacheable pieces while preserving
  [PR #52226](https://github.com/quarkusio/quarkus/pull/52226) semantics.

The original use-case is therefore broader than SBOM display. Quarkus
application-model consumers need to know both:

- which dependencies were selected into the application model; and
- which dependencies each component declared directly for the relevant launch
  mode, including optional/provided/excluded dependencies that did not become
  application dependencies.

## Current Semantics

`ResolvedDependency.getDependencies()` means resolved direct dependency
coordinates that are present in the application model.

`ResolvedDependency.getDirectDependencies()` means configured declared direct
dependencies for the component, excluding test dependencies of transitive
dependencies, and including missing/filtered dependencies marked with
`DependencyFlags.MISSING_FROM_APPLICATION`.

The Maven resolver builds both lists in `ApplicationDependencyResolver`:

- declared dependency scope and optional are copied from Maven/Aether
  dependency metadata;
- declared dependencies not present in the application model are retained with
  `MISSING_FROM_APPLICATION`;
- declared dependencies present in the app model inherit the selected version
  and app-model flags.

The Gradle collector mirrors this in `DependencyDataCollector.setDirectDeps`.
It follows Gradle's selected module graph for the components being enriched,
but uses Maven effective-model parsing to recover data Gradle does not expose
directly in the needed form: declared POM dependency edges, Maven scopes, and
optional markers.

## Consumers

### SBOM

`CoreSbomContributionConfig` uses `getDependencies()` to mark top-level SBOM
components and emit SBOM dependency edges.

Current SBOM behavior mostly needs selected direct edges. A Gradle
`ResolutionResult`-derived selected graph could likely satisfy this consumer if
the per-component selected parent/child edges are reconstructed correctly.

This is important but not the hardest semantic constraint.

### Dev UI and Diagnostics

The Dev UI dependency graph and Maven resolver dependency logging use
`getDependencies()` to display or log selected dependency edges.

These consumers can likely tolerate Gradle selected graph edges.

### Modular Packaging

`ModularitySteps.computeAutomaticDependencies(...)` consumes
`getDirectDependencies()` to synthesize module dependencies for automatic
modules.

It relies on configured-declared semantics:

- skips `MISSING_FROM_APPLICATION` dependencies;
- filters non-runtime dependencies;
- checks Maven scopes such as `compile`, `provided`, and `runtime`;
- propagates optionality from declared dependencies and provided scope into
  generated module dependency metadata.

Replacing this with only Gradle selected graph edges would be a semantic change.
It would lose optional/provided/missing declared-edge information and could
generate overly strong, missing, or otherwise wrong module dependency metadata.

Modular packaging is the hard consumer that justifies preserving
Maven-effective-model-like declared dependency metadata unless Quarkus accepts a
deliberate semantic change.

## Why Maven Effective Models Exist

Gradle's selected dependency graph answers "what was selected and why in this
build graph." That is necessary and should remain authoritative for selected
versions and artifacts.

It does not fully answer "what did this Maven component declare directly in its
effective POM, including optional/provided/excluded dependencies that are not in
the selected application graph."

The regression tests added with
[PR #52226](https://github.com/quarkusio/quarkus/pull/52226) prove the gap:

- a dependency declared by `lib-a` but absent from the application must still
  appear in `getDirectDependencies()` with `MISSING_FROM_APPLICATION`;
- test-scope root dependencies appear only in the test application model;
- test dependencies of transitive dependencies do not appear;
- normal model generation must not leak test dependencies after test-model
  generation.

Therefore replacing Maven effective-model declared-dependency collection with
only `ResolutionResult` direct edges is not a mechanical cleanup. It is a
behavior change.

## Semantic Drift Risk

There is an additional orthogonal risk: the current Gradle collector enriches a
Gradle-selected application model with Maven-effective-model declared metadata.

That hybrid shape is intentional, but it is not free:

- Gradle has many selection mechanisms: platforms, enforced platforms,
  constraints, dependency substitution, capabilities, attributes, variants,
  component metadata rules, rich versions, conflict resolution, excludes, and
  user resolution strategy actions.
- Maven effective-model building has different semantics. Maven dependency
  mediation is much narrower, and Maven model building cannot represent every
  Gradle selection rule.
- The current collector maps Maven-declared direct dependencies back onto the
  Gradle-selected application model. Present dependencies inherit the
  Gradle-selected version and flags; missing dependencies are retained as
  declared-but-missing.

This means the selected artifact graph must remain Gradle-authoritative for
Gradle builds. Producing SBOM relationships, Dev UI dependency graphs, or
modular packaging metadata from a Maven-resolved graph would be wrong when
Gradle selection differs.

The safe interpretation is:

- use Gradle resolution for selected components, versions, variants, artifact
  files, and selected parent/child edges;
- use Maven effective models only to enrich selected Maven components with
  declared direct-dependency metadata that Gradle does not expose directly;
- document that declared metadata is best-effort enrichment, not an alternate
  dependency resolver;
- avoid letting Maven model resolution produce a dependency graph that competes
  with Gradle's selected graph.

This risk matters for all current consumers:

- SBOM: wrong selected relationships or versions would be a supply-chain
  correctness bug.
- Dev UI: wrong dependencies would mislead users while diagnosing their build.
- Modular packaging: wrong declared/selected mapping could generate incorrect
  module dependency metadata.

Future designs should therefore reduce the Maven-model surface, not expand it.
The next semantic step should be tied to the broader build-tool-agnostic
dependency model work, so Maven, Gradle, and future build integrations can share
clear selected-graph and metadata contracts instead of each plugin inventing a
parallel dependency model.

## Related Public Efforts

This work overlaps with several public Quarkus 4 / working-group / tooling
efforts:

- The public [Quarkus working-groups overview](https://quarkus.io/working-groups/)
  and [Quarkus 4 roadmap discussion](https://github.com/quarkusio/quarkus/discussions/52020)
  place JPMS/JLink, Java 21/25, Leyden AOT, extension restructuring, and
  CLI/re-augmentation in the Quarkus 4 orbit.
- Quarkus 4 working group reports for
  [January 2026](https://github.com/quarkusio/quarkus/discussions/52231) and
  [February 2026](https://github.com/quarkusio/quarkus/discussions/52713) list
  JPMS support and modular packaging, tooling for multiple platforms, Java 21
  minimum-version work, and Vert.x 5 / Netty 4.2 as active Quarkus 4 items.
  Modular packaging is directly relevant because it is the strongest current
  consumer of declared direct-dependency metadata.
- The [Modularity / JPMS / JLink working group](https://github.com/quarkusio/quarkus/discussions/53223)
  explicitly targets fully modular Quarkus applications, first-class `jlink`
  packaging, generated-class module ownership, service-loading boundaries,
  container layering, and modular dev mode. Gradle app-model work must not
  produce a dependency model that fights that direction.
- The [modularization tracking epic #51583](https://github.com/quarkusio/quarkus/issues/51583)
  records that automatic module graphs derived from Maven dependency
  information can add too many edges and memory overhead. That reinforces the
  need to keep Gradle-selected graph data separate from Maven-declared
  enrichment.
- The February 2026 working-group report notes Quarkus 4 roadmap/tracking and
  AOT/Leyden progress. The report also mentions a new
  `buildAotEnhancedImage` Gradle task, which is a reminder that Gradle task
  modeling for new packaging modes will keep mattering.
- The [quarkus-dev mailing list](https://groups.google.com/g/quarkus-dev)
  shows the project moved `main` toward Quarkus 4 work in late June 2026,
  making Quarkus 4 a plausible window for larger Gradle plugin boundary
  changes.
- The [Quarkus Config and IDEs discussion](https://github.com/quarkusio/quarkus/discussions/42671)
  explicitly suggests a Tooling API that can load a Quarkus project model with
  the actual application build classpath and metadata. That aligns with making
  selected graph and metadata boundaries explicit.
- [Gradle ApplicationModel modernization issue #49335](https://github.com/quarkusio/quarkus/issues/49335)
  is the closest public Gradle-specific app-model design anchor. It describes
  the split between the old `GradleApplicationModelBuilder` path and the newer
  `QuarkusApplicationModelTask` introduced for configuration-cache
  compatibility, plus the missing workspace-discovery/project-isolation story.
- The [Gradle configuration-cache discussion #52506](https://github.com/quarkusio/quarkus/discussions/52506)
  explicitly identifies configuration-time effective-config/system-property
  reads as cache-hostile and proposes moving values to execution or modeling
  them as explicit inputs. The same principle applies here to dependency graph
  resolution and Maven model work.
- Gradle issues [#49813](https://github.com/quarkusio/quarkus/issues/49813),
  [#46682](https://github.com/quarkusio/quarkus/issues/46682),
  [#39218](https://github.com/quarkusio/quarkus/issues/39218), and
  [#43576](https://github.com/quarkusio/quarkus/issues/43576) are relevant
  guardrails for test execution behavior, live `Project` state under
  configuration cache, missing build-cache inputs, and dev-mode/source-set
  modeling.
- The [first-class Bazel discussion #54762](https://github.com/quarkusio/quarkus/discussions/54762)
  is not a Gradle issue, but it reinforces a broader theme: Quarkus build
  integration should not assume Maven or Gradle plugin execution as the only way
  to materialize an application model. Clear selected-graph and metadata
  boundaries help other build tools too.

Dev mode is adjacent but out of scope for this workstream. Gradle `quarkusDev`
still relies on Quarkus-side model/dev-mode machinery that includes Maven-model
assumptions. Moving Gradle dev mode toward a Gradle-native continuous-build
based run is a larger effort that should reference the same build-tool-agnostic
dependency model work, but it is not part of M1 or M2 here.

Open gap: no public Quarkus 4 working-group item found so far is specifically
named "Gradle project isolation." The closest public anchors are the Gradle
ApplicationModel modernization issue and configuration-cache discussions linked
above.

## Current Gradle-Native Risks

Current local risks are about when work happens and what live Gradle/project
objects are captured:

- `ApplicationModelTaskConfigurator` still precomputes project declared
  dependency metadata from the mutable project graph. The current M1 code avoids
  deployment-configuration resolution there, but the cross-project model access
  remains a `P1-GM-03` project-isolation problem.
- `QuarkusApplicationModelTask` must keep selected graph/artifact state on
  Gradle-supported lazy provider/file-collection boundaries. Reintroducing
  stored `Configuration`, `ArtifactCollection`, or live `Project` state would
  regress configuration-cache compatibility.
- `DependencyDataCollector` still builds Maven effective models and asks
  `GradlePomResolver` for parent/imported BOM POMs. That is acceptable as task
  work when the collector is enabled, but not as configuration work and not as a
  competing selected dependency graph.
- Component-variant and deployment-configuration fallback providers still have
  provider-backed resolution logic that is fragile if queried during
  configuration.

## Design Options

### Option A: Selected Graph Sidecar

Introduce a task-owned sidecar that serializes the selected Gradle graph and
selected artifact metadata for a launch mode.

Inputs:

- Gradle-native resolution result provider;
- artifact views/file collections for selected runtime/deployment/compile-only
  artifacts;
- launch mode and app/project metadata.

Output:

- serialized selected graph/artifact metadata consumed by
  `QuarkusApplicationModelTask` and `QuarkusDeclaredDependenciesTask`.

Benefits:

- centralizes selected-graph walking in one task;
- makes application-model generation consume files rather than live Gradle
  resolution objects;
- gives dry-run/help/tasks a clear success criterion: registering tasks must
  not query the graph sidecar inputs;
- preserves Gradle as selected-version/artifact authority.

Costs:

- new intermediate output and migration complexity;
- must avoid merely moving configuration-time `.get()` calls into the sidecar
  task registration;
- needs careful serialization of enough graph information for existing app
  model generation.

Assessment: possible local Gradle implementation detail, but no longer the
primary M2 framing. If introduced later, it should serve the broader
build-tool-agnostic dependency model contract rather than becoming another
Gradle-only application-model format.

### Option B: Keep `QuarkusDeclaredDependenciesTask`

Keep a producer task for external Maven declared-dependency metadata as a
transitional boundary while Maven-declared metadata is still needed.

Benefits:

- preserves `getDirectDependencies()`, optional/scope, and
  `MISSING_FROM_APPLICATION` semantics;
- isolates expensive Maven effective-model work;
- can remain opt-in and cacheable;
- naturally consumes the selected graph sidecar or module list instead of
  re-deriving module inputs from live configuration access.

Costs:

- Maven effective-model work remains;
- recursive parent/import POM cache-key modeling remains pragmatic unless
  improved later;
- project-dependency declared dependencies still need the `P1-GM-03` metadata
  boundary.

Assessment: this was useful as a local experiment, but is no longer the current
M1 recommendation. It made Maven work explicit but also added another
resolution-backed task boundary and did not match the preferred final state.
The current containment path removes it and runs Maven-declared enrichment in
the application-model task action while the app-model task already owns the
selected graph.

### Option C: Use `ResolutionResult` Only

Use Gradle's selected graph as the only source of direct dependency edges.

Benefits:

- most Gradle-native;
- likely sufficient for SBOM selected edges, Dev UI, and diagnostics;
- removes Maven model cost.

Costs:

- loses configured declared deps that are absent from the application;
- loses Maven optional/provided/scope semantics needed by modular packaging;
- would require new compatibility decisions and tests documenting behavior
  loss.

Assessment: not acceptable as the default replacement unless Quarkus explicitly
accepts a semantic change, likely not before modular packaging requirements are
revisited.

### Option C2: Final Gradle-Native Dependency Model

Use Gradle's selected graph and Gradle-modeled metadata to feed a
build-tool-agnostic Quarkus dependency model for all known application-model
consumers.

Expected final shape:

- SBOM uses Gradle-selected components, versions, artifacts, and selected
  direct edges.
- Dev UI uses the same Gradle-selected graph for effective dependency display.
- Modular packaging uses Gradle-native selected graph/module metadata, or its
  requirements are adjusted so it no longer needs Maven-declared
  optional/provided/missing-edge semantics for Gradle builds.
- `QuarkusDeclaredDependenciesTask` remains removed.
- `enableDeclaredDependencyCollector` remains removed.
- Maven-specific declared dependency semantics are either represented in the
  shared dependency model as explicit metadata or scoped to Maven builds.

Assessment: this is the preferred final direction if Quarkus 4 work can make
the consumers accept build-tool-authoritative semantics. It is not an M1 fix
because current modular packaging behavior still expects Maven-declared direct
dependency metadata.

### Option D: Component Metadata Rules / Artifact Transforms

Use metadata rules or transforms to extract per-component extension/deployment
metadata or normalize variants.

Benefits:

- Gradle-native and potentially cacheable;
- useful for extension metadata, capabilities, and artifact-derived sidecars.

Costs:

- not a good whole-application graph output mechanism;
- does not provide Maven effective-model declared dependency semantics;
- metadata rules should not be used for side effects.

Assessment: useful for extension metadata and variant cleanup, not as the main
declared-dependency replacement.

## Two-Milestone Strategy

### Milestone 1: Immediate containment

Goal: remove the eager-resolution / dry-run regression without claiming the
final dependency model is solved.

Implemented shape:

- Remove `QuarkusDeclaredDependenciesTask`.
- Move the external Maven-declared enrichment currently performed by
  `QuarkusDeclaredDependenciesTask` into `QuarkusApplicationModelTask.execute()`.
- Keep the extra Maven POM/effective-model work strictly inside the app-model
  task action.
- Keep selected graph and artifact access on Gradle-supported lazy inputs:
  `ResolutionResult.getRootComponent()`,
  `ArtifactCollection.getResolvedArtifacts()`, and Gradle file collections.
- Do not retain `Configuration`, `ArtifactCollection`, or `Project` instances
  as task state.
- Do not return to provider-backed collection that computes declared
  dependencies while Gradle snapshots application-model task inputs.
- Remove `enableDeclaredDependencyCollector`; declared dependency enrichment is
  always attempted when an app model is generated.
- Remove the declared-dependencies output file, producer-task dependency, and
  "write empty declared deps when disabled" path.
- Remove `quarkus.declared-dependencies.refresh=true` with the producer task.
- Document that `QuarkusApplicationModelTask` is not build-cacheable because the
  serialized app model contains machine-local paths and because this milestone
  intentionally does not model Maven effective-model POM closure as cacheable
  inputs.

Rationale:

- On `main`, POM lookup is hidden imperative work done only when the collector
  is explicitly enabled.
- The current branch's `QuarkusDeclaredDependenciesTask` POM artifact view is a
  more Gradle-modeled version of the same lookup, but it is worse for
  graph-only builds because it exposes POM resolution to task input
  snapshotting.
- Static inspections of `:nessie-quarkus` and `:polaris-server` suggest that
  making enrichment always-on is unlikely to add a large amount of resolution
  work. Normal Gradle/Quarkus app resolution already resolves the expensive
  selected graph; the Maven effective-model delta is mostly parent/import POMs.
- Since the producer task is not part of the intended final Gradle-native model,
  removing it now is simpler than making its POM view internal and preserving a
  transitional task boundary.
- This milestone prefers correct Gradle phase behavior and simpler code over
  separately cacheable declared-dependency output.
- It also accepts that a configuration-cache dry-run may materialize supported
  resolution-result task inputs while the cache entry is stored. The regression
  gate is a plain dry-run without configuration cache plus a follow-up real
  build proving the cached graph does not poison execution.

Status: implemented in the rewritten branch by commit `f56491a335f` (`Rework
Gradle application model task wiring`).

### Milestone 2: Build-tool-agnostic dependency model follow-up

Goal: remove Maven-declared enrichment from normal Gradle application-model
generation by moving Quarkus consumers to a shared build-tool-agnostic
dependency model.

Recommended shape:

- Define a build-tool-agnostic dependency model contract for selected
  components, selected edges, artifact metadata, and optional/provided/missing
  declared-edge metadata.
- Make Gradle's selected graph authoritative for selected components, versions,
  variants, artifacts, and selected edges.
- Decide which Maven-declared semantics are genuinely required by SBOM, Dev UI,
  and modular packaging, and represent them explicitly in the shared model or
  scope them to Maven builds.
- Move consumers away from assuming that Gradle application models are enriched
  by Maven effective-model parsing.
- Keep `QuarkusDeclaredDependenciesTask` and `enableDeclaredDependencyCollector`
  removed.

Rationale:

- This avoids semantic drift between Gradle-selected dependencies and a
  Maven-ish declared model.
- It aligns SBOM, Dev UI, modular packaging, tooling APIs, Gradle, Maven, and
  future build integrations around the same build-tool-authoritative graph.
- It fits the broader Quarkus 4 direction, but depends on changes outside the
  Gradle configuration-cache/project-isolation workstream.
- It provides the dependency-model foundation that a later Gradle-native
  `quarkusDev` run can build on, without including that dev-mode rewrite in M2.

## Detailed Phases

### Phase 1: Broaden regression gates

Status: completed as part of M1.

Focused TestKit coverage proves no deployment configuration/POM resolution
during plain dry-run task graph calculation for:

- `help`;
- `tasks`;
- dry-run of relevant application tasks;
- declared dependency enrichment always enabled;
- component variants enabled and disabled where still relevant.

This phase does not change semantics. It makes configuration-time resolution
visible without centering production design on `--dry-run`.

### Phase 2: Inline declared-dependency enrichment

Status: completed as part of M1.

Removed `QuarkusDeclaredDependenciesTask` and
`enableDeclaredDependencyCollector`, then run external Maven declared-dependency
collection from `QuarkusApplicationModelTask.execute()`.

The implementation must avoid provider-backed collection that runs during task
input snapshotting. The declared enrichment should happen only after the
application-model task starts executing.

This is an interim containment step, not the final Gradle-native dependency
model.

### Phase 3: Simplify tests and offline behavior

Status: completed as part of M1.

Updated tests so declared-dependency behavior is no longer opt-in:

- remove `-PenableDeclaredDependencyCollector=true` from declared dependency
  behavior tests;
- verify `DeclaredDependenciesMinimalTest` still passes;
- verify `quarkusGoOffline` still prepares everything needed for app-model task
  execution;
- verify dry-run/help/tasks do not resolve deployment configurations.

### Phase 4: Build-tool-agnostic dependency model design

Status: M2 follow-up.

Design the shared dependency model that M2 should target.

The design must cover at least:

- selected components and versions;
- selected parent/child edges;
- artifact metadata and paths;
- optional/provided/missing declared-edge semantics;
- build-tool-specific metadata extensions;
- serialization and Tooling API boundaries.

### Phase 5: Gradle selected graph adapter

Status: M2 follow-up.

Implement the Gradle adapter that populates the shared dependency model from
Gradle's selected graph and artifact metadata.

Do not reintroduce the old always-wired POM artifact-view producer task. If a
Gradle-local sidecar task is useful, it should be an implementation detail of
this adapter and should produce the shared model shape.

### Phase 6: Revisit Maven-declared metadata boundary

Status: M2 follow-up.

If Maven-declared metadata is still needed after the shared model is introduced,
decide whether it belongs in the shared model, is Maven-only, or remains a
temporary Gradle enrichment path.

### Phase 7: Project-dependency declared dependencies

Status: M2 / `P1-GM-03` follow-up.

Coordinate with `P1-GM-03`.

Replace cross-project mutable project model reads with variant/sidecar metadata
or another isolated-projects-compatible contract.

### Phase 8: Related but excluded Gradle-native dev mode

Status: related future work, not M2 scope.

Track Gradle-native `quarkusDev` separately. It should reference the shared
dependency model and can eventually consume it, but it is a larger dev-mode /
continuous-build rewrite and must not be included in the M2 dependency-model
slice.

### Phase 9: Optional semantic simplification

Status: later follow-up after M2.

Only after the shared model is stable, evaluate whether Quarkus can offer or
switch to a selected-graph-only direct dependency mode.

That decision needs explicit tests and documentation for lost optional/scope and
missing-declared-dependency behavior. If accepted, this phase removes remaining
Maven-declared enrichment from normal Gradle application-model generation.

## Success Gates

- No `StartParameter.isDryRun()` production task-shape branches.
- `help`, `tasks`, and dry-run do not resolve deployment configurations.
- Real task execution still resolves the required graphs and artifacts.
- `DeclaredDependenciesMinimalTest` semantics remain intact without
  `-PenableDeclaredDependencyCollector=true`.
- Modular packaging keeps optional/provided/missing declared-edge behavior.
- SBOM and Dev UI selected dependency edges remain correct.
