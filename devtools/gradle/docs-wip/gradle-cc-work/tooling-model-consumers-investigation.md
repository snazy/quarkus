# Gradle Tooling Model Consumers Investigation

Date: 2026-07-11

Status: investigation note

Related:

- [Build Tooling Model Design](build-tooling-model-design.md)
- [POM Resolution Boundary Design](pom-resolution-boundary-design.md)
- [Application Model And Codegen](application-model-and-codegen.md)

## Question

Which IDEs or tools detect Quarkus Gradle projects through the Quarkus Gradle
tooling model, and which consumers actually request
`io.quarkus.bootstrap.model.ApplicationModel` through Gradle Tooling API?

## Summary

The current evidence does not show mainstream IDE integrations using the Quarkus
Gradle `ApplicationModel` tooling model for Quarkus project detection or run
configuration setup.

The IDE integrations inspected use one of these shapes instead:

- dependency/classpath or project-label detection for Quarkus;
- Gradle/Eclipse Buildship/IDE-native project import state for Gradle ownership;
- command or launch-configuration execution of Gradle tasks such as
  `quarkusDev`.

Confirmed `ApplicationModel` tooling-model consumers are mostly inside Quarkus
itself:

- bootstrap/devtools helpers that open a Gradle Tooling API connection;
- compatibility utilities and legacy Gradle tasks that call
  `ToolingUtils.create(...)`;
- Quarkus Gradle integration tests that exercise the tooling model directly.

This changes the risk framing. We should still preserve the tooling model as a
public-ish compatibility surface, but the current modernization work should not
assume that IntelliJ, Eclipse, or VS Code require the existing enriched
`ApplicationModel` shape for import or detection.

## IntelliJ IDEA

JetBrains' public Quarkus documentation describes Quarkus run configurations as
IDE run configurations that execute the needed Maven goal or Gradle task.

Observed behavior from installed IDEA 2026.1 Quarkus plugin bytecode:

- Quarkus detection appears library-based. `QuarkusUtils.hasQuarkusLibrary(...)`
  checks for `io.quarkus:quarkus-core`.
- The Gradle import hook creates Quarkus run configurations after Gradle import
  for modules that have the Quarkus library.
- String and bytecode scans found no references to `ApplicationModel`,
  `ModelParameter`, `io.quarkus.bootstrap`, or `QuarkusModelBuildAction`.

Confidence:

- high for installed IDEA 2026.1 behavior;
- medium for JetBrains' long-term implementation because the implementation is
  proprietary.

Implication: IntelliJ should not be treated as proven consumer of the Quarkus
Gradle `ApplicationModel` tooling model. It likely detects Quarkus from module
dependencies and uses Gradle tasks for run configurations.

References:

- JetBrains Quarkus help:
  <https://www.jetbrains.com/help/idea/quarkus.html>
- Quarkus IDE tooling guide:
  <https://quarkus.io/guides/ide-tooling>

## VS Code

VS Code Quarkus support is implemented by `vscode-quarkus`, Quarkus LS, LSP4MP,
and JDT LS.

Observed behavior:

- lightweight VS Code activation scans `pom.xml` and `build.gradle` files for
  `io.quarkus`;
- normal project labels come from JDT/LS workspace/project label commands;
- the Gradle label is produced by checking Eclipse Buildship's Gradle project
  nature;
- the Quarkus label is contributed by Quarkus LS by checking whether a Quarkus
  runtime class is on the JDT classpath;
- Gradle actions construct terminal commands around `gradle` or `gradlew`
  tasks such as `quarkusDev`, `buildNative`, and `addExtension`.

Targeted searches found no `ApplicationModel`, `GradleConnector`,
`ToolingModelBuilder`, or `QuarkusGradleModelFactory` use in the VS Code or
Quarkus LS paths.

Confidence: high for current public source.

References:

- VS Code Quarkus README:
  <https://github.com/redhat-developer/vscode-quarkus>
- Quarkus LS README:
  <https://github.com/redhat-developer/quarkus-ls>
- `vscode-quarkus` activation:
  <https://github.com/redhat-developer/vscode-quarkus/blob/master/src/extension.ts>
- `vscode-quarkus` Gradle command support:
  <https://github.com/redhat-developer/vscode-quarkus/blob/master/src/buildSupport/GradleBuildSupport.ts>
- LSP4MP Gradle label provider:
  <https://github.com/eclipse-lsp4mp/lsp4mp/blob/master/microprofile.jdt/org.eclipse.lsp4mp.jdt.core/src/main/java/org/eclipse/lsp4mp/jdt/internal/core/providers/GradleProjectLabelProvider.java>
- Quarkus LS project label provider:
  <https://github.com/redhat-developer/quarkus-ls/blob/master/quarkus.jdt.ext/com.redhat.microprofile.jdt.quarkus/src/main/java/com/redhat/microprofile/jdt/internal/quarkus/providers/QuarkusProjectLabelProvider.java>

## Eclipse / JBoss Tools / CodeReady Studio

Eclipse-based Quarkus support appears to use Eclipse/JDT project state and
Buildship launches, not the Quarkus Gradle tooling model.

Observed behavior:

- `ProjectUtils.isQuarkusProject(IJavaProject)` checks for a Quarkus marker
  type on the JDT classpath, using `io.quarkus.runtime.LaunchMode`;
- the launch UI validates "Java project" plus "Quarkus project";
- Maven versus Gradle dispatch is based on Eclipse project natures;
- Gradle execution is owned by `GradleToolSupport`, which creates Buildship run
  configurations and runs Gradle tasks such as `quarkusDev` or
  `quarkus:add-extension`.

Targeted searches found no `ApplicationModel`, `GradleApplicationModelBuilder`,
`QuarkusGradleModelFactory`, `connection.model(ApplicationModel.class)`, or
custom Gradle tooling model request in JBoss Tools Quarkus or Quarkus LS.

Confidence: high for current public JBoss Tools Quarkus and Quarkus LS source.

References:

- Quarkus Eclipse announcement:
  <https://quarkus.io/blog/eclipse-got-quarkused/>
- JBoss Tools Quarkus project utilities:
  <https://github.com/jbosstools/jbosstools-quarkus/blob/main/plugins/org.jboss.tools.quarkus.core/src/org/jboss/tools/quarkus/core/project/ProjectUtils.java>
- JBoss Tools Quarkus Gradle support:
  <https://github.com/jbosstools/jbosstools-quarkus/blob/main/plugins/org.jboss.tools.quarkus.core/src/org/jboss/tools/quarkus/tool/GradleToolSupport.java>
- JBoss Tools Quarkus launch UI:
  <https://github.com/jbosstools/jbosstools-quarkus/blob/main/plugins/org.jboss.tools.quarkus.ui/src/org/jboss/tools/quarkus/ui/launch/QuarkusProjectTab.java>

## Confirmed Quarkus-Side Consumers

The legacy `io.quarkus` Gradle plugin registers the model provider:

- `QuarkusPlugin.registerModel()` registers
  `new GradleApplicationModelBuilder()`.
- `GradleApplicationModelBuilder` implements
  `ParameterizedToolingModelBuilder<ModelParameter>`.
- `canBuild(...)` returns true for `ApplicationModel.class.getName()`.
- unparameterized requests default to `LaunchMode.DEVELOPMENT`;
  parameterized requests use `ModelParameter.getMode()`.

Confirmed callers:

- `QuarkusLauncher` loads `IDELauncherImpl`; for Gradle projects,
  `IDELauncherImpl` calls `BuildToolHelper.enableGradleAppModelForDevMode(...)`,
  serializes the Gradle model, and uses workspace module output directories as
  additional application archives.
- `QuarkusGradleModelFactory.create(...)` opens a Gradle `ProjectConnection` and
  runs `QuarkusModelBuildAction`.
- `QuarkusModelBuildAction.execute(...)` calls
  `controller.getModel(ApplicationModel.class, ModelParameter.class, ...)`.
- `QuarkusGradleModelFactory.createForTasks(...)` calls
  `connection.model(ApplicationModel.class)`.
- `BuildToolHelper.enableGradleAppModel*` uses
  `QuarkusGradleModelFactory` for Gradle projects and exports serialized models.
- `AppModelGradleResolver.resolveModel(...)` calls `ToolingUtils.create(...)`.
- `QuarkusPlatformTask` uses `AppModelGradleResolver` while merging platforms.
- Gradle integration tests use the Tooling API model directly, including
  `QuarkusModelBuilderTest` and `CompileOnlyDependencyFlagsTest`.

Local source references:

- [QuarkusPlugin.java](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java)
- [GradleApplicationModelBuilder.java](../../../../devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/GradleApplicationModelBuilder.java)
- [QuarkusGradleModelFactory.java](../../../../independent-projects/bootstrap/gradle-resolver/src/main/java/io/quarkus/bootstrap/resolver/QuarkusGradleModelFactory.java)
- [QuarkusModelBuildAction.java](../../../../independent-projects/bootstrap/gradle-resolver/src/main/java/io/quarkus/bootstrap/resolver/QuarkusModelBuildAction.java)
- [BuildToolHelper.java](../../../../independent-projects/bootstrap/core/src/main/java/io/quarkus/bootstrap/utils/BuildToolHelper.java)
- [IDELauncherImpl.java](../../../../independent-projects/bootstrap/core/src/main/java/io/quarkus/bootstrap/IDELauncherImpl.java)
- [QuarkusLauncher.java](../../../../core/launcher/src/main/java/io/quarkus/launcher/QuarkusLauncher.java)
- [AppModelGradleResolver.java](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/AppModelGradleResolver.java)
- [QuarkusPlatformTask.java](../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusPlatformTask.java)
- [QuarkusModelBuilderTest.java](../../../../integration-tests/gradle/src/test/java/io/quarkus/gradle/builder/QuarkusModelBuilderTest.java)
- [CompileOnlyDependencyFlagsTest.java](../../../../integration-tests/gradle/src/test/java/io/quarkus/gradle/CompileOnlyDependencyFlagsTest.java)

## Design Implications

1. Preserve `ApplicationModel` tooling-model compatibility unless we explicitly
   introduce a versioned replacement. It is still a Quarkus-exposed Tooling API
   model and Quarkus uses it internally.
2. Do not overfit the design to unproven IDE usage. Current evidence says IDEs
   detect Quarkus through dependencies/classpath/project labels and execute
   Gradle tasks.
3. Treat `BuildToolHelper` and `QuarkusGradleModelFactory` as the important
   non-task consumers to validate. `IDELauncherImpl` is the IDE-named Quarkus
   entry point that reaches them for Gradle projects.
4. Keep the model's dev/test workspace-discovery semantics explicit. Even if
   IDEs do not use the model directly, Quarkus bootstrap/devtools callers may
   depend on them.
5. Direct-dependency/effective-POM enrichment should be mode/use-case driven.
   The IDE investigation did not find a mainstream IDE consumer that requires
   external Maven effective-model declared-dependency enrichment.

## Remaining Unknowns

- IntelliJ Ultimate is proprietary. Public docs and installed-plugin bytecode do
  not show `ApplicationModel` usage, but we cannot prove behavior across all
  JetBrains versions from public source alone.
- Third-party tools outside the known Quarkus IDE plugins may use
  `QuarkusGradleModelFactory` or request `ApplicationModel` directly. No such
  consumer was found during this investigation.
- Historical IDE/plugin versions may have used different paths. The current
  design should preserve compatibility for the existing model type, but the
  modernization plan should target current known consumers.
