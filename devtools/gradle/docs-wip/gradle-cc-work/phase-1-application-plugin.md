# Phase 1: Application Plugin Review

Read-only review of `devtools/gradle/gradle-application-plugin` for Gradle
build-cache, configuration-cache, and isolated-projects compatibility. This
report is informed by `phase-1-gradle-model.md`; inherited `gradle-model`
issues are noted only when the application plugin adds a distinct wiring,
task-property, or cacheability symptom.

## Findings

### Finding: [P1-AP-01] Cross-project task wiring blocks isolated projects

- ID: `P1-AP-01`
- Area: application plugin
- Component: [QuarkusPlugin.java:737](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java), [QuarkusPlugin.java:814](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java)
- Build cache impact: none
- Project isolation impact: direct blocker
- Configuration cache impact: related
- Severity: must fix before enabling
- Evidence: `afterEvaluate()` calls `visitProjectDependencies()`, which walks
  another project's compile/runtime configurations, converts `ProjectDependency`
  with `dependencyProject.project(projectDep.getPath())`, resolves included
  builds through `ToolingUtils.findIncludedProject(...)`, registers
  `afterEvaluate` callbacks on dependency projects, reads dependency project
  tasks, and wires local tasks to dependency `jar`, Jandex, and
  process-resources tasks.
- Gradle contract: `gradle-constraints.md` section "Isolated Projects"; official refs: isolated projects user guide and "How to share outputs between projects".
- Why it matters: isolated projects disallows mutable cross-project model access such as another project's configurations, tasks, `afterEvaluate`, and task lookup. This is plugin-local and separate from the `gradle-model` tooling traversal.
- Confidence / gaps: high. Existing isolated-projects coverage is single-project, so it does not exercise this path.
- Suggested PR boundary: replace dependency-project traversal with variant/artifact wiring or explicit task dependencies derived from resolved project outputs; start with a multi-project TestKit reproducer.
- Verification: multi-project app with `implementation(project(":lib"))`, local extension dependency if possible, `quarkusBuild --configuration-cache -Dorg.gradle.unsafe.isolated-projects=true`, and a second run proving cache reuse.

### Finding: [P1-AP-02] Cacheable build tasks read mutable build-service state as a hidden input

- ID: `P1-AP-02`
- Area: application plugin
- Component: [QuarkusPlugin.java:161](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java), [QuarkusBuildTask.java:59](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildTask.java), [QuarkusBuildTask.java:356](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusBuildTask.java), [ForcedPropertieBuildService.java:10](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/services/ForcedPropertieBuildService.java), [ImageBuild.java:26](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/ImageBuild.java), [ImagePush.java:21](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/ImagePush.java)
- Build cache impact: cacheability blocker
- Project isolation impact: cleanup
- Configuration cache impact: warning
- Severity: must fix before enabling
- Evidence: build paths still read
  `getAdditionalForcedProperties().get().getProperties()` into effective config
  through an `@Internal` build-service property. That service now uses
  concurrent storage and immutable snapshots, and `nativeArgs` is modeled
  separately, but `ImageBuild` and `ImagePush` still mutate the shared service
  at task execution time. Those values are outside any consuming build task's
  cache key. The service is also registered as
  `forcedPropertiesService-${project.getName()}`, so same-named projects in the
  same Gradle build can still collide.
- Gradle contract: `gradle-constraints.md` sections "Build Cache" and "Build Cache / Worker and service checks"; official refs: build cache and build services user guides.
- Why it matters: build output can change based on image task service mutations
  that are not part of the task cache key. The service name also uses only
  `project.getName()`, so same-named projects can share state accidentally.
- Confidence / gaps: high for the remaining hidden input; medium on collision impact.
- Suggested PR boundary: use `new-application-plugin-design.md` together with
  `application-plugin-build-shapes/design.md`. The preferred direction is to
  keep legacy `io.quarkus` behavior as compatibility behavior and move the
  explicit named-output model to the standalone `io.quarkus.application`
  plugin, using `quarkusApplication.builds` derived stable tasks rather than
  making `quarkusBuild` inputs conditional on the task graph.
- Verification: TestKit case where image build/push options change between builds and `quarkusBuild` must not be reused from cache.
- Partial progress:
  - Local hardening commit makes `ForcedPropertieBuildService` use concurrent
    storage and returns immutable snapshots instead of exposing its live mutable
    map. This preserves the current late cross-task signaling semantics and
    does not fix the cache-key issue, because cacheable task inputs still cannot
    safely depend on state mutated by task actions during execution.
  - `f56491a335f` (`Rework Gradle application model task wiring`) moves configuration-time
    `QuarkusBuild.nativeArgs(Action)` values into a modeled extension
    `MapProperty`, exposes that property through the task extension view, and
    merges normalized native arguments with the late build-service snapshot when
    constructing effective config. This fixes the hidden-input part for
    `nativeArgs` without changing image build/push late mutation semantics.
  - `f56491a335f` (`Rework Gradle application model task wiring`) routes
    the legacy `buildNative`/`testNative` aliases through the extension's
    modeled `nativeBuild` property instead of mutating `Project` extra
    properties. This removes one more hidden/native flag path, but does not
    change the image build/push late build-service mutation.

### Finding: [P1-AP-05] Cacheable workers receive broad, undeclared process environment and opaque fork actions

- ID: `P1-AP-05`
- Area: application plugin
- Component: [QuarkusTask.java:88](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusTask.java), [QuarkusTask.java:99](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusTask.java), [QuarkusTask.java:119](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusTask.java), [QuarkusPluginExtensionView.java:73](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusPluginExtensionView.java)
- Build cache impact: cache miss risk
- Project isolation impact: none
- Configuration cache impact: warning
- Severity: should fix
- Evidence: cacheable build and code-generation tasks still execute worker fork
  actions from `ListProperty<Action<? super JavaForkOptions>>` and then pass
  the whole process environment into forked workers with
  `environmentVariablesPrefixedBy("").get()` at task execution time. The direct
  worker-control system property reads were replaced with `ProviderFactory` lookups by
  `f56491a335f`. That keeps the configuration-cache/build-cache key from
  directly tracking the caller's environment, but it also means worker behavior
  can still depend on broad runtime environment state unless the task is
  intentionally non-cacheable or the environment is narrowed to modeled entries.
- Gradle contract: `gradle-constraints.md` sections "Configuration Cache" and "Build Cache / Worker and service checks"; official refs: configuration-cache requirements and Worker API user guide.
- Why it matters: worker JVM args and environment can affect code generation
  and packaging but are not fully modeled as stable scalar task inputs. The
  opaque `Action` objects can also capture live Gradle model state.
- Confidence / gaps: medium; some environment tracking is intentionally delegated to `cachingRelevantProperties`, but the worker still receives all environment variables by default.
- Suggested PR boundary: introduce typed fork option inputs for supported knobs,
  limit forwarded environment to modeled entries where cacheability matters, and
  mark arbitrary action-based customization incompatible with caching until it
  has a stable input model.
- Verification: custom codegen or build step that reads an environment variable or custom worker JVM property; changing it should invalidate cache or the task should be explicitly non-cacheable.

## Verification Gap

Existing application-plugin tests include some build-cache and
configuration-cache coverage, including isolated-projects coverage for a
single-project scenario. The gaps are multi-project isolated-projects coverage,
cache-key tests for forced properties and custom source layouts, and
reproducibility tests around cache restoration from a clean `build/` directory.
