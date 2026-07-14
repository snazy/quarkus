# Fixed Findings

Status: current
Last reviewed: 2026-07-14

Findings moved here are no longer active tracking items. Keep the original ID
stable so follow-up PRs, discussions, and release notes can refer back to the
same finding.

## Current Branch Fixed-Work Ledger

The current rewritten `gradle-app-cc` branch contains these workstream commits:

- `5d27241513c` (`Warn about Gradle DSL changes for Quarkus 4`)
- `67113760350` (`Require Gradle 9.6 for Quarkus Gradle plugins`)
- `bc099e63e87` (`Share Gradle TestKit fixtures across plugin tests`)
- `f56491a335f` (`Rework Gradle application model task wiring`)
- `a3e685d7172` (`Add Gradle extension deployment plugin`)
- `3c5d783cf73` (`Update Gradle integration tests for plugin rewiring`)

Older local commits referenced in archived docs were folded into this stack.
Use the current SHAs above for PR mapping unless explicitly investigating
history.

## Build Infrastructure

### [P1-BI-01] Build logic snapshots the whole system-property bag for tests

- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`)
- Summary: direct plugin/test system-property and environment access was moved
  toward Gradle providers where it matters for plugin/task configuration. The
  remaining broad worker environment concern is tracked separately as
  `P1-AP-05`.

### Configuration-cache defaults and Gradle 9.6 baseline

- Fixed by:
  - `5d27241513c` (`Warn about Gradle DSL changes for Quarkus 4`)
  - `67113760350` (`Require Gradle 9.6 for Quarkus Gradle plugins`)
- Summary: Quarkus 4 Gradle plugin work now targets Gradle 9.6. The old Gradle
  8.14 integration-test profile was removed, and all Gradle plugin entry points
  use a shared minimum-version check from `gradle-model`.

## Gradle Model

### [P1-GM-02] Static platform imports leak mutable model state across builders

- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`)
- Historical context: commit `89a9405d5a43d4cbe9051381478da0eb56362145` and PR
  `#25613` explain why platform imports became shared state originally.
- Summary: platform imports are scoped through a build service and synchronized
  immutable snapshots instead of static mutable maps.

### [P1-GM-04] Custom configurations mostly keep legacy mixed roles

- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`)
- Summary: shared Gradle model wiring now uses clearer resolvable/consumable
  configuration roles where touched by the application-model refactor.

### [P1-GM-05] Declared-dependency collector M1 containment

- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`)
- Current design: `declared-dependencies-gradle-native-design.md`
- Archived history:
  `archive/legacy/history/p1-gm-05-declared-dependency-collector-plan.md`,
  `archive/legacy/history/p1-gm-05e-modeled-task-inputs.md`,
  `archive/legacy/evidence/declared-dependencies-gradle-native-investigation.md`,
  `archive/legacy/evidence/dry-run-resolution-inventory.md`
- Summary: the rejected producer-task shape was removed. Declared-dependency
  enrichment now runs inside application-model task execution, while selected
  graph/artifact access stays on Gradle-supported lazy providers. M2 remains a
  broader build-tool-agnostic dependency model follow-up.

## Extension Plugin

### [P1-EP-01] Runtime plugin configures the deployment project after evaluation

- Fixed by: `a3e685d7172` (`Add Gradle extension deployment plugin`)
- Related current follow-up: `public-dsl-shape-changes.md`
- Summary: deployment-project setup moved to the new
  `io.quarkus.extension.deployment` plugin. The runtime plugin resolves
  deployment-project metadata through dependencies and a marker variant instead
  of mutating a deployment project after evaluation.

### [P1-EP-02] Deployment `Test.doFirst` builds the application model from live Gradle state

- Fixed by:
  - `f56491a335f` (`Rework Gradle application model task wiring`)
  - `a3e685d7172` (`Add Gradle extension deployment plugin`)
- Archived history:
  `archive/legacy/history/p1-ep-02-application-model-generation-plan.md`
- Summary: application-model generation is represented by launch-mode-aware
  generated model tasks and consumed by tests, dev mode, go-offline, info, and
  update paths where this branch touches them.

### [P1-EP-03] `ExtensionDescriptorTask` has undeclared inputs/outputs and live extension state

- Fixed by: `a3e685d7172` (`Add Gradle extension deployment plugin`)
- Summary: extension descriptor generation now uses modeled inputs and outputs
  compatible with Gradle up-to-date and build-cache tracking.

### [P1-EP-04] `ValidateExtensionTask` resolves hidden live configurations during execution

- Fixed by: `a3e685d7172` (`Add Gradle extension deployment plugin`)
- Summary: validation inputs are modeled explicitly enough for the current
  configuration-cache and parallel-execution test coverage.

### [P1-EP-05] Annotation processor wiring resolves compile classpath while dependencies are being configured

- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`)
- Summary: annotation processor version selection now prefers platform/BOM
  metadata and falls back to a debug-logged compile-classpath lookup only when
  necessary.

### [P1-EP-06] Legacy deployment-classpath fallback mutates dependency graph in `beforeResolve`

- Fixed by: `a3e685d7172` (`Add Gradle extension deployment plugin`)
- Archived history:
  `archive/legacy/history/p1-ep-06-legacy-deployment-classpath-plan.md`
- Summary: the legacy extension-plugin-local deployment-classpath fallback was
  removed. The public `disableQuarkusComponentVariants` compatibility decision
  remains deferred separately.

### Extension deployment generated test-model isolation

- Fixed by: local follow-up implementation after the Phase 1 branch stack.
- Current design: `extension-deployment-test-model-isolation.md`
- Archived plan:
  `archive/extension-deployment-test-model-isolation-implementation-plan.md`
- Summary: generated `io.quarkus.extension.deployment` test application models
  now use a current-project descriptor plus shared artifact-view local output
  resolution instead of `ProjectDescriptorBuilder.buildForApp(project)` and the
  legacy all-project workspace scan. Broader integration coverage remains
  deferred in `new-application-plugin-design.md`.

## Application Plugin

### [P1-AP-03] Code generation cache key omits the actual source-parent set passed to the worker

- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`)
- Summary: code-generation task inputs now model the source directories passed
  to workers.

### [P1-AP-04] Cacheable app-parts task writes undeclared scratch state under `build/`

- Fixed by: `f56491a335f` (`Rework Gradle application model task wiring`)
- Summary: local scratch/output state for cacheable app-parts paths is declared
  so Gradle can distinguish cacheable outputs from local task state.

### Application-plugin cleanup without standalone finding IDs

- Fixed by:
  - `5d27241513c` (`Warn about Gradle DSL changes for Quarkus 4`)
  - `f56491a335f` (`Rework Gradle application model task wiring`)
- Summary: removed the deprecated `quarkusTestConfig` task, introduced shared
  task base plumbing, removed live Gradle project helper APIs from the
  application extension, routed native build aliases through modeled extension
  state, and added deprecated DSL usage diagnostics.

### New application-plugin launch slices

- Fixed by: local follow-up implementation after the Phase 1 branch stack.
- Current designs:
  - `quarkus-run-task-design.md`
  - `quarkus-dev-continuous-build-design.md`
  - `quarkus-remote-dev-task-design.md`
- Archived plans:
  - `archive/quarkus-run-task-implementation-plan.md`
  - `archive/quarkus-dev-continuous-build-implementation-plan.md`
  - `archive/quarkus-remote-dev-task-implementation-plan.md`
- Summary: the standalone `io.quarkus.application` plugin now has implemented
  named JVM package run tasks, `quarkusApplicationDev --continuous` for the
  first production-output delivery slice, and a standalone
  `quarkusApplicationRemoteDev` client task with an internal mutable-jar
  producer under `build/quarkus-remote-dev`. Continuous testing, stdin/control
  UX, broader remote-dev backend integration coverage, and cache/input
  hardening remain deferred follow-ups.

## Test And Fixture Infrastructure

### Shared TestKit fixtures

- Fixed by: `bc099e63e87` (`Share Gradle TestKit fixtures across plugin tests`)
- Summary: shared Gradle TestKit support moved into `gradle-model` test
  fixtures so application and extension plugin tests use one baseline.

### Integration-test rewiring for the new plugin/model shape

- Fixed by: `3c5d783cf73` (`Update Gradle integration tests for plugin rewiring`)
- Summary: Gradle integration-test fixtures were updated for the extension
  deployment plugin and rewritten application-model/dependency behavior.
