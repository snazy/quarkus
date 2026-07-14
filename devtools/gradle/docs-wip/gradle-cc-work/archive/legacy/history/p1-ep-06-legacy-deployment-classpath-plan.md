# P1-EP-06 Legacy Deployment Classpath Fallback

Status: historical
Superseded by: ../../tracker.md

This document records the completed `P1-EP-06` work: removing the
extension-plugin legacy deployment-classpath fallback used when
`disableQuarkusComponentVariants=true`.

## Problem

`QuarkusExtensionPlugin.exportDeploymentClasspath(...)` registers
`beforeResolve` callbacks on deployment-project compile classpath
configurations. Those callbacks call `DeploymentClasspathBuilder`, which creates
deployment configurations, duplicates existing configurations, resolves them
with `getResolvedConfiguration()`, discovers Quarkus runtime extensions, and
adds deployment dependencies while classpath resolution is already starting.

That was ordering-sensitive and a poor fit for configuration cache and
isolated projects. The path was legacy and only active when
`disableQuarkusComponentVariants=true`.

## Current Shape

- `QuarkusExtensionPlugin` initializes `ApplicationDeploymentClasspathBuilder`
  configurations on the deployment project.
- `ApplicationDeploymentClasspathBuilder` already has a
  `disableQuarkusComponentVariants` fallback for deployment configurations.
  That path uses provider-backed dependencies on the deployment configuration
  instead of registering extension-plugin `beforeResolve` callbacks.
- The extension-plugin-local `exportDeploymentClasspath(deploymentProject)`
  call and `DeploymentClasspathBuilder` were removed by commit `e1361230e43`
  (`Remove legacy Gradle extension deployment classpath export`).
- `ConditionalDependenciesEnabler`, the legacy resolver used when component
  variants are disabled, remains in the shared `gradle-model` fallback.

## History

`disableQuarkusComponentVariants` was introduced by PR
https://github.com/quarkusio/quarkus/pull/49224, merged July 31, 2025, and
backported as `76f952b7c88`. That PR switched Gradle conditional/deployment
dependency handling to a component-variant-based resolver.

The new resolver fixed https://github.com/quarkusio/quarkus/issues/48992 and
the broader old-resolver problems documented in `docs/src/main/asciidoc/gradle-tooling.adoc`:
the previous implementation did not apply relevant exclusions to enabled
conditional/deployment dependencies and could leak dev-mode-only dependencies
into non-dev-mode classpaths.

The property was kept as an escape hatch "for now" in case the new resolver
introduced regressions. Follow-up history confirms it was useful for real
regressions while the variant path was being hardened:

- https://github.com/quarkusio/quarkus/issues/49470 / PR
  https://github.com/quarkusio/quarkus/pull/49514 fixed an extension-plugin
  ambiguity introduced by component variants. No dedicated local regression
  test was found; the PR discussion explicitly noted that coverage was still
  desired.
- https://github.com/quarkusio/quarkus/issues/49522 / PR
  https://github.com/quarkusio/quarkus/pull/49529 fixed a dev compile-only
  configuration attribute issue.
- https://github.com/quarkusio/quarkus/issues/49743 led to platform-constraint
  hardening in the component-variant path.
- https://github.com/quarkusio/quarkus/issues/49684 appears related to the
  post-component-variant regression set. Regression coverage was added later in
  PR https://github.com/quarkusio/quarkus/pull/55253.
- https://github.com/quarkusio/quarkus/issues/52063 shows the property was
  still used as an IntelliJ/IDE-sync workaround later; that underlying issue
  was fixed separately, but the property was not removed. No direct IDE-sync
  regression test was found.

No explicit scheduled removal issue or PR was found for
`disableQuarkusComponentVariants` itself. That wider property/removal decision
is tracked separately as a very-late follow-up in `deferred-findings.md`; it is
not part of `P1-EP-06`.

## Direction

Delete the extension-plugin `beforeResolve` fallback and rely on the modeled
deployment configurations created by `ApplicationDeploymentClasspathBuilder`.

Do not remove `disableQuarkusComponentVariants` itself as part of P1-EP-06.
That is a wider compatibility decision because the public docs still advertise
the property and recent history shows users have used it as a regression
workaround.

The key P1-EP-06 question is narrower: can the extension-plugin-local
`beforeResolve` fallback disappear while the property still works through
`ApplicationDeploymentClasspathBuilder`'s shared fallback? The old extension
fallback exports deployment configurations derived from `implementation` and
`testImplementation`, while the newer shared builder creates launch-mode
runtime and deployment configurations for `NORMAL`, `DEVELOPMENT`, and `TEST`.
The new coverage exercises the legacy flag in an extension-project fixture and
the generated test application-model path works without the old export hook.

## Completed Work

### `P1-EP-06A`: Legacy-Flag Coverage

Status: covered by PR https://github.com/quarkusio/quarkus/pull/55253
(`Add Gradle component variant regression tests`). The local squashed commit on
this workstream branch is `c2ffd08f846`; the separate test-only branch commit is
`52cfdae90c6`.

Coverage added:

- Add or identify regression coverage for the uncovered post-#49224 cases:
  - extension-plugin variant ambiguity from #49470 / PR #49514;
  - test/classloader configuration regression from #49684 / PR #49762;
  - IDE/tooling-model sync regression from #52063 / PR #52097 outside an IDE.
- Add focused TestKit coverage for an extension project run with
  `-PdisableQuarkusComponentVariants=true`.
- Exercise the deployment test application-model generation path, not only task
  registration.
- Assert that the generated model task and deployment test task complete under
  the legacy flag.

Verification:

- `./gradlew --no-scan :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest`
- `./mvnw -f integration-tests/gradle test -Dtest=TestCompositeBuildWithExtensionsTest -Dstart-containers -Dtest-containers`

Current result:

- The component-variant extension-plugin deployment-test path passes.
- The no-arg `ToolingUtils.create(...)` model-builder path passes.
- The `-PdisableQuarkusComponentVariants=true` extension-plugin deployment-test
  path is now covered by a failing reproducer on the old `7f77cd63e957` shape.
  The issue is that the legacy `DeploymentClasspathBuilder` duplicates
  `quarkusDependency` as a resolvable configuration and then tries to add
  dependencies to that non-declarable configuration.
- The fix was folded into the rewritten `Gradle: Use resolvable configurations
  in gradle-model` commit (`27ecde229c19...`) by making the temporary
  resolvable configuration extend from the source configuration instead of
  copying dependencies into it.
- The source-extension config classloader regression test from #49684 passes on
  `27ecde229c19...` with the integration-test fixture patch applied and on the
  current workstream `HEAD`.

### `P1-EP-06B`: Remove `beforeResolve` Fallback

Status: fixed by commit `e1361230e43` (`Remove legacy Gradle extension
deployment classpath export`).

Implementation:

- Removed `QuarkusExtensionPlugin.exportDeploymentClasspath(...)` and
  `DeploymentClasspathBuilder`.
- Keep the `disableQuarkusComponentVariants` property behavior intact for
  application-model generation by relying on the shared builder's fallback.

Verification:

- Focused extension-plugin tests with and without
  `-PdisableQuarkusComponentVariants=true`.
- Relevant integration-test success gates listed below.

Reasoning:

1. The old fallback:
   - `QuarkusExtensionPlugin.exportDeploymentClasspath(...)` registers
     `beforeResolve` callbacks on the deployment project's
     `compileClasspath` and `testCompileClasspath`.
   - `DeploymentClasspathBuilder` creates deployment configurations derived
     from `implementation` and `testImplementation`.
   - It resolves those temporary configurations, discovers first-met Quarkus
     runtime extensions, and adds their deployment dependencies to the
     generated deployment configurations.
2. The shared fallback:
   - `ApplicationDeploymentClasspathBuilder.initConfigurations(...)` creates
     launch-mode runtime and deployment configurations for `NORMAL`,
     `DEVELOPMENT`, and `TEST`.
   - When `disableQuarkusComponentVariants=true`, the shared builder should use
     the legacy conditional-dependency resolver internally while still avoiding
     extension-plugin-local mutation during classpath resolution.
   - The required behavior for extension deployment tests is that
     `GenerateApplicationModelTask` for `LaunchMode.TEST` still resolves an
     application model with the expected runtime and deployment dependencies.
3. The focused extension-plugin regression coverage passes without the old
   fallback, so the old late mutation path is duplicate behavior for the tested
   generated-model use case.

Primary command set:

- `cd devtools/gradle && ./gradlew --no-scan --rerun-tasks :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest`
- `./mvnw -f integration-tests/gradle test -Dtest=TestCompositeBuildWithExtensionsTest -Dstart-containers -Dtest-containers`

Broader command set if the removal touches shared fallback behavior:

- `./mvnw -f integration-tests/gradle test -Dtest=DevDepsLeakIntoProdClaspathTest,EnforcingPlatformForConditionalDepsTest,CompileOnlyExtensionDependencyDevModeTest,ConditionalDependenciesTest,ConditionalDependenciesKotlinTest,TestCompositeBuildWithExtensionsTest,TestFixtureMultiModuleTest -Dstart-containers -Dtest-containers`

Local implementation:

- Removed the extension-plugin-local
  `QuarkusExtensionPlugin.exportDeploymentClasspath(...)` call and helper.
- Deleted `DeploymentClasspathBuilder`.
- Kept `disableQuarkusComponentVariants` and the shared
  `ApplicationDeploymentClasspathBuilder` fallback untouched.

Local verification:

- `cd devtools/gradle && ./gradlew --no-scan --rerun-tasks :gradle-extension-plugin:test --tests io.quarkus.extension.gradle.QuarkusExtensionPluginTest`
  passes: 8 tests, 0 failures.
- `./mvnw -f integration-tests/gradle test -Dtest=TestCompositeBuildWithExtensionsTest -Dstart-containers -Dtest-containers`
  passes: 1 test, 0 failures.
- The broader historical gate with the tests from commit `c2ffd08f846` was run.
  The component-variant-related success gates pass:
  `DevDepsLeakIntoProdClaspathTest`,
  `EnforcingPlatformForConditionalDepsTest`,
  `ConditionalDependenciesKotlinTest`,
  `TestCompositeBuildWithExtensionsTest`, and
  `TestFixtureMultiModuleTest`.
- That broader gate still fails with the same known local failure shape seen
  before this removal: `ConditionalDependenciesTest` has failures/errors caused
  by missing locally published `org.acme:ext-t`, `org.acme:ext-l`, and
  `org.acme:ext-m` artifacts plus an extension dependency verification failure
  during the fixture publish step; `CompileOnlyExtensionDependencyDevModeTest`
  returns `BROKEN: quarkusDev mode has terminated` instead of `hello`.

## Success Gates

Existing coverage that should stay green for P1-EP-06 changes:

- `DevDepsLeakIntoProdClaspathTest`: direct #48992 regression coverage for
  dev-mode dependencies leaking into non-dev classpaths.
- `EnforcingPlatformForConditionalDepsTest`: direct platform/BOM constraint and
  exclusion coverage for conditional dependencies after variant-path hardening.
- `CompileOnlyExtensionDependencyDevModeTest`: coverage for the dev
  compile-only attributes regression fixed after #49522.
- `ConditionalDependenciesTest` and `ConditionalDependenciesKotlinTest`: broad
  conditional/deployment dependency behavior coverage.
- `TestCompositeBuildWithExtensionsTest` and `TestFixtureMultiModuleTest`:
  application-model dependency flag coverage for composite/test-fixture
  scenarios affected by dependency graph changes.
- New P1-EP-06 coverage for `-PdisableQuarkusComponentVariants=true` in the
  extension-plugin generated-model/deployment-test path.

## Open Questions

- The current local test evidence says generated model tasks are served by
  `ApplicationDeploymentClasspathBuilder`; no dependency on the old
  `DeploymentClasspathBuilder` export configurations has been observed in the
  focused extension-plugin regression tests.
- The current regression coverage asserts task success for the generated-model
  and deployment-test path under the legacy flag. Add model-content assertions
  only if future changes expose a gap in that success signal.
