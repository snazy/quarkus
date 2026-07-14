# Phase 1: Consolidated Current-State Review

This is the deduplicated Phase 1 review of `devtools/gradle` for Gradle build
cache, configuration cache, and project isolation compatibility. It is based on
the shared official-Gradle constraint rubric in `gradle-constraints.md` and the
four shard reports:

- `phase-1-build-infra.md`
- `phase-1-gradle-model.md`
- `phase-1-extension-plugin.md`
- `phase-1-application-plugin.md`

## Executive Summary

`devtools/gradle` already has targeted compatibility work, especially in the
application plugin, but it is not currently ready to claim broad compatibility
with Gradle's build cache, configuration cache, or isolated projects.

The main blockers are not cosmetic. They are structural Gradle contract
violations:

- shared `gradle-model` code resolves and mutates dependency metadata at
  resolution time;
- tooling-model code reads mutable state from other projects;
- both plugins wire tasks through live `Project`, `Configuration`, task, and
  source-set objects;
- some cacheable task paths still receive inputs that are hidden from Gradle's
  cache key;
- extension-plugin descriptor/validation/model-generation issues are now fixed
  locally, but documentation/migration work remains for the new deployment
  plugin shape, plus broader integration coverage around generated deployment
  test models.

The work should not be fixed as one large refactor. A practical path is to add
small reproducers first, fix shared `gradle-model` root causes next, then move
plugin task wiring onto provider-backed task properties and variant/artifact
contracts in focused PRs.

## Build Infrastructure

### Current State

The build infrastructure is mostly a test-matrix and build-orchestration risk,
not the primary source of Gradle model incompatibility. The build scripts do not
show the same direct isolated-projects violations as the plugin code.

### Problems

No active build-infrastructure findings remain in Phase 1. `P1-BI-01` is fixed;
`P1-BI-02` and `P1-BI-03` are deferred.

## Gradle Model

### Current State

`gradle-model` is the shared foundation consumed by both plugins. Its findings
should be treated as root causes because plugin-level workarounds will be
fragile while shared model building still relies on live Gradle objects, eager
resolution, provider-triggered metadata mutation, and cross-project traversal.
`P1-GM-05` M1 is fixed in the rewritten branch and tracked in
`fixed-findings.md`; remaining
project metadata work belongs to `P1-GM-03`. M2 remains explicit follow-up
tied to the broader build-tool-agnostic dependency model work, not another
Gradle-only producer-task slice.

### Problems

#### [P1-GM-01] Component-variant provider mutates resolution metadata while resolving dependencies

- Source: [ApplicationDeploymentClasspathBuilder.java:299](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/dependency/ApplicationDeploymentClasspathBuilder.java), [QuarkusComponentVariants.java:221](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/dependency/QuarkusComponentVariants.java)
- Impact: configuration-cache warning, dependency-resolution ordering risk.
- State: must fix before enabling broad compatibility.
- Summary: the shared component-variant path resolves platform data from a
  provider-backed callback, analyzes dependencies, and registers component
  metadata variants through `withModule(...)` from that callback. This is
  separate from the removed `P1-EP-06` extension-plugin fallback and remains an
  ordering-sensitive path that makes Gradle's provider/resolution contracts
  harder to satisfy.

#### [P1-GM-03] Application model resolves project dependencies by reading other projects' mutable model

- Source: [GradleApplicationModelBuilder.java:275](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/GradleApplicationModelBuilder.java), [GradleProjectDependencyDeclaredDependencyCollector.java:38](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/dependency/GradleProjectDependencyDeclaredDependencyCollector.java), [DependencyUtils.java:96](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/dependency/DependencyUtils.java), [ToolingUtils.java:67](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/ToolingUtils.java)
- Impact: direct isolated-projects blocker.
- State: must fix before isolated-projects support.
- Summary: application-model building and project-declared dependency
  enrichment still follow project dependencies back through root project or
  included-build mutable model lookup. They read other projects' extensions,
  source sets, configurations, layout, group, version, and workspace paths.
  Isolated projects requires these relationships to be expressed through
  dependencies, variants, artifacts, or another stable metadata contract
  instead.

## Extension Plugin

### Current State

The extension plugin has made substantial local progress. `P1-EP-01`,
`P1-EP-02`, `P1-EP-03`, `P1-EP-04`, and `P1-EP-06` are fixed in the rewritten branch and
tracked in `fixed-findings.md`. The runtime plugin no longer uses
`afterEvaluate` to find and mutate the deployment project; deployment-project
setup moved behind the new `io.quarkus.extension.deployment` plugin and a
marker variant. The later generated deployment test-model isolation issue is
also implemented for the focused `quarkusGenerateTestAppModel` path: the
deployment plugin now uses a current-project descriptor and shared artifact-view
local output resolution instead of the legacy all-project workspace scan.

Remaining extension-plugin work is documentation/migration, broader integration
coverage for representative generated test-model shapes, plus any failures
inherited from shared `gradle-model` blockers outside the isolated deployment
test-model path.

### Problems

No active extension-plugin-specific Phase 1 findings remain. Keep
`public-dsl-shape-changes.md` open for migration/documentation follow-up, and
use `archive/p1-ep-01-deployment-project-plugin-plan.md` as implementation
history. Keep the broader generated deployment test-model integration coverage
deferred in `new-application-plugin-design.md`. Continue to triage remaining
isolated-projects failures against `P1-GM-01` and `P1-GM-03`, except where the
focused `io.quarkus.extension.deployment` test-model path already has its own
local-output implementation.

## Legacy Application Plugin

### Current State

These findings apply to the legacy `io.quarkus` application plugin in
`gradle-application-plugin`, not automatically to the standalone
`io.quarkus.application` plugin in `gradle-app-plugin`.

The legacy application plugin has more build-cache-oriented task modeling than
the extension plugin, but important cacheable paths still have hidden inputs and
undeclared state. Its isolated-projects support is also incomplete because
multi-project task wiring still reads and mutates dependency projects.

### Problems

#### [P1-AP-01] Cross-project task wiring blocks isolated projects

- Source: [QuarkusPlugin.java:737](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java), [QuarkusPlugin.java:814](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java)
- Impact: direct isolated-projects blocker.
- State: must fix before isolated-projects support.
- Summary: `afterEvaluate()` calls `visitProjectDependencies()`, which traverses
  dependency-project configurations, resolves local and included-build project
  dependencies back to live `Project` instances, registers `afterEvaluate`
  callbacks on other projects, reads their tasks, and wires task dependencies to
  their `jar`, Jandex, and process-resources tasks.

This does not describe the new `io.quarkus.application` plugin's task model,
which consumes dependency projects through Gradle artifacts, variants, and
dependency resolution instead of dependency-project task traversal.

#### [P1-AP-02] Cacheable build tasks read mutable build-service state as a hidden input

- Source: [QuarkusPlugin.java:161](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java), [QuarkusBuildTask.java:59](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildTask.java), [QuarkusBuildTask.java:356](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildTask.java), [ForcedPropertieBuildService.java:10](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/services/ForcedPropertieBuildService.java), [ImageBuild.java:26](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/ImageBuild.java), [ImagePush.java:21](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/ImagePush.java)
- Impact: build-cache blocker.
- State: must fix remaining hidden-input path before relying on cacheability.
- Summary: native arguments are now modeled separately, but cacheable build
  paths still merge late build-service properties from image task paths through
  an `@Internal` service property. `ImageBuild` and `ImagePush` mutate that
  service at task execution time, those values are not part of the consuming
  task cache key, and the service is keyed by project name only. The legacy
  `buildNative`/`testNative` aliases now route through modeled extension state
  instead of mutating `Project` extra properties, so the remaining legacy issue
  is the image task late build-service mutation. The preferred design direction
  is tracked in `new-application-plugin-design.md` and
  `application-plugin-build-shapes/design.md`: keep legacy `io.quarkus`
  behavior as compatibility behavior and provide stable build-shape tasks in
  the standalone `io.quarkus.application` plugin instead of adding
  graph-conditional inputs to one shared `quarkusBuild` task.

For the new plugin, the named-output task model is the intended resolution of
this finding. Remaining concerns are build-cache hardening and validation gaps,
not configuration-cache or isolated-project blockers of the same shape.

#### [P1-AP-05] Cacheable workers receive broad, undeclared process environment and opaque fork actions

- Source: [QuarkusTask.java:88](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusTask.java), [QuarkusTask.java:99](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusTask.java), [QuarkusTask.java:119](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusTask.java), [QuarkusPluginExtensionView.java:73](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusPluginExtensionView.java)
- Impact: cache miss risk and configuration-cache warning.
- State: should fix.
- Summary: cacheable workers still receive the whole process environment and
  arbitrary `Action<JavaForkOptions>` customizations that can affect output but
  are not fully represented as stable task inputs. Direct worker-control system
  property reads were migrated to Gradle providers, and the environment is read
  at task execution time, so the remaining issue is intentionally broad runtime
  environment forwarding plus opaque fork actions.

The new plugin no longer forwards the whole environment by default and exposes
typed fork-option properties. It still mirrors fork customization as a
build-cache caution, so related tasks should stay conservative about cache
claims until typed inputs and tests prove the contract. This is not a direct
new-plugin isolated-project blocker.

## Cross-Cutting Root Causes

- Live Gradle model leaks into execution: task actions and workers still depend
  on `Project`, `Task`, `Configuration`, `SourceSet`, or mutable extension
  objects.
- Cross-project relationships are modeled by project traversal rather than
  variants, dependencies, or stable identity providers.
- Some task paths still have hidden inputs through build services, process
  environment, system properties, plain fields, and arbitrary action callbacks.
- Dependency resolution is often triggered through legacy APIs and resolution
  callbacks instead of role-specific configurations and provider-backed artifact
  views.

## Phase 1 Verification Gaps

- The original Phase 1 review was static. Follow-up PR slices now have targeted
  verification recorded in `fixed-findings.md`, but active findings still need
  their own focused reproducers.
- Missing broader TestKit/integration coverage for multi-project
  isolated-projects behavior. The generated extension-deployment test model now
  has focused coverage, but representative composite, helper-module,
  classifier, and Jandex/indexed-output shapes still need slower coverage.
- Missing cache-key regression tests for remaining hidden forced properties,
  image build/push option propagation, environment-sensitive workers, and cache
  restore from a clean `build/`.
- Missing plugin-level Gradle version matrix for compatibility behavior.

## Suggested Phase 2 Direction

The next phase should turn these findings into dependency-ordered PR groups:

1. Add small TestKit reproducers for the clearest current failures.
2. Fix shared `gradle-model` root causes: cross-project tooling traversal and
   provider-triggered metadata mutation.
3. Fix remaining application-plugin cacheable task inputs before broadening
   cache claims: image build/push forced properties and worker environment/fork
   customization.
4. Finish extension-plugin public documentation and migration notes for the new
   deployment plugin shape, then broaden isolated-projects smoke tests around
   representative extension builds to separate inherited `gradle-model` blockers
   from plugin-local issues.
5. Replace the remaining cross-project task wiring and shared model/plugin
   traversal with variant/artifact based wiring or isolated-compatible explicit
   contracts.
6. Add a small supported-Gradle-version compatibility matrix once the main
   blockers have targeted tests.

Do not mark additional tasks cacheable until their inputs, outputs, local state,
normalization, worker parameters, and build-service interactions have been
reviewed together.
