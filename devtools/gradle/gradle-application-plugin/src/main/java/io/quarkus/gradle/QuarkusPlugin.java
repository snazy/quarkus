package io.quarkus.gradle;

import static io.quarkus.gradle.GradleUtils.composeDevFiles;
import static io.quarkus.gradle.extension.QuarkusPluginExtension.combinedOutputSourceDirs;
import static io.quarkus.gradle.tasks.QuarkusGradleUtils.getSourceSet;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.java.archives.Attributes;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import io.quarkus.gradle.actions.BeforeTestAction;
import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.extension.QuarkusLegacyTaskUsageLevel;
import io.quarkus.gradle.extension.QuarkusPluginExtension;
import io.quarkus.gradle.extension.SourceSetExtension;
import io.quarkus.gradle.tasks.BuildAotEnhancedImage;
import io.quarkus.gradle.tasks.Deploy;
import io.quarkus.gradle.tasks.ImageBuild;
import io.quarkus.gradle.tasks.ImageCheckRequirementsTask;
import io.quarkus.gradle.tasks.ImagePush;
import io.quarkus.gradle.tasks.LegacyTaskUsageDiagnostics;
import io.quarkus.gradle.tasks.LegacyTaskUsageDiagnostics.LegacyTaskUsage;
import io.quarkus.gradle.tasks.QuarkusAddExtension;
import io.quarkus.gradle.tasks.QuarkusApplicationModelTask;
import io.quarkus.gradle.tasks.QuarkusBuild;
import io.quarkus.gradle.tasks.QuarkusBuildCacheableAppParts;
import io.quarkus.gradle.tasks.QuarkusBuildDependencies;
import io.quarkus.gradle.tasks.QuarkusBuildTask;
import io.quarkus.gradle.tasks.QuarkusDev;
import io.quarkus.gradle.tasks.QuarkusGenerateCode;
import io.quarkus.gradle.tasks.QuarkusGoOffline;
import io.quarkus.gradle.tasks.QuarkusGradleUtils;
import io.quarkus.gradle.tasks.QuarkusInfo;
import io.quarkus.gradle.tasks.QuarkusListCategories;
import io.quarkus.gradle.tasks.QuarkusListExtensions;
import io.quarkus.gradle.tasks.QuarkusListPlatforms;
import io.quarkus.gradle.tasks.QuarkusPluginExtensionView;
import io.quarkus.gradle.tasks.QuarkusRemoteDev;
import io.quarkus.gradle.tasks.QuarkusRemoveExtension;
import io.quarkus.gradle.tasks.QuarkusRun;
import io.quarkus.gradle.tasks.QuarkusShowEffectiveConfig;
import io.quarkus.gradle.tasks.QuarkusTest;
import io.quarkus.gradle.tasks.QuarkusUpdate;
import io.quarkus.gradle.tasks.services.ForcedPropertieBuildService;
import io.quarkus.gradle.tooling.DefaultProjectDescriptor;
import io.quarkus.gradle.tooling.GradleApplicationModelBuilder;
import io.quarkus.gradle.tooling.ProjectDescriptorBuilder;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.gradle.tooling.dependency.DependencyUtils;
import io.quarkus.gradle.tooling.dependency.ExtensionDependency;
import io.quarkus.gradle.tooling.dependency.ProjectExtensionDependency;
import io.quarkus.gradle.tooling.tasks.ApplicationModelTaskConfigurator;
import io.quarkus.gradle.util.CustomFileSystemOperations;
import io.quarkus.runtime.LaunchMode;

public class QuarkusPlugin implements Plugin<Project> {

    public static final String ID = "io.quarkus";
    public static final String DEFAULT_OUTPUT_DIRECTORY = "quarkus-app";

    public static final String EXTENSION_NAME = "quarkus";
    public static final String LIST_EXTENSIONS_TASK_NAME = "listExtensions";
    public static final String LIST_CATEGORIES_TASK_NAME = "listCategories";
    public static final String LIST_PLATFORMS_TASK_NAME = "listPlatforms";
    public static final String ADD_EXTENSION_TASK_NAME = "addExtension";
    public static final String REMOVE_EXTENSION_TASK_NAME = "removeExtension";
    public static final String QUARKUS_GENERATE_CODE_TASK_NAME = "quarkusGenerateCode";
    public static final String QUARKUS_GENERATE_CODE_DEV_TASK_NAME = "quarkusGenerateCodeDev";
    public static final String QUARKUS_GENERATE_CODE_TESTS_TASK_NAME = "quarkusGenerateCodeTests";
    public static final String QUARKUS_BUILD_DEP_TASK_NAME = "quarkusDependenciesBuild";
    public static final String QUARKUS_BUILD_APP_PARTS_TASK_NAME = "quarkusAppPartsBuild";
    public static final String QUARKUS_SHOW_EFFECTIVE_CONFIG_TASK_NAME = "quarkusShowEffectiveConfig";
    public static final String QUARKUS_BUILD_TASK_NAME = "quarkusBuild";
    public static final String QUARKUS_DEV_TASK_NAME = "quarkusDev";
    public static final String QUARKUS_RUN_TASK_NAME = "quarkusRun";
    public static final String QUARKUS_REMOTE_DEV_TASK_NAME = "quarkusRemoteDev";
    public static final String QUARKUS_TEST_TASK_NAME = "quarkusTest";
    public static final String QUARKUS_GO_OFFLINE_TASK_NAME = "quarkusGoOffline";
    public static final String QUARKUS_INFO_TASK_NAME = "quarkusInfo";
    public static final String QUARKUS_UPDATE_TASK_NAME = "quarkusUpdate";
    public static final String IMAGE_BUILD_TASK_NAME = "imageBuild";
    public static final String IMAGE_PUSH_TASK_NAME = "imagePush";
    public static final String DEPLOY_TASK_NAME = "deploy";
    public static final String BUILD_AOT_ENHANCED_IMAGE_TASK_NAME = "buildAotEnhancedImage";
    private static final Map<String, String> LEGACY_APPLICATION_TASK_REPLACEMENTS = Map.of(
            QUARKUS_BUILD_TASK_NAME,
            "Apply io.quarkus.application, register an explicit named output under quarkusApplication.builds, and run quarkus<name>Build.",
            IMAGE_BUILD_TASK_NAME,
            "Apply io.quarkus.application, configure image output under quarkusApplication.builds.<name>.image, and run quarkus<name>ImageBuild.",
            IMAGE_PUSH_TASK_NAME,
            "Apply io.quarkus.application, configure image output under quarkusApplication.builds.<name>.image, and run quarkus<name>ImagePush.",
            DEPLOY_TASK_NAME,
            "Apply io.quarkus.application, configure deployments under quarkusApplication.builds.<name>.deployments, and run quarkus<name>DeployTo<deployment>.",
            BUILD_AOT_ENHANCED_IMAGE_TASK_NAME,
            "Apply io.quarkus.application, configure AOT-enhanced image output under quarkusApplication.builds.<name>.aotEnhancedImage, and run quarkus<name>AotEnhancedImageBuild.");

    @Deprecated
    public static final String BUILD_NATIVE_TASK_NAME = "buildNative";
    @Deprecated
    public static final String TEST_NATIVE_TASK_NAME = "testNative";
    private static final Map<String, String> DEPRECATED_NATIVE_TASK_REPLACEMENTS = Map.of(
            BUILD_NATIVE_TASK_NAME,
            "Apply io.quarkus.application, register a native executable output under quarkusApplication.builds, and run quarkus<name>Build.",
            TEST_NATIVE_TASK_NAME,
            "Apply io.quarkus.application, register a native executable output under quarkusApplication.builds, and run quarkus<name>NativeTest.");

    // this name has to be the same as the directory in which the tests reside
    public static final String NATIVE_TEST_SOURCE_SET_NAME = "native-test";

    public static final String NATIVE_TEST_IMPLEMENTATION_CONFIGURATION_NAME = "nativeTestImplementation";
    public static final String NATIVE_TEST_RUNTIME_ONLY_CONFIGURATION_NAME = "nativeTestRuntimeOnly";

    public static final String INTEGRATION_TEST_TASK_NAME = "quarkusIntTest";
    public static final String INTEGRATION_TEST_SOURCE_SET_NAME = "integrationTest";
    public static final String INTEGRATION_TEST_IMPLEMENTATION_CONFIGURATION_NAME = "integrationTestImplementation";
    public static final String INTEGRATION_TEST_RUNTIME_ONLY_CONFIGURATION_NAME = "integrationTestRuntimeOnly";
    public static final String IMAGE_CHECK_REQUIREMENTS_NAME = "quarkusImageExtensionChecks";

    private final ToolingModelBuilderRegistry registry;

    @SuppressWarnings("CdiInjectionPointsInspection")
    @Inject
    public QuarkusPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(Project project) {
        GradleVersionSupport.requireMinimumGradleVersion();

        // Apply the `java` plugin
        project.getPluginManager().apply(JavaPlugin.class);

        registerModel();

        // register extension
        final QuarkusPluginExtension quarkusExt = project.getExtensions().create(EXTENSION_NAME, QuarkusPluginExtension.class,
                project);

        createSourceSets(project);
        createConfigurations(project);
        registerTasks(project, quarkusExt);
        registerLegacyTaskUsageDiagnostics(project, quarkusExt);
    }

    private void registerTasks(Project project, QuarkusPluginExtension quarkusExt) {
        TaskContainer tasks = project.getTasks();
        String forcedPropertiesService = String.format("forcedPropertiesService-%s", project.getName());
        Provider<ForcedPropertieBuildService> serviceProvider = project.getGradle().getSharedServices().registerIfAbsent(
                forcedPropertiesService, ForcedPropertieBuildService.class,
                spec -> {
                });

        // register custom file system operations build service
        Provider<CustomFileSystemOperations> customFs = project.getGradle().getSharedServices().registerIfAbsent(
                "customFileSystemOperations-%s".formatted(project.getPath().replace(":", "_")),
                CustomFileSystemOperations.class,
                spec -> {
                });

        final String devRuntimeConfigName = ApplicationDeploymentClasspathBuilder
                .getBaseRuntimeConfigName(LaunchMode.DEVELOPMENT);
        final Configuration devRuntimeDependencies = project.getConfigurations().maybeCreate(devRuntimeConfigName);

        tasks.register(LIST_EXTENSIONS_TASK_NAME, QuarkusListExtensions.class);
        tasks.register(LIST_CATEGORIES_TASK_NAME, QuarkusListCategories.class);
        tasks.register(LIST_PLATFORMS_TASK_NAME, QuarkusListPlatforms.class);
        tasks.register(ADD_EXTENSION_TASK_NAME, QuarkusAddExtension.class);
        tasks.register(REMOVE_EXTENSION_TASK_NAME, QuarkusRemoveExtension.class);

        ApplicationDeploymentClasspathBuilder normalClasspath = new ApplicationDeploymentClasspathBuilder(project,
                LaunchMode.NORMAL);
        ApplicationDeploymentClasspathBuilder testClasspath = new ApplicationDeploymentClasspathBuilder(project,
                LaunchMode.TEST);
        ApplicationDeploymentClasspathBuilder devClasspath = new ApplicationDeploymentClasspathBuilder(project,
                LaunchMode.DEVELOPMENT);
        registerBootstrapResolverConfiguration(project, devClasspath);
        realizeConfigurationProviders(project);

        Provider<DefaultProjectDescriptor> projectDescriptor = ProjectDescriptorBuilder.buildForApp(project);
        DependencyDataCollector depDataCollector = new DependencyDataCollector(
                project.getDependencies(), project.getProviders());

        var quarkusGenerateTestAppModelTask = ApplicationModelTaskConfigurator.registerLegacyGenerateApplicationModelTask(
                project,
                projectDescriptor, testClasspath, depDataCollector, LaunchMode.TEST);
        var quarkusGenerateDevAppModelTask = ApplicationModelTaskConfigurator.registerLegacyGenerateApplicationModelTask(
                project,
                projectDescriptor, devClasspath, depDataCollector, LaunchMode.DEVELOPMENT);
        var quarkusGenerateAppModelTask = ApplicationModelTaskConfigurator.registerLegacyGenerateApplicationModelTask(project,
                projectDescriptor, normalClasspath, depDataCollector, LaunchMode.NORMAL);
        tasks.register(QUARKUS_GO_OFFLINE_TASK_NAME, QuarkusGoOffline.class, task -> {
            task.getApplicationModel()
                    .set(quarkusGenerateAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel));
            task.getDevApplicationModel()
                    .set(quarkusGenerateDevAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel));
            task.getTestApplicationModel()
                    .set(quarkusGenerateTestAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel));
        });
        tasks.register(QUARKUS_INFO_TASK_NAME, QuarkusInfo.class, task -> task.getApplicationModel()
                .set(quarkusGenerateAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel)));
        tasks.register(QUARKUS_UPDATE_TASK_NAME, QuarkusUpdate.class, task -> task.getApplicationModel()
                .set(quarkusGenerateAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel)));

        // quarkusGenerateCode
        TaskProvider<QuarkusGenerateCode> quarkusGenerateCode = tasks.register(QUARKUS_GENERATE_CODE_TASK_NAME,
                QuarkusGenerateCode.class, LaunchMode.NORMAL, SourceSet.MAIN_SOURCE_SET_NAME,
                quarkusExt.getCodeGenerationInputs().get());
        quarkusGenerateCode.configure(task -> configureGenerateCodeTask(task, quarkusGenerateAppModelTask,
                QuarkusGenerateCode.QUARKUS_GENERATED_SOURCES, quarkusExt));
        // quarkusGenerateCodeDev
        TaskProvider<QuarkusGenerateCode> quarkusGenerateCodeDev = tasks.register(QUARKUS_GENERATE_CODE_DEV_TASK_NAME,
                QuarkusGenerateCode.class, LaunchMode.DEVELOPMENT, SourceSet.MAIN_SOURCE_SET_NAME,
                quarkusExt.getCodeGenerationInputs().get());
        quarkusGenerateCodeDev.configure(task -> {
            task.dependsOn(quarkusGenerateCode);
            configureGenerateCodeTask(task, quarkusGenerateDevAppModelTask, QuarkusGenerateCode.QUARKUS_GENERATED_SOURCES,
                    quarkusExt);
        });
        // quarkusGenerateCodeTests
        TaskProvider<QuarkusGenerateCode> quarkusGenerateCodeTests = tasks.register(QUARKUS_GENERATE_CODE_TESTS_TASK_NAME,
                QuarkusGenerateCode.class, LaunchMode.TEST, SourceSet.TEST_SOURCE_SET_NAME,
                quarkusExt.getCodeGenerationInputs().get());
        quarkusGenerateCodeTests.configure(task -> {
            task.dependsOn("compileQuarkusTestGeneratedSourcesJava");
            configureGenerateCodeTask(task, quarkusGenerateTestAppModelTask,
                    QuarkusGenerateCode.QUARKUS_TEST_GENERATED_SOURCES, quarkusExt);
        });

        TaskProvider<QuarkusApplicationModelTask> quarkusBuildAppModelTask = tasks.register("quarkusBuildAppModel",
                QuarkusApplicationModelTask.class, task -> {
                    task.dependsOn(tasks.named(JavaPlugin.CLASSES_TASK_NAME));
                    ApplicationModelTaskConfigurator.configure(project, task, projectDescriptor,
                            normalClasspath, depDataCollector, LaunchMode.NORMAL, true);
                });
        TaskProvider<QuarkusShowEffectiveConfig> quarkusShowEffectiveConfig = tasks.register(
                QUARKUS_SHOW_EFFECTIVE_CONFIG_TASK_NAME,
                QuarkusShowEffectiveConfig.class, task -> {
                    configureQuarkusBuildTask(project, task, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
                    task.setDescription("Show effective Quarkus build configuration.");
                });

        TaskProvider<QuarkusBuildDependencies> quarkusBuildDependencies = tasks.register(QUARKUS_BUILD_DEP_TASK_NAME,
                QuarkusBuildDependencies.class,
                task -> {
                    configureQuarkusBuildTask(project, task, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
                    task.getOutputs().doNotCacheIf("Dependencies are never cached", t -> true);
                });

        Property<Boolean> cacheLargeArtifacts = quarkusExt.getCacheLargeArtifacts();

        TaskProvider<QuarkusBuildCacheableAppParts> quarkusBuildCacheableAppParts = tasks.register(
                QUARKUS_BUILD_APP_PARTS_TASK_NAME,
                QuarkusBuildCacheableAppParts.class, task -> {
                    configureQuarkusBuildTask(project, task, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
                    task.dependsOn(quarkusGenerateCode);
                    task.getOutputs().doNotCacheIf(
                            "Not adding uber-jars, native binaries and mutable-jar package type to Gradle " +
                                    "build cache by default. To allow caching of uber-jars, native binaries and mutable-jar " +
                                    "package type, set 'cacheUberAndNativeRunners' in the 'quarkus' Gradle extension to 'true'.",
                            new Spec<Task>() {
                                @Override
                                public boolean isSatisfiedBy(Task t) {
                                    QuarkusBuildCacheableAppParts q = (QuarkusBuildCacheableAppParts) t;
                                    return !q.isCachedByDefault() && !cacheLargeArtifacts.get();
                                }
                            });
                });

        TaskProvider<QuarkusBuild> quarkusBuild = tasks.register(QUARKUS_BUILD_TASK_NAME, QuarkusBuild.class, build -> {
            configureQuarkusBuildTask(project, build, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
            build.dependsOn(quarkusBuildDependencies, quarkusBuildCacheableAppParts);
            build.getOutputs().doNotCacheIf(
                    "Only collects and combines the outputs of " + QUARKUS_BUILD_APP_PARTS_TASK_NAME + " and "
                            + QUARKUS_BUILD_DEP_TASK_NAME + ", see 'cacheLargeArtifacts' in the 'quarkus' Gradle extension " +
                            "for details.",
                    new Spec<Task>() {
                        @Override
                        public boolean isSatisfiedBy(Task t) {
                            return !cacheLargeArtifacts.get();
                        }
                    });
        });

        TaskProvider<ImageCheckRequirementsTask> quarkusRequiredExtension = tasks.register(IMAGE_CHECK_REQUIREMENTS_NAME,
                ImageCheckRequirementsTask.class, task -> {
                    task.getOutputFile().set(project.getLayout().getBuildDirectory().file("quarkus/image-name"));
                    task.getApplicationModel()
                            .set(quarkusGenerateAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel));
                    task.getContainerImageBuilder().set(project.getProviders()
                            .systemProperty(ImageCheckRequirementsTask.QUARKUS_CONTAINER_IMAGE_BUILDER));

                });

        TaskProvider<ImageBuild> imageBuildTask = tasks.register(IMAGE_BUILD_TASK_NAME, ImageBuild.class, task -> {
            task.dependsOn(quarkusRequiredExtension);
            configureQuarkusBuildTask(project, task, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
            task.getBuilderName().set(quarkusRequiredExtension.flatMap(ImageCheckRequirementsTask::getOutputFile));
            task.finalizedBy(quarkusBuild);
        });

        TaskProvider<ImagePush> imagePushTask = tasks.register(IMAGE_PUSH_TASK_NAME, ImagePush.class, task -> {
            task.dependsOn(quarkusRequiredExtension);
            configureQuarkusBuildTask(project, task, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
            task.getBuilderName().set(quarkusRequiredExtension.flatMap(ImageCheckRequirementsTask::getOutputFile));
            task.finalizedBy(quarkusBuild);
        });

        TaskProvider<Deploy> deployTask = tasks.register(DEPLOY_TASK_NAME, Deploy.class, task -> {
            configureQuarkusBuildTask(project, task, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
            task.finalizedBy(quarkusBuild);
        });

        TaskProvider<BuildAotEnhancedImage> buildAotEnhancedImageTask = tasks.register(BUILD_AOT_ENHANCED_IMAGE_TASK_NAME,
                BuildAotEnhancedImage.class, task -> {
                    configureQuarkusBuildTask(project, task, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
                });

        TaskProvider<QuarkusDev> quarkusDev = tasks.register(QUARKUS_DEV_TASK_NAME, QuarkusDev.class, devRuntimeDependencies,
                quarkusExt);
        quarkusDev.configure(task -> configureDevModeTask(task, quarkusGenerateDevAppModelTask,
                quarkusGenerateTestAppModelTask));

        project.afterEvaluate(evaluated -> addDependencyOnJandexIfConfigured(evaluated,
                quarkusShowEffectiveConfig,
                quarkusBuildDependencies,
                quarkusBuildCacheableAppParts,
                imageBuildTask,
                imagePushTask,
                deployTask,
                buildAotEnhancedImageTask,
                quarkusDev));
        TaskProvider<QuarkusRun> quarkusRun = tasks.register(QUARKUS_RUN_TASK_NAME, QuarkusRun.class,
                build -> {
                    configureQuarkusBuildTask(project, build, quarkusBuildAppModelTask, serviceProvider, customFs, quarkusExt);
                    build.dependsOn(quarkusBuild);

                });
        TaskProvider<QuarkusRemoteDev> quarkusRemoteDev = tasks.register(QUARKUS_REMOTE_DEV_TASK_NAME, QuarkusRemoteDev.class,
                devRuntimeDependencies, quarkusExt);
        quarkusRemoteDev.configure(task -> configureDevModeTask(task, quarkusGenerateDevAppModelTask,
                quarkusGenerateTestAppModelTask));
        TaskProvider<QuarkusTest> quarkusTest = tasks.register(QUARKUS_TEST_TASK_NAME, QuarkusTest.class,
                devRuntimeDependencies, quarkusExt);
        quarkusTest.configure(task -> configureDevModeTask(task, quarkusGenerateTestAppModelTask,
                quarkusGenerateTestAppModelTask));

        tasks.register(BUILD_NATIVE_TASK_NAME, DefaultTask.class, task -> {
            task.finalizedBy(quarkusBuild);
            task.doFirst(t -> t.getLogger()
                    .warn("The 'buildNative' task has been deprecated in favor of 'build -Dquarkus.native.enabled=true'"));
        });

        configureBuildNativeTask(project, quarkusExt);
        project.getPlugins().withType(
                BasePlugin.class,
                basePlugin -> tasks.named(BasePlugin.ASSEMBLE_TASK_NAME, task -> task.dependsOn(quarkusBuild)));
        project.getPlugins().withType(
                JavaPlugin.class,
                javaPlugin -> {

                    project.afterEvaluate(this::afterEvaluate);

                    TaskProvider<Task> classesTask = tasks.named(JavaPlugin.CLASSES_TASK_NAME);
                    TaskProvider<Task> resourcesTask = tasks.named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
                    TaskProvider<Task> testClassesTask = tasks.named(JavaPlugin.TEST_CLASSES_TASK_NAME);
                    TaskProvider<Task> testResourcesTask = tasks.named(JavaPlugin.PROCESS_TEST_RESOURCES_TASK_NAME);

                    SourceSetContainer sourceSets = QuarkusGradleUtils.getSourceSets(project);
                    SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                    SourceSet testSourceSet = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);

                    quarkusGenerateCode.configure(task -> {
                        Configuration config = project.getConfigurations().getByName(
                                ApplicationDeploymentClasspathBuilder.getBaseRuntimeConfigName(LaunchMode.NORMAL));
                        task.dependsOn(resourcesTask, config);
                        task.setCompileClasspath(config);
                        task.getSourceDirectories().from(getSourcesParents(mainSourceSet));
                    });
                    quarkusGenerateCodeDev.configure(task -> {
                        Configuration config = project.getConfigurations().getByName(
                                ApplicationDeploymentClasspathBuilder
                                        .getBaseRuntimeConfigName(LaunchMode.DEVELOPMENT));
                        task.dependsOn(resourcesTask, config);
                        task.setCompileClasspath(config);
                        task.getSourceDirectories().from(getSourcesParents(mainSourceSet));
                    });
                    quarkusGenerateCodeTests.configure(task -> {
                        Configuration config = project.getConfigurations().getByName(
                                ApplicationDeploymentClasspathBuilder.getBaseRuntimeConfigName(LaunchMode.TEST));
                        task.dependsOn(resourcesTask, config);
                        task.setCompileClasspath(config);
                        task.getSourceDirectories().from(getSourcesParents(testSourceSet));
                    });

                    quarkusDev.configure(task -> {
                        task.dependsOn(classesTask, resourcesTask, testClassesTask, testResourcesTask,
                                quarkusGenerateCodeDev,
                                quarkusGenerateCodeTests);
                    });
                    quarkusRemoteDev.configure(task -> {
                        task.dependsOn(classesTask, resourcesTask);
                    });
                    quarkusTest.configure(task -> {
                        task.dependsOn(classesTask, resourcesTask, testClassesTask, testResourcesTask,
                                quarkusGenerateCode,
                                quarkusGenerateCodeTests);
                    });
                    quarkusBuildCacheableAppParts.configure(
                            task -> task.dependsOn(classesTask, resourcesTask, tasks.named(JavaPlugin.JAR_TASK_NAME)));

                    SourceSet intTestSourceSet = sourceSets.getByName(INTEGRATION_TEST_SOURCE_SET_NAME);
                    intTestSourceSet.setCompileClasspath(
                            intTestSourceSet.getCompileClasspath()
                                    .plus(mainSourceSet.getOutput())
                                    .plus(testSourceSet.getOutput()));
                    intTestSourceSet.setRuntimeClasspath(
                            intTestSourceSet.getRuntimeClasspath()
                                    .plus(mainSourceSet.getOutput())
                                    .plus(testSourceSet.getOutput()));

                    TaskProvider<Test> testTask = tasks.named(JavaPlugin.TEST_TASK_NAME, Test.class);
                    FileCollection intTestSourceOutputClasses = intTestSourceSet.getOutput().getClassesDirs();
                    FileCollection intTestClasspath = intTestSourceSet.getRuntimeClasspath();

                    tasks.register(INTEGRATION_TEST_TASK_NAME, Test.class, intTestTask -> {
                        intTestTask.setGroup("verification");
                        intTestTask.setDescription("Runs Quarkus integration tests");
                        intTestTask.dependsOn(quarkusBuild);
                        intTestTask.shouldRunAfter(testTask);
                        intTestTask.setClasspath(intTestClasspath);
                        intTestTask.setTestClassesDirs(intTestSourceOutputClasses);
                    });

                    SourceSet nativeTestSourceSet = sourceSets.getByName(NATIVE_TEST_SOURCE_SET_NAME);
                    nativeTestSourceSet.setCompileClasspath(
                            nativeTestSourceSet.getCompileClasspath()
                                    .plus(mainSourceSet.getOutput())
                                    .plus(intTestSourceSet.getOutput())
                                    .plus(testSourceSet.getOutput()));
                    nativeTestSourceSet.setRuntimeClasspath(
                            nativeTestSourceSet.getRuntimeClasspath()
                                    .plus(mainSourceSet.getOutput())
                                    .plus(intTestSourceSet.getOutput())
                                    .plus(testSourceSet.getOutput()));

                    FileCollection nativeTestClassesDirs = project.files(
                            nativeTestSourceSet.getOutput().getClassesDirs(),
                            intTestSourceOutputClasses);
                    FileCollection nativeTestClasspath = nativeTestSourceSet.getRuntimeClasspath();

                    tasks.register(TEST_NATIVE_TASK_NAME, Test.class, testNative -> {
                        testNative.setDescription("Runs native image tests");
                        testNative.setGroup("verification");
                        testNative.dependsOn(quarkusBuild);
                        testNative.shouldRunAfter(testTask);
                        testNative.setClasspath(nativeTestClasspath);
                        testNative.setTestClassesDirs(nativeTestClassesDirs);
                    });

                    tasks.withType(Test.class).configureEach(t -> {
                        t.setSystemProperties(extractQuarkusTestSystemProperties(project));
                        t.systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
                        t.jvmArgs(
                                "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                                "--add-exports=java.base/jdk.internal.module=ALL-UNNAMED");

                        t.getInputs().files(quarkusGenerateTestAppModelTask);
                        // Register matching compose files from project root as inputs
                        t.getInputs().files(composeDevFiles(project));
                        // Quarkus test configuration action which should be executed before any Quarkus test
                        var objects = project.getObjects();
                        var classesDirs = mainSourceSet.getOutput().getClassesDirs();
                        var projectDir = project.getLayout().getProjectDirectory().getAsFile();
                        var combinedOutputSourceDirs = combinedOutputSourceDirs(project);
                        var mainSourceDirectories = mainSourceSet.getResources().getSourceDirectories();
                        t.doFirst(new BeforeTestAction(
                                projectDir,
                                combinedOutputSourceDirs,
                                quarkusGenerateTestAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel),
                                quarkusBuild.map(QuarkusBuild::getNativeRunner),
                                classesDirs,
                                objects.newInstance(QuarkusPluginExtensionView.class, quarkusExt, mainSourceDirectories),
                                objects.mapProperty(String.class, Object.class)
                                        .convention(quarkusExt.manifest().getAttributes()),
                                objects.mapProperty(String.class, Attributes.class).convention(quarkusExt.getAttributes())));

                        // also make each task use the JUnit platform since it's the only supported test environment
                        t.useJUnitPlatform();
                    });
                    // quarkusBuild is expected to run after the project has passed the tests
                    quarkusBuildCacheableAppParts.configure(task -> task.shouldRunAfter(tasks.withType(Test.class)));

                    tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class,
                            compileJava -> {
                                // add the code gen sources
                                final SourceSet generatedSourceSet = sourceSets
                                        .getByName(QuarkusGenerateCode.QUARKUS_GENERATED_SOURCES);
                                addCodeGenSourceDirs(compileJava, generatedSourceSet);
                                // quarkusGenerateCode is a dependency
                                compileJava.dependsOn(quarkusGenerateCode);
                                // quarkusGenerateCodeDev must run before compileJava in case quarkusDev is the target
                                compileJava.mustRunAfter(quarkusGenerateCodeDev);

                                // The code below needs to be reviewed. There is no test coverage for it.
                                // We need to properly configure/use these tasks. For now they aren't actually used
                                // but w/o the code below, in some cases, Gradle may fail on determining task ordering.
                                // https://github.com/quarkusio/quarkus/issues/45057 states that this issue happens with
                                // org.gradle.parallel=true when building from IntelliJ
                                // Similar code is added for compileKotlin (although for Kotlin these task don't seem to exist).
                                if (tasks.contains(new NamedImpl(generatedSourceSet.getCompileJavaTaskName()))) {
                                    compileJava.mustRunAfter(tasks.named(generatedSourceSet.getCompileJavaTaskName()));
                                }
                            });
                    tasks.named(JavaPlugin.COMPILE_TEST_JAVA_TASK_NAME, JavaCompile.class,
                            compileTestJava -> {
                                // add the code gen test sources
                                final SourceSet generatedSourceSet = sourceSets
                                        .getByName(QuarkusGenerateCode.QUARKUS_TEST_GENERATED_SOURCES);
                                addCodeGenSourceDirs(compileTestJava, generatedSourceSet);
                                compileTestJava.dependsOn(quarkusGenerateCode, quarkusGenerateCodeTests);
                                if (tasks.contains(new NamedImpl(generatedSourceSet.getCompileJavaTaskName()))) {
                                    compileTestJava.mustRunAfter(tasks.named(generatedSourceSet.getCompileJavaTaskName()));
                                }
                                if (project.getGradle().getStartParameter().getTaskNames().contains(QUARKUS_DEV_TASK_NAME)) {
                                    compileTestJava.getOptions().setFailOnError(false);
                                }
                            });
                });

        project.getPlugins().withId("org.jetbrains.kotlin.jvm", plugin -> {
            quarkusDev.configure(task -> task.shouldPropagateJavaCompilerArgs(false));
            SourceSetContainer ssc = QuarkusGradleUtils.getSourceSets(project);
            final SourceSet generatedSourceSet = ssc.getByName(QuarkusGenerateCode.QUARKUS_GENERATED_SOURCES);
            final SourceSet generatedTestSourceSet = ssc.getByName(QuarkusGenerateCode.QUARKUS_TEST_GENERATED_SOURCES);
            tasks.named("compileKotlin", task -> {
                addCodeGenSourceDirs(task, generatedSourceSet);
                task.dependsOn(quarkusGenerateCode);
                task.mustRunAfter(quarkusGenerateCodeDev);
                if (tasks.contains(new NamedImpl(generatedSourceSet.getCompileJavaTaskName()))) {
                    task.mustRunAfter(tasks.named(generatedSourceSet.getCompileJavaTaskName()));
                }
                String generatedSourcesCompileTaskName = generatedSourceSet.getCompileTaskName("kotlin");
                if (tasks.contains(new NamedImpl(generatedSourcesCompileTaskName))) {
                    task.mustRunAfter(tasks.named(generatedSourcesCompileTaskName));
                }
            });
            tasks.named("compileTestKotlin", task -> {
                addCodeGenSourceDirs(task, generatedTestSourceSet);
                task.dependsOn(quarkusGenerateCodeTests);
                if (tasks.contains(new NamedImpl(generatedTestSourceSet.getCompileJavaTaskName()))) {
                    task.mustRunAfter(tasks.named(generatedTestSourceSet.getCompileJavaTaskName()));
                }
                final String generatedSourcesCompileTaskName = generatedTestSourceSet.getCompileTaskName("kotlin");
                if (tasks.contains(new NamedImpl(generatedSourcesCompileTaskName))) {
                    task.mustRunAfter(tasks.named(generatedSourcesCompileTaskName));
                }
            });
            // kapt stub tasks don't inherit the source dirs injected into compileKotlin, so wire them explicitly.
            // TODO: perhaps we should prioritize IDE devX and proper wiring into the main SS, and invert
            //  the handling here: kapt should be the base case, and KSP should be the edge-case.
            //  See issues [1] and [2] for full context.
            //  * [1] https://github.com/quarkusio/quarkus/issues/29698
            //  * [2] https://github.com/quarkusio/quarkus/issues/50486
            project.getPlugins().withId("org.jetbrains.kotlin.kapt", kaptPlugin -> {
                tasks.matching(t -> t.getName().equals("kaptGenerateStubsKotlin")).configureEach(task -> {
                    addCodeGenSourceDirs(task, generatedSourceSet);
                    task.dependsOn(quarkusGenerateCode);
                });
                tasks.matching(t -> t.getName().equals("kaptGenerateStubsTestKotlin")).configureEach(task -> {
                    addCodeGenSourceDirs(task, generatedTestSourceSet);
                    task.dependsOn(quarkusGenerateCodeTests);
                });
            });
        });
    }

    private static void addCodeGenSourceDirs(JavaCompile compileJava, SourceSet generatedSourceSet) {
        final File baseDir = generatedSourceSet.getJava().getClassesDirectory().get().getAsFile();
        compileJava.source(baseDir);
    }

    private static void addCodeGenSourceDirs(Task compileKotlin, SourceSet generatedSourceSet) {
        final File baseDir = generatedSourceSet.getJava().getClassesDirectory().get().getAsFile();
        final Object[] codeGenDirs = new Object[] { baseDir };
        try {
            var sourcesMethod = compileKotlin.getClass().getMethod("source", Object[].class);
            sourcesMethod.invoke(compileKotlin, new Object[] { codeGenDirs });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void configureQuarkusBuildTask(Project project, QuarkusBuildTask task,
            TaskProvider<? extends QuarkusApplicationModelTask> quarkusGenerateAppModelTask,
            Provider<ForcedPropertieBuildService> serviceProvider,
            Provider<CustomFileSystemOperations> customFs,
            QuarkusPluginExtension quarkusExt) {
        task.getApplicationModel().set(quarkusGenerateAppModelTask.flatMap(QuarkusApplicationModelTask::getApplicationModel));
        SourceSet mainSourceSet = getSourceSet(project, SourceSet.MAIN_SOURCE_SET_NAME);
        task.getAdditionalForcedProperties().set(serviceProvider);
        task.usesService(serviceProvider);
        task.getFileSystemOperationsProvider().set(customFs);
        task.usesService(customFs);
        task.setCompileClasspath(mainSourceSet.getCompileClasspath().plus(mainSourceSet.getRuntimeClasspath())
                .plus(mainSourceSet.getAnnotationProcessorPath())
                .plus(mainSourceSet.getResources()));
        task.getCachingRelevantInput().set(quarkusExt
                .cachingRelevantProperties(quarkusExt.getCachingRelevantProperties().get()));
        task.getJarEnabled().set(project.provider(() -> quarkusExt.packageConfig().jar().enabled()
                && !nativeBuildEnabled(quarkusExt)));
        task.getNativeEnabled().set(project.provider(() -> nativeBuildEnabled(quarkusExt)));
        task.getNativeSourcesOnly().set(quarkusExt.nativeConfig().sourcesOnly());
        task.getRunnerSuffix().set(quarkusExt.packageConfig().computedRunnerSuffix());
        task.getRunnerName().set(
                quarkusExt.packageConfig().outputName().orElseGet(() -> quarkusExt.getFinalName().get()));
        task.getOutputDirectory()
                .set(Path.of(quarkusExt.packageConfig().outputDirectory().map(Path::toString)
                        .orElse(QuarkusPlugin.DEFAULT_OUTPUT_DIRECTORY)));
        task.getJarType().set(quarkusExt.packageConfig().jar().type());
        task.getManifestAttributes().set(quarkusExt.manifest().getAttributes());
        task.getManifestSections().set(quarkusExt.manifest().getSections());
    }

    private static boolean nativeBuildEnabled(QuarkusPluginExtension quarkusExt) {
        return quarkusExt.nativeConfig().enabled() || quarkusExt.getNativeBuild().getOrElse(false);
    }

    private static void configureGenerateCodeTask(QuarkusGenerateCode task,
            TaskProvider<? extends QuarkusApplicationModelTask> applicationModelTaskTaskProvider, String generateSourcesDir,
            QuarkusPluginExtension quarkusExt) {
        SourceSet generatedSources = getSourceSet(task.getProject(), generateSourcesDir);
        Set<File> sourceSetOutput = generatedSources.getOutput().filter(f -> f.getName().equals(generateSourcesDir)).getFiles();
        if (sourceSetOutput.isEmpty()) {
            throw new GradleException("Failed to configure " + task.getPath() + ": sourceSet " + generateSourcesDir
                    + " has no output");
        }
        task.getApplicationModel()
                .set(applicationModelTaskTaskProvider.flatMap(QuarkusApplicationModelTask::getApplicationModel));
        task.getGeneratedOutputDirectory().set(generatedSources.getJava().getClassesDirectory());
        task.getCachingRelevantInput()
                .set(quarkusExt.cachingRelevantProperties(quarkusExt.getCachingRelevantProperties().get()));
        task.getManifestAttributes().set(quarkusExt.manifest().getAttributes());
        task.getManifestSections().set(quarkusExt.manifest().getSections());
    }

    private static void configureDevModeTask(QuarkusDev task,
            TaskProvider<? extends QuarkusApplicationModelTask> applicationModelTaskProvider,
            TaskProvider<? extends QuarkusApplicationModelTask> testApplicationModelTaskProvider) {
        task.getApplicationModel()
                .set(applicationModelTaskProvider.flatMap(QuarkusApplicationModelTask::getApplicationModel));
        task.getTestApplicationModel()
                .set(testApplicationModelTaskProvider.flatMap(QuarkusApplicationModelTask::getApplicationModel));
    }

    private static void registerBootstrapResolverConfiguration(Project project,
            ApplicationDeploymentClasspathBuilder devClasspath) {
        if (project.getConfigurations()
                .findByName(ApplicationDeploymentClasspathBuilder.QUARKUS_BOOTSTRAP_RESOLVER_CONFIGURATION) != null) {
            return;
        }
        project.getConfigurations().register(
                ApplicationDeploymentClasspathBuilder.QUARKUS_BOOTSTRAP_RESOLVER_CONFIGURATION,
                configuration -> QuarkusDev.configureBootstrapResolverConfiguration(project, configuration,
                        devClasspath.getPlatformConfiguration()));
    }

    private static void realizeConfigurationProviders(Project project) {
        // Gradle may enumerate consumable variants while calculating the configuration-cache task graph.
        // Keeping pending configuration providers in the container can make that enumeration realize configurations
        // while it is iterating the same container, which fails with ConcurrentModificationException.
        // Realizing this project's configuration providers here does not resolve any dependency graph.
        project.getConfigurations().all(configuration -> {
        });
    }

    private void createSourceSets(Project project) {
        SourceSetContainer sourceSets = QuarkusGradleUtils.getSourceSets(project);
        sourceSets.create(INTEGRATION_TEST_SOURCE_SET_NAME);
        sourceSets.create(NATIVE_TEST_SOURCE_SET_NAME);
        sourceSets.create(QuarkusGenerateCode.QUARKUS_GENERATED_SOURCES);
        sourceSets.create(QuarkusGenerateCode.QUARKUS_TEST_GENERATED_SOURCES);
    }

    private void createConfigurations(Project project) {

        final ConfigurationContainer configContainer = project.getConfigurations();

        // Custom configuration to be used for the dependencies of the testNative task
        configContainer.getByName(NATIVE_TEST_IMPLEMENTATION_CONFIGURATION_NAME)
                .extendsFrom(configContainer.findByName(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME));
        configContainer.getByName(NATIVE_TEST_RUNTIME_ONLY_CONFIGURATION_NAME)
                .extendsFrom(configContainer.findByName(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME));

        // create a custom configuration to be used for the dependencies of the quarkusIntTest task
        configContainer
                .maybeCreate(INTEGRATION_TEST_IMPLEMENTATION_CONFIGURATION_NAME)
                .extendsFrom(configContainer.findByName(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME));
        configContainer.maybeCreate(INTEGRATION_TEST_RUNTIME_ONLY_CONFIGURATION_NAME)
                .extendsFrom(configContainer.findByName(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME));

        ApplicationDeploymentClasspathBuilder.initConfigurations(project);
    }

    private Set<File> getSourcesParents(SourceSet mainSourceSet) {
        Set<File> srcDirs = mainSourceSet.getJava().getSrcDirs();
        return srcDirs.stream()
                .map(File::getParentFile)
                .collect(Collectors.toSet());
    }

    private void registerModel() {
        registry.register(new GradleApplicationModelBuilder());
    }

    private void registerLegacyTaskUsageDiagnostics(Project project, QuarkusPluginExtension quarkusExt) {
        project.getGradle().getTaskGraph().whenReady(taskGraph -> {
            var level = quarkusExt.getDiagnostics().getLegacyTaskUsage().get();
            if (level == QuarkusLegacyTaskUsageLevel.OFF) {
                return;
            }

            Map<String, String> replacements = new HashMap<>(LEGACY_APPLICATION_TASK_REPLACEMENTS);
            replacements.putAll(DEPRECATED_NATIVE_TASK_REPLACEMENTS);

            var usages = taskGraph.getAllTasks().stream()
                    .filter(task -> task.getProject().equals(project))
                    .map(Task::getName)
                    .filter(replacements::containsKey)
                    .distinct()
                    .map(taskName -> new LegacyTaskUsage(taskName, replacements.get(taskName)))
                    .toList();
            LegacyTaskUsageDiagnostics.report(level, usages, project.getLogger(),
                    project.getLayout().getBuildDirectory().file(LegacyTaskUsageDiagnostics.REPORT_PATH).get().getAsFile());
        });
    }

    private void configureBuildNativeTask(Project project, QuarkusPluginExtension quarkusExt) {
        project.getGradle().getTaskGraph().whenReady(taskGraph -> {
            if (taskGraph.hasTask(project.getPath() + BUILD_NATIVE_TASK_NAME)
                    || taskGraph.hasTask(project.getPath() + TEST_NATIVE_TASK_NAME)) {
                quarkusExt.getNativeBuild().set(true);
            }
        });
    }

    private void afterEvaluate(Project project) {

        visitProjectDependencies(project, new HashSet<>());

        ConfigurationContainer configurations = project.getConfigurations();

        SourceSetExtension sourceSetExtension = project.getExtensions().getByType(QuarkusPluginExtension.class)
                .sourceSetExtension();

        if (sourceSetExtension.extraNativeTest() != null) {
            SourceSetContainer sourceSets = QuarkusGradleUtils.getSourceSets(project);
            SourceSet nativeTestSourceSets = sourceSets.getByName(NATIVE_TEST_SOURCE_SET_NAME);
            nativeTestSourceSets.setCompileClasspath(
                    nativeTestSourceSets.getCompileClasspath()
                            .plus(sourceSets.getByName(INTEGRATION_TEST_SOURCE_SET_NAME).getOutput())
                            .plus(sourceSetExtension.extraNativeTest().getOutput()));
            nativeTestSourceSets.setRuntimeClasspath(
                    nativeTestSourceSets.getRuntimeClasspath()
                            .plus(sourceSets.getByName(INTEGRATION_TEST_SOURCE_SET_NAME).getOutput())
                            .plus(sourceSetExtension.extraNativeTest().getOutput()));

            configurations.getByName(NATIVE_TEST_IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(
                    configurations.findByName(sourceSetExtension.extraNativeTest().getImplementationConfigurationName()));
            configurations.getByName(NATIVE_TEST_RUNTIME_ONLY_CONFIGURATION_NAME).extendsFrom(
                    configurations.findByName(sourceSetExtension.extraNativeTest().getRuntimeOnlyConfigurationName()));
        }
    }

    private void visitProjectDep(Project project, Project dep, Set<String> visited) {
        if (dep.getState().getExecuted()) {
            setupQuarkusBuildTaskDeps(project, dep, visited);
        } else {
            dep.afterEvaluate(p -> setupQuarkusBuildTaskDeps(project, p, visited));
        }
    }

    private void setupQuarkusBuildTaskDeps(Project project, Project dep, Set<String> visited) {
        if (!visited.add(dep.getPath())) {
            return;
        }

        project.getLogger().debug("Configuring {} task dependencies on {} tasks", project, dep);

        getLazyTask(project, QUARKUS_BUILD_TASK_NAME)
                .flatMap(quarkusBuild -> getLazyTask(dep, JavaPlugin.JAR_TASK_NAME))
                .ifPresent(jarTask -> {
                    for (String taskName : new String[] { QUARKUS_GENERATE_CODE_TASK_NAME, QUARKUS_GENERATE_CODE_DEV_TASK_NAME,
                            QUARKUS_GENERATE_CODE_TESTS_TASK_NAME }) {
                        getLazyTask(project, taskName)
                                .ifPresent(quarkusTask -> quarkusTask.configure(t -> t.dependsOn(jarTask)));
                    }
                });
        getLazyTask(project, QUARKUS_DEV_TASK_NAME).ifPresent(quarkusDev -> {
            getLazyTask(project, JavaPlugin.PROCESS_RESOURCES_TASK_NAME)
                    .ifPresent(t -> quarkusDev.configure(qd -> qd.dependsOn(t)));
            if (project.getRootProject().equals(dep.getRootProject())) {
                addDependencyOnJandexIfConfigured(dep, quarkusDev);
            }
        });

        visitProjectDependencies(dep, visited);
    }

    private void addDependencyOnJandexIfConfigured(Project project, TaskProvider<?>... quarkusTasks) {
        for (String taskName : new String[] {
                // This is the task of the 'org.kordamp.gradle.jandex' Gradle plugin
                "jandex",
                // This is the task of the 'com.github.vlsi.jandex' Gradle plugin
                "processJandexIndex" }) {
            getLazyTask(project, taskName).ifPresent(t -> {
                for (TaskProvider<?> tp : quarkusTasks) {
                    tp.configure(qd -> qd.mustRunAfter(t));
                }
            });
        }
    }

    protected void visitProjectDependencies(Project dep, Set<String> visited) {
        final Configuration compileConfig = dep.getConfigurations().findByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
        if (compileConfig != null) {
            processDependencyProjectClasspath(dep, compileConfig, visited);
        }
        final Configuration runtimeOnlyConfig = dep.getConfigurations().findByName(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME);
        if (runtimeOnlyConfig != null) {
            processDependencyProjectClasspath(dep, runtimeOnlyConfig, visited);
        }
    }

    private void processDependencyProjectClasspath(Project dependencyProject, Configuration config, Set<String> visited) {
        config.getIncoming().getDependencies()
                .forEach(d -> {
                    Project depProject = null;

                    if (d instanceof ProjectDependency projectDep) {
                        depProject = dependencyProject.project(projectDep.getPath());
                    } else if (d instanceof ExternalModuleDependency externalModuleDep) {
                        depProject = ToolingUtils.findIncludedProject(dependencyProject, externalModuleDep);
                    }

                    if (depProject == null) {
                        return;
                    }

                    if (depProject.getState().getExecuted()) {
                        visitLocalProject(dependencyProject, depProject, visited);
                    } else {
                        depProject.afterEvaluate(p -> visitLocalProject(dependencyProject, p, visited));
                    }
                });
    }

    private void visitLocalProject(Project project, Project localProject, Set<String> visited) {
        // local dependency, so we collect also its dependencies
        visitProjectDep(project, localProject, visited);
        ExtensionDependency<?> extensionDependency = DependencyUtils.getExtensionInfoOrNull(project, localProject);
        if (extensionDependency instanceof ProjectExtensionDependency projectExtDep) {
            visitProjectDep(project, projectExtDep.getDeploymentModule(), visited);
        }
    }

    private Optional<TaskProvider<Task>> getLazyTask(Project project, String name) {
        try {
            return Optional.of(project.getTasks().named(name));
        } catch (UnknownTaskException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> extractQuarkusTestSystemProperties(Project project) {
        return new HashMap<>(project.getProviders()
                .systemPropertiesPrefixedBy("quarkus.test.")
                .get());
    }
}
