package io.quarkus.gradle.application;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

import io.quarkus.deployment.dev.DevModeMain;
import io.quarkus.gradle.application.dsl.PluginInternalHelper;
import io.quarkus.gradle.application.dsl.QuarkusApplicationBuild;
import io.quarkus.gradle.application.dsl.QuarkusApplicationConfigInputs;
import io.quarkus.gradle.application.dsl.QuarkusApplicationDeployment;
import io.quarkus.gradle.application.dsl.QuarkusApplicationExtension;
import io.quarkus.gradle.application.dsl.QuarkusApplicationForkOptions;
import io.quarkus.gradle.application.dsl.QuarkusApplicationRunnerOutput;
import io.quarkus.gradle.application.internal.modelgen.ClasspathBuilder;
import io.quarkus.gradle.application.internal.modelgen.GenerateModelTask;
import io.quarkus.gradle.application.internal.planning.PackageOutputName;
import io.quarkus.gradle.application.internal.planning.TaskNamePlanner;
import io.quarkus.gradle.application.internal.planning.TaskNameSegment;
import io.quarkus.gradle.application.internal.planning.TaskNames;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildDescriptor;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentDescriptor;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentImageSource;
import io.quarkus.gradle.application.model.QuarkusApplicationLaunchKind;
import io.quarkus.gradle.application.model.QuarkusApplicationVariantAttributes;
import io.quarkus.gradle.application.tasks.QuarkusApplicationAotEnhancedImageBuildTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationAotEnhancedImagePushTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationAotEnhancedImageTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationAotTrainingTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationBaseTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationBuildTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationContinuousTestTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationDeployTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationDevTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationGenerateCodeTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationImageBuildTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationImagePushTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationImageTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationNativeTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationNativeTestTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationPackageTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationRemoteDevTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationRunTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationTask;
import io.quarkus.gradle.dependency.LocalComponentOutputViews;
import io.quarkus.gradle.tooling.dependency.DeclaredDependencyEnrichmentMode;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.gradle.tooling.dependency.ExternalModuleDeclaredDependencyInput;
import io.quarkus.gradle.tooling.tasks.GeneratePomClosureTask;
import io.quarkus.runtime.LaunchMode;

final class TaskRegistration {

    private static final String QUARKUS_APPLICATION_GROUP = "quarkus application";
    private static final String DEV_MODE_CLASSPATH_CONFIGURATION = "quarkusApplicationDevModeClasspath";
    private static final String REMOTE_DEV_BUILD_NAME = "remoteDev";
    private static final String REMOTE_DEV_BUILD_TASK_NAME = "quarkusApplicationRemoteDevBuild";
    private static final String REMOTE_DEV_TASK_NAME = "quarkusApplicationRemoteDev";
    private static final List<String> JANDEX_TASK_NAMES = List.of("jandex", "processJandexIndex");

    private final TaskNamePlanner planner = new TaskNamePlanner();
    private final Set<String> taskNames = new HashSet<>();
    private final Map<String, BuildRegistration> buildNames = new HashMap<>();
    private final Map<String, String> imageReferences = new HashMap<>();
    private TaskProvider<GenerateModelTask> applicationModel;
    private TaskProvider<GenerateModelTask> devApplicationModel;
    private TaskProvider<GenerateModelTask> codegenApplicationModel;
    private TaskProvider<GenerateModelTask> testCodegenApplicationModel;
    private TaskProvider<QuarkusApplicationGenerateCodeTask> generateCode;
    private TaskProvider<QuarkusApplicationGenerateCodeTask> generateTestCode;

    void register(Project project, QuarkusApplicationExtension extension) {
        ClasspathBuilder classpath = new ClasspathBuilder(project);
        Provider<Configuration> devModeClasspath = registerDevModeClasspathConfiguration(project);
        applicationModel = registerApplicationModelTask(project, classpath, "quarkusApplicationModel", LaunchMode.NORMAL,
                SourceSet.MAIN_SOURCE_SET_NAME, true,
                "quarkus/application-model/quarkus-application-model.dat",
                "Resolves the Quarkus application model used by named application build tasks.",
                DeclaredDependencyEnrichmentMode.SELECTED_MODULE_POMS);
        devApplicationModel = registerApplicationModelTask(project, classpath, "quarkusApplicationDevModel",
                LaunchMode.DEVELOPMENT, SourceSet.MAIN_SOURCE_SET_NAME, true,
                "quarkus/application-model/quarkus-application-dev-model.dat",
                "Resolves the Quarkus application model used by Gradle-native dev mode.",
                DeclaredDependencyEnrichmentMode.NONE);
        wireJandexTasksIntoApplicationModels(project);
        codegenApplicationModel = registerApplicationModelTask(project, classpath, "quarkusApplicationCodegenModel",
                LaunchMode.NORMAL, SourceSet.MAIN_SOURCE_SET_NAME, false,
                "quarkus/application-model/quarkus-application-codegen-model.dat",
                "Resolves the Quarkus application model used before main-source code generation.",
                DeclaredDependencyEnrichmentMode.NONE);
        testCodegenApplicationModel = registerApplicationModelTask(project, classpath, "quarkusApplicationTestCodegenModel",
                LaunchMode.TEST, SourceSet.TEST_SOURCE_SET_NAME, false,
                "quarkus/application-model/quarkus-application-test-codegen-model.dat",
                "Resolves the Quarkus application model used before test-source code generation.",
                DeclaredDependencyEnrichmentMode.NONE);
        generateCode = registerGenerateCodeTask(project, extension, classpath, "quarkusApplicationGenerateCode",
                LaunchMode.NORMAL, SourceSet.MAIN_SOURCE_SET_NAME, codegenApplicationModel,
                "generated/sources/quarkus-application/main",
                "Runs Quarkus code generators for main sources.");
        generateTestCode = registerGenerateCodeTask(project, extension, classpath, "quarkusApplicationGenerateTestCode",
                LaunchMode.TEST, SourceSet.TEST_SOURCE_SET_NAME, testCodegenApplicationModel,
                "generated/sources/quarkus-application/test",
                "Runs Quarkus code generators for test sources.");
        wireGeneratedSourcesIntoJavaCompilation(project);
        wireGeneratedSourcesIntoKotlinCompilation(project);
        registerDevTask(project, extension, classpath, devModeClasspath);
        registerRemoteDevTasks(project, extension);
        extension.getBuilds().all(build -> registerBuild(project, extension, build));
    }

    private static Provider<Configuration> registerDevModeClasspathConfiguration(Project project) {
        return project.getConfigurations().register(DEV_MODE_CLASSPATH_CONFIGURATION, configuration -> {
            configuration.setDescription("Internal classpath used to launch Gradle-native Quarkus dev mode.");
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setCanBeDeclared(true);
            configuration.getDependencies().add(devModeDependency(project, "quarkus-bootstrap-gradle-resolver"));
            configuration.getDependencies().add(devModeDependency(project, "quarkus-bootstrap-maven-resolver"));
            configuration.getDependencies().add(devModeDependency(project, "quarkus-core-deployment"));
        });
    }

    private static Dependency devModeDependency(Project project, String artifactId) {
        String pomPropsPath = "META-INF/maven/io.quarkus/" + artifactId + "/pom.properties";
        Properties properties = new Properties();
        try (InputStream stream = DevModeMain.class.getClassLoader().getResourceAsStream(pomPropsPath)) {
            if (stream == null) {
                throw new GradleException("Failed to locate " + pomPropsPath + " on the plugin classpath");
            }
            properties.load(stream);
        } catch (IOException e) {
            throw new GradleException("Failed to load " + pomPropsPath + " from the plugin classpath", e);
        }
        String groupId = requiredPomProperty(properties, pomPropsPath, "groupId");
        String version = requiredPomProperty(properties, pomPropsPath, "version");
        return project.getDependencies().create(groupId + ":" + artifactId + ":" + version);
    }

    private static String requiredPomProperty(Properties properties, String source, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new GradleException("Classpath resource " + source + " is missing " + name);
        }
        return value;
    }

    private void registerDevTask(Project project, QuarkusApplicationExtension extension, ClasspathBuilder classpath,
            Provider<Configuration> devModeClasspath) {
        registerTaskName(project, "quarkusApplicationDev");
        project.getTasks().register("quarkusApplicationDev", QuarkusApplicationDevTask.class, task -> {
            configureDevTask(project, extension, classpath, devModeClasspath, task);
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Runs Gradle-native Quarkus dev mode using Gradle continuous build.");
            task.notCompatibleWithConfigurationCache(
                    "Gradle-native Quarkus dev mode requires Gradle continuous build and keeps a long-lived deployment session.");
        });
    }

    private void configureDevTask(Project project, QuarkusApplicationExtension extension, ClasspathBuilder classpath,
            Provider<Configuration> devModeClasspath, QuarkusApplicationDevTask task) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet mainSourceSet = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        LocalComponentOutputViews localComponentOutputs = LocalComponentOutputViews.of(project.getObjects(),
                classpath.getDevRuntimeConfiguration());

        task.dependsOn(project.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));
        task.dependsOn(devApplicationModel);
        task.getLaunchKind().set(QuarkusApplicationLaunchKind.DEV);
        task.getBuildName().set("dev");
        task.getBuildType().set(QuarkusApplicationBuildType.FAST_JAR);
        task.getContinuousBuild().set(project.getGradle().getStartParameter().isContinuous());
        task.getApplicationName().set(project.getName());
        task.getApplicationVersion().set(project.getVersion().toString());
        task.getQuarkusBuildProperties().set(extension.getQuarkusBuildProperties());
        task.getQuarkusBuildProperties().putAll(extension.getDev().getQuarkusBuildProperties());
        task.getDevJvmArgs().set(extension.getDev().getForkOptions().getJvmArgs());
        task.getDevSystemProperties().set(extension.getDev().getForkOptions().getSystemProperties());
        task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        task.getBuildDirectory().set(project.getLayout().getBuildDirectory());
        task.getApplicationModel().set(devApplicationModel.flatMap(GenerateModelTask::getApplicationModel));
        task.getSourceDirectories().from(mainSourceSet.getResources().getSourceDirectories());
        task.getDevModeClasspath().from(devModeClasspath);
        task.getApplicationClasses().from(mainSourceSet.getOutput().getClassesDirs());
        if (mainSourceSet.getOutput().getResourcesDir() != null) {
            task.getApplicationResources().from(mainSourceSet.getOutput().getResourcesDir());
        }
        task.getDependencyClasses().from(localComponentOutputs.classFiles());
        task.getDependencyResources().from(localComponentOutputs.resourceFiles());
        task.getRuntimeJarsWithoutOutputVariants()
                .from(localComponentOutputs.jarFilesWithoutOutputVariants(project.getProviders()));
        task.getReceiptFile().set(project.getLayout().getBuildDirectory()
                .file("quarkus-dev/dev-iteration.properties"));
        task.getCloseReceiptFile().set(project.getLayout().getBuildDirectory()
                .file("quarkus-dev/dev-session-closed.txt"));
        task.getOutputSnapshotFile().set(project.getLayout().getBuildDirectory()
                .file("quarkus-dev/dev-output-snapshot.tsv"));
        configureConfigInputs(task, extension.getConfigInputs());
    }

    private void registerRemoteDevTasks(Project project, QuarkusApplicationExtension extension) {
        TaskProvider<QuarkusApplicationPackageTask> remoteDevBuild = registerRemoteDevPackageTask(project, extension);
        registerRemoteDevTask(project, extension, remoteDevBuild);
    }

    private TaskProvider<QuarkusApplicationPackageTask> registerRemoteDevPackageTask(Project project,
            QuarkusApplicationExtension extension) {
        registerTaskName(project, REMOTE_DEV_BUILD_TASK_NAME);
        return project.getTasks().register(REMOTE_DEV_BUILD_TASK_NAME, QuarkusApplicationPackageTask.class, task -> {
            configureRemoteDevPackageTask(project, extension, task);
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Builds the internal mutable-jar package used by Gradle-native Quarkus remote dev.");
        });
    }

    private void configureRemoteDevPackageTask(Project project, QuarkusApplicationExtension extension,
            QuarkusApplicationPackageTask task) {
        task.getBuildName().set(REMOTE_DEV_BUILD_NAME);
        task.getBuildType().set(QuarkusApplicationBuildType.MUTABLE_JAR);
        task.getOutputName().set(project.provider(() -> PackageOutputName.assemble(project.getName(), "",
                project.getVersion().toString())));
        task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("quarkus-remote-dev/build"));
        task.getQuarkusBuildProperties().set(extension.getQuarkusBuildProperties());
        task.getQuarkusBuildProperties().putAll(extension.getRemoteDev().getQuarkusBuildProperties());
        task.getManifestAttributes().set(Map.of());
        task.getApplicationName().set(project.getName());
        task.getApplicationVersion().set(project.getVersion().toString());
        task.getGradleBuildDirectory().set(project.getLayout().getBuildDirectory());
        task.getApplicationModel().set(applicationModel.flatMap(GenerateModelTask::getApplicationModel));
        task.getPackageResultFile().set(project.getLayout().getBuildDirectory()
                .file("quarkus-remote-dev/build-result/package-result.properties"));
        configureForkOptions(task.getBuildForkOptions(), extension.getBuildForkOptions());
        configureRemoteDevForkOptions(task.getBuildForkOptions(), extension);
        configureJavaInputs(project, task);
        configureConfigInputs(task, extension.getConfigInputs());
    }

    private void registerRemoteDevTask(Project project, QuarkusApplicationExtension extension,
            TaskProvider<QuarkusApplicationPackageTask> remoteDevBuild) {
        registerTaskName(project, REMOTE_DEV_TASK_NAME);
        project.getTasks().register(REMOTE_DEV_TASK_NAME, QuarkusApplicationRemoteDevTask.class, task -> {
            task.dependsOn(remoteDevBuild);
            task.getLaunchKind().set(QuarkusApplicationLaunchKind.REMOTE_DEV);
            task.getBuildName().set(REMOTE_DEV_BUILD_NAME);
            task.getBuildType().set(QuarkusApplicationBuildType.MUTABLE_JAR);
            task.getOutputName().set(remoteDevBuild.flatMap(QuarkusApplicationPackageTask::getOutputName));
            task.getOutputDirectory().set(remoteDevBuild.flatMap(QuarkusApplicationPackageTask::getOutputDirectory));
            task.getContinuousBuild().set(project.getGradle().getStartParameter().isContinuous());
            task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
            task.getQuarkusBuildProperties().set(extension.getQuarkusBuildProperties());
            task.getQuarkusBuildProperties().putAll(extension.getRemoteDev().getQuarkusBuildProperties());
            task.getPackageResultFile().set(remoteDevBuild.flatMap(QuarkusApplicationPackageTask::getPackageResultFile));
            task.getPackageOutputDirectory().set(remoteDevBuild.flatMap(QuarkusApplicationPackageTask::getOutputDirectory));
            task.getReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file("quarkus-remote-dev/build-result/remote-dev-result.properties"));
            task.getPackageSnapshotFile().set(project.getLayout().getBuildDirectory()
                    .file("quarkus-remote-dev/snapshot/package-snapshot.tsv"));
            task.getCloseReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file("quarkus-remote-dev/snapshot/session-closed.txt"));
            configureConfigInputs(task, extension.getConfigInputs());
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Runs Gradle-native Quarkus remote dev using an internal mutable-jar package.");
            task.notCompatibleWithConfigurationCache(
                    "Gradle-native Quarkus remote dev requires Gradle continuous build and keeps a long-lived remote session.");
        });
    }

    private TaskProvider<GenerateModelTask> registerApplicationModelTask(Project project,
            ClasspathBuilder classpath, String taskName, LaunchMode launchMode, String sourceSetName,
            boolean dependsOnClasses, String modelPath, String description,
            DeclaredDependencyEnrichmentMode enrichmentMode) {
        SourceSet sourceSet = project.getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(sourceSetName);
        TaskProvider<GeneratePomClosureTask> pomClosureTask = enrichmentMode == DeclaredDependencyEnrichmentMode.SELECTED_MODULE_POMS
                ? registerPomClosureTask(project, classpath, taskName, launchMode)
                : null;
        return project.getTasks().register(taskName, GenerateModelTask.class, task -> {
            if (dependsOnClasses) {
                task.dependsOn(project.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));
            }
            task.getLaunchMode().set(launchMode);
            task.getProjectGroup().set(project.getGroup().toString());
            task.getProjectName().set(project.getName());
            task.getProjectVersion().set(project.getVersion().toString());
            task.getProjectBuildFile().fileValue(project.getBuildFile());
            task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
            task.getBuildDirectory().set(project.getLayout().getBuildDirectory());
            if (dependsOnClasses) {
                task.getApplicationClassesDirectories().from(sourceSet.getOutput().getClassesDirs());
                if (sourceSet.getOutput().getResourcesDir() != null) {
                    task.getApplicationResourcesDirectories().from(sourceSet.getOutput().getResourcesDir());
                }
            }
            task.getApplicationSourceDirectoryPaths().set(directoryPaths(sourceSet.getAllJava()));
            task.getApplicationResourceSourceDirectoryPaths().set(directoryPaths(sourceSet.getResources()));
            task.getOriginalClasspath().setFrom(originalClasspath(classpath, launchMode));
            task.getAppClasspath().configureFrom(runtimeConfiguration(classpath, launchMode));
            task.getPlatformConfiguration().configureFrom(classpath.getPlatformPropertiesConfiguration());
            task.getPlatformInfo().configureFrom(classpath.getPlatformPropertiesConfiguration());
            task.getCompileOnlyClasspath().configureFrom(compileOnlyConfiguration(classpath, launchMode));
            task.getDeploymentClasspath().configureFrom(deploymentConfiguration(classpath, launchMode));
            task.getDeploymentClasspathFiles()
                    .from(deploymentConfiguration(classpath, launchMode).getIncoming().getArtifacts().getArtifactFiles());
            task.getMavenLocalRepositoryRoots().set(project.getProviders().systemProperty("maven.repo.local")
                    .map(List::of)
                    .orElse(List.of()));
            task.getDeclaredDependencyEnrichmentMode().set(enrichmentMode);
            if (pomClosureTask != null) {
                task.getPomClosureFile().set(pomClosureTask.flatMap(GeneratePomClosureTask::getPomClosureFile));
            }
            task.getApplicationModel().set(project.getLayout().getBuildDirectory().file(modelPath));
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription(description);
        });
    }

    private TaskProvider<GeneratePomClosureTask> registerPomClosureTask(Project project, ClasspathBuilder classpath,
            String modelTaskName, LaunchMode launchMode) {
        Configuration runtimeConfiguration = runtimeConfiguration(classpath, launchMode);
        Configuration deploymentConfiguration = deploymentConfiguration(classpath, launchMode);
        ArtifactView runtimePomView = pomArtifactView(runtimeConfiguration);
        ArtifactView deploymentPomView = pomArtifactView(deploymentConfiguration);
        Provider<Set<ResolvedArtifactResult>> runtimeArtifacts = runtimeConfiguration.getIncoming()
                .getArtifacts()
                .getResolvedArtifacts();
        Provider<Set<ResolvedArtifactResult>> deploymentArtifacts = deploymentConfiguration.getIncoming()
                .getArtifacts()
                .getResolvedArtifacts();
        Provider<List<ExternalModuleDeclaredDependencyInput>> externalModuleInputs = project
                .provider(() -> DependencyDataCollector
                        .externalModuleDeclaredDependencyInputs(Stream.concat(runtimeArtifacts.get().stream(),
                                deploymentArtifacts.get().stream()).toList()));
        Provider<Map<String, String>> selectedPomFiles = project.provider(() -> {
            Map<String, String> result = new HashMap<>();
            collectPomFilesByGav(runtimePomView.getArtifacts().getArtifacts(), result);
            collectPomFilesByGav(deploymentPomView.getArtifacts().getArtifacts(), result);
            return result;
        });
        String taskName = modelTaskName + "PomClosure";
        return project.getTasks().register(taskName, GeneratePomClosureTask.class, task -> {
            task.getExternalModuleInputs().set(externalModuleInputs);
            task.getSelectedPomFilesByGav().set(selectedPomFiles);
            task.getSelectedPomFiles().from(runtimePomView.getFiles(), deploymentPomView.getFiles());
            task.getMavenLocalRepositoryRoots().set(project.getProviders().systemProperty("maven.repo.local")
                    .map(List::of)
                    .orElse(List.of()));
            task.getPomClosureFile().set(project.getLayout().getBuildDirectory()
                    .file("quarkus/application-model/pom-closure/" + modelTaskName + ".properties"));
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Resolves the Maven POM closure used to enrich " + modelTaskName + ".");
        });
    }

    private static ArtifactView pomArtifactView(Configuration configuration) {
        return configuration.getIncoming().artifactView(view -> {
            view.withVariantReselection();
            view.lenient(true);
            view.componentFilter(component -> component instanceof ModuleComponentIdentifier);
            view.attributes(attributes -> attributes.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "pom"));
        });
    }

    private static void collectPomFilesByGav(Set<ResolvedArtifactResult> artifacts, Map<String, String> target) {
        for (ResolvedArtifactResult artifact : artifacts) {
            ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
            if (componentIdentifier instanceof ModuleComponentIdentifier module) {
                target.put(module.getGroup() + ":" + module.getModule() + ":" + module.getVersion(),
                        artifact.getFile().getAbsolutePath());
            }
        }
    }

    private static FileCollection originalClasspath(ClasspathBuilder classpath, LaunchMode launchMode) {
        if (launchMode == LaunchMode.TEST) {
            return classpath.getOriginalTestRuntimeClasspathAsInput();
        }
        if (launchMode == LaunchMode.DEVELOPMENT) {
            return classpath.getOriginalDevRuntimeClasspathAsInput();
        }
        return classpath.getOriginalRuntimeClasspathAsInput();
    }

    private static Provider<List<String>> directoryPaths(SourceDirectorySet sourceDirectories) {
        return sourceDirectories.getSourceDirectories().getElements().map(elements -> elements.stream()
                .map(FileSystemLocation::getAsFile)
                .map(File::getAbsolutePath)
                .sorted()
                .toList());
    }

    private static Configuration runtimeConfiguration(ClasspathBuilder classpath,
            LaunchMode launchMode) {
        if (launchMode == LaunchMode.TEST) {
            return classpath.getTestRuntimeConfiguration();
        }
        if (launchMode == LaunchMode.DEVELOPMENT) {
            return classpath.getDevRuntimeConfiguration();
        }
        return classpath.getRuntimeConfiguration();
    }

    private static Configuration deploymentConfiguration(ClasspathBuilder classpath,
            LaunchMode launchMode) {
        if (launchMode == LaunchMode.TEST) {
            return classpath.getTestDeploymentConfiguration();
        }
        return classpath.getDeploymentConfiguration();
    }

    private static Configuration compileOnlyConfiguration(ClasspathBuilder classpath,
            LaunchMode launchMode) {
        if (launchMode == LaunchMode.TEST) {
            return classpath.getTestCompileOnlyConfiguration();
        }
        return classpath.getCompileOnlyConfiguration();
    }

    private TaskProvider<QuarkusApplicationGenerateCodeTask> registerGenerateCodeTask(Project project,
            QuarkusApplicationExtension extension, ClasspathBuilder classpath, String taskName,
            LaunchMode launchMode, String sourceSetName, TaskProvider<GenerateModelTask> modelTask,
            String generatedSourcesPath, String description) {
        registerTaskName(project, taskName);
        SourceSet sourceSet = project.getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(sourceSetName);
        Set<File> sourceParentDirectories = sourceParents(sourceSet);
        return project.getTasks().register(taskName, QuarkusApplicationGenerateCodeTask.class, task -> {
            task.getApplicationModel().set(modelTask.flatMap(GenerateModelTask::getApplicationModel));
            task.getLaunchMode().set(launchMode);
            task.getApplicationName().set(project.getName());
            task.getApplicationVersion().set(project.getVersion().toString());
            task.getBuildDirectory().set(project.getLayout().getBuildDirectory());
            task.getGeneratedOutputDirectory().set(project.getLayout().getBuildDirectory().dir(generatedSourcesPath));
            task.getQuarkusBuildProperties().set(extension.getQuarkusBuildProperties());
            task.getCodegenProviders().set(extension.getCodegen().getProviders());
            task.getCodegenInputNames().set(extension.getCodegen().getInputNames());
            configureForkOptions(task.getCodegenForkOptions(), extension.getCodeGenForkOptions());
            task.getClasspath().from(originalClasspath(classpath, launchMode),
                    deploymentConfiguration(classpath, launchMode).getIncoming().getArtifacts().getArtifactFiles());
            task.getSourceParentDirectories().from(sourceParentDirectories);
            configureConfigInputs(task, extension.getConfigInputs());
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription(description);
        });
    }

    private static Set<File> sourceParents(SourceSet sourceSet) {
        return sourceSet.getJava().getSrcDirs().stream()
                .map(File::getParentFile)
                .collect(Collectors.toSet());
    }

    private void wireGeneratedSourcesIntoJavaCompilation(Project project) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet mainSourceSet = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet testSourceSet = java.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);

        project.getTasks().named(mainSourceSet.getCompileJavaTaskName(), JavaCompile.class, task -> {
            task.dependsOn(generateCode);
            task.source(generateCode.flatMap(QuarkusApplicationGenerateCodeTask::getGeneratedOutputDirectory));
        });
        project.getTasks().named(testSourceSet.getCompileJavaTaskName(), JavaCompile.class, task -> {
            task.dependsOn(generateCode, generateTestCode);
            task.source(generateTestCode.flatMap(QuarkusApplicationGenerateCodeTask::getGeneratedOutputDirectory));
        });
    }

    private void wireGeneratedSourcesIntoKotlinCompilation(Project project) {
        project.getPlugins().withId("org.jetbrains.kotlin.jvm",
                plugin -> KotlinGeneratedSourceWiring.wireKotlinCompileTasks(project, generateCode, generateTestCode));
        project.getPlugins().withId("org.jetbrains.kotlin.kapt",
                plugin -> KotlinGeneratedSourceWiring.wireKaptStubTasks(project, generateCode, generateTestCode));
    }

    private void wireJandexTasksIntoApplicationModels(Project project) {
        for (String jandexTaskName : JANDEX_TASK_NAMES) {
            TaskCollection<?> jandexTasks = project.getTasks().matching(task -> task.getName().equals(jandexTaskName));
            applicationModel.configure(task -> task.dependsOn(jandexTasks));
            devApplicationModel.configure(task -> task.dependsOn(jandexTasks));
        }
    }

    private void registerBuild(Project project, QuarkusApplicationExtension extension, QuarkusApplicationBuild build) {
        BuildRegistration buildRegistration = validateNamedBuild(build);
        TaskNames names = planner.taskNames(buildRegistration.descriptor());

        TaskProvider<? extends QuarkusApplicationBuildTask> namedBuild = registerNamedBuildTask(project, extension,
                buildRegistration,
                names.build());
        if (buildRegistration.type().isJar()) {
            registerNamedPackageElementsConfiguration(project, buildRegistration.descriptor(), namedBuild);
        }
        if (buildRegistration.type() == QuarkusApplicationBuildType.NATIVE_EXECUTABLE) {
            registerNamedNativeTestTask(project, buildRegistration, names.nativeTest(), extension.getConfigInputs());
        }
        if (buildRegistration.type().isJar()) {
            registerNamedRunTask(project, extension, buildRegistration, names.run(), names.build());
        }
        registerReservedLaunchTask(project, extension, buildRegistration,
                "quarkus" + buildRegistration.taskSegment().value() + "ContinuousTest",
                QuarkusApplicationContinuousTestTask.class, QuarkusApplicationLaunchKind.CONTINUOUS_TEST);

        validateNamedImageReference(project, build);
        registerNamedImageBuildTask(project, extension, buildRegistration, names.imageBuild());
        registerNamedImagePushTask(project, extension, buildRegistration, names.imagePush());
        PluginInternalHelper.whenAotEnhancedImageConfigured(build, ignored -> {
            validateNamedAotEnhancedImageReference(build);
            registerNamedAotTrainingTask(project, buildRegistration, names.aotTraining(), extension.getConfigInputs());
            registerNamedAotEnhancedImageBuildTask(project, extension, buildRegistration, names.aotEnhancedImageBuild(), names);
            registerNamedAotEnhancedImagePushTask(project, extension, buildRegistration, names.aotEnhancedImagePush(), names);
        });

        Map<String, String> deploymentNames = new HashMap<>();
        build.getDeployments().all(deployment -> {
            validateNamedDeployment(deployment, deploymentNames);
            String deployTaskName = planner.deployTaskName(buildRegistration.descriptor(),
                    new QuarkusApplicationDeploymentDescriptor(
                            deployment.getName(),
                            deployment.getTarget(),
                            deployment.getImageSource()
                                    .getOrElse(QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH),
                            Optional.ofNullable(deployment.getImageReference().getOrNull())));
            registerNamedDeployTask(project, extension, buildRegistration, deployment, deployTaskName, names);
        });
    }

    private void registerNamedPackageElementsConfiguration(Project project, QuarkusApplicationBuildDescriptor descriptor,
            TaskProvider<? extends QuarkusApplicationBuildTask> namedBuild) {
        String configurationName = packageElementsConfigurationName(descriptor.name());
        project.getConfigurations().register(configurationName, configuration -> {
            configuration.setDescription("Provides the primary runnable JAR produced by the '"
                    + descriptor.name() + "' Quarkus application package build.");
            configuration.setCanBeConsumed(true);
            configuration.setCanBeResolved(false);
            configuration.setCanBeDeclared(false);
            configuration.getAttributes().attribute(Category.CATEGORY_ATTRIBUTE,
                    project.getObjects().named(Category.class, QuarkusApplicationVariantAttributes.PACKAGE_CATEGORY));
            configuration.getAttributes().attribute(Usage.USAGE_ATTRIBUTE,
                    project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
            configuration.getAttributes().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
            configuration.getAttributes().attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    project.getObjects().named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
            configuration.getAttributes().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    ArtifactTypeDefinition.JAR_TYPE);
            configuration.getAttributes().attribute(QuarkusApplicationVariantAttributes.BUILD_NAME_ATTRIBUTE,
                    descriptor.name());
            configuration.getAttributes().attribute(QuarkusApplicationVariantAttributes.BUILD_TYPE_ATTRIBUTE,
                    descriptor.type().jarType().orElse(descriptor.type().name()));
            project.getArtifacts().add(configurationName, namedBuild.flatMap(task -> ((QuarkusApplicationPackageTask) task)
                    .getPrimaryJarFile()), artifact -> artifact.builtBy(namedBuild));
        });
    }

    static String packageElementsConfigurationName(String buildName) {
        return "quarkus" + TaskNameSegment.of(buildName).value() + "PackageElements";
    }

    private TaskProvider<? extends QuarkusApplicationBuildTask> registerNamedBuildTask(Project project,
            QuarkusApplicationExtension extension, BuildRegistration buildRegistration, String taskName) {
        registerTaskName(project, taskName);
        Class<? extends QuarkusApplicationBuildTask> taskType = buildRegistration.type().isNativeOutput()
                ? QuarkusApplicationNativeTask.class
                : QuarkusApplicationPackageTask.class;
        return project.getTasks().register(taskName, taskType, task -> {
            configureNamedBuildTask(project, extension, task, buildRegistration);
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription(buildDescription(buildRegistration));
            if (task instanceof QuarkusApplicationPackageTask packageTask) {
                packageTask.getManifestAttributes().set(buildRegistration.build().getManifestAttributes());
                packageTask.getPackageResultFile().set(project.getLayout().getBuildDirectory()
                        .file(packageResultPath(buildRegistration, "package-result.properties")));
            }
            if (task instanceof QuarkusApplicationNativeTask nativeTask) {
                nativeTask.getNativeArguments().set(buildRegistration.build().getNativeArguments());
                nativeTask.getNativeResultFile().set(project.getLayout().getBuildDirectory()
                        .file(packageResultPath(buildRegistration, "native-result.properties")));
            }
        });
    }

    private void registerNamedNativeTestTask(Project project, BuildRegistration buildRegistration,
            String taskName, QuarkusApplicationConfigInputs configInputs) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, QuarkusApplicationNativeTestTask.class, task -> {
            configureNamedTask(task, buildRegistration, configInputs);
            task.setGroup("verification");
            task.setDescription("Runs tests against the '" + buildRegistration.name() + "' native executable.");
        });
    }

    private void registerNamedRunTask(Project project, QuarkusApplicationExtension extension,
            BuildRegistration buildRegistration, String taskName, String packageTaskName) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, QuarkusApplicationRunTask.class, task -> {
            configureNamedBuildTask(project, extension, task, buildRegistration);
            task.dependsOn(packageTaskName);
            task.getPackageResultFile().set(project.getLayout().getBuildDirectory()
                    .file(packageResultPath(buildRegistration, "package-result.properties")));
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Runs the '" + buildRegistration.name()
                    + "' Quarkus application from its package build output.");
        });
    }

    private void registerReservedLaunchTask(Project project, QuarkusApplicationExtension extension,
            BuildRegistration buildRegistration, String taskName,
            Class<? extends QuarkusApplicationTask> taskType, QuarkusApplicationLaunchKind launchKind) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, taskType, task -> {
            configureNamedTask(task, buildRegistration, extension.getConfigInputs());
            if (task instanceof QuarkusApplicationContinuousTestTask continuousTestTask) {
                continuousTestTask.getLaunchKind().set(launchKind);
            }
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription(launchDescription(buildRegistration.build(), launchKind));
        });
    }

    private void registerNamedImageBuildTask(Project project, QuarkusApplicationExtension extension,
            BuildRegistration buildRegistration, String taskName) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, QuarkusApplicationImageBuildTask.class, task -> {
            configureNamedImageTask(project, extension, task, buildRegistration);
            task.getOutputDirectory()
                    .set(project.getLayout().getBuildDirectory().dir(operationOutputPath(buildRegistration, "image-build")));
            task.getReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file(operationResultPath(buildRegistration, "image-build", "image-build-result.properties")));
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Builds the container image for the '" + buildRegistration.name()
                    + "' Quarkus application build.");
        });
    }

    private void registerNamedImagePushTask(Project project, QuarkusApplicationExtension extension,
            BuildRegistration buildRegistration, String taskName) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, QuarkusApplicationImagePushTask.class, task -> {
            configureNamedImageTask(project, extension, task, buildRegistration);
            task.getOutputDirectory()
                    .set(project.getLayout().getBuildDirectory().dir(operationOutputPath(buildRegistration, "image-push")));
            task.getReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file(operationResultPath(buildRegistration, "image-push", "image-push-result.properties")));
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Builds and pushes the container image for the '" + buildRegistration.name()
                    + "' Quarkus application build.");
        });
    }

    private void registerNamedAotTrainingTask(Project project, BuildRegistration buildRegistration,
            String taskName, QuarkusApplicationConfigInputs configInputs) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, QuarkusApplicationAotTrainingTask.class, task -> {
            configureNamedTask(task, buildRegistration, configInputs);
            task.getAotFile().set(buildRegistration.build().getAotEnhancedImage().getAotFile());
            task.setGroup("verification");
            task.setDescription("Runs AOT training for the '" + buildRegistration.name() + "' Quarkus application build.");
        });
    }

    private void registerNamedAotEnhancedImageBuildTask(Project project, QuarkusApplicationExtension extension,
            BuildRegistration buildRegistration, String taskName, TaskNames names) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, QuarkusApplicationAotEnhancedImageBuildTask.class, task -> {
            configureNamedAotEnhancedImageTask(project, extension, task, buildRegistration);
            task.getOutputDirectory()
                    .set(project.getLayout().getBuildDirectory().dir(operationOutputPath(buildRegistration, "aot-build")));
            task.dependsOn(names.imageBuild());
            task.getBaseImageReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file(operationResultPath(buildRegistration, "image-build", "image-build-result.properties")));
            task.getReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file(operationResultPath(buildRegistration, "aot-build", "aot-image-build-result.properties")));
            var aotEnhancedImage = buildRegistration.build().getAotEnhancedImage();
            task.getAotFile().set(aotEnhancedImage.getAotFile());
            task.getAotFileProducerTaskName().set(aotEnhancedImage.getAotFileProducerTaskName());
            task.getImageReference().set(aotEnhancedImage.getImageReference());
            wireAotFileProducer(task);
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Builds the AOT-enhanced container image for the '" + buildRegistration.name()
                    + "' Quarkus application build.");
        });
    }

    private void registerNamedAotEnhancedImagePushTask(Project project, QuarkusApplicationExtension extension,
            BuildRegistration buildRegistration, String taskName, TaskNames names) {
        registerTaskName(project, taskName);
        project.getTasks().register(taskName, QuarkusApplicationAotEnhancedImagePushTask.class, task -> {
            configureNamedAotEnhancedImageTask(project, extension, task, buildRegistration);
            task.getOutputDirectory()
                    .set(project.getLayout().getBuildDirectory().dir(operationOutputPath(buildRegistration, "aot-push")));
            task.dependsOn(names.imagePush());
            task.getBaseImageReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file(operationResultPath(buildRegistration, "image-push", "image-push-result.properties")));
            task.getReceiptFile().set(project.getLayout().getBuildDirectory()
                    .file(operationResultPath(buildRegistration, "aot-push", "aot-image-push-result.properties")));
            var aotEnhancedImage = buildRegistration.build().getAotEnhancedImage();
            task.getAotFile().set(aotEnhancedImage.getAotFile());
            task.getAotFileProducerTaskName().set(aotEnhancedImage.getAotFileProducerTaskName());
            task.getImageReference().set(aotEnhancedImage.getImageReference());
            wireAotFileProducer(task);
            task.setGroup(QUARKUS_APPLICATION_GROUP);
            task.setDescription("Builds and pushes the AOT-enhanced container image for the '" + buildRegistration.name()
                    + "' Quarkus application build.");
        });
    }

    private void registerNamedDeployTask(Project project, QuarkusApplicationExtension extension,
            BuildRegistration buildRegistration, QuarkusApplicationDeployment deployment, String taskName,
            TaskNames names) {
        registerTaskName(project, taskName);
        var build = buildRegistration.build();
        TaskProvider<QuarkusApplicationDeployTask> deploy = project.getTasks().register(taskName,
                QuarkusApplicationDeployTask.class, task -> {
                    configureNamedBuildTask(project, extension, task, buildRegistration);
                    task.getDeploymentName().set(deployment.getName());
                    task.getDeploymentTarget().set(deployment.getTarget());
                    task.getImageSource().set(deployment.getImageSource());
                    task.getImageReference().set(deployment.getImageReference());
                    task.getReceiptFile().set(project.getLayout().getBuildDirectory()
                            .file(operationResultPath(buildRegistration, "deployments/" + deployment.getName(),
                                    "deployment-result.properties")));
                    task.setGroup(QUARKUS_APPLICATION_GROUP);
                    task.setDescription("Deploys the '" + build.getName() + "' Quarkus application build to the '"
                            + deployment.getName() + "' " + deployment.getTarget().quarkusDeployTarget() + " target.");
                });
        deploy.configure(task -> {
            if (deployment.getImageSource().getOrElse(
                    QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH) == QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH) {
                task.dependsOn(names.imagePush());
                task.getNormalImagePushReceiptFile().set(project.getLayout().getBuildDirectory()
                        .file(operationResultPath(buildRegistration, "image-push", "image-push-result.properties")));
            }
        });
        PluginInternalHelper.whenAotEnhancedImageConfigured(build, ignored -> deploy.configure(task -> {
            if (deployment.getImageSource().getOrElse(
                    QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH) == QuarkusApplicationDeploymentImageSource.AOT_ENHANCED_IMAGE_PUSH) {
                task.dependsOn(names.aotEnhancedImagePush());
                task.getAotEnhancedImagePushReceiptFile().set(project.getLayout().getBuildDirectory()
                        .file(operationResultPath(buildRegistration, "aot-push", "aot-image-push-result.properties")));
            }
        }));
    }

    private static String operationOutputPath(BuildRegistration buildRegistration, String operation) {
        return "quarkus-builds/" + buildRegistration.name() + "/" + operation;
    }

    private static String packageResultPath(BuildRegistration buildRegistration, String fileName) {
        return operationResultPath(buildRegistration, "package", fileName);
    }

    private static String operationResultPath(BuildRegistration buildRegistration, String operation, String fileName) {
        return "quarkus-build-results/" + buildRegistration.name() + "/" + operation + "/" + fileName;
    }

    private void configureNamedImageTask(Project project, QuarkusApplicationExtension extension,
            QuarkusApplicationImageTask task, BuildRegistration buildRegistration) {
        configureNamedBuildTask(project, extension, task, buildRegistration);
        var build = buildRegistration.build();
        task.getImageReference().set(build.getImage().getImageReference());
        task.getImageRepository().set(build.getImage().getRepository());
        task.getImageTag().set(build.getImage().getTag());
        task.getImageBuilder().set(build.getImage().getBuilder());
        task.getImageQuarkusBuildProperties().set(build.getImage().getQuarkusBuildProperties());
        task.getQuarkusBuildProperties().putAll(build.getImage().getQuarkusBuildProperties());
    }

    private void configureNamedAotEnhancedImageTask(Project project, QuarkusApplicationExtension extension,
            QuarkusApplicationAotEnhancedImageTask task, BuildRegistration buildRegistration) {
        configureNamedImageTask(project, extension, task, buildRegistration);
        var build = buildRegistration.build();
        task.getAotImageRepository().set(build.getAotEnhancedImage().getRepository());
        task.getAotImageTag().set(build.getAotEnhancedImage().getTag());
        task.getImageSuffix().set(build.getAotEnhancedImage().getImageSuffix());
    }

    private static void wireAotFileProducer(QuarkusApplicationAotEnhancedImageTask task) {
        if (task.getAotFileProducerTaskName().isPresent()) {
            task.dependsOn(task.getAotFileProducerTaskName().get());
        }
    }

    private static void validateNamedAotEnhancedImageReference(QuarkusApplicationBuild build) {
        if (build.getAotEnhancedImage().getImageReference().isPresent()
                && (build.getAotEnhancedImage().getRepository().isPresent()
                        || build.getAotEnhancedImage().getTag().isPresent())) {
            throw new IllegalArgumentException(
                    "AOT-enhanced image reference cannot be combined with repository or tag");
        }
    }

    private void configureNamedBuildTask(Project project, QuarkusApplicationExtension extension,
            QuarkusApplicationBuildTask task, BuildRegistration buildRegistration) {
        configureNamedTask(task, buildRegistration, extension.getConfigInputs());
        configureAdditionalDescriptorShapeProperties(task, buildRegistration.build());
        task.getQuarkusBuildProperties().set(extension.getQuarkusBuildProperties());
        task.getQuarkusBuildProperties().putAll(buildRegistration.build().getQuarkusBuildProperties());
        task.getApplicationName().set(project.getName());
        task.getApplicationVersion().set(project.getVersion().toString());
        task.getGradleBuildDirectory().set(project.getLayout().getBuildDirectory());
        task.getApplicationModel().set(applicationModel.flatMap(GenerateModelTask::getApplicationModel));
        configureForkOptions(task.getBuildForkOptions(), extension.getBuildForkOptions());
        configureJavaInputs(project, task);
    }

    private static void configureForkOptions(QuarkusApplicationForkOptions target, QuarkusApplicationForkOptions source) {
        target.getJvmArgs().set(source.getJvmArgs());
        target.getSystemProperties().set(source.getSystemProperties());
        target.getEnvironment().set(source.getEnvironment());
        target.getMinHeapSize().set(source.getMinHeapSize());
        target.getMaxHeapSize().set(source.getMaxHeapSize());
        target.getEnableAssertions().set(source.getEnableAssertions());
        target.getDebug().set(source.getDebug());
        target.getDefaultCharacterEncoding().set(source.getDefaultCharacterEncoding());
    }

    private static void configureRemoteDevForkOptions(QuarkusApplicationForkOptions target,
            QuarkusApplicationExtension extension) {
        target.getJvmArgs().addAll(extension.getRemoteDev().getForkOptions().getJvmArgs());
        target.getSystemProperties().putAll(extension.getRemoteDev().getForkOptions().getSystemProperties());
    }

    private static void configureAdditionalDescriptorShapeProperties(QuarkusApplicationBuildTask task,
            QuarkusApplicationBuild build) {
        if (build instanceof QuarkusApplicationRunnerOutput runnerOutput) {
            task.getAdditionalDescriptorShapeProperties().put("quarkus.package.runner-suffix",
                    runnerOutput.getArchiveRunnerSuffix());
            task.getAdditionalDescriptorShapeProperties().put("quarkus.package.jar.add-runner-suffix",
                    runnerOutput.getArchiveAddRunnerSuffix().map(String::valueOf));
        }
    }

    private static void configureJavaInputs(Project project, QuarkusApplicationBuildTask task) {
        JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
        if (java == null) {
            return;
        }
        SourceSet mainSourceSet = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        task.getRuntimeClasspath().from(mainSourceSet.getCompileClasspath(), mainSourceSet.getRuntimeClasspath(),
                mainSourceSet.getAnnotationProcessorPath(), mainSourceSet.getResources());
        task.getSourceDirectories().from(mainSourceSet.getResources().getSourceDirectories());
    }

    private static void configureNamedTask(QuarkusApplicationTask task, BuildRegistration buildRegistration,
            QuarkusApplicationConfigInputs configInputs) {
        task.getBuildName().set(buildRegistration.name());
        task.getBuildType().set(buildRegistration.type());
        task.getOutputName().set(buildRegistration.build().getOutputName());
        task.getOutputDirectory().set(buildRegistration.build().getOutputDirectory());
        configureConfigInputs(task, configInputs);
    }

    private static void configureConfigInputs(QuarkusApplicationBaseTask task,
            QuarkusApplicationConfigInputs configInputs) {
        task.getGradlePropertyPrefixes().set(configInputs.getProjectProperties().getPrefixes());
        task.getGradlePropertyNames().set(configInputs.getProjectProperties().getNames());
        task.getSystemPropertyPrefixes().set(configInputs.getSystemProperties().getPrefixes());
        task.getSystemPropertyNames().set(configInputs.getSystemProperties().getNames());
        task.getEnvironmentVariablePrefixes().set(configInputs.getEnvironmentVariables().getPrefixes());
        task.getEnvironmentVariableNames().set(configInputs.getEnvironmentVariables().getNames());
        task.getLegacyAmbientConfigCapture().set(configInputs.getLegacyAmbientConfigCapture());
        if (configInputs.getLegacyAmbientConfigCapture().getOrElse(false)) {
            task.notCompatibleWithConfigurationCache(
                    "Legacy ambient config capture reads all Gradle properties, JVM system properties, and environment variables.");
        }
    }

    private static String buildDescription(BuildRegistration buildRegistration) {
        return switch (buildRegistration.type()) {
            case FAST_JAR, LEGACY_JAR, MUTABLE_JAR, UBER_JAR -> "Builds the '" + buildRegistration.name() + "' "
                    + buildRegistration.type().jarType().orElseThrow() + " Quarkus application.";
            case NATIVE_EXECUTABLE -> "Builds the '" + buildRegistration.name() + "' native executable Quarkus application.";
            case NATIVE_SOURCES -> "Generates native-image sources for the '" + buildRegistration.name()
                    + "' Quarkus application.";
        };
    }

    private static String launchDescription(QuarkusApplicationBuild build, QuarkusApplicationLaunchKind launchKind) {
        return switch (launchKind) {
            case RUN -> "Reserved for future Gradle-native Quarkus application run support for the '" + build.getName()
                    + "' application; currently fails when executed.";
            case DEV -> "Reserved for future Gradle-native Quarkus dev mode for the '" + build.getName()
                    + "' application; currently fails when executed.";
            case REMOTE_DEV -> "Reserved for future Gradle-native Quarkus remote dev mode for the '" + build.getName()
                    + "' application; currently fails when executed.";
            case CONTINUOUS_TEST -> "Reserved for future Gradle-native Quarkus continuous testing for the '"
                    + build.getName() + "' application; currently fails when executed.";
        };
    }

    private BuildRegistration validateNamedBuild(QuarkusApplicationBuild build) {
        var buildRegistration = new BuildRegistration(build);
        var previous = buildNames.putIfAbsent(buildRegistration.taskSegment().collisionKey(), buildRegistration);
        if (previous != null) {
            throw new GradleException(
                    "Quarkus application build names '" + previous.name() + "' and '" + buildRegistration.name()
                            + "' derive the same task-name segment");
        }
        return buildRegistration;
    }

    private static void validateNamedDeployment(QuarkusApplicationDeployment deployment,
            Map<String, String> deploymentNames) {
        String previous = deploymentNames.putIfAbsent(TaskNameSegment.of(deployment.getName()).collisionKey(),
                deployment.getName());
        if (previous != null) {
            throw new GradleException("Quarkus application deployment names '" + previous + "' and '"
                    + deployment.getName() + "' derive the same task-name segment");
        }
    }

    private void validateNamedImageReference(Project project, QuarkusApplicationBuild build) {
        Optional<String> knownReference = knownImageReference(project, build);
        if (knownReference.isEmpty()) {
            return;
        }
        String reference = knownReference.get();
        String previous = imageReferences.putIfAbsent(reference, build.getName());
        if (previous != null && !previous.equals(build.getName())) {
            throw new GradleException("Quarkus application image reference '" + reference
                    + "' is used by named outputs '" + previous + "' and '" + build.getName() + "'");
        }
    }

    private static Optional<String> knownImageReference(Project project, QuarkusApplicationBuild build) {
        if (build.getImage().getImageReference().isPresent()) {
            if (build.getImage().getRepository().isPresent() || build.getImage().getTag().isPresent()) {
                throw new GradleException("Quarkus application image reference cannot be combined with repository or tag");
            }
            return Optional.of(build.getImage().getImageReference().get());
        }
        if (build.getImage().getRepository().isPresent()) {
            return Optional.of(build.getImage().getRepository().get() + ":" + imageTag(project, build));
        }
        return Optional.empty();
    }

    private static String imageTag(Project project, QuarkusApplicationBuild build) {
        if (build.getImage().getTag().isPresent()) {
            return build.getImage().getTag().get();
        }
        return defaultImageTag(project.getVersion().toString());
    }

    private static String defaultImageTag(String projectVersion) {
        if (projectVersion == null || "unspecified".equals(projectVersion)) {
            throw new IllegalArgumentException(
                    "Image tag defaults to project.version, but project.version is unspecified. "
                            + "Configure image.tag or project.version.");
        }
        return projectVersion;
    }

    private void registerTaskName(Project project, String taskName) {
        String key = taskName.toLowerCase(Locale.ROOT);
        if (!taskNames.add(key) || project.getTasks().getNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(key::equals)) {
            throw new GradleException("Quarkus application task name '" + taskName + "' collides with an existing task");
        }
    }

    private record BuildRegistration(
            QuarkusApplicationBuild build,
            QuarkusApplicationBuildDescriptor descriptor) {
        BuildRegistration(QuarkusApplicationBuild build) {
            this(build, new QuarkusApplicationBuildDescriptor(build.getName(), build.getBuildType()));
        }

        String name() {
            return descriptor.name();
        }

        QuarkusApplicationBuildType type() {
            return descriptor.type();
        }

        TaskNameSegment taskSegment() {
            return TaskNameSegment.of(name());
        }
    }
}
