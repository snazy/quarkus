# Public DSL/API Shape Changes

This tracks local branch changes that remove or narrow public Gradle plugin
task or extension surface. Use it when preparing PR descriptions, migration
notes, or release documentation.

## Current Local Changes

### `quarkusTestConfig` task removal

- Commit: `f56491a335f` (`Rework Gradle application model task wiring`)
- Surface removed:
  - `quarkusTestConfig` task registration from the `io.quarkus` Gradle plugin.
  - `io.quarkus.gradle.tasks.QuarkusTestConfig` task implementation class.
- Rationale: the task was long deprecated and preserving it would keep legacy
  project-property access paths alive.
- Documentation need: mention as a removed deprecated Gradle task before this
  PR stack is finalized.

### `QuarkusPluginExtension` live-helper and convenience-method removal

- Commit: `f56491a335f` (`Rework Gradle application model task wiring`)
- Surface removed:
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
- Remaining intended DSL/API shape:
  - Managed property getters such as `getFinalName()`,
    `getCleanupBuildOutput()`, `getCacheLargeArtifacts()`,
    `getCodeGenerationInputs()`, `getCodeGenerationProviders()`,
    `getQuarkusBuildProperties()`, `getNativeArguments()`, and
    `getCachingRelevantProperties()`.
  - `manifest(Action<Manifest>)`
  - `sourceSets(Action<? super SourceSetExtension>)`
  - `buildForkOptions(Action<? super JavaForkOptions>)`
  - `codeGenForkOptions(Action<? super JavaForkOptions>)`
  - `set(String, String)` and `set(String, Provider<String>)`
- Rationale: the removed helpers either force live `Project` access, construct
  application models from mutable Gradle state, or prevent the extension from
  using Gradle-managed abstract properties.
- Documentation need: document the migration to property getters and remove any
  examples that call the removed convenience/live-helper methods.

### `QuarkusExtensionConfiguration` managed-property shape

- Commit: `a3e685d7172` (`Add Gradle extension deployment plugin`)
- Surface removed:
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
- Remaining intended DSL/API shape:
  - Managed property getters such as `getDisableValidation()`,
    `getDeploymentArtifact()`, `getDeploymentModule()`,
    `getExcludedArtifacts()`, `getParentFirstArtifacts()`,
    `getRunnerParentFirstArtifacts()`, `getLesserPriorityArtifacts()`,
    `getConditionalDependencies()`, `getConditionalDevDependencies()`, and
    `getDependencyConditions()`.
  - `capabilities(Action<Capabilities>)`
  - `removedResources(Action<RemovedResources>)`
  - Read-only accessors for derived DSL state:
    `getProvidedCapabilities()`, `getRequiredCapabilities()`, and
    `getRemoveResources()`.
- Rationale: the extension is now a Gradle-managed abstract type. Keeping the
  property getters as the public shape lets Gradle wire the extension without
  manually storing mutable `Property`/`ListProperty` fields and removes
  JavaBean setter aliases that are awkward for configuration-cache and
  project-isolation friendly wiring.
- Documentation need: update examples to assign Gradle properties through the
  existing DSL/property syntax or `get*().set(...)` from Java/plugin code, not
  through the removed JavaBean setters.

## Open Checks

- Quarkus docs/tests search for removed methods is complete; only WIP/archive
  notes and surviving intended getters/actions were found.
- Decide whether this needs a migration-guide note because the removed methods
  were public even if deprecated or implementation-oriented.
- In the PR description, call out that `sourceSets(Action)`, `manifest(Action)`,
  fork-option actions, `get*` managed properties, and `set(String, ...)` remain.
