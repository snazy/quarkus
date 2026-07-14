# Gradle Constraints Rubric

This rubric summarizes official Gradle documentation constraints for reviewing
`devtools/gradle` for build-cache, configuration-cache, and isolated-projects
compatibility. It is a review aid, not a replacement for the official Gradle
docs and Javadocs cited below.

## Source Scope

Use official Gradle sources only for constraint claims:

- Gradle User Manual and DSL reference;
- Gradle Javadocs;
- Gradle release notes for versions Quarkus supports.

Avoid relying on blog posts, forum answers, or old examples unless an official
Gradle source confirms the same constraint.

## Configuration Cache

Configuration Cache is stable since Gradle 8.1 and the preferred execution mode
since Gradle 9.0, though it is still not enabled by default.

Hard constraints:

- Task fields, `@TaskAction`, `doFirst`, and `doLast` must not reference live
  JVM state, Gradle model types, or dependency-management model types such as
  `Project`, `Gradle`, `Settings`, `SourceSet`, `Configuration`,
  `ResolvedArtifact`, or `ArtifactResult`.
- Tasks must not call `Task.getProject()` or otherwise use `Project` during
  execution.
- Tasks must not directly read or configure another task instance during
  execution; connect tasks through inputs, outputs, and dependencies.
- Tasks must not access task extensions, conventions, extra properties, or
  build listeners at execution time.
- External processes, direct file reads, broad environment/system-property
  enumeration, and similar external inputs during configuration must be modeled
  with providers or `ValueSource`.
- `Task.notCompatibleWithConfigurationCache(reason)` is acceptable as a
  migration marker for intentionally incompatible tasks, but it is not a fix and
  causes configuration state to be discarded for that graph unless warning mode
  is used.

Common violations:

- Task classes storing `Project`, `SourceSet`, `Configuration`, `TaskContainer`,
  resolved artifacts, or extension objects as fields and later using them in
  `@TaskAction`.
- `doFirst` or `doLast` closures capturing `project`, `sourceSets`,
  `configurations`, or script top-level state.
- `System.getenv()` or `System.getProperties()` enumeration, prefix filtering by
  hand, or direct file parsing during configuration.
- `project.copy`, `project.delete`, `project.exec`, `project.javaexec`,
  `project.files`, or `project.fileTree` inside task actions.
- Shared mutable state between configuration and execution, or between tasks.

Replacement patterns:

- Replace `Project` scalar values with task inputs: `Property<String>`,
  `ListProperty<T>`, `MapProperty<K, V>`, and similar Gradle property types.
- Replace `SourceSet` or `Configuration` task fields with `FileCollection`,
  `ConfigurableFileCollection`, `DirectoryProperty`, or `RegularFileProperty`
  annotated with correct input/output annotations.
- Replace `project.copy/sync/delete` with injected `FileSystemOperations`.
- Replace `project.exec/javaexec` with injected `ExecOperations`.
- Replace configuration-time environment/system/Gradle property reads with
  `ProviderFactory.environmentVariable`, `systemProperty`, `gradleProperty`,
  prefixed provider APIs, `fileContents`, or `ValueSource`.
- Use shared `BuildService` for cross-task shared state/resources; make it
  thread-safe and connect it via `@ServiceReference` or `Task.usesService(...)`
  when concurrency limits matter.

Official sources:

- https://docs.gradle.org/current/userguide/configuration_cache.html
- https://docs.gradle.org/current/userguide/configuration_cache_requirements.html
- https://docs.gradle.org/8.1/release-notes.html
- https://docs.gradle.org/9.0.0/release-notes.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/Task.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/provider/ProviderFactory.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/provider/ValueSource.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/file/FileSystemOperations.html
- https://docs.gradle.org/current/javadoc/org/gradle/process/ExecOperations.html

## Isolated Projects

Isolated Projects is experimental and moving. It enables Configuration Cache and
extends its constraints. Fix Configuration Cache issues first, then review
cross-project model access.

Hard constraints and warning signs:

- Flag cross-project mutable model access: `rootProject`, `parent`,
  `project(":x")`, `findProject`, `getRootProject().getSubprojects()`,
  `subprojects {}`, `allprojects {}`, and callbacks that configure another
  project.
- Under Isolated Projects, another `Project` may expose only safe immutable
  state. Mutable state includes `group`, `version`, `buildDir`, `tasks`,
  `configurations`, `dependencies`, `extensions`, `layout`, `providers`, and
  `objects`.
- Cross-project task lookup APIs are isolated-projects-incompatible.
- Tooling API/model-builder paths may run in parallel and should not assume
  sequential project model building or mutable cross-project access.

Replacement patterns:

- Publish/consume artifacts through outgoing variants and dependency
  configurations instead of reading another project's tasks, files,
  configurations, or extension state.
- For project dependency identity, prefer `ProjectDependency#getPath()`.
- For actual resolved project participation, use `ResolutionResult`; declared
  `ProjectDependency` entries can miss transitives or substitutions.
- Replace shared root/subproject configuration with convention plugins applied
  by each project.
- If settings-time lifecycle configuration is unavoidable, evaluate whether
  `gradle.lifecycle.beforeProject/afterProject` isolated callbacks apply.
- Use `project.isolated` / `IsolatedProject` only for safe identity and
  directory reads across project boundaries.

Official sources:

- https://docs.gradle.org/current/userguide/isolated_projects.html
- https://docs.gradle.org/current/userguide/how_to_share_outputs_between_projects.html
- https://docs.gradle.org/current/userguide/upgrading_version_8.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/ProjectDependency.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/project/IsolatedProject.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/invocation/GradleLifecycle.html

## Build Cache

Build-cache compatibility requires complete, normalized task inputs and outputs
and reproducible, relocatable outputs.

Hard constraints:

- Every task property must have exactly one correct role annotation:
  `@Input`, `@InputFile`, `@InputDirectory`, `@InputFiles`, `@Classpath`,
  `@CompileClasspath`, `@Nested`, output annotations, `@Internal`, `@Console`,
  `@LocalState`, etc. Annotations belong on getters/properties.
- `@CacheableTask` requires complete declared inputs and outputs.
- Mark a task `@CacheableTask` only when outputs are reproducible and
  relocatable.
- File inputs on cacheable tasks require normalization: `@PathSensitive`,
  `@Classpath`, or `@CompileClasspath`.
- Use `@Classpath` for runtime classpaths and `@CompileClasspath` only when
  ABI-only changes are sufficient.
- Avoid modeling ordered classpaths as plain `@InputFiles` if order matters.
- Outputs should be discrete and non-overlapping. Avoid `FileTree` as
  output collections; Gradle documents that this disables caching for
  `@OutputFiles`/`@OutputDirectories`.

Reproducibility checks:

- Generated outputs must not embed undeclared timestamps, UUIDs, absolute
  project paths, platform-specific separators, locale/default charset effects,
  current user/home paths, environment values, or system properties.
- If such values affect output, declare normalized inputs or remove the
  variability.

Worker and service checks:

- Worker API inputs must flow through `WorkParameters`.
- Workers must not depend on undeclared task/project mutable state.
- Treat `noIsolation()` as sharing static state; use classloader/process
  isolation when library state or classpath isolation matters.
- Build service parameters must be modeled, service implementations must be
  thread-safe, and `maxParallelUsages` should be set when shared resources need
  concurrency limits.
- A build service itself is not a task input.

Do not mark tasks cacheable when they are lifecycle/aggregation tasks,
interactive dev/run tasks, deploy/push/update/network tasks, depend on mutable
remote state, write outside declared outputs, are very cheap, have
unreproducible outputs, or depend on undeclared environment/credentials.

Official sources:

- https://docs.gradle.org/current/userguide/build_cache.html
- https://docs.gradle.org/current/userguide/build_cache_concepts.html
- https://docs.gradle.org/current/userguide/common_caching_problems.html
- https://docs.gradle.org/current/userguide/incremental_build.html
- https://docs.gradle.org/current/userguide/validation_problems.html
- https://docs.gradle.org/current/userguide/best_practices_tasks.html
- https://docs.gradle.org/current/userguide/worker_api.html
- https://docs.gradle.org/current/userguide/build_services.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/CacheableTask.html
- https://docs.gradle.org/current/javadoc/org/gradle/work/DisableCachingByDefault.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/Classpath.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/CompileClasspath.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/PathSensitive.html
- https://docs.gradle.org/current/javadoc/org/gradle/workers/WorkerExecutor.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/services/ServiceReference.html

## Artifact Resolution And Provider APIs

Configuration and artifact APIs must be used lazily and with clear roles.

Hard constraints and warning signs:

- Each custom `Configuration` should have one clear role: declarable,
  resolvable, or consumable. Review `canBeConsumed`, `canBeResolved`, and
  `canBeDeclared` explicitly.
- Treat `Configuration.getResolvedConfiguration()` as legacy. Prefer
  `configuration.getIncoming()` and `ResolvableDependencies` APIs for new or
  touched code.
- Flag eager resolution during configuration: `Configuration.resolve()`,
  `getResolvedConfiguration()`, `ArtifactCollection.getArtifacts()`,
  `FileCollection.getFiles()`, `getAsPath()`, `toList()`, and similar calls.
- Resolution results are reused after first request; mutation after resolution
  is an error.
- Do not convert live `Configuration`/`FileCollection` values to `Set<File>`
  during configuration. That discards implicit task dependencies.
- Provider closures captured into task inputs must not retain live Gradle model
  or dependency-management objects that later execute during task execution.
- Artifact attributes should be final before calling
  `getIncoming().getArtifacts()` or `artifactView(...)`.

Replacement patterns:

- Prefer live resolution inputs for tasks. `ResolvableDependencies.getFiles()`
  returns a lazy `FileCollection` that carries producer task dependencies.
- `ResolutionResult.getRootComponent()` and `ArtifactCollection` provider APIs
  can preserve producer task tracking when wired lazily.
- When using `ArtifactCollection.getArtifacts()` for scalar snapshots, verify
  producer tasks are still wired explicitly.
- Keep provider chains live with `Property.set(provider)`, `map`, and `flatMap`.
- Prefer `ArtifactView` for variant-specific artifacts instead of mutating
  configuration state.

Official sources:

- https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/Configuration.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/ResolvableDependencies.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/ArtifactCollection.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/result/ResolvedArtifactResult.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/result/ResolutionResult.html
- https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html

## Review Priority For `devtools/gradle`

Apply the rubric in this order:

1. Shared `gradle-model` configuration and resolution behavior.
2. Application-plugin task implementation and cacheability surface, especially
   `QuarkusGenerateCode`, `QuarkusBuild`, and `QuarkusBuildCacheableAppParts`.
3. Extension-plugin cross-project access and task modeling.
4. Build infrastructure, publication/install wiring, and test matrix behavior.

Use Configuration Cache findings to inform Isolated Projects findings, because
Isolated Projects extends Configuration Cache constraints.
