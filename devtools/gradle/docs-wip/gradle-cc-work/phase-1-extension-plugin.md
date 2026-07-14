# Phase 1: Extension Plugin Review

Read-only review of `devtools/gradle/gradle-extension-plugin` for Gradle
build-cache, configuration-cache, and isolated-projects compatibility. This
report is informed by `phase-1-gradle-model.md`; inherited `gradle-model` issues
are noted only when the extension plugin adds a distinct symptom or mitigation.

## Findings

No active extension-plugin-specific Phase 1 findings remain in this shard.

`P1-EP-01` is fixed in the rewritten branch by the deployment-project plugin split recorded in
`fixed-findings.md` and documented by
`archive/p1-ep-01-deployment-project-plugin-plan.md`. The runtime plugin no longer
uses `afterEvaluate` to find and mutate the deployment project; deployment-side
setup moved to `io.quarkus.extension.deployment`, and runtime validation now
resolves deployment information through project dependencies plus a dedicated
marker variant.

Keep extension-plugin migration/docs follow-up in
`public-dsl-shape-changes.md`; use
`archive/p1-ep-01-deployment-project-plugin-plan.md` as implementation history.
Any remaining isolated-projects failures should first be checked against
inherited shared `gradle-model` findings `P1-GM-01` and `P1-GM-03`.

## Verification Gap

Existing extension-plugin tests cover functional descriptor/validation behavior,
generated application-model behavior, component-variant regression scenarios,
and one `--parallel` validation case. Missing coverage remains around
broader isolated-projects smoke testing after the shared `gradle-model`
blockers are addressed.
