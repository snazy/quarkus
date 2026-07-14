# System Property And Environment Inventory

This inventory tracks direct `System.getProperty`, `System.getProperties`,
`System.getenv`, and `Boolean.getBoolean` usage under `devtools/gradle`.
It is not itself a finding list. Promote an entry to an active finding only
when it has a concrete build-cache, configuration-cache, project-isolation, or
behavioral consequence.

## Fixed

### Plugin and task environment/system-property provider migration

- Calls: former direct plugin/task `System.getProperty(...)`,
  `System.getenv(...)`, and `Boolean.getBoolean(...)` reads in production
  Gradle plugin code.
- Tracking: `P1-AP-05`, `P1-GM-05`, and provider-cleanup inventory.
- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`).
- Status: fixed in the rewritten branch for the direct production call sites.
- Notes: this does not close `P1-AP-05` or `P1-GM-05E`. Workers still receive a
  broad task-execution environment, opaque fork actions remain, and Maven model
  resolution still receives broad provider-backed system properties.

### `devtools/gradle/build-logic/src/main/kotlin/io.quarkus.devtools.java-library.gradle.kts`

- Calls: former `System.getProperties()` / `System.getProperty("maven.repo.local")`.
- Tracking: `P1-BI-01`.
- Upstream PR: https://github.com/quarkusio/quarkus/pull/55222 (merged on
  2026-07-02).
- Status: merged upstream.

### `devtools/gradle/settings.gradle.kts`

- Calls: former `System.getenv("DEVELOCITY_ACCESS_KEY")` and `System.getenv("CI")`.
- Tracking: settings inventory cleanup.
- Upstream PR: https://github.com/quarkusio/quarkus/pull/55223 (open as of
  2026-07-02).
- Status: open upstream PR.
- Notes: configuration-time settings-script environment reads were replaced
  with `providers.environmentVariable(...)`, preserving the previous
  null-or-empty behavior.

## Active Findings

### Worker process environment and fork-option customizations

- Calls: `QuarkusTask` whole-environment forwarding and extension-provided
  fork-option actions.
- Tracking: `P1-AP-05`.
- Notes: direct worker-control system-property reads now use Gradle providers.
  Keep this active for the broad runtime environment and opaque fork actions,
  but avoid promoting integration-test fallback properties such as
  `gradle.quarkus.gradle-worker.max-heap` to public API without an explicit
  design decision.

## Already Purpose-Built Or Lower Priority

### Declared dependency collector system property copy

- Calls: `DependencyDataCollector` passes provider-backed JVM system properties
  into Maven model resolution.
- Tracking: `P1-GM-05` follow-up boundary.
- Notes: `P1-GM-05E` M1 is fixed in the rewritten branch by moving enrichment into
  `QuarkusApplicationModelTask` execution and keeping `QuarkusApplicationModelTask`
  non-build-cacheable. Broad Maven model system properties remain deliberately
  outside stable Gradle cache keys because exposing the whole system-property
  map would make cache artifacts non-portable. Revisit under the broader
  build-tool-agnostic dependency model only if a concrete, well-known Maven
  property needs direct modeling.

### Configuration value sourcing

- Calls: `QuarkusConfigValueSource`, `BaseConfig`, and `EffectiveConfig`
  intentionally handle system properties and environment variables as part of
  Quarkus configuration source modeling.
- Tracking: no active standalone finding.
- Notes: do not mechanically replace these without reviewing the existing
  ValueSource/config-source design.

### Command-style non-cacheable tasks

- Calls: `QuarkusDev`, `QuarkusRun`, `Deploy`, and
  `ImageCheckRequirementsTask` direct system-property reads.
- Tracking: no active standalone finding.
- Notes: mostly non-cacheable command-style task behavior. Consider provider
  migration only when a specific task is being made configuration-cache or
  build-cache compatible.

### Tooling model workspace-discovery property

- Calls: `GradleApplicationModelBuilder` reads
  `quarkus.bootstrap.workspace-discovery`.
- Tracking: implicitly related to `P1-GM-03`.
- Notes: likely belongs with tooling-model/project-isolation work rather than a
  standalone provider cleanup.

### Tests and test fixtures

- Calls: direct system property and environment reads in test sources and test
  resources.
- Tracking: none.
- Notes: not production plugin behavior.
