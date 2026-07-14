# Gradle CC Workstream

Status: current
Last reviewed: 2026-07-14

This tracked WIP workstream captures Quarkus Gradle plugin work for Gradle
configuration-cache, build-cache, and project-isolation compatibility.

The historical Phase 1 review is complete. Current work is no longer a pure
read-only review; it is an incremental Quarkus 4 branch with focused follow-up
findings, migration notes, and evidence captured separately.

## Current Source Of Truth

- `tracker.md`: current branch state, active findings, next work.
- `phase-1-consolidated-review.md`: active findings grouped by build
  infrastructure, `gradle-model`, extension plugin, and application plugin.
- `fixed-findings.md`: concise ledger of findings fixed or materially advanced
  by the current rewritten branch.
- `deferred-findings.md`: findings intentionally removed from near-term work.
- `public-dsl-shape-changes.md`: public Gradle DSL/API changes that need PR,
  migration, and release-note attention.
- `declared-dependencies-gradle-native-design.md`: current dependency-model
  design record; M2 remains a follow-up tied to the broader build-tool-agnostic
  dependency model effort.
- `new-application-plugin-design.md`: current design for a separate Gradle-native
  Quarkus application plugin with hard configuration-cache and
  isolated-projects gates; the current implementation lives in
  `devtools/gradle/gradle-app-plugin` with plugin ID
  `io.quarkus.application`.
- `application-plugin-coexistence-design.md`: WIP design notes for migration
  mode when both legacy `io.quarkus` and new `io.quarkus.application` are
  applied to one project.
- `application-model-and-codegen.md`: working reference for what Quarkus
  application models contain, why code generation needs them, and which
  workspace-module/source-folder details are production-build requirements
  versus dev/tooling concerns.
- `build-tooling-model-design.md`: starter design record for the remaining
  Gradle tooling-model/project-isolation problem after the standalone
  `io.quarkus.application` plugin split.
- `extension-deployment-test-model-isolation.md`: implemented focused fix for
  replacing the `io.quarkus.extension.deployment` generated test model's
  cross-project workspace scan with shared Gradle artifact-view local-output
  resolution; broader integration coverage remains deferred.
- `tooling-model-consumers-investigation.md`: evidence about which IDEs and
  Quarkus-side paths actually consume the Gradle `ApplicationModel` tooling
  model.
- `pom-resolution-boundary-design.md`: implemented design for the new plugin's
  Gradle-native POM/effective-model enrichment path. The implemented slice
  covers the new `io.quarkus.application` task-produced package/build model;
  legacy application-model, extension deployment test model, and tooling-model
  paths remain follow-ups.
- `quarkus-dev-continuous-build-design.md`: current design and investigation
  record for Gradle-native `quarkusApplicationDev --continuous`, including
  implemented production-output delivery and deferred continuous-testing,
  stdin/control, dependency/classpath rebootstrap, and integration-test work.
- `quarkus-remote-dev-task-design.md`: current design record for the
  implemented standalone `quarkusApplicationRemoteDev` client task, its
  internal mutable-jar package producer, and remaining remote-dev follow-ups.
- `gradle-app-plugin-parity-investigation.md`: working inventory of hidden
  legacy `io.quarkus` application-plugin and `integration-tests/gradle`
  contracts that the new `io.quarkus.application` plugin should preserve,
  explicitly defer, or cover with regression tests.
- `quarkus-core-external-build-updates-design.md`: Quarkus core/dev-mode design
  seed for consuming externally built class/resource output changes from Gradle
  or another build tool.
- `quarkus-core-external-build-updates-implementation-plan.md`: implementation
  plan for the core/dev-mode external build-output seams needed before
  Gradle-native dev and continuous testing can be wired end to end.
- `kotlin-kapt-generated-sources-design.md`: implemented design note for the
  new-plugin generated-source wiring gap for Kotlin/JVM and KAPT, including the
  KSP/source-set cycle constraints.
- `quarkus-run-task-design.md`: current design record for the implemented named
  JVM package run tasks and their remaining parity-test follow-ups.
- `application-plugin-build-shapes/`: current P1-AP-02 design docs for the
  named Quarkus application build model now hosted by
  `io.quarkus.application`; phase implementation plans are archived there.
- `task-cacheability-follow-up.md`: later cross-task cacheability review.

## Current Branch Stack

The current local `gradle-app-cc` stack is:

- `5d27241513c` (`Warn about Gradle DSL changes for Quarkus 4`)
- `67113760350` (`Require Gradle 9.6 for Quarkus Gradle plugins`)
- `bc099e63e87` (`Share Gradle TestKit fixtures across plugin tests`)
- `f56491a335f` (`Rework Gradle application model task wiring`)
- `a3e685d7172` (`Add Gradle extension deployment plugin`)
- `3c5d783cf73` (`Update Gradle integration tests for plugin rewiring`)

## Active Findings

- `P1-GM-01`: component-variant provider mutates resolution metadata while
  resolving dependencies.
- `P1-GM-03`: application-model and project-declared dependency paths read other
  projects' mutable model.
- `P1-AP-01`: legacy `io.quarkus` application plugin cross-project task wiring
  blocks isolated projects. This does not describe the new
  `io.quarkus.application` task model.
- `P1-AP-02`: legacy `io.quarkus` cacheable build tasks still have late
  build-service hidden input paths. The new plugin's named-output model is the
  intended resolution; remaining work is validation and cache hardening.
- `P1-AP-05`: legacy `io.quarkus` workers still receive broad environment/fork
  customization that is not fully modeled as cache input state. The new plugin
  reduces this to a conservative build-cache claim question, not a direct
  isolated-project blocker.

## Archive

Historical plans, CI inventories, and investigation artifacts were moved out of
the top level:

- `archive/legacy/history/`: superseded implementation plans and branch-rewrite notes.
- `archive/legacy/evidence/`: CI failure inventories, dry-run evidence, local
  investigation notes, and one-off Gradle init scripts.
- `archive/new-application-plugin-move-investigation.md`: historical
  source/test/module inventory for moving named application tasks out of the
  legacy plugin.
- `archive/new-application-plugin-implementation-plan.md`: completed plan for
  creating `gradle-app-plugin` and moving the named application task model.
- `archive/pom-resolution-boundary-implementation-plan.md`: completed
  implementation record for the new-plugin POM/effective-model enrichment
  slice.
- `archive/p1-ap-01-codegen-project-walk-plan.md` and
  `archive/p1-ap-01-codegen-implementation-plan.md`: completed records for the
  new-plugin codegen task model.
- `archive/kotlin-kapt-generated-sources-implementation-plan.md`: completed
  implementation record for Kotlin/JVM and KAPT generated-source wiring.
- `archive/quarkus-run-task-implementation-plan.md`: completed implementation
  record for named JVM package run tasks.
- `archive/quarkus-dev-continuous-build-implementation-plan.md`: completed
  implementation record for the first Gradle-native `quarkusApplicationDev`
  production-output delivery slice.
- `archive/quarkus-remote-dev-task-implementation-plan.md`: completed
  implementation record for the standalone Gradle-native
  `quarkusApplicationRemoteDev` task.
- `archive/extension-deployment-test-model-isolation-implementation-plan.md`:
  completed implementation record for generated extension-deployment test models
  under configuration cache and isolated projects.
- `archive/p1-ep-01-deployment-project-plugin-plan.md`: completed
  implementation record for the `io.quarkus.extension.deployment` plugin split.
- `archive/quarkus-core-external-build-transport-implementation-plan.md`:
  completed implementation record for the TCP production-output transport
  slice.
- `archive/gradle-app-plugin-holistic-review.md`: closed review ledger for
  the `gradle-app-plugin` holistic pass; use active design docs for current
  follow-up decisions.
- `archive/agent-handoffs/`: historical continuation packets from agent runs.

Treat archived docs as context and evidence, not as current implementation
plans. Prefer the current source-of-truth files above when deciding next work.

## Finding ID Scheme

- `P1-BI-##`: build infrastructure findings.
- `P1-GM-##`: `gradle-model` findings.
- `P1-EP-##`: extension plugin findings.
- `P1-AP-##`: application plugin findings.

Fixed findings move to `fixed-findings.md`; deferred findings move to
`deferred-findings.md`. Keep IDs stable when discussing later PRs.

## Constraints

- Do not fix everything in one PR.
- Preserve behavior unless a finding shows the behavior is incompatible or
  incorrect.
- Prefer small PRs with targeted tests and clear review boundaries.
- Do not mark additional tasks cacheable until inputs, outputs, normalization,
  local state, worker parameters, and build-service interactions have been
  reviewed together.
- Keep remote mutations, issue comments, PR comments, and pushes out of scope
  unless explicitly requested.
