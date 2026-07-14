# Deferred Findings

Findings moved here are not active near-term work items. Keep the original ID
stable so later PRs, discussions, or roadmap updates can refer back to the same
finding.

## [P1-BI-02] Maven bridge forces `clean` before every Gradle build

- Area: build infrastructure
- Original report: `phase-1-build-infra.md`
- Status: deferred
- Reason: the forced `clean` is not ideal for cache/up-to-date validation, but
  it was added in response to explicit reviewer feedback when the build scripts
  were updated. Changing it should be treated as a separate discussion rather
  than a narrow compatibility cleanup.
- Revisit trigger: concrete evidence that the Maven bridge is blocking useful
  Gradle cache validation, or renewed agreement that the reviewer-requested
  behavior can become opt-in.

## [P1-BI-03] Cache/configuration-cache TestKit coverage is single-Gradle-version only

- Area: build infrastructure
- Original report: `phase-1-build-infra.md`
- Status: deferred
- Reason: the local `devtools/gradle` wrapper is already on Gradle `9.6.0`,
  which is good enough for the current workstream. Adding a latest-8.x/latest-9.x
  matrix would increase test runtime and maintenance cost before we have fixes
  that need the extra coverage.
- Revisit trigger: a Gradle-version-specific failure, an explicit supported
  version compatibility claim, or a later PR that needs a focused version matrix
  to protect behavior.

## [disable-quarkus-component-variants-removal] Very late compatibility decision for `disableQuarkusComponentVariants`

- Area: extension plugin / `gradle-model`
- Related fixed finding: `P1-EP-06`
- Status: very late follow-up
- Reason: removing the extension-plugin-local `beforeResolve` fallback did not
  require removing the public `disableQuarkusComponentVariants` escape hatch.
  The property is still documented and recent history shows it was useful while
  the component-variant path was being hardened. Whether to deprecate or remove
  the property should be a separate compatibility decision, not a blocker for
  the current configuration-cache/project-isolation cleanup plan.
- Inputs needed before revisiting:
  - public docs update/deprecation plan for `disableQuarkusComponentVariants`;
  - confirmation that known post-component-variant regressions handled by the
    escape hatch have fixes in the variant path;
  - confidence from regression coverage for dev-only dependency leakage,
    exclusions/platform constraints, extension-plugin generated models, dev
    compile-only dependencies, and IDE/tooling model sync.
- Possible outcomes:
  - deprecate the property first, then remove the legacy resolver in a later
    release;
  - or keep the property as a documented legacy escape hatch with explicitly
    limited configuration-cache/project-isolation compatibility claims.
- Revisit trigger: a project-level decision to retire the escape hatch, a
  release planning discussion around old resolver removal, or a compatibility
  claim that must include or exclude the legacy flag explicitly.
