# Application Models And Code Generation

Status: working notes for Gradle configuration-cache / isolated-projects work
Last reviewed: 2026-07-09

## Existing Documentation

There does not appear to be a good single conceptual document in the Quarkus
codebase or public Quarkus guides that explains the application model shape,
why each part exists, and how build tools should populate it.

Existing coverage is partial:

- `ApplicationModel` has useful API-level Javadoc in
  `independent-projects/bootstrap/app-model/src/main/java/io/quarkus/bootstrap/model/ApplicationModel.java`.
- Public Gradle docs mention that Quarkus build/codegen run in workers and
  show serialized app-model paths in debug output, but they do not explain the
  model contract.
- The public build-items reference says `CurateOutcomeBuildItem` exposes the
  resulting `ApplicationModel` to build steps, but it does not document the
  model structure.
- Existing `gradle-cc-work` docs discuss specific modernization findings and
  plans, but not the end-to-end model contract.

This file records the current understanding needed for the Gradle-native
application plugin.

## What The Application Model Represents

`ApplicationModel` is Quarkus bootstrap's resolved view of an application. It
is not only a dependency list. It combines:

- the application artifact;
- resolved runtime and deployment dependencies;
- compile-only dependencies when needed by augmentation or tooling;
- direct-dependency metadata, including dependencies configured but missing
  from the selected application graph;
- Quarkus platform imports and platform properties;
- extension capability metadata;
- class-loading metadata from extension descriptors, including parent-first,
  runner-parent-first, lower-priority artifacts, removed resources, and
  excluded artifacts;
- extension dev-mode JVM configuration metadata;
- workspace-module metadata for local projects when the build tool can provide
  it;
- the set of reloadable workspace dependencies for dev/test style launch
  modes.

The model is consumed by Quarkus bootstrap, augmentation, deployment build
steps, code generators, dev mode, test bootstrap, and devtools commands. The
same core type is used in Maven and Gradle, but each build tool has different
ways to discover and serialize the data.

## Why Code Generation Needs A Model

Code generation runs before Java compilation, but it still needs a Quarkus
bootstrap view of the application:

- it creates a `CuratedApplication`;
- it creates the deployment class loader;
- it invokes `io.quarkus.deployment.CodeGenerator`;
- code generators receive the `ApplicationModel` and build-system properties;
- some generators inspect dependencies to locate helper artifacts or tools.

The gRPC code generator is a concrete example of dependency use, not
source-folder use. It absolutely needs `.proto` inputs, but those come from the
`CodeGenContext` input directory, such as `src/main/proto` or `src/test/proto`,
and optionally from runtime dependency content trees when dependency proto
scanning is enabled. It uses `ApplicationModel` to inspect dependencies and
find artifacts such as protoc/grpc executables, import-providing artifacts, and
plugin artifacts.

For Gradle project dependencies, this means dependency proto scanning can work
only for proto files visible through the selected dependency artifact's content
tree, for example a jar or directory artifact that actually contains the
`.proto` files. The new plugin should not inspect sibling projects'
`src/main/proto` or `src/test/proto` directories directly. If a producer Gradle
module wants another module's gRPC codegen to see its proto files, those proto
files need to be exposed through a normal Gradle artifact or variant that the
consumer resolves.

This creates an ordering constraint for Gradle:

```text
pre-codegen application model
  -> quarkusApplicationGenerateCode
  -> compileJava / compileKotlin
  -> classes
  -> production application model
  -> build/image/native/deploy tasks
```

The pre-codegen model must not depend on compiled application classes, because
those classes depend on code generation. The production model may depend on
classes and resources because package/build tasks need the compiled
application artifact.

## What Workspace Modules Add

`WorkspaceModule` attaches local build-workspace information to the application
artifact and to local project dependencies. It can contain:

- module id, module directory, build directory, and build files;
- main/test/additional artifact source groups;
- source directories;
- resource directories;
- output directories for classes/resources;
- generated-source directories;
- direct dependencies and dependency constraints;
- parent workspace-module information.

`ResolvedDependency.getContentTree()` prefers the workspace module output tree
when present. That allows Quarkus to read local project output directories
instead of only packaged jars. This is important for development workflows and
for local project dependencies whose jar may not be the artifact Gradle should
force consumers to build first.

## Why Source Folders Appear In Legacy Gradle Models

The legacy Gradle model path builds workspace modules by walking every project
in the root build and included builds:

- `ProjectDescriptorBuilder.buildWorkspaceModules(...)` iterates
  `rootProject.getAllprojects()` and included-build projects.
- `ProjectDescriptorBuilder.initSourceDirs(...)` inspects `Jar` tasks and
  `Test` tasks.
- It maps source sets to the source/resource directories and output directories
  that feed those jars/tests.
- `QuarkusApplicationModelTask` attaches matching workspace modules to
  resolved dependencies when the dependency coordinates match a collected
  workspace module.

That explains why the legacy serialized model can contain source folders for
many projects. It eagerly builds a workspace descriptor for the whole Gradle
workspace, then attaches descriptors where dependency coordinates match.

The first implemented replacement for one generated-model path is documented in
`extension-deployment-test-model-isolation.md`: the
`io.quarkus.extension.deployment` test model now uses a current-project
descriptor plus artifact-view local classes/resources outputs instead of the
all-project workspace scan. Broader tooling/dev source metadata remains a
separate design concern.

The source-folder data has real consumers:

- legacy `QuarkusDev` turns workspace-module source/resource/output paths into
  `DevModeContext.ModuleInfo`;
- Quarkus bootstrap uses workspace-module output trees for reloadable
  dependencies and class-loader construction;
- devtools project-state and info commands use workspace-module metadata;
- some deployment processors use the application module's source/resource
  roots for source-aware behavior, for example welcome/dev UI and Qute
  source-location logic.

However, this does not mean every production build model needs all source
folders for all projects. For a normal package build, the essential data is the
selected application/deployment graph and the content roots needed for
augmentation. Source roots are mostly a dev/tooling/source-location concern.

## New Gradle Plugin Interpretation

For the Gradle-native application plugin, do not blindly reproduce the legacy
"walk every project and serialize all source folders" behavior.

Current direction:

- normal package/image/native/deploy tasks should depend on Gradle-resolved
  artifacts and explicit task inputs, not live cross-project project state;
- the application artifact should include its compiled classes/resources as
  resolved paths;
- local project dependency task ordering should come from Gradle dependency
  resolution and selected artifacts;
- workspace-module source roots should be populated only when a task or feature
  materially needs source-aware behavior;
- dev mode, run, and continuous test need a separate design because they
  genuinely need source/resource/output roots and should integrate with
  Gradle's continuous build model instead of reconstructing projects ad hoc;
- if future source-aware production features need source roots, model those as
  explicit inputs or producer artifacts, not cross-project inspection.

The new plugin's current `GenerateModelTask` follows this narrower direction:
it builds a workspace module for the current application project from declared
task inputs, records existing application classes/resources as the application
artifact paths, derives dependencies from resolved classpaths, and does not
attach source folders for every dependency project.

## Open Design Questions

- Which production build steps truly require source roots rather than compiled
  outputs/content trees?
- Should source-root metadata be absent from normal production models unless a
  source-aware feature is enabled?
- Should dev/run/continuous-test use a separate, richer workspace model rather
  than overloading the package-build application model?
- For project dependencies and included builds, should Quarkus-specific
  workspace metadata be exposed as a Gradle variant/artifact if needed, instead
  of discovered by the consuming project?
- Which extension processors currently assume `getApplicationModule()` has
  source/resource directories during production augmentation, and are those
  assumptions required or only best-effort tooling behavior?

## References

- `ApplicationModel`:
  `independent-projects/bootstrap/app-model/src/main/java/io/quarkus/bootstrap/model/ApplicationModel.java`
- `WorkspaceModule` and sources:
  `independent-projects/bootstrap/app-model/src/main/java/io/quarkus/bootstrap/workspace/WorkspaceModule.java`
  and related `ArtifactSources`, `SourceDir`, `LazySourceDir` types.
- Legacy Gradle workspace model collection:
  `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/ProjectDescriptorBuilder.java`
- Legacy Gradle model serialization:
  `devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tasks/QuarkusApplicationModelTask.java`
- New plugin model serialization:
  `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/modelgen/GenerateModelTask.java`
- Codegen worker:
  `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/codegen/worker/CodegenWorker.java`
- Public Gradle guide:
  `https://quarkus.io/guides/gradle-tooling`
- Public build-item reference:
  `https://quarkus.io/guides/all-builditems`
