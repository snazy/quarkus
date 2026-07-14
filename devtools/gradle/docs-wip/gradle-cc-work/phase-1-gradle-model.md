# Phase 1: `gradle-model` Review

Read-only review of `devtools/gradle/gradle-model` for Gradle build-cache,
configuration-cache, and isolated-projects compatibility.

## Findings

### Finding: [P1-GM-01] Component-variant provider mutates resolution metadata while resolving dependencies

- ID: `P1-GM-01`
- Area: `gradle-model`
- Component: [ApplicationDeploymentClasspathBuilder.java:299](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/dependency/ApplicationDeploymentClasspathBuilder.java), [QuarkusComponentVariants.java:221](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/dependency/QuarkusComponentVariants.java)
- Build cache impact: inefficiency
- Project isolation impact: likely blocker / compatibility risk
- Configuration cache impact: warning
- Severity: must fix before enabling
- Evidence: `ApplicationDeploymentClasspathBuilder` wires `platformSpecProperty.value(project.provider(this::resolvePlatformSpec))`, and that provider calls `getPlatformConfiguration().resolve()` at lines 284-286. `QuarkusComponentVariants` attaches another provider to dependencies at lines 219-227; first invocation analyzes dependencies, mutates component metadata via `withModule(...)` at lines 254-257, 309-311, and 325-329, and `processConfiguration()` resolves copied configurations at lines 380-383. This is the shared component-variant path; the extension-plugin-local legacy `beforeResolve` fallback was removed under `P1-EP-06`.
- Gradle contract: `gradle-constraints.md` sections "Artifact Resolution And Provider APIs" and "Configuration Cache"; official refs: `Configuration`, `Provider`, `ArtifactCollection`, configuration-cache requirements.
- Why it matters: selected variants depend on one-shot provider invocation order and on mutating component metadata while dependency analysis is being triggered from a provider-backed dependency callback. That is fragile with configuration-cache reuse and likely worse under isolated/project-parallel model building.
- Confidence / gaps: high for risky pattern; medium on exact Gradle failure mode because no focused reproducer has been added.
- Suggested PR boundary: isolate conditional/deployment variant registration from provider-triggered resolution; add a failing Gradle TestKit scenario first, then move analysis to a deterministic configuration/model step that does not mutate component metadata from a dependency provider callback.
- Verification: multi-project app with conditional extensions, `--configuration-cache`, `-Dorg.gradle.unsafe.isolated-projects=true`, and a second run proving cache reuse plus stable selected variants.

### Finding: [P1-GM-03] Application model resolves project dependencies by reading other projects' mutable model

- ID: `P1-GM-03`
- Area: `gradle-model`
- Component: [GradleApplicationModelBuilder.java:275](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/GradleApplicationModelBuilder.java), [GradleProjectDependencyDeclaredDependencyCollector.java:38](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/dependency/GradleProjectDependencyDeclaredDependencyCollector.java), [DependencyUtils.java:96](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/dependency/DependencyUtils.java), [ToolingUtils.java:67](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/ToolingUtils.java)
- Build cache impact: none
- Project isolation impact: direct blocker
- Configuration cache impact: related
- Severity: must fix before enabling
- Evidence: resolved project components are mapped back to `Project` via
  `project.getRootProject().findProject(...)` and included-build mutable model
  access, then their `extensions`, `SourceSetContainer`, layout, build file,
  group/version, source-set outputs, and workspace paths are read in
  `GradleApplicationModelBuilder`. The current declared-dependency M1 shape also
  precomputes project-declared metadata through
  `GradleProjectDependencyDeclaredDependencyCollector`, which iterates
  `project.getRootProject().getAllprojects()`, resolves project components back
  to `Project`, and reads their configurations. `DependencyUtils` still reads
  local extension-project configuration and source sets via live `Project`
  access.
- Gradle contract: `gradle-constraints.md` section "Isolated Projects"; official refs: isolated projects user guide, `ProjectDependency`, `IsolatedProject`.
- Why it matters: isolated projects only allows safe immutable identity/directory
  access across project boundaries. This path reconstructs workspace modules and
  project-declared metadata by inspecting another project's live mutable model,
  so multi-project Quarkus application models will remain incompatible until
  that metadata is provided through variants, artifacts, or another stable
  Gradle-native contract.
- Confidence / gaps: high.
- Suggested PR boundary: first add a focused isolated-projects TestKit reproducer for a multi-project app with a local Quarkus extension/project dependency; then introduce a project-descriptor/outgoing-variant path so consumers resolve metadata instead of traversing `Project`.
- Verification: `quarkusGenerateAppModel` and Tooling API import on a multi-project build with `-Dorg.gradle.unsafe.isolated-projects=true`.

## Fixed Or Deferred Shard Findings

`P1-GM-05` is no longer an active Phase 1 shard finding. Its structural
refactor and M1 containment slice are fixed in the rewritten branch and recorded in
`fixed-findings.md`; the detailed history remains in
`archive/legacy/history/p1-gm-05-declared-dependency-collector-plan.md` and
`archive/legacy/history/p1-gm-05e-modeled-task-inputs.md`. The project-dependency metadata side is
tracked under active finding `P1-GM-03`; M2 is a broader
build-tool-agnostic dependency model follow-up, not another Gradle-only
producer-task slice.
