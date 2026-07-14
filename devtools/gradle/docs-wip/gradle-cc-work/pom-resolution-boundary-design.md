# POM Resolution Boundary Design

Date: 2026-07-11

Status: implemented design for the new `io.quarkus.application` plugin path

Owner / audience: Gradle configuration-cache and project-isolation workstream

Related:

- [Declared Dependencies Gradle-Native Design](declared-dependencies-gradle-native-design.md)
- [POM Resolution Boundary Implementation Plan](archive/pom-resolution-boundary-implementation-plan.md)
- [P1-GM-05E Modeled Task Inputs](archive/legacy/history/p1-gm-05e-modeled-task-inputs.md)
- [POM Artifact View Probe](archive/legacy/evidence/query-pom-artifact-view.init.gradle)
- [Gradle IT Full Run 2026-07-04](archive/legacy/evidence/gradle-it-full-run-2026-07-04.md)
- [Build Tooling Model Design](build-tooling-model-design.md)
- [Application Model And Codegen](application-model-and-codegen.md)

## Problem Statement

Some Gradle-side application model consumers need declared dependency metadata
that Gradle does not expose directly in the same shape as Quarkus'
Maven-oriented application model:

- declared direct dependency edges;
- Maven scopes;
- optional markers;
- declared dependencies that are not part of the selected application runtime
  graph, represented with `MISSING_FROM_APPLICATION`.

Today, model tasks that build this metadata recover it by parsing Maven
effective models for selected external Maven modules. That work is centralized in
`DependencyDataCollector`, `MavenEffectiveModelResolver`, and `PomResolver`, but
the Gradle-facing resolver boundary is still inconsistent:

- current task and tooling paths still construct `GradlePomResolver` with a
  live `DependencyHandler`;
- `GradlePomResolver` still uses
  `DependencyHandler.createArtifactResolutionQuery()` for POM lookup;
- the new application plugin uses Gradle artifact views for several classpath and
  dev-mode artifact paths, but not for the POM/effective-model enrichment path;
- the extension deployment plugin and legacy application plugin also use the same
  model generation machinery.

This slice implemented a new Gradle-native path for the new
`io.quarkus.application` plugin first. Keep the existing legacy
application-model code path around `QuarkusApplicationModelTask`,
`ApplicationModelTaskConfigurator`, and `GradleApplicationModelBuilder`
functionally unchanged. Shared value types or pure helpers are acceptable when
they do not alter legacy behavior.

## Current Production Entry Points And Consumers

### New Application Plugin

`gradle-app-plugin` registers `GenerateModelTask` for:

- normal application model;
- dev application model;
- main codegen model;
- test codegen model.

`GenerateModelTask.execute()` now builds external declared dependency metadata
only when the task is configured with
`DeclaredDependencyEnrichmentMode.SELECTED_MODULE_POMS`. The normal package/build
model uses that mode and consumes a deterministic POM closure file produced by
`GeneratePomClosureTask`. Dev and codegen model tasks use
`DeclaredDependencyEnrichmentMode.NONE` and skip external Maven effective-model
enrichment entirely.

The task no longer injects `DependencyHandler` or constructs
`GradlePomResolver(getDependencyHandler(), ...)`. Dynamic Gradle POM lookup for
parent/imported-BOM closure is isolated in the package-model POM closure
producer.

The same task now models source/resource roots as path strings. It no longer
declares source-root contents as `@InputFiles` when only the root directories are
needed for workspace source metadata.

### Legacy Application Plugin

`gradle-application-plugin` creates a `DependencyDataCollector` with
`project.getDependencies()` and passes it into
`ApplicationModelTaskConfigurator`. The registered application-model tasks then
use `QuarkusApplicationModelTask`, which also constructs a
`GradlePomResolver` with a live `DependencyHandler` for external Maven declared
dependency enrichment.

The legacy plugin is no longer the primary target for configuration-cache and
project-isolation guarantees. Preserve this behavior during the new
Gradle-native implementation. Do not refactor the legacy model task as part of
the POM-resolution fix.

### Extension Deployment Plugin

`gradle-extension-deployment-plugin` registers a test application model for
extension deployment module tests. It also constructs `DependencyDataCollector`
with `project.getDependencies()` and uses `ApplicationModelTaskConfigurator`.

The original POM-resolution slice routed extension deployment test models
through the same shared application-model task machinery as normal application
model generation. A later isolated local-output slice moved the generated
deployment test model away from the all-project workspace scan, but the design
still treats external effective-POM declared-dependency enrichment as
unnecessary for extension deployment test models:

- the split `gradle-extension-deployment-plugin` only injects
  `quarkus-internal-test.serialized-app-model.path` into `Test` JVMs;
- current tests assert model availability, selected graph/classpath behavior, or
  extension flags, not enriched external `getDirectDependencies()` semantics;
- on `github/main`, the legacy extension plugin used `ToolingUtils.create(...)`
  during `Test.doFirst`, while declared dependency collection was gated by the old
  opt-in collector, so enriched external declared metadata was not an inherent
  legacy extension-test requirement either.

Extension deployment tests need the serialized test model, selected graph and
classpath flags, and workspace metadata.

The produced model is serialized as `quarkus-app-test-model.dat` and injected
into every deployment `Test` task through
`-Dquarkus-internal-test.serialized-app-model.path=...`.

### Extension Plugin

`gradle-extension-plugin` does not itself generate or consume a serialized
`ApplicationModel` for production behavior.

It uses shared Gradle model utilities and `ApplicationModelBuilder` constants for
extension descriptor properties such as parent-first artifacts, excluded
artifacts, lesser-priority artifacts, and removed resources. Its model-related
TestKit coverage observes the extension deployment plugin behavior.

The extension deployment plugin now uses a Gradle-native local-output path for
its generated test model. It did not drive this POM-resolution implementation
plan, because the POM slice intentionally left extension deployment test models
unchanged. The plain extension plugin benefits indirectly from shared helpers
but does not need the effective-POM path for descriptor generation.

### Tooling Model Builder

`GradleApplicationModelBuilder` is the Gradle Tooling API provider for
`ApplicationModel`. Confirmed consumers are primarily Quarkus-side bootstrap and
devtools paths such as `QuarkusGradleModelFactory`, `BuildToolHelper`,
`AppModelGradleResolver`, and direct Tooling API integration tests. Current
IDE/plugin investigation did not find IntelliJ, VS Code Quarkus tooling,
Eclipse/JBoss Tools, CodeReady Studio, or Quarkus LS requesting this model
directly.

The builder still collects declared dependency metadata directly from a live
`Project` and `Configuration`. This path is a larger build-tooling-model problem
and remains covered by `build-tooling-model-design.md` and
`tooling-model-consumers-investigation.md`.

The tooling-model builder should not drive the mode decision for task-produced
models. Its historically important capability is Tooling API compatibility and
workspace-discovery behavior. External Maven effective-model enrichment was
added later for declared-dependency semantics and should be an explicit
compatibility/enrichment concern, not the default answer for dev/codegen model
generation.

This design should not make that path worse, but a complete tooling-model fix
requires a separate design. The current builder is a compatibility adapter
registered by the legacy `io.quarkus` plugin; it reads live Gradle project state
for workspace discovery and other Tooling API behavior. Public issue
`quarkusio/quarkus#49335` and local history show that replacing it safely is
about producer-owned metadata contracts and model shape, not simply reusing the
task-produced model code.

## Prior Work And Current Interpretation

The historical `P1-GM-05E` design already identified the right conceptual split:

- Gradle-backed input collection resolves and records known module POMs.
- Maven model building consumes a `PomResolver` backed by a modeled known-POM
  map.
- Parent POMs and imported BOMs discovered during Maven model building need an
  explicit fallback or iterative prefetch strategy.

That producer-task approach was later superseded because the old
`QuarkusDeclaredDependenciesTask` shape exposed too much resolution-backed state
as task inputs and was not the intended final dependency-model design. The
underlying boundary remains useful: code that builds Maven effective models
should not need a live Gradle `DependencyHandler`, `Project`, `Configuration`, or
`ResolvedArtifactResult`.

The relevant old implementation work is present in local Git history but is not
on the current direct branch ancestry. The useful pieces that survived in current
code are:

- `ExternalModuleDeclaredDependencyInput`;
- `DeclaredDepsResult` and `DeclaredDependency`;
- `PomResolver.prefetchPoms(...)`;
- `GradlePomResolver(Map<GAV, File>, ...)`;
- the recording/prefetch loop in `DependencyDataCollector`.

The deleted pieces should not be restored wholesale:

- `QuarkusDeclaredDependenciesTask`;
- `DeclaredDependenciesFile`;
- the opt-in `enableDeclaredDependencyCollector` flow;
- the public/transitional declared-dependencies producer-task topology.

The current `declared-dependencies-gradle-native-design.md` says not to
reintroduce the old always-wired POM artifact-view producer task. This should be
read narrowly:

- do not restore the removed public/transitional declared-dependencies producer
  task as-is;
- do reuse the modeled input idea inside a shared resolver/adapter boundary when
  it is needed to remove live Gradle API usage from effective-model execution.

## POM Artifact View Evidence

Current production code does not use `artifactType=pom` artifact views for the
effective-model path. Production POM lookup still goes through
`GradlePomResolver` with `DependencyHandler.createArtifactResolutionQuery()` and
`MavenPomArtifact`.

However, earlier local evidence shows that Gradle can expose POM artifacts
through an artifact view:

```groovy
configuration.incoming.artifactView { view ->
    view.lenient(true)
    view.componentFilter { component ->
        component instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier
    }
    view.withVariantReselection()
    view.attributes { attributes ->
        attributes.attribute(
            org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
            'pom')
    }
}
```

The 2026-07-04 integration-test notes also record a successful switch of the
declared-dependencies POM input to Gradle's native `artifactType=pom` artifact
view, with `JavaPlatformWithEagerResolutionTest` and
`DeclaredDependenciesMinimalTest` passing afterward.

This answers the feasibility question well enough for the next implementation
plan. Use Gradle's `artifactType=pom` artifact view for selected module POMs, with
focused TestKit coverage across the Gradle versions Quarkus supports.

## Implemented Direction

The implemented new-plugin path introduces a Gradle-native POM input and closure
producer for the new
`gradle-app-plugin` model generation path. Reuse small shared serializable value
types from `gradle-model` where useful, but do not rewire the legacy model task
or tooling model in this phase.

The implemented boundary:

- accepts already-modeled Gradle classpath/resolution inputs for the enriched
  package model task;
- derive external module POM lookup identities from selected module artifacts;
- resolves selected module POM files through Gradle `artifactType=pom` artifact
  views with variant reselection;
- writes a deterministic POM closure file containing resolved and known-missing
  parent/imported-BOM POM entries;
- lets `GenerateModelTask` construct
  `GradlePomResolver(Map<GAV, File>, missingPoms, repositoryRoots)` instead of
  `GradlePomResolver(DependencyHandler, ...)`.

The immediate goal is not to make application-model tasks build-cacheable. The
goal is to move POM lookup for selected modules to an explicit Gradle-modeled
boundary for enriched model tasks, and keep Maven effective-model building
independent from live Gradle services.

The existing `DependencyHandler` constructors remain available for legacy and
tooling fallback paths until those paths are redesigned separately.

## Use-Case Scope

Parent POMs and imported BOM POMs are only needed when a task asks Quarkus to
recover Maven effective-model declared dependency metadata for external Maven
components. They are not inherently required for every application model use
case.

The current hard consumer is modular packaging. `ModularitySteps` consumes
`ResolvedDependency.getDirectDependencies()` and relies on Maven-declared
semantics that are not present in Gradle's selected graph alone:

- dependencies declared by an external component but missing from the selected
  application graph;
- `MISSING_FROM_APPLICATION`;
- Maven scopes such as `compile`, `provided`, and `runtime`;
- optional markers that influence generated module dependency metadata.

SBOM support is a package-time consumer. Core SBOM contribution construction uses
`ApplicationModel.getDependencies()` for selected application components and
selected dependency edges, and the CycloneDX generator can use Maven effective
models for richer package metadata such as license information. That means SBOM
work can benefit from effective-POM metadata, but it does not justify making
dev-mode, run-mode, or continuous-test model generation resolve recursive
parent/import POM closure by default.

Dev-mode and Dev UI references found in production code do not require external
Maven effective-model declared dependencies. The relevant dev-mode sorting and
welcome-page code uses `WorkspaceModule.getDirectDependencies()` for workspace
modules, while runtime class loading and launch setup primarily need the selected
application graph, deployment/runtime classpaths, workspace source/resource
roots, and reloadable workspace module metadata.

Code generation likewise needs an application/deployment classpath and workspace
metadata. No current codegen use case has been identified that needs external
Maven effective-model declared dependency enrichment.

Therefore new-plugin model generation should support an explicit
declared-dependency enrichment mode:

- package/build models can opt into external declared-dependency enrichment,
  because modular packaging and package-time SBOM can need it;
- dev, run, continuous-test, and codegen models should default to selected
  Gradle graph and workspace metadata only;
- extension deployment test models use the later local-output isolation path and
  still default to selected Gradle graph and workspace metadata only.

This keeps the expensive and fragile recursive POM concern scoped to consumers
that can materially use the result.

The intended first mapping is:

| Model task / path | Enrichment mode | Rationale |
| --- | --- | --- |
| `quarkusApplicationModel` used by package/build tasks | `SELECTED_MODULE_POMS` | Packaging can feed modular packaging and package-time SBOM work. |
| `quarkusApplicationDevModel` | `NONE` | Dev-mode needs selected graph, classpaths, workspace roots, and reloadable workspace metadata. |
| `quarkusApplicationCodegenModel` | `NONE` | Code generation needs application/deployment classpath and workspace metadata. |
| `quarkusApplicationTestCodegenModel` | `NONE` | Test code generation has the same requirement shape as main code generation. |
| `quarkusGenerateTestAppModel` from `gradle-extension-deployment-plugin` | Existing legacy path initially | Deployment tests use the shared legacy model task machinery today. Do not change it in this phase. |
| Legacy application-model tasks | Preserve current behavior | Legacy is not the primary config-cache/project-isolation target. Do not change it in this phase. |
| Tooling model builder | Existing fallback initially | Covered by the separate build-tooling model design. |

## Parent And Imported BOM POMs

Selected module POMs are only the first layer. Maven effective-model building may
request parent POMs and imported BOM POMs that are not selected runtime or
deployment artifacts.

There are three plausible implementation shapes:

1. **Known selected POMs only.** Use the explicit POM map for selected modules,
   then fall back to repository-root lookup for parent/import POMs when they
   happen to be present in Gradle's dependency cache or `maven.repo.local`.
   This removes most direct Gradle API usage but can still miss parent/import
   POMs.
2. **Iterative Gradle prefetch.** Keep the existing recording resolver loop, but
   route newly discovered parent/import `GAV`s through a clearly named
   Gradle-facing adapter before Maven model building is retried. This still uses
   Gradle resolution during task execution unless the adapter can be modeled as
   task input, so it must not claim Worker API compatibility.
3. **Fully modeled recursive POM closure.** Discover and materialize the complete
   effective-model POM closure before the task action. This is the cleanest
   boundary, but it is more intrusive and may expose a large cache-key surface.

For packaging enrichment, stage 1 alone is not functionally sufficient. The
implemented slice resolves the effective-model POM closure needed by
Maven model building, including parent POMs and imported BOM POMs. Use the
existing recording resolver loop to discover missing parent/import `GAV`s, but
move the Gradle-facing resolution into a dedicated POM-closure producer so the
application-model task consumes a stable file/map instead of a live
`DependencyHandler`.

This implementation does not make the closure producer build-cacheable. It is
scoped only to model tasks that enable external declared-dependency enrichment,
and it makes the full closure visible as a task output consumed by the
application-model task.

## Source Root Input Fix

The new plugin's `GenerateModelTask` does not treat source file contents as
inputs when it only needs the source root directories.

Implemented shape:

- source-root properties are modeled as path-only directory-root inputs;
- compiled classes/resources remain task inputs where the model needs current
  output directories;
- ordinary Java/resource source edits no longer force codegen model generation
  just because the source-root `FileCollection` contents changed.

This is orthogonal to POM resolution, but it explains why the POM issue appears
during Gradle continuous dev iterations.

## Shared API Shape

Implemented types:

- `DeclaredDependencyEnrichmentMode`: new-plugin model-task input that makes the
  POM input boundary explicit. Current values are `NONE` and
  `SELECTED_MODULE_POMS`.
- `GeneratePomClosureTask`: package-model producer task that starts from selected
  module POM artifact views, runs the existing Maven effective-model discovery
  loop, resolves dynamically discovered parent/imported-BOM POMs, and writes a
  deterministic closure file.
- `PomClosureResult` and `PomClosureResultCodec`: internal deterministic
  resolved/missing POM closure file model.

New-plugin task actions should receive only the modeled result. They should not
receive or capture `DependencyHandler`, `Configuration`, `ArtifactView`,
`Project`, or live Gradle artifact result objects for Maven effective-model
work.

The existing `ExternalModuleDeclaredDependencyInput` already models selected
external module identity for declared dependency collection. Prefer extending or
reusing that concept instead of introducing a parallel identity model.

## Testing Goals

Regression coverage should include:

- new application plugin model generation no longer uses
  `DependencyHandler.createArtifactResolutionQuery()` during task action when
  declared-dependency enrichment is enabled;
- dev/run/continuous-test/codegen model tasks do not resolve or snapshot external
  POM inputs when declared-dependency enrichment is disabled;
- extension deployment plugin and legacy application model behavior are
  unchanged by this phase;
- source/resource file edits do not rerun codegen model generation when only
  source root paths are required;
- effective-model tests still cover parents, imported BOMs, optional/provided
  dependencies, missing POM behavior, and repeated unresolved POM caching;
- the extension deployment plugin still injects a usable serialized test
  application model into deployment tests;
- `DeclaredDependenciesMinimalTest` still passes;
- TestKit coverage for a tiny Quarkus app and a tiny extension deployment module
  under `--configuration-cache` and isolated projects where the plugin claims
  compatibility.

## Follow-Up Design Work

1. Tooling-model modernization still needs its own implementation design. The
   design must preserve or replace Tooling API compatibility and
   workspace-discovery behavior without live cross-project Gradle model reads.
   Current IDE investigation did not find mainstream IDEs consuming the model
   directly, but Quarkus-side bootstrap/devtools consumers still need a
   compatible story.
2. Broaden regression coverage for extension deployment test models beyond the
   focused isolated-project TestKit case: representative integration tests
   should still boot with a serialized test model and see local
   runtime/deployment artifacts, selected dependency flags, classifier outputs,
   and workspace metadata correctly.

## Implemented State And Validation

Implemented on 2026-07-11:

1. `quarkusApplicationModel` is configured with
   `DeclaredDependencyEnrichmentMode.SELECTED_MODULE_POMS`.
2. `quarkusApplicationDevModel`, `quarkusApplicationCodegenModel`, and
   `quarkusApplicationTestCodegenModel` are configured with
   `DeclaredDependencyEnrichmentMode.NONE`.
3. `quarkusApplicationModelPomClosure` produces the deterministic POM closure
   file consumed by `quarkusApplicationModel`.
4. `GenerateModelTask` no longer injects or uses `DependencyHandler`.
5. Source/resource roots on `GenerateModelTask` are path-only inputs.
6. Legacy application-model and tooling-model paths remain unchanged.

Validation run:

```bash
cd devtools/gradle
./gradlew :gradle-app-plugin:test :gradle-model:test --tests io.quarkus.gradle.tooling.GradlePomResolverTest
```

The new plugin was also smoke-tested through Nessie's included-build setup:

```bash
cd /home/snazy/devel/projectnessie/nessie/nessie
./gradlew :nessie-quarkus:quarkusApplicationDev --continuous
```

Nessie started successfully, `/` returned HTML, and `/api/v1/config` returned
JSON containing `"defaultBranch" : "main"`.

## Current Recommendation

Treat this design as implemented for the new-plugin task-produced application
model paths. The remaining work is follow-up design/implementation, not part of
this slice:

1. Modernize the tooling-model path without live cross-project Gradle model
   reads.
2. Broaden extension deployment test-model integration coverage for composite
   extension builds, extension-to-extension dependencies, helper libraries,
   classifier artifacts, and Jandex/indexed local outputs.
3. Revisit POM-closure build-cacheability only after the closure is fully
   modeled and relocatability is reviewed.
4. Modernize the outer `devtools/gradle` TestKit metadata wiring so the outer
   build itself can be used as an isolated-project hard gate.
