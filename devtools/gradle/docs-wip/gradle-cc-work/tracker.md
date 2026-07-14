# Live Tracker

Status: current
Last reviewed: 2026-07-14

## Objective

Track the Quarkus Gradle configuration-cache, build-cache, and
project-isolation workstream after the Phase 1 review and branch rewrite.

## Current Phase

Quarkus 4 branch refinement after rewrite. The immediate focus is choosing the
next small active finding while keeping public DSL/migration notes accurate.

## Current Branch Stack

- `5d27241513c` (`Warn about Gradle DSL changes for Quarkus 4`)
- `67113760350` (`Require Gradle 9.6 for Quarkus Gradle plugins`)
- `bc099e63e87` (`Share Gradle TestKit fixtures across plugin tests`)
- `f56491a335f` (`Rework Gradle application model task wiring`)
- `a3e685d7172` (`Add Gradle extension deployment plugin`)
- `3c5d783cf73` (`Update Gradle integration tests for plugin rewiring`)

The stack intentionally folds many earlier local learning commits into the
larger reviewable commits above. Archived docs may still mention old local
SHAs; treat those as historical breadcrumbs only.

## Current Source Artifacts

- `README.md`: workstream entrypoint and current source-of-truth map.
- `phase-1-consolidated-review.md`: active findings and current state.
- `phase-1-gradle-model.md`: active `gradle-model` findings.
- `phase-1-application-plugin.md`: active application-plugin findings.
- `phase-1-extension-plugin.md`: extension-plugin state; no active
  extension-plugin-specific findings remain.
- `phase-1-build-infra.md`: build-infra state; no active build-infra findings
  remain.
- `fixed-findings.md`: current branch fixed-work ledger.
- `deferred-findings.md`: intentionally deferred findings.
- `public-dsl-shape-changes.md`: public DSL/API removals and migration notes.
- `declared-dependencies-gradle-native-design.md`: current design record for
  the declared-dependency M2 follow-up.
- `new-application-plugin-design.md`: current design direction for the separate
  Gradle-native application plugin, `io.quarkus.application`.
- `gradle-app-plugin-parity-investigation.md`: working inventory of hidden
  legacy application-plugin and `integration-tests/gradle` behavior contracts
  to preserve, explicitly defer, or cover with regression tests for
  `io.quarkus.application`.
- `kotlin-kapt-generated-sources-design.md`: implemented design note for
  closing the new-plugin Kotlin/JVM and KAPT generated-source wiring gap
  without reintroducing the KSP/source-set cycle.
- `quarkus-run-task-design.md`: design record for implemented named JVM
  package run tasks and remaining parity-test follow-ups.
- `quarkus-remote-dev-task-design.md`: design record for the implemented
  standalone Gradle-native `quarkusApplicationRemoteDev` task and its remaining
  remote-dev follow-ups.
- `extension-deployment-test-model-isolation.md`: implemented focused fix for
  generated extension-deployment test models under configuration cache and
  isolated projects; broader integration coverage remains deferred.
- `application-plugin-build-shapes/design.md`: detailed named-output build,
  image, deployment, AOT, JVM package, and native task model now hosted by
  `io.quarkus.application`.
- `application-plugin-build-shapes/phase-b-task-topology.md`: Phase B task
  names, task types, dependency edges, convenience-task boundaries, and
  execution diagrams.
- `application-plugin-build-shapes/archive/phase-a/implementation-plan.md`:
  historical Phase A implementation plan for the pre-split named-output model.
- `archive/new-application-plugin-implementation-plan.md`: completed plan for
  moving the named-output model into `gradle-app-plugin`.
- `task-cacheability-follow-up.md`: later task-cacheability review.
- `system-property-env-inventory.md`: remaining system-property/environment
  inventory.

Archived context:

- `archive/legacy/history/`: superseded implementation plans and branch-rewrite notes.
- `archive/legacy/evidence/`: CI inventories, investigation notes, dry-run evidence,
  and one-off query scripts.
- `archive/new-application-plugin-move-investigation.md`: historical
  investigation that explains the move from legacy-plugin named tasks to
  `gradle-app-plugin`.
- `archive/pom-resolution-boundary-implementation-plan.md`,
  `archive/p1-ap-01-codegen-project-walk-plan.md`,
  `archive/p1-ap-01-codegen-implementation-plan.md`,
  `archive/kotlin-kapt-generated-sources-implementation-plan.md`,
  `archive/quarkus-run-task-implementation-plan.md`,
  `archive/quarkus-dev-continuous-build-implementation-plan.md`,
  `archive/quarkus-remote-dev-task-implementation-plan.md`,
  `archive/extension-deployment-test-model-isolation-implementation-plan.md`,
  `archive/p1-ep-01-deployment-project-plugin-plan.md`,
  and
  `archive/quarkus-core-external-build-transport-implementation-plan.md`:
  completed implementation records moved out of the active plan set.
- `archive/gradle-app-plugin-holistic-review.md`: closed review ledger for the
  new application plugin holistic pass.

## Active Findings

- `P1-GM-01`: component-variant provider mutates resolution metadata while
  resolving dependencies.
- `P1-GM-03`: application-model and project-declared dependency paths read other
  projects' mutable model.
- `P1-AP-01`: legacy `io.quarkus` application plugin cross-project task wiring
  blocks isolated projects. This does not describe the new
  `io.quarkus.application` task model.
- `P1-AP-02`: legacy `io.quarkus` cacheable build tasks read late build-service
  state as hidden input. The new plugin's named-output model is the intended
  resolution; remaining work is validation and cache hardening.
- `P1-AP-05`: legacy `io.quarkus` worker process environment and fork
  customizations remain broad and not fully modeled. The new plugin reduces
  this to a conservative build-cache claim question, not a direct
  isolated-project blocker.

## Fixed Or Contained Work

- Build infrastructure findings are either fixed or deferred.
- Extension-plugin findings `P1-EP-01`, `P1-EP-02`, `P1-EP-03`, `P1-EP-04`,
  and `P1-EP-06` are fixed or contained locally.
- Generated extension-deployment test models now have a focused
  isolated-project local-output fix; broader representative integration coverage
  remains deferred.
- The standalone `io.quarkus.application` plugin now has implemented
  Gradle-native launch slices for named package run tasks,
  `quarkusApplicationDev --continuous`, and the standalone
  `quarkusApplicationRemoteDev` client task. Remaining launch work is mainly
  continuous testing, stdin/control UX, broader remote-dev backend coverage,
  and cache/input hardening.
- `P1-GM-05` M1 containment is implemented in the current rewritten stack by
  moving declared-dependency enrichment back into application-model task
  execution and removing the rejected producer-task shape.
- Gradle 9.6 is now the local Quarkus 4 plugin minimum; Gradle 8-specific gates
  are no longer part of this branch.

## Current Next Step

Best next candidates:

- `P1-GM-01` if the goal is to reduce dependency-resolution/variant mutation
  risk first.
- `P1-GM-03` if the goal is to attack isolated-projects blockers through a
  Gradle-native project metadata contract.
- `P1-AP-02` follow-up if the goal is to continue hardening the explicit
  package/native/image/deploy model now hosted in `io.quarkus.application`.
  Use `new-application-plugin-design.md` for the plugin boundary and
  `application-plugin-build-shapes/design.md` plus
  `application-plugin-build-shapes/phase-b-task-topology.md` for the detailed
  task model. The current direction is a standalone
  `quarkusApplication.builds` named-output model with derived tasks such as
  `quarkusAppBuild`, `quarkusNative1Build`, and
  `quarkusNative1NativeTest`, rather than conditional inputs on one shared
  legacy `quarkusBuild` task.

For `P1-AP-02`, the named-output phases A through F were implemented and then
moved out of the legacy plugin into `devtools/gradle/gradle-app-plugin`.
Archived phase plans remain useful history, but active work should start from
the new-plugin design and current source.

Legacy diagnostics now include all legacy application task usage, including
`quarkusBuild`, `imageBuild`, `imagePush`, `buildNative`, `testNative`,
`deploy`, and `buildAotEnhancedImage`. `quarkusBuild` diagnostics identify
legacy model usage; they do not deprecate the task name. Diagnostics remain
`OFF` by default in Quarkus 4.0 and can be enabled through
`quarkus.diagnostics.legacy-task-usage`.

Gradle JVM Test Suite integration is intentionally deferred to the native/test
execution phase.

Keep `public-dsl-shape-changes.md` and the documentation/migration piece of
`P1-EP-01` visible for PR/release-note work.

M2 for declared dependencies remains a separate follow-up under the broader
build-tool-agnostic dependency model effort. The larger Gradle-native
`quarkusDev` / continuous-build rewrite should reference that model but is not
part of this workstream's current branch stack.
