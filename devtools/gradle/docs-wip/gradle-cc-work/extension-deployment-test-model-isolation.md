# Extension Deployment Test Model Isolation

Status: implemented focused fix; broader integration coverage deferred
Last reviewed: 2026-07-14

## Problem

The new `io.quarkus.extension.deployment` Gradle plugin previously routed
generated extension-deployment test application models through the legacy shared
Gradle model path.

The concrete path is:

1. `QuarkusExtensionDeploymentPlugin.registerTestApplicationModel(...)`
2. `ProjectDescriptorBuilder.buildForApp(project)`
3. `ProjectDescriptorBuilder.buildWorkspaceModules(project)`
4. `project.getRootProject().getAllprojects()` and included-build project walks

That meant the deployment plugin no longer relied on the runtime extension
plugin mutating the deployment project, but its generated test model still read
other Gradle projects directly. This was not suitable for isolated projects and
kept configuration-cache/project-isolation claims for the deployment plugin
scoped to paths that did not exercise this generated model.

## Implemented State

The focused isolated-project fix is implemented. The archived implementation
record is
`archive/extension-deployment-test-model-isolation-implementation-plan.md`.

Current shape:

- `ProjectDescriptorBuilder.buildForCurrentProject(project)` builds only the
  deployment project's own descriptor for the isolated path.
- `LocalComponentOutputViews` in `gradle-model` centralizes artifact-view based
  local classes/resources/jar output resolution for both
  `io.quarkus.application` dev wiring and
  `io.quarkus.extension.deployment` generated test model wiring.
- `ApplicationModelTaskConfigurator` exposes separate legacy and isolated task
  registration methods, with the legacy application plugin still on the legacy
  live-project path.
- `QuarkusExtensionDeploymentPlugin.registerTestApplicationModel(...)` uses the
  current-project descriptor and local component output views instead of
  `ProjectDescriptorBuilder.buildForApp(project)`.
- `QuarkusApplicationModelTask` can attach local output artifacts to matching
  resolved dependencies without requiring a prebuilt all-project workspace
  index.
- `QuarkusExtensionDeploymentPluginTest` has a focused
  `quarkusGenerateTestAppModel` regression that runs with configuration cache
  and isolated projects, and verifies local main and `test-fixtures` outputs are
  represented separately in the serialized application model.

Remaining work is broader coverage, not the core implementation: add slower
integration tests for composite extension builds, extension-to-extension
dependencies, helper libraries, classifier artifacts, and Jandex/indexed local
outputs. That follow-up is tracked in `new-application-plugin-design.md`.

## Existing Documentation To Revisit

- `archive/p1-ep-01-deployment-project-plugin-plan.md`
  - Documents the new `io.quarkus.extension.deployment` plugin and the removal
    of runtime-plugin-to-deployment-project mutation.
  - Its completed status stays true for the marker-variant and mutation split.
    The generated deployment test model no longer uses the legacy
    workspace-discovery path for the isolated deployment-plugin task.
- `pom-resolution-boundary-design.md`
  - The POM-resolution slice deliberately left extension deployment test models
    alone. The later isolated local-output fix described here is now complete,
    while POM enrichment for those test models remains unnecessary.
- `application-model-and-codegen.md`
  - Documents why the legacy model collects workspace modules by walking root
    and included-build projects.
  - This note records the first implemented replacement path for generated
    deployment test models. Tooling/dev source metadata remains separate.
- `build-tooling-model-design.md`
  - Already points at outgoing producer metadata variants as the general
    replacement for reading dependency projects' mutable Gradle model.
  - The extension-deployment test model should first try the narrower
    artifact-view output path. Revisit the broader producer metadata design only
    for fields that cannot be obtained from Gradle resolution and variants.

## What The Deployment Plugin Actually Needs

`registerTestApplicationModel(...)` itself does not inherently need an
`allprojects` walk. It needs to register `quarkusGenerateTestAppModel` and wire
the resulting serialized model into every deployment-project `Test` task through
`quarkus-internal-test.serialized-app-model.path`.

The shared `GenerateApplicationModelTask` currently requires a
`DefaultProjectDescriptor`, and that descriptor provides:

- the deployment project's own `WorkspaceModule`, used to construct the root
  application artifact in the generated test model;
- a coordinate-indexed workspace-module lookup used while walking resolved
  dependencies, so local project dependencies can be represented as workspace
  modules rather than ordinary jars.

For extension deployment tests, the important local dependency is normally the
runtime module:

```gradle
dependencies {
    implementation project(":runtime")
}
```

The generated test model needs to know that this dependency is a local runtime
workspace module with output metadata, not just an external jar. The legacy
implementation obtains that by pre-indexing every project in the root and
included builds.

## Implemented Direction

The Gradle-native shape replaces global project traversal with Gradle
dependency resolution and artifact views.

Implemented shape after investigation and follow-up discussion:

- do not start with a broad source-root workspace metadata contract for this
  issue;
- share a Gradle-native local component output resolver, backed by artifact
  views with variant reselection, in `gradle-model` so both
  `io.quarkus.application` and `io.quarkus.extension.deployment` can use it;
- the shared resolver should cover selected component identity, classes output
  directories, resources output directories, jar fallback artifacts, and any
  classifier/variant data needed to map resolved components back into the
  generated model;
- `io.quarkus.extension.deployment` uses those output views for generated
  test model local component metadata instead of opening producer projects;
- source/resource source directories should be omitted from the extension
  deployment test-model fix unless a targeted consumer or regression test proves
  they are required;
- keep a versioned generic workspace/source metadata artifact as a later
  tooling/dev-mode design option, not as the first extension-deployment fix;
- extension-specific metadata, such as runtime/deployment artifact mapping,
  descriptor properties, conditional dependencies, dev dependencies, and
  dependency conditions, should be a separate facet or variant if needed;
- components without local output variants remain ordinary external/file
  dependencies and should use jar fallback behavior where appropriate.

This is separate from the existing marker-variant direction:

- runtime extension project -> resolves deployment marker variant to validate
  that the deployment project applies `io.quarkus.extension.deployment`;
- deployment project -> resolves local component classes/resources/jar outputs
  to build its generated test application model without opening other projects.

## Investigation Work

Status after implementation: the major questions below were answered well
enough to implement the focused fix. Remaining work is broader integration
coverage for representative real-world Gradle shapes.

1. Compare extension-deployment test model metadata needs with the tooling-model
   replacement design.

   Inspect `build-tooling-model-design.md` and current tooling-model consumers
   to decide whether the extension-deployment test model needs the same broad
   workspace metadata contract. If only output-side data overlaps, prefer a
   reusable local component output resolver now and defer source-root metadata to
   tooling/dev-mode work.

   Finding: there is significant overlap for output-side metadata, but the
   extension deployment test model should not force the full dev/tooling source
   metadata contract. Local component output resolution is now shared through
   `gradle-model`; source-root workspace metadata remains a later tooling/dev
   design unless proven necessary.

2. Determine whether the deployment test model needs local component output
   metadata only for the extension runtime project, or also for arbitrary local
   helper modules in the resolved graph.

   The common split-extension case is `implementation project(":runtime")`, but
   fixtures and real projects may include local support modules, test fixtures,
   or included-build dependencies whose workspace metadata still matters.

   Finding: local component output metadata is needed for every participating
   local component in the resolved deployment test/application graph, not only
   the runtime module. The legacy model attaches workspace metadata to every
   resolved dependency whose coordinates match the workspace index.

3. Inventory the exact local component output fields required by extension
   deployment tests, Quarkus test bootstrap, and devtools consumers of the
   serialized test model.

   This should identify the minimum data needed to construct the generated test
   model without opening producer projects: module/component identity,
   classes/resources outputs, jar fallback artifacts, classifier information,
   and any direct dependency edges that cannot be reconstructed from the selected
   Gradle graph.

   Finding: for the extension deployment test-model fix, the likely minimum is
   component coordinates, artifact key/classifier, local workspace-module
   identity, classes/resources output directories, jar fallback artifacts, and
   selected direct workspace dependency edges if reloadable ordering still needs
   them. Source/resource source directories, build files, generated source roots,
   and broader dev/tooling metadata should be optional or deferred.

4. Decide whether direct workspace dependency edges can be reconstructed from the
   selected Gradle dependency graph, or whether the shared helper/task wiring
   must model them explicitly.

   The answer affects both correctness and metadata size. If the selected graph
   is sufficient, consumers can derive edges from resolved components. If not,
   the generated test model wiring needs explicit dependency-edge data.

   Finding: selected graph edges should be used where possible. Only model
   selected direct workspace edges explicitly if the consumer cannot reconstruct
   them from the resolution result without opening producer projects. This is a
   design detail for the shared helper/task-wiring phase.

5. Choose or create the isolated-project TestKit reproducer.

   The fixture failed with the old `buildForApp(project)` path under
   `-Dorg.gradle.unsafe.isolated-projects=true` and now passes once deployment
   test model generation consumes local component outputs from Gradle resolution
   instead of opening other projects.

   Finding: use the existing two-project extension-deployment TestKit shape from
   `QuarkusExtensionDeploymentPluginTest.deploymentTestsUseGeneratedApplicationModel`,
   but target `:deployment:quarkusGenerateTestAppModel` directly under
   `--configuration-cache -Dorg.gradle.unsafe.isolated-projects=true`.

## Evidence From Investigation

### Tooling-Model Overlap And Scope Split

The tooling model and extension-deployment test model overlap, but not enough to
make full source-root metadata the first fix.

The broader tooling/dev model can need a full workspace metadata core:

- `WorkspaceModule` carries module identity, module/build directories, build
  files, source sets, parent id, test classpath additions/exclusions, direct
  dependency constraints, and direct dependencies.
- Current Gradle model generation fills dependency workspace modules from live
  projects with module id, project directory, build directory, build file, and
  source/resource/output roots.
- Dev/tooling consumers read module directories, build directories,
  source/resource directories, output directories, generated source directories,
  and direct workspace dependency edges for reload ordering and project
  information.

For extension deployment test model generation, the likely hard requirement is
output-side local component metadata. Source/resource source roots are primarily
used by dev-mode style behavior: source watching, resource watching/copying,
changed-source-to-class mapping, code generation source parents, and legacy
Quarkus-side compilation. Deployment module unit tests should not need those
paths unless a targeted regression proves otherwise.

Therefore, split the contract:

- local component outputs: shared now via artifact views in `gradle-model`;
- workspace source metadata: optional later contract for dev/tooling paths;
- extension metadata: optional separate facet for extension-specific semantics.

The existing `io.quarkus.application` dev task already has the output-side
pattern in `TaskRegistration.devModeArtifactView(...)` and the surrounding
classes/resources/jar view setup. That code should move into a shared
`gradle-model` helper rather than staying under
`io.quarkus.gradle.application.internal`, because
`gradle-extension-deployment-plugin` also needs it.

### Local Component Scope

The replacement should not special-case only `project(":runtime")`.

Evidence:

- `QuarkusApplicationModelTask` attempts a workspace-module lookup for every
  resolved dependency by coordinates.
- In test mode, a matched local module can be marked reloadable and can be
  recorded as a direct workspace dependency of its parent module.
- Existing Gradle integration coverage for test fixtures asserts arbitrary local
  modules and test-fixture classifier artifacts are `workspace-module` and
  `reloadable`.
- Existing composite-build extension fixtures have deployment modules depending
  on included-build helper libraries, with local helper-to-helper dependencies.

The local component output resolver should therefore cover all selected local
components on the graph relevant to deployment test model generation.

### Reproducer

The smallest reproducer should be a focused TestKit case based on the existing
two-project deployment plugin fixture:

```text
settings.gradle:
  include 'runtime', 'deployment'

runtime:
  plugins { id 'java' }
  group = 'org.acme'
  version = '1.0.0'

deployment:
  plugins { id 'io.quarkus.extension.deployment' }
  group = 'org.acme'
  version = '1.0.0'
  dependencies { implementation project(':runtime') }
```

Run:

```bash
:deployment:quarkusGenerateTestAppModel --configuration-cache -Dorg.gradle.unsafe.isolated-projects=true
```

Historical failure shape before the local-output fix:

```text
Project ':deployment' cannot access 'Project.allprojects' functionality on another project ':'
```

If the root project collection access had been removed while the model still
opened dependency projects directly, follow-up failures would have been expected
around mutable project reads such as group, version, layout/build file, tasks,
and source sets.

## Source References

- `devtools/gradle/gradle-extension-deployment-plugin/src/main/java/io/quarkus/extension/deployment/gradle/QuarkusExtensionDeploymentPlugin.java`
- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/ProjectDescriptorBuilder.java`
- `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tasks/QuarkusApplicationModelTask.java`
- `devtools/gradle/gradle-extension-plugin/src/main/java/io/quarkus/extension/gradle/QuarkusExtensionPlugin.java`
