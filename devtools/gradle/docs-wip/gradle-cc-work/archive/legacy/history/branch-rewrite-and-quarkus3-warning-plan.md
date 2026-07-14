# Gradle App CC Branch Rewrite And Quarkus 3 Warning Plan

Date: 2026-07-06

Status: historical
Superseded by: ../../tracker.md

Audience: Gradle configuration-cache / project-isolation workstream

## Goals

- Prepare a small Quarkus 3 PR that warns users about Gradle plugin DSL/API
  changes planned for Quarkus 4.
- Rewrite the `gradle-app-cc` Quarkus 4 branch so the final commit stack tells
  the current design story directly.
- Remove intermediate local designs from public history, especially commits
  that introduced producer-task declared-dependency wiring later superseded by
  M1 containment.
- Keep each rewritten commit reviewable, testable, and meaningful on its own.

## Non-Goals

- Do not backport Quarkus 4 implementation changes into Quarkus 3.
- Do not remove the legacy Gradle DSL/API shape in Quarkus 3.
- Do not make the Quarkus 4 branch rewrite a single public squash commit.
- Do not include the larger Gradle-native `quarkusDev` / continuous-build
  rewrite in this stack. That work is related and should reference the broader
  build-tool-agnostic dependency model, but it is not part of this branch
  rewrite.

## Commit 0: Quarkus 3 Warning / Deprecation PR

This should be the first public slice before the Quarkus 4 cleanup lands.

Target branch: Quarkus 3 branch.

Shape:

1. Keep behavior unchanged.
2. Add deprecation annotations and Javadocs to Gradle plugin APIs that still
   exist in Quarkus 3 but are planned for removal or narrowing in Quarkus 4.
3. Add low-risk forward-compatible managed properties to Quarkus 3 where that
   lets a build script work against both Quarkus 3 and Quarkus 4.
4. Add user-facing migration documentation with an inventory of the Gradle DSL
   and API changes planned for Quarkus 4, including Groovy and Kotlin DSL
   examples.
5. Add targeted tests only if needed to prove deprecated aliases still work.

Compatibility shim policy:

- Prefer documenting the managed-property shape when it already exists in
  Quarkus 3.
- Add new Quarkus 3 managed properties only when the implementation is
  behavior-preserving, small, and directly useful for Quarkus 3 / Quarkus 4
  build-script compatibility.
- Do not backport Quarkus 4 implementation rewrites into Quarkus 3.
- Do not remove the old Quarkus 3 DSL shape. The old shape should delegate to
  the new property where practical.
- The strongest current candidate is adding `nativeArguments` to the Quarkus 3
  application extension and routing `QuarkusBuild.nativeArgs(Action)` through
  it, so users can migrate native arguments before Quarkus 4.

Q3 / Q4 compatibility inventory from local ref comparison:

- Already available in Quarkus 3 and suitable to document as Q3 / Q4 compatible:
  - `QuarkusPluginExtension.getFinalName()`
  - `QuarkusPluginExtension.getCleanupBuildOutput()`
  - `QuarkusPluginExtension.getCacheLargeArtifacts()`
  - `QuarkusPluginExtension.getCodeGenerationInputs()`
  - `QuarkusPluginExtension.getCodeGenerationProviders()`
  - `QuarkusPluginExtension.manifest(Action<Manifest>)`
  - `QuarkusPluginExtension.sourceSets(Action<? super SourceSetExtension>)`
  - `QuarkusPluginExtension.buildForkOptions(Action<? super JavaForkOptions>)`
  - `QuarkusPluginExtension.codeGenForkOptions(Action<? super JavaForkOptions>)`
  - `QuarkusPluginExtension.getQuarkusBuildProperties()`
  - `QuarkusPluginExtension.getCachingRelevantProperties()`
  - `QuarkusPluginExtension.set(String, String)`
  - `QuarkusPluginExtension.set(String, Provider<String>)`
  - `QuarkusExtensionConfiguration.getDeploymentArtifact()`
  - `QuarkusExtensionConfiguration.getDeploymentModule()`
  - `QuarkusExtensionConfiguration.getExcludedArtifacts()`
  - `QuarkusExtensionConfiguration.getParentFirstArtifacts()`
  - `QuarkusExtensionConfiguration.getRunnerParentFirstArtifacts()`
  - `QuarkusExtensionConfiguration.getLesserPriorityArtifacts()`
  - `QuarkusExtensionConfiguration.getConditionalDependencies()`
  - `QuarkusExtensionConfiguration.getConditionalDevDependencies()`
  - `QuarkusExtensionConfiguration.getDependencyConditions()`
  - `QuarkusExtensionConfiguration.capabilities(Action<Capabilities>)`
  - `QuarkusExtensionConfiguration.removedResources(Action<RemovedResources>)`
  - `QuarkusExtensionConfiguration.getProvidedCapabilities()`
  - `QuarkusExtensionConfiguration.getRequiredCapabilities()`
  - `QuarkusExtensionConfiguration.getRemoveResources()`
- Missing in Quarkus 3 but suitable as cheap compatibility shims:
  - `QuarkusPluginExtension.getNativeArguments()`: add a
    `MapProperty<String, String>`, copy it into `QuarkusPluginExtensionView`,
    merge it with task-local forced properties in `QuarkusBuildTask` and
    `QuarkusShowEffectiveConfig`, and route
    `QuarkusBuild.nativeArgs(Action<Map<String, ?>>)` through it.
  - `QuarkusExtensionConfiguration.getDisableValidation()`: return the
    existing `disableValidation` property and make `isValidationDisabled()`
    delegate to it.
- Do not backport as Quarkus 3 shims:
  - abstract Gradle-managed extension type conversion;
  - extension task cacheability rewrites;
  - deployment marker and deployment classpath rewrites;
  - `quarkusTestConfig` task removal;
  - live project/model helpers such as `resourcesDir()`,
    instance `combinedOutputSourceDirs()`, `appJarOrClasses()`,
    `getAppModelResolver(...)`, and `getApplicationModel(...)`;
  - `getNativeBuild()` unless it becomes an explicit public migration API
    decision.

`Action`-based DSL policy:

- Keep `Action` methods when they preserve a useful nested Gradle DSL shape,
  especially for Kotlin DSL users:
  - `QuarkusPluginExtension.manifest(Action<Manifest>)`
  - `QuarkusBuild.manifest(Action<Manifest>)`
  - `QuarkusPluginExtension.sourceSets(Action<? super SourceSetExtension>)`
  - `QuarkusPluginExtension.buildForkOptions(Action<? super JavaForkOptions>)`
  - `QuarkusPluginExtension.codeGenForkOptions(Action<? super JavaForkOptions>)`
  - `QuarkusExtensionConfiguration.capabilities(Action<Capabilities>)`
  - `QuarkusExtensionConfiguration.removedResources(Action<RemovedResources>)`
  - `QuarkusDev.compilerOptions(Action<CompilerOptions>)`
  - `QuarkusDev.extensionJvmOptions(Action<ExtensionDevModeJvmOptionFilter>)`
- Deprecate for removal in Quarkus 3, and remove in Quarkus 4, `Action`
  methods that only wrap mutation of a temporary collection when a Gradle
  managed property is available.
- Current known removal candidate:
  - `QuarkusBuild.nativeArgs(Action<Map<String, ?>>)`, once
    `QuarkusPluginExtension.getNativeArguments()` exists as the replacement.

Documentation policy:

- The Quarkus 3 PR should add or update a user-facing documentation page with a
  complete inventory of the Gradle plugin DSL/API changes planned for Quarkus 4.
- The inventory should explicitly define the intended user-facing Gradle DSL
  surface. Public Java visibility alone should not be presented as a support
  guarantee, because some public getters exist for Gradle task/property
  modeling or plugin internals rather than for build-script authors.
- The inventory should distinguish APIs that already have a Quarkus 3 / Quarkus
  4 compatible replacement from APIs that only have a Quarkus 4 migration
  target.
- Public APIs that are not intended as stable build-script DSL should be listed
  as implementation exposure where useful, with guidance not to rely on them
  for Quarkus 4 compatibility unless they are explicitly documented as
  supported DSL.
- The page should include copy/pasteable Groovy and Kotlin DSL examples for
  each common migration path.
- Warning messages should point to this page instead of embedding long migration
  guidance in the build output.
- The warning URL should point to a concrete Quarkus 3 documentation version,
  not a `latest` URL. The exact versioned URL can be adjusted manually before
  the Quarkus 3 PR is finalized.

Warn about:

- Deprecated `quarkusTestConfig` task removal in Quarkus 4.
- `QuarkusPluginExtension` live helper / convenience APIs planned for removal:
  - `finalName()`
  - `setFinalName(String)`
  - `setCleanupBuildOutput(boolean)`
  - `setCacheLargeArtifacts(boolean)`
  - `setCodeGenerationInputs(List<String>)`
  - `setCodeGenerationProviders(List<String>)`
  - `resourcesDir()`
  - `combinedOutputSourceDirs()`
  - `appJarOrClasses()`
  - `getAppModelResolver(...)`
  - `getApplicationModel(...)`
- `QuarkusBuild.nativeArgs(Action<Map<String, ?>>)`, replaced by
  `QuarkusPluginExtension.getNativeArguments()`.
- Preferred `QuarkusPluginExtension` managed-property shape:
  - `getFinalName().set(...)`
  - `getCleanupBuildOutput().set(...)`
  - `getCacheLargeArtifacts().set(...)`
  - `getCodeGenerationInputs().set(...)`
  - `getCodeGenerationProviders().set(...)`
  - `getQuarkusBuildProperties()`
  - `getNativeArguments()`
  - `getCachingRelevantProperties()`
  - `manifest(Action<Manifest>)`
  - `sourceSets(Action<? super SourceSetExtension>)`
  - `buildForkOptions(Action<? super JavaForkOptions>)`
  - `codeGenForkOptions(Action<? super JavaForkOptions>)`
  - `set(String, String)` and `set(String, Provider<String>)`
- `QuarkusExtensionConfiguration` JavaBean setter aliases planned for removal:
  - `setDisableValidation(boolean)`
  - `isValidationDisabled()`
  - `setDeploymentArtifact(String)`
  - `setDeploymentModule(String)`
  - `setExcludedArtifacts(List<String>)`
  - `setParentFirstArtifacts(List<String>)`
  - `setRunnerParentFirstArtifacts(List<String>)`
  - `setLesserPriorityArtifacts(List<String>)`
  - `setConditionalDependencies(List<String>)`
  - `setConditionalDevDependencies(List<String>)`
  - `setDependencyConditions(List<String>)`
- Preferred `QuarkusExtensionConfiguration` managed-property shape:
  - `getDisableValidation().set(...)`
  - `getDeploymentArtifact().set(...)`
  - `getDeploymentModule().set(...)`
  - `getExcludedArtifacts().set(...)`
  - `getParentFirstArtifacts().set(...)`
  - `getRunnerParentFirstArtifacts().set(...)`
  - `getLesserPriorityArtifacts().set(...)`
  - `getConditionalDependencies().set(...)`
  - `getConditionalDevDependencies().set(...)`
  - `getDependencyConditions().set(...)`
  - `capabilities(Action<Capabilities>)`
  - `removedResources(Action<RemovedResources>)`

Open decision:

- `QuarkusBuild.nativeArgs(Action)` is deprecated for removal in Quarkus 3 and
  removed in Quarkus 4. `QuarkusPluginExtension.getNativeArguments()` is the
  replacement.

Warning policy:

- Prefer deprecation annotations, Javadocs, and migration docs over broad build
  warnings.
- Runtime/build warnings should only be emitted when a user explicitly calls a
  deprecated API. Do not warn merely because the Gradle plugin is applied.
- Only instrument deprecated APIs for runtime diagnostics when internal plugin
  code does not call the same public method, or when internal call sites can be
  moved to a private/package-private delegate first. Do not use stack-trace
  heuristics to guess whether a call came from a build script.
- Preferred pattern for APIs that are also used internally:
  - keep the current public method as the deprecated user-facing wrapper;
  - make that wrapper record deprecated DSL usage and then delegate;
  - route Quarkus internal callers to a non-recording delegate or directly to
    the underlying managed property;
  - use a private or package-private delegate when possible;
  - if Gradle/package boundaries require the delegate to be public, use an
    explicit `Internal` suffix, such as `setFinalNameInternal(...)`, plus
    Javadocs that say it is not intended as build-script DSL.

Possible warning implementation:

- Deprecated API methods can record their own usage into a small
  plugin-scoped reporter when called during configuration.
- The reporter can keep API name, replacement guidance, and a full call-site
  stack trace.
- A lightweight warning task can print the collected usages when it is executed,
  or Quarkus task types can print the collected usages at task execution time.
- Capturing call sites is possible by creating an exception or walking the
  current thread stack in the deprecated method.
- Preferred diagnostic shape:
  - normal build output prints one aggregated warning with the deprecated API
    names, the versioned migration URL, and the diagnostics file path;
  - the diagnostics file is written under the project's build directory and
    contains a summary at the top followed by the full captured stack traces for
    each recorded usage;
  - the diagnostics file is not declared as a task output and is not used as a
    task input or cache key;
  - writing the diagnostics file does not depend on Gradle's `--stacktrace`
    option.
- Warning messages should include the versioned Quarkus 3 documentation URL for
  the migration inventory.
- The collected usage data must not become a build-cache key. Treat it as
  diagnostic state, not as task behavior input.

Warning implementation concerns:

- There is no Gradle-wide hook that tells us which plugin DSL methods were
  called. Every deprecated method that should be reported must record itself.
- Public Java visibility is not enough to identify user DSL usage. If a
  deprecated public method is also used by Quarkus plugin internals during
  configuration, recording inside that method would produce false positives.
  Such methods should get deprecation annotations/Javadocs only unless internal
  callers are first routed to a non-recording implementation path.
- The wrapper/delegate split is the preferred way to make diagnostics precise
  without relying on stack-trace heuristics. It is appropriate when the split is
  mechanical and behavior-preserving. If the split would be invasive, leave the
  method as documentation-only deprecation in Quarkus 3.
- A separate warning task only runs if it is wired into the requested task graph,
  for example by making Quarkus tasks depend on it. That adds visible task-graph
  behavior, so it should stay lightweight and should use `onlyIf` when no
  deprecated usages were recorded.
- Logging directly from each deprecated method is simpler and more reliable,
  but it warns during configuration even if the requested task would not execute
  Quarkus work.
- The diagnostics file should be overwritten per build invocation, or use a
  build-invocation-specific file name, to avoid stale call-site reports. A
  project-local reporter can synchronize writes inside one Gradle process, but
  separate concurrent Gradle invocations can still race on the same build
  directory.
- Because the diagnostics file is intentionally not a task output, it is a
  best-effort user aid. It should not be used as proof that a cacheable task did
  or did not execute.
- The Quarkus 3 PR should start with the least invasive option that still gives
  users actionable migration guidance. If a task-based reporter is added, it
  should be covered by a focused TestKit test.

Suggested Quarkus 3 PR title:

`Document planned Gradle plugin DSL cleanup for Quarkus 4`

Suggested Quarkus 3 commit title:

`Warn about planned Gradle plugin DSL cleanup`

Suggested Quarkus 3 backport branch name:

`gradle-q3-warn-deprecate`

Branch strategy:

- Implement the warning/deprecation/shim commit on the Quarkus 4
  `gradle-app-cc` stack so it ends up in the Q4 cleanup branch.
- Carry the same commit, or an equivalent backport if required, to the Quarkus 3
  branch `gradle-q3-warn-deprecate` in the `/home/snazy/devel/quarkusio/quarkus/other`
  worktree.
- The warning reporter is part of this first warning/deprecation commit, not a
  later follow-up.

## Rewrite Strategy For `gradle-app-cc`

Preferred strategy: rebuild the stack from the final tree.

Do not mechanically fix up each later commit into its historical ancestor.
Several commits were valid local experiments but are now superseded by the
final design. Preserving them in public history would make review harder.

Recommended workflow:

1. Keep the current branch as a safety branch.
   Current safety refs include `gradle-app-cc-save` and `snazy/gradle-app-cc`.
2. Create a fresh rewrite branch from the intended Quarkus 4 base.
3. Apply the final diff from the current `gradle-app-cc` branch as one
   working-tree state.
4. Split that final state into commits using path-level and hunk-level staging.
5. Order commits by review story and dependency, not by original local history.
6. After each commit or small group, run the narrowest relevant tests.
7. Run the full focused Gradle suite before publishing the rewritten stack.

Success criteria:

- No commit introduces `QuarkusDeclaredDependenciesTask` only for a later commit
  to remove it.
- No commit depends on a later fix to compile.
- No commit contains an already-rejected `StartParameter.isDryRun()` production
  branch.
- Public DSL/API removals are grouped and documented.
- Tests that explain a behavior change live with or before the change.
- Commit messages explain why a Gradle compatibility boundary changed.

## Proposed Rewritten Commit Stack

### 1. Quarkus 3 Warning / Deprecation PR

This is a separate Quarkus 3 PR, not part of the Quarkus 4 branch, but it
should be created first so users get early migration guidance.

See "Commit 0" above.

### 2. Gradle TestKit Fixture And Regression Foundation

Fold together test-fixture-only changes that are prerequisites for later
application/extension plugin tests.

Likely source commits:

- `13145b2090f` (`Share Gradle TestKit fixtures across application plugin tests`)
- relevant fixture-only parts of later cleanup commits.

Verification:

- compile test fixtures;
- targeted existing plugin tests.

### 3. Small Independent Task Cacheability Fixes

Keep independent task cacheability fixes separate and early.

Likely source commits:

- `a2722720852` (`Make Gradle extension descriptor task cacheable`)
- `e9307204904` (`Make Gradle image extension check cacheable`)

Verification:

- targeted task tests for `ExtensionDescriptorTask`;
- targeted task tests for image extension checks.

### 4. Provider/System-Property Cleanup And Shared Task Base

Group mechanical provider-based cleanup and shared task-service plumbing.

Likely source commits:

- `57df02abcf0` (`Use Gradle providers for plugin environment inputs`)
- `098e5a4837b` (`Introduce shared Gradle task base class`)
- `cb607e20814` (`Use Gradle providers for model cleanup flags`)
- the `QuarkusBaseTask.getDependencyHandler()` part from
  `1e8ed6feb14`.

Verification:

- `:gradle-model:compileJava`
- `:gradle-application-plugin:test` targeted provider/configuration-cache
  tests.

### 5. Remove Deprecated / Live Project Application Plugin DSL Helpers

Group Quarkus 4 public DSL removals for the application plugin.

Likely source commits:

- `62a149af2b8` (`Remove deprecated Quarkus test config task`)
- `60b0e8ca49b` (`Remove live Gradle project helpers from Quarkus extension`)
- any final cleanup around public DSL comments.

Precondition:

- Quarkus 3 warning/deprecation PR exists.
- Any Quarkus 3 compatibility shims that are meant to let users migrate before
  Quarkus 4, especially `nativeArguments` and `getDisableValidation()`, have
  been accepted or explicitly dropped.
- The warning/deprecation PR decides whether `Action`-based DSL methods that do
  not add value over direct Gradle `Property` / `Provider` APIs should be
  deprecated for removal in Quarkus 3 and removed from the Quarkus 4 cleanup
  stack.

Verification:

- `QuarkusPluginTest`
- DSL/migration tests if added.

### 6. Native Argument / Forced Property Plumbing

Keep the native task alias and native argument behavior changes together.

Likely source commits:

- `ce43ab5a045` (`Route native task aliases through extension state`)
- relevant final native argument property changes.

Verification:

- `QuarkusPluginTest.nativeArgsShouldPopulateExtensionNativeArguments`
- `AdditionalForcedPropertiesTest`
- relevant build/native task configuration tests.

### 7. Move Shared Gradle Model Utilities

Keep low-risk code movement separate if it helps review.

Likely source commits:

- `ae8de13a2af` (`Move Gradle source set utilities to gradle-model`)
- `0581e37a43a` (`Use serializable source lists in Gradle project descriptors`)
- `cb4ed704ce7` (`Include generated source paths in Gradle project descriptors`)

Verification:

- `:gradle-model:test`
- relevant project descriptor tests.

### 8. Extension Plugin Managed Properties And Deployment Plugin

Group extension-plugin public DSL shape changes and the new deployment plugin
into a coherent Quarkus 4 slice.

Likely source commits:

- `bf5d3af2968` (`Cover direct runtime extension project naming`)
- `b270776e4da` (`Use managed properties for extension plugin configuration`)
- `366e0373ec8` (`Add a Gradle plugin for extension deployment modules`)
- `2e9ec7ea147` (`Apply extension deployment plugin in Gradle composite fixtures`)
- `ed7dda1fec3` (`Preserve deployment marker producer dependencies`)

Precondition:

- Quarkus 3 warning/deprecation PR documents the extension DSL migration.

Verification:

- `:gradle-extension-deployment-plugin:test`
- `:gradle-extension-plugin:test`

### 9. Generated Application Model Task And Consumers

Group the generated application model task and task-consumer wiring.

Likely source commits:

- earlier `GenerateTestApplicationModelTask` / `GenerateApplicationModelTask`
  move/rename commits if still separate in history;
- `173f82c72f0` (`Wire extension deployment tests to generated app models`)
- generated-model wiring from `QuarkusGoOffline`, `QuarkusDev`,
  `QuarkusInfo`, and `QuarkusUpdate` changes.

Verification:

- `TasksConfigurationCacheCompatibilityTest`
- `QuarkusExtensionPluginTest.generatedApplicationModelTask...`
- targeted `quarkusGoOffline`, `quarkusDev`, `quarkusInfo`, and
  `quarkusUpdate` tests where available.

### 10. Configuration Cache Defaults

Keep the three configuration-cache default changes separate from task/model
rewrites.

Likely source commits:

- `b5874e17416` (`Enable configuration cache for Gradle plugin build`)
- `463ee24e5d6` (`Run Gradle plugin TestKit builds with configuration cache`)
- `42717cd8eac` (`Use configuration cache defaults in Gradle integration tests`)
- `27aade324df` (`Enable configuration cache for Gradle extension unit test`)
- `e8967af6515` (`Enable configuration cache for Gradle native build ITs`)

Verification:

- Maven `process-sources` for Gradle modules;
- focused TestKit suite;
- selected `integration-tests/gradle` once current plugin artifacts are wired.

### 11. Declared Dependency Collector Structural Refactor

Keep the behavior-preserving collector refactor and tests separate from the M1
containment change.

Likely source commits:

- `baa96d0ffda` (`Model external declared dependency collection inputs`) only
  if still useful after M1;
- `50fbc5e0be0` (`Fix declared dependency POM resolution in Gradle tasks`);
- the final structural pieces from `1cf23bfe07a`-style work if present in the
  current stack.

Do not include:

- `d567695bf6b` as a standalone app-model task input commit if M1 no longer
  needs it.
- `4871f87cecb` as a producer-task commit.

Verification:

- `DependencyDataCollectorTest`
- `GradlePomResolverTest`
- `MavenEffectiveModelResolverTest`

### 12. Declared Dependency M1 Containment

This should be a clean final-shape commit, not a revert of earlier producer-task
work.

Source commit:

- `1e8ed6feb14` (`Remove Gradle declared dependency producer tasks`)

Contains:

- remove `QuarkusDeclaredDependenciesTask`;
- remove serialized declared-dependencies file;
- remove `enableDeclaredDependencyCollector`;
- remove producer-task refresh policy;
- move external Maven enrichment into `QuarkusApplicationModelTask` execution;
- keep lazy Gradle-supported selected graph/artifact task properties;
- add/keep dry-run configuration-cache regression contract.

Verification:

- `:gradle-model:test`
- `:gradle-application-plugin:test`
- `:gradle-extension-plugin:test`
- Maven `process-sources` for Gradle modules.

### 13. CI / Cross-Platform Fixups Folded Into Owners Or Kept Up Front

Do not keep these as a late pile unless they are genuinely independent. Fold
each into the commit that introduced the failure where possible.

Likely source commits:

- `d818294e9c4` (`Fix Gradle model tests on Windows`)
- `87793f526b3` (`Fix Gradle native task package inputs`)
- `fd8e8f2cacf` (`Fix Gradle dev-mode models for included builds`)
- `8052b3205dc` (`Fix Gradle dev mode project dependency models`)
- `d6f0d0cd386` (`Restore quarkusDev dependency declarations`)
- `4462c19939b` (`Declare dev mode annotation processor path as classpath`)
- `238454c99ef` (`Fix Gradle tooling model platform imports serialization`)
- `135e927eb5f` (`Avoid eager extension dependency jar inspection`)
- `03fab03fe84` (`Avoid immutable collections in extension validation providers`)
- `d818294e9c4` (`Fix Gradle model tests on Windows`)

Rule:

- If a fix only exists because an earlier rewritten commit introduced the
  breakage, fold it into that earlier commit.
- Keep a standalone fix commit only if it fixes a pre-existing issue and has a
  clear independent review story.
- If a CI/cross-platform fix is worth keeping as standalone, move it near the
  beginning of the rewritten branch so all later commits inherit the fixed
  baseline.

### 14. Post-Rewrite Gradle 9 Minimum Baseline

Handle this immediately after the `gradle-app-cc` branch rewrite, before
starting additional feature work.

Rationale:

- The Quarkus 4 Gradle work targets configuration-cache and project-isolation
  compatibility on the current Gradle line.
- Gradle 9 is the active major line, and Gradle 10 preparation is more relevant
  than preserving Gradle 8-specific behavior in a Quarkus 4 cleanup branch.
- Gradle 8 configuration-cache serialization behavior can produce failures that
  are not representative of the intended Quarkus 4 support matrix.
- Quarkus 4 is a major-version boundary, so it is the appropriate place to
  raise the minimum Gradle version if the project accepts the compatibility
  tradeoff.

Expected shape:

- Raise the Gradle plugin minimum-version check to Gradle 9.
- Remove Gradle 8.14-specific test executions from the Quarkus 4 branch.
- Keep Gradle 9 configuration-cache failures as blockers.
- Update user-facing Gradle plugin compatibility documentation.
- Update the Quarkus 3 warning/migration documentation to mention the planned
  Quarkus 4 Gradle 9 minimum.
- Do not carry implementation workarounds that exist only to satisfy Gradle 8
  configuration-cache serialization behavior.

Verification:

- Run the Gradle plugin unit/TestKit suite with the supported Gradle 9 wrapper.
- Run the relevant Gradle integration-test suite with Gradle 9.
- Confirm no remaining Gradle 8-only profiles or CI matrix entries are still
  treated as required gates for the Quarkus 4 branch.

## Validation Plan For The Rewritten Stack

Per-slice validation:

- Run narrow tests for the touched module after each commit or group.
- Run Maven `process-sources` when Java files move or imports change.

Final local gate:

```bash
cd devtools/gradle
./gradlew :gradle-model:test :gradle-application-plugin:test :gradle-extension-plugin:test
```

```bash
./mvnw process-sources -f devtools/gradle/pom.xml -pl gradle-model,gradle-application-plugin,gradle-extension-plugin
```

Integration-test gate:

- Run selected `integration-tests/gradle` only when the current plugin artifact
  boundary is known to use the rewritten worktree.
- Avoid interpreting failures from stale `mavenLocal` plugin artifacts as
  evidence against the rewritten branch.

## Decisions And Remaining Open Questions

- Quarkus 3 warning/deprecation backport branch:
  `gradle-q3-warn-deprecate`.
- Quarkus 4 branch rewrite base: `github/main`.
- The warning/deprecation/shim commit should be present in the Quarkus 4
  `gradle-app-cc` branch history and then be carried to the Quarkus 3 branch.
- Quarkus 3 warning/deprecation PR shape: include additive compatibility shims,
  user-facing migration docs, deprecated API markers/Javadocs, and aggregated
  runtime diagnostics for explicit deprecated DSL/API usage.
- After the branch rewrite, raise the Quarkus 4 Gradle plugin support baseline
  to Gradle 9 and remove Gradle 8-specific gates/workarounds from the Q4
  branch.
- `QuarkusBuild.nativeArgs(Action)` is deprecated for removal in Quarkus 3 and
  removed in Quarkus 4. Similar `Action`-based APIs should still be reviewed
  with the same rule: if the `Action` method does not add real value over a
  managed `Property` / `Provider` API, prefer deprecating it for removal in
  Quarkus 3 and removing it in Quarkus 4.
- CI-only fixups should be classified during the rewrite. Fold branch-induced
  fixes into the commit that introduced the issue. Keep genuinely independent
  fixes as standalone commits near the beginning of the rewritten branch.
