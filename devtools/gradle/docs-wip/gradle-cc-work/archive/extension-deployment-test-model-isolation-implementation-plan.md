# Extension Deployment Test Model Isolation Implementation Plan

Status: completed implementation record
Last reviewed: 2026-07-14

## Goal

Make `io.quarkus.extension.deployment` generated test application models
compatible with Gradle isolated projects by removing the
`ProjectDescriptorBuilder.buildForApp(project)` cross-project workspace scan.

The target path is:

```text
QuarkusExtensionDeploymentPlugin.registerTestApplicationModel(...)
  -> ProjectDescriptorBuilder.buildForApp(project)
  -> ProjectDescriptorBuilder.buildWorkspaceModules(project)
  -> rootProject.getAllprojects()
```

The replacement should use Gradle resolution results and artifact views for
local component outputs, not producer-project model access.

## Design Summary

Use a narrow output-side fix first.

- Extract the reusable artifact-view logic from
  `io.quarkus.gradle.application.TaskRegistration` into `gradle-model`.
- Reuse it from both `io.quarkus.application` dev-mode wiring and
  `io.quarkus.extension.deployment` generated test model wiring.
- Build a local-component output index from classes/resources output artifact
  views and jar fallback artifacts.
- Feed that output index into generated test model collection so local project
  dependencies can keep workspace/reloadable semantics without reading other
  projects.
- Keep source/resource source directories out of this fix. Full source-root
  workspace metadata remains a later tooling/dev-mode design if needed.

## Implementation Invariants

An implementation should preserve these invariants throughout the phases below:

- `io.quarkus.extension.deployment` generated test model registration must not
  call `Project.getRootProject()`, `Project.getAllprojects()`, included-build
  project model APIs, or register callbacks on producer projects.
- Existing `io.quarkus.application` dev-task behavior must remain equivalent:
  the shared helper may replace local artifact-view setup, but dev-mode-specific
  task wiring stays in the application plugin.
- Existing legacy application-model callers must not receive local-output
  replacement behavior unless they explicitly opt in through the configurator.
- Local output replacement must preserve artifact coordinates, including type
  and classifier. In particular, classifier artifacts such as `test-fixtures`
  must not be rewritten to the main artifact's classes/resources directories.
- Gradle task dependencies for local classes/resources/jar outputs must flow from
  declared task inputs or artifact-view file collections, not from manual task
  ordering.

## Implementation Phases

### 1. Add Shared Local Component Output Helpers

Add a package in `gradle-model`, likely under
`io.quarkus.gradle.dependency` or `io.quarkus.gradle.tooling`, with a small
helper that owns the artifact-view setup currently private to
`TaskRegistration`.

Proposed shape:

```java
public final class LocalComponentOutputViews {
    public static LocalComponentOutputViews of(ObjectFactory objects, Configuration configuration);

    public ArtifactView classes();
    public ArtifactView resources();
    public ArtifactView jars();

    public Provider<Set<ResolvedArtifactResult>> classArtifacts();
    public Provider<Set<ResolvedArtifactResult>> resourceArtifacts();
    public Provider<Set<ResolvedArtifactResult>> jarArtifacts();

    public Provider<Set<File>> jarFilesWithoutOutputVariants(ProviderFactory providers);
}
```

The helper should:

- create artifact views with `withVariantReselection()`;
- request `LibraryElements.CLASSES` with `jvm-classes-directory`;
- request `LibraryElements.RESOURCES` with `jvm-resources-directory`;
- request `LibraryElements.JAR` with `jar`;
- keep the views lenient, matching current dev-mode behavior;
- compute jar fallback by excluding components that have class or resource
  output variants.

Use this helper from `TaskRegistration.configureDevTask(...)` so the new helper
is covered by existing `io.quarkus.application` dev task behavior.
`TaskRegistration.configureDevTask(...)` may keep using the `ArtifactView`
instances directly for file collections such as dependency classes and
dependency resources. Model-generation callers should use the artifact-provider
accessors so they can preserve selected component and artifact identity while
also declaring file inputs separately.

### 2. Add A Current-Project Descriptor Path

Add a current-project-only descriptor builder in `ProjectDescriptorBuilder`.

Proposed API:

```java
public static Provider<DefaultProjectDescriptor> buildForCurrentProject(Project project)
```

It should:

- create a descriptor only for the current project;
- initialize the current project's module id, module dir, build dir, and build
  file;
- initialize current-project source/output metadata the same way the legacy path
  does today;
- not call `project.getRootProject()`, `getAllprojects()`, or included-build
  APIs;
- not register callbacks on other projects.

This is enough for the root deployment module's application artifact. Dependency
workspace semantics will come from the local-output index, not from a global
`WorkspaceModuleId` map.
The source/resource-source omission in this plan applies to dependency modules
discovered through Gradle resolution. The current deployment project may keep
its own existing descriptor metadata because that does not require cross-project
access.

### 3. Feed Local Outputs Into Model Generation

Extend `QuarkusApplicationModelTask` with optional local-output inputs derived
from the shared helper.

Recommended model:

- keep the existing `QuarkusResolvedClasspath` for the selected runtime graph
  and normal artifact files;
- add internal `SetProperty<ResolvedArtifactResult>` inputs for class output
  artifacts and resource output artifacts;
- add `ConfigurableFileCollection` inputs for the corresponding files so Gradle
  tracks task dependencies and file inputs;
- optionally add jar fallback files if the task needs them outside the existing
  app classpath artifacts.

At execution time, build:

```text
selected artifact/component identity -> local output paths
```

Then, while collecting resolved dependencies:

- use the normal selected graph and artifacts to determine dependency
  coordinates, type, classifier, directness, extension flags, and existing jar
  artifacts;
- if the component has local class/resource outputs, set the dependency
  `resolvedPaths` to those output dirs instead of the jar;
- attach a minimal `WorkspaceModule` with `WorkspaceModuleId` for that component
  so the dependency keeps the `workspace-module` flag;
- mark it reloadable for non-normal launch modes using the same conditions as
  today;
- preserve parent direct workspace dependency edges where they can be derived
  from the selected graph.

The minimal dependency `WorkspaceModule` does not need source/resource source
dirs. It only needs an id for workspace identity and dependency-edge recording.
If later tests show a consumer requires module/build directories for this path,
add only those fields that can be obtained without opening producer projects.

The local-output lookup must not flatten artifact variants too far. The
generated model is keyed by artifact coordinates, including classifier and type,
so the implementation must preserve enough identity from the original resolved
artifact and the output artifact view to avoid applying a component's main
classes/resources to a classifier artifact such as `test-fixtures`. Component
identity is sufficient for excluding jar fallbacks when any output variant
exists, but model dependency replacement needs artifact/classifier/variant-aware
matching or an explicitly documented fallback rule for cases where Gradle only
exposes component-level output identity.

### 4. Wire Extension Deployment Plugin

Change `QuarkusExtensionDeploymentPlugin.registerTestApplicationModel(...)` to:

- replace `ProjectDescriptorBuilder.buildForApp(project)` with the
  current-project-only descriptor;
- create `LocalComponentOutputViews` from the same runtime configuration used
  for the test app model;
- pass the class/resource output artifact providers into
  `ApplicationModelTaskConfigurator` / `QuarkusApplicationModelTask`;
- keep the serialized test model JVM argument wiring unchanged.

`ApplicationModelTaskConfigurator` will likely need an overload or an optional
parameter object so existing callers can opt into local-output wiring without
disrupting legacy callers.

### 5. Preserve Existing Application Dev Wiring

Update `TaskRegistration.configureDevTask(...)` to use the shared
`LocalComponentOutputViews` helper.

Do not move dev-mode-only logic into `gradle-model`:

- source set source directory handling;
- task dependencies;
- receipts and output snapshots;
- launch kind/build-name/build-type setup;
- config input wiring.

Only the artifact-view creation and jar fallback logic should be shared.

### 6. Tests

Add tests in increasing strength:

1. Helper-level or plugin-level smoke coverage:
   - verifies `LocalComponentOutputViews` still provides classes/resources/jar
     fallback behavior for the new application dev task path, or relies on
     existing dev-task coverage if already sufficient.

2. Focused isolated-project reproducer:
   - base it on
     `QuarkusExtensionDeploymentPluginTest.deploymentTestsUseGeneratedApplicationModel`;
   - run `:deployment:quarkusGenerateTestAppModel --configuration-cache
     -Dorg.gradle.unsafe.isolated-projects=true`;
   - verify that the fixture exercises the old `buildForApp(project)` /
     `getAllprojects()` failure before the fix, then assert success after the
     implementation.

3. Local dependency behavior:
   - extend the focused fixture with a local helper dependency, or add a second
     fixture, and assert the generated model represents local components with
     workspace/reloadable semantics and output paths.
   - mirror the important legacy expectations from `TestFixtureMultiModuleTest`:
     local modules and classifier artifacts such as `test-fixtures` should keep
     `workspace-module` and `reloadable` flags where the old test model did, and
     classifier artifacts must not accidentally receive the main artifact's
     local output directories.

4. Existing integration guardrails:
   - use `TestFixtureMultiModuleTest` as the reference for arbitrary local
     modules and test fixtures;
   - use `multi-composite-build-extensions-project` as the strongest extension
     fixture reference because it covers included builds, two local extensions,
     extension-to-extension dependencies, and helper libraries;
   - keep `BasicCompositeBuildExtensionQuarkusBuildTest` and
     `MultiCompositeBuildExtensionsQuarkusBuildTest` in mind for included local
     extension/library outputs in `quarkus-app/lib/main`;
   - keep conditional extension dependency tests in mind so the output-side
     change does not disturb existing selected-graph behavior from
     `ApplicationDeploymentClasspathBuilder`;
   - keep Jandex ordering tests in mind because artifact-view output wiring must
     preserve task dependencies for classes/resources that include generated or
     indexed outputs.

## Existing Tests Worth Knowing

The implementation should treat these existing tests and fixtures as semantic
guardrails, even if not all of them need to run in every local iteration.

- `integration-tests/gradle/src/test/java/io/quarkus/gradle/extension/ExtensionUnitTestTest.java`
  runs `:deployment:test` for `extensions/simple-extension`. This is the
  closest legacy integration test for extension deployment unit tests.
- `devtools/gradle/gradle-extension-deployment-plugin/src/test/java/io/quarkus/extension/deployment/gradle/QuarkusExtensionDeploymentPluginTest.java`
  proves the deployment plugin wires `quarkusGenerateTestAppModel` into
  `Test`, but today it only checks model-file presence and does not run with
  isolated projects.
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/TestCompositeBuildWithExtensionsTest.java`
  deserializes an application test model for the multi-composite extension
  fixture and asserts top-level runtime extension artifacts keep runtime,
  deployment, runtime-extension, and top-level flags while not being reloadable.
- `integration-tests/gradle/src/main/resources/multi-composite-build-extensions-project`
  is the strongest fixture to keep in mind for extension-related included-build
  behavior. It has two included extension builds, local helper libraries,
  extension-to-extension dependencies, and deployment processors that consume
  helper-library classes.
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/TestFixtureMultiModuleTest.java`
  is the clearest existing assertion that local modules and test-fixture
  classifier artifacts remain `workspace-module` and `reloadable` in test
  application models.
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/DeclaredDependenciesMinimalTest.java`
  is a useful pattern for generating normal and test models in one invocation
  and asserting test-scope data does not leak.
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/ConditionalDependenciesTest.java`
  and `EnforcingPlatformForConditionalDepsTest.java` are semantic guardrails for
  extension conditional and conditional-dev dependency selection. The isolation
  fix should preserve this through the existing classpath builder path.
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/JandexMultiModuleTest.java`
  and dev-mode Jandex included-build tests are order-sensitive guardrails. The
  artifact-view wiring must not drop task dependencies needed for jandex/indexed
  outputs.

## Validation Commands

Targeted checks:

```bash
./mvnw -pl devtools/gradle/gradle-extension-deployment-plugin -DskipITs test
./mvnw -pl devtools/gradle/gradle-app-plugin -DskipITs test
```

Broader Gradle integration tests may be needed if local helper/test-fixture
behavior changes:

```bash
./mvnw -pl integration-tests/gradle -Dtest=TestFixtureMultiModuleTest test
./mvnw -pl integration-tests/gradle -Dtest=TestCompositeBuildWithExtensionsTest test
```

Use the Quarkus build instructions for exact flags if these modules require
additional local build setup.

## Open Design Checks During Implementation

- Confirm whether `ResolvedArtifactResult` sets can safely be task inputs in the
  same way existing `QuarkusResolvedClasspath` uses them.
- Confirm whether the model task can derive direct workspace dependency edges
  from `ResolvedDependencyResult` traversal alone.
- Confirm whether id-only dependency `WorkspaceModule` values are sufficient for
  extension deployment tests. Add module/build directory fields only if a test
  or consumer proves they are needed.
- Confirm whether jar fallback is already covered by the original runtime
  configuration artifacts before adding extra fallback inputs to the model task.
- Keep the implementation contained to `gradle-model`,
  `gradle-app-plugin`, and `gradle-extension-deployment-plugin`.
