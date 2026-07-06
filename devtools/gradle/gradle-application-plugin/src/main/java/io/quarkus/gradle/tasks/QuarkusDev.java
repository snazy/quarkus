package io.quarkus.gradle.tasks;

import static io.quarkus.analytics.dto.segment.ContextBuilder.CommonSystemProperties.GRADLE_VERSION;
import static io.quarkus.analytics.dto.segment.TrackEventType.DEV_MODE;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.options.Option;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.process.ExecOperations;
import org.gradle.util.GradleVersion;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.analytics.AnalyticsService;
import io.quarkus.analytics.config.FileLocationsImpl;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.ConfiguredClassLoading;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.devmode.DependenciesFilter;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.PathsCollection;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.deployment.dev.DevModeCommandLine;
import io.quarkus.deployment.dev.DevModeCommandLineBuilder;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.DevModeMain;
import io.quarkus.deployment.dev.ExtensionDevModeJvmOptionFilter;
import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.dependency.QuarkusComponentVariants;
import io.quarkus.gradle.dsl.CompilerOption;
import io.quarkus.gradle.dsl.CompilerOptions;
import io.quarkus.gradle.extension.QuarkusPluginExtension;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.LaunchMode;

@DisableCachingByDefault(because = "Not cacheable")
public abstract class QuarkusDev extends QuarkusTask {

    public static final String IO_QUARKUS_DEVMODE_ARGS = "io.quarkus.devmode-args";

    private final Configuration quarkusDevConfiguration;
    // Captured at configuration time so the task action does not call Task.getProject() at execution time, which is
    // deprecated and removed in Gradle 10. This task is not compatible with the configuration cache, so holding a
    // Project reference is acceptable (it is never serialized).
    private final transient Project project;

    private final CompilerOptions compilerOptions = new CompilerOptions();
    private final ExtensionDevModeJvmOptionFilter extensionJvmOptions = new ExtensionDevModeJvmOptionFilter();

    private final Property<Boolean> shouldPropagateJavaCompilerArgs;

    private final Set<File> filesIncludedInClasspath = new HashSet<>();

    @SuppressWarnings("unused")
    @Inject
    public QuarkusDev(Configuration quarkusDevConfiguration, QuarkusPluginExtension extension) {
        this("Development mode: enables hot deployment with background compilation", quarkusDevConfiguration, extension);
    }

    public QuarkusDev(
            String name,
            Configuration quarkusDevConfiguration,
            @SuppressWarnings("unused") QuarkusPluginExtension extension) {
        super(name);
        this.project = getProject();
        this.quarkusDevConfiguration = quarkusDevConfiguration;
        var mainSourceSet = QuarkusGradleUtils.getSourceSet(project, SourceSet.MAIN_SOURCE_SET_NAME);

        getSources().from(mainSourceSet.getAllJava().getSourceDirectories());
        getCompilationOutput().from(mainSourceSet.getOutput().getClassesDirs());
        getMainSourceSetAnnotationProcessorPath()
                .from(getProviderFactory().provider(mainSourceSet::getAnnotationProcessorPath));

        getWorkingDirectory().convention(getProjectDir().map(Directory::getAsFile));

        shouldPropagateJavaCompilerArgs = getObjects().property(Boolean.class);
        shouldPropagateJavaCompilerArgs.convention(true);

        getOpenJavaLang().convention(false);

        var javaCompileTask = project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class);

        dependsOn(javaCompileTask);

        getSourceEncoding().set(javaCompileTask.map(JavaCompile::getOptions).map(CompileOptions::getEncoding));
        getJavaCompileTaskArgs().set(javaCompileTask.map(JavaCompile::getOptions).map(CompileOptions::getCompilerArgs)
                .filter(args -> !args.isEmpty()));
    }

    @Input
    @Optional
    protected abstract Property<String> getSourceEncoding();

    @Input
    @Optional
    protected abstract ListProperty<String> getJavaCompileTaskArgs();

    @Classpath
    protected abstract ConfigurableFileCollection getMainSourceSetAnnotationProcessorPath();

    /**
     * The dependency Configuration associated with this task.
     *
     * @return quarkusDevConfiguration returns the configuration
     */
    @SuppressWarnings("unused")
    @Internal
    public Configuration getQuarkusDevConfiguration() {
        return this.quarkusDevConfiguration;
    }

    /**
     * The JVM sources (Java, Kotlin, ..) for the project
     *
     * @return the FileCollection of all java source files present in the source directories
     */
    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /**
     * The JVM classes directory (compilation output)
     *
     * @return the FileCollection of all java source files present in the source directories
     */
    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCompilationOutput();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getApplicationModel();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getTestApplicationModel();

    /**
     * The directory to be used as the working dir for the dev process.
     *
     * Defaults to the project directory.
     *
     * @return workingDirectory
     */
    @Input
    public abstract Property<File> getWorkingDirectory();

    @Input
    public abstract MapProperty<String, String> getEnvironmentVariables();

    @Input
    @Optional
    public abstract Property<Boolean> getForceC2();

    @Input
    public abstract ListProperty<String> getJvmArguments();

    @Option(description = "Set JVM arguments", option = "jvm-args")
    public void setJvmArgs(List<String> jvmArgs) {
        getJvmArguments().set(jvmArgs);
    }

    @Input
    public abstract ListProperty<String> getArguments();

    @Option(description = "Set application arguments", option = "quarkus-args")
    public void setArgs(List<String> args) {
        getArguments().set(args);
    }

    @Input
    @Option(description = "Modules to add to the application", option = "modules")
    public abstract ListProperty<String> getModules();

    @Input
    @Option(description = "Open Java Lang module", option = "open-lang-package")
    public abstract Property<Boolean> getOpenJavaLang();

    @Input
    @Option(description = "Additional parameters to pass to javac when recompiling changed source files", option = "compiler-args")
    public abstract ListProperty<String> getCompilerArguments();

    @SuppressWarnings("unused")
    @Internal
    public CompilerOptions getCompilerOptions() {
        return this.compilerOptions;
    }

    @SuppressWarnings("unused")
    public QuarkusDev compilerOptions(Action<CompilerOptions> action) {
        action.execute(compilerOptions);
        return this;
    }

    @SuppressWarnings("unused")
    @Internal
    public ExtensionDevModeJvmOptionFilter getExtensionJvmOptions() {
        return this.extensionJvmOptions;
    }

    @SuppressWarnings("unused")
    public QuarkusDev extensionJvmOptions(Action<ExtensionDevModeJvmOptionFilter> action) {
        action.execute(extensionJvmOptions);
        return this;
    }

    @Input
    @Option(description = "Sets test class or method name to be included (for continuous testing), '*' is supported.", option = "tests")
    public abstract ListProperty<String> getTests();

    @Inject
    public abstract ExecOperations getExecOperations();

    @TaskAction
    public void startDev() {
        if (!sourcesExist()) {
            throw new GradleException(
                    "At least one source directory (e.g. src/main/java, src/main/kotlin) should contain sources before starting Quarkus in dev mode when using Gradle. "
                            + "Please initialize your project a bit further before restarting Quarkus in dev mode.");
        }

        if (!classesExist()) {
            throw new GradleException("The project has no output yet, " +
                    "this should not happen as build should have been executed first. " +
                    "Does the project have any source files?");
        }
        AnalyticsService analyticsService = new AnalyticsService(FileLocationsImpl.INSTANCE,
                new GradleMessageWriter(getLogger()));
        analyticsService.buildAnalyticsUserInput((String prompt) -> {
            System.out.print(prompt);
            try (Scanner scanner = new Scanner(new FilterInputStream(System.in) {
                @Override
                public void close() throws IOException {
                    //don't close System.in!
                }
            })) {
                return scanner.nextLine();
            } catch (Exception e) {
                getLogger().debug("Failed to collect user input for analytics", e);
                return "";
            }
        });

        try {
            final DevModeCommandLine runner = newLauncher(analyticsService);
            String outputFile = getProviderFactory().systemProperty(IO_QUARKUS_DEVMODE_ARGS).getOrNull();
            if (outputFile == null) {
                getExecOperations().exec(action -> {
                    action.commandLine(runner.getArguments()).workingDir(getWorkingDirectory().get());
                    action.environment(getEnvironmentVariables().get());
                    action.setStandardInput(System.in)
                            .setErrorOutput(System.out)
                            .setStandardOutput(System.out);
                });
            } else {
                try (BufferedWriter is = Files.newBufferedWriter(Paths.get(outputFile))) {
                    for (String i : runner.getArguments()) {
                        is.write(i);
                        is.newLine();
                    }
                }
            }

        } catch (Exception e) {
            throw new GradleException("Failed to run", e);
        } finally {
            analyticsService.close();
        }
    }

    private boolean sourcesExist() {
        final Set<FileSystemLocation> srcDirLocations = getSources().getElements().get();
        for (FileSystemLocation srcDirLocation : srcDirLocations) {
            final File srcDir = srcDirLocation.getAsFile();
            if (srcDir.exists() && srcDir.isDirectory()) {
                final File[] files = srcDir.listFiles();
                if (files != null && files.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean classesExist() {
        for (FileSystemLocation location : getCompilationOutput().getElements().get()) {
            final File locationAsFile = location.getAsFile();
            if (locationAsFile.isDirectory()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns launch mode for the target application model.
     *
     * @return launch mode for the target application model
     */
    @Internal
    protected LaunchMode getLaunchMode() {
        return LaunchMode.DEVELOPMENT;
    }

    private DevModeCommandLine newLauncher(final AnalyticsService analyticsService) throws Exception {
        final JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);

        String java = null;

        if (GradleVersion.current().compareTo(GradleVersion.version("6.7")) >= 0) {
            JavaToolchainService toolChainService = project.getExtensions().getByType(JavaToolchainService.class);
            JavaToolchainSpec toolchainSpec = javaPluginExtension.getToolchain();
            Provider<JavaLauncher> javaLauncher = toolChainService.launcherFor(toolchainSpec);
            if (javaLauncher.isPresent()) {
                java = javaLauncher.get().getExecutablePath().getAsFile().getAbsolutePath();
            }
        }
        var projectDir = getProjectDir().get().getAsFile();
        var buildDir = getBuildDir().get().getAsFile();
        DevModeCommandLineBuilder builder = DevModeCommandLine.builder(java)
                .forceC2(getForceC2().getOrNull())
                .projectDir(projectDir)
                .buildDir(buildDir)
                .outputDir(buildDir)
                .debug(getProviderFactory().systemProperty("debug").getOrNull())
                .debugHost(getProviderFactory().systemProperty("debugHost").getOrNull())
                .debugPort(getProviderFactory().systemProperty("debugPort").getOrNull())
                .suspend(getProviderFactory().systemProperty("suspend").getOrNull());
        if (!getProviderFactory().systemProperty(IO_QUARKUS_DEVMODE_ARGS).isPresent()) {
            builder.jvmArgs("-Dquarkus.console.basic=true")
                    .jvmArgs("-Dio.quarkus.force-color-support=true");
        }
        if (!getTests().get().isEmpty()) {
            builder.jvmArgs("-Dquarkus-internal.test.specific-selection=gradle:"
                    + String.join(",", getTests().get()));
        }

        if (getJvmArguments().isPresent() && !getJvmArguments().get().isEmpty()) {
            builder.jvmArgs(getJvmArguments().get());
        }

        if (getOpenJavaLang().isPresent() && getOpenJavaLang().get()) {
            builder.addOpens("java.base/java.lang=ALL-UNNAMED");
        }

        if (getModules().isPresent() && !getModules().get().isEmpty()) {
            builder.addModules(getModules().get());
        }

        // This no longer captures `ext` properties.
        builder.buildSystemProperties(getProviderFactory().gradlePropertiesPrefixedBy("").get());

        //  this is a minor hack to allow ApplicationConfig to be populated with defaults
        builder.applicationName(getProjectName().get());
        builder.applicationVersion(getProject().getVersion().toString());

        builder.sourceEncoding(getSourceEncoding().getOrNull());

        final Path serializedModel = getApplicationModel().get().getAsFile().toPath();
        final ApplicationModel appModel = ToolingUtils.deserializeAppModel(serializedModel);
        builder.extensionDevModeConfig(appModel.getExtensionDevModeConfig())
                .extensionDevModeJvmOptionFilter(extensionJvmOptions);

        builder.jvmArgs("-Dgradle.project.path=" + projectDir.getAbsolutePath());

        analyticsService.sendAnalytics(
                DEV_MODE,
                appModel,
                Map.of(GRADLE_VERSION, GradleVersion.current().getVersion()),
                buildDir);

        final Set<ArtifactKey> projectDependencies = new HashSet<>();
        for (ResolvedDependency localDep : DependenciesFilter.getReloadableModules(appModel)) {
            addLocalProject(localDep, builder, projectDependencies, appModel.getAppArtifact().getWorkspaceModule().getId()
                    .equals(localDep.getWorkspaceModule().getId()));
        }

        addQuarkusDevModeDeps(builder, appModel);

        //look for an application.properties
        Set<Path> resourceDirs = new HashSet<>();
        for (SourceDir resourceDir : appModel.getApplicationModule().getMainSources().getResourceDirs()) {
            resourceDirs.add(resourceDir.getOutputDir());
        }

        final Collection<ArtifactKey> configuredParentFirst = ConfiguredClassLoading.builder()
                .setApplicationModel(appModel)
                .setApplicationRoot(PathsCollection.from(resourceDirs))
                .setMode(QuarkusBootstrap.Mode.DEV)
                .build().getParentFirstArtifacts();

        for (io.quarkus.maven.dependency.ResolvedDependency artifact : appModel.getDependencies()) {
            if (!projectDependencies.contains(artifact.getKey())) {
                artifact.getResolvedPaths().forEach(p -> {
                    File file = p.toFile();
                    if (file.exists() && configuredParentFirst.contains(artifact.getKey())
                            && filesIncludedInClasspath.add(file)) {
                        getLogger().debug("Adding dependency {}", file);
                        builder.classpathEntry(artifact.getKey(), file);
                    }
                });
            }
        }

        builder.sourceJavaVersion(javaPluginExtension.getSourceCompatibility().toString());
        builder.targetJavaVersion(javaPluginExtension.getTargetCompatibility().toString());

        builder.annotationProcessorPaths(getMainSourceSetAnnotationProcessorPath().getFiles());

        for (CompilerOption compilerOptions : compilerOptions.getCompilerOptions()) {
            builder.compilerOptions(compilerOptions.getName(), compilerOptions.getArgs());
        }

        if (shouldPropagateJavaCompilerArgs.get() && getCompilerArguments().get().isEmpty()) {
            var compileArgs = getJavaCompileTaskArgs();
            if (compileArgs.isPresent()) {
                builder.compilerOptions("java", compileArgs.get());
            }
        } else {
            builder.compilerOptions("java", getCompilerArguments().get());
        }

        modifyDevModeContext(builder);

        builder.jvmArgs("-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + serializedModel.toAbsolutePath());

        final Path serializedTestModel = getTestApplicationModel().get().getAsFile().toPath();
        builder.jvmArgs("-D" + BootstrapConstants.SERIALIZED_TEST_APP_MODEL + "=" + serializedTestModel.toAbsolutePath());

        //        extension().outputDirectory().mkdirs();

        var args = getArguments();
        if (args.isPresent() && !args.get().isEmpty()) {
            builder.applicationArgs(String.join(" ", args.get()));
        }

        return builder.build();
    }

    protected void modifyDevModeContext(DevModeCommandLineBuilder builder) {

    }

    public static void configureBootstrapResolverConfiguration(Project project, Configuration configuration,
            Configuration platformConfig) {
        configuration.setCanBeConsumed(false);
        configuration.setCanBeResolved(true);
        configuration.extendsFrom(platformConfig);
        configuration.getDependencies().add(getQuarkusBootstrapResolver(project, "quarkus-bootstrap-gradle-resolver"));
        configuration.getDependencies().add(getQuarkusBootstrapResolver(project, "quarkus-bootstrap-maven-resolver"));
        var disableComponentVariants = ApplicationDeploymentClasspathBuilder.isDisableComponentVariants(project.getProviders());
        if (!disableComponentVariants.get()) {
            configuration.attributes(attrs -> QuarkusComponentVariants.setCommonAttributes(attrs, project.getObjects()));
        }
    }

    private void addQuarkusDevModeDeps(DevModeCommandLineBuilder builder, ApplicationModel appModel) {

        final ConfigurationContainer configContainer = project.getConfigurations();
        var devModeDependencyConfiguration = configContainer
                .findByName(ApplicationDeploymentClasspathBuilder.QUARKUS_BOOTSTRAP_RESOLVER_CONFIGURATION);
        if (devModeDependencyConfiguration == null) {
            throw new GradleException("Failed to locate "
                    + ApplicationDeploymentClasspathBuilder.QUARKUS_BOOTSTRAP_RESOLVER_CONFIGURATION);
        }
        addDependencyIfAbsent(devModeDependencyConfiguration, getQuarkusCoreDeployment(appModel));

        for (ResolvedArtifact appDep : devModeDependencyConfiguration.getResolvedConfiguration().getResolvedArtifacts()) {
            ModuleVersionIdentifier artifactId = appDep.getModuleVersion().getId();
            //we only use the launcher for launching from the IDE, we need to exclude it
            if (!(artifactId.getGroup().equals("io.quarkus")
                    && artifactId.getName().equals("quarkus-ide-launcher"))) {
                if (artifactId.getGroup().equals("io.quarkus")
                        && artifactId.getName().equals("quarkus-class-change-agent")) {
                    builder.jvmArgs("-javaagent:" + appDep.getFile().getAbsolutePath());
                } else {
                    builder.classpathEntry(ArtifactKey.of(appDep.getModuleVersion().getId().getGroup(), appDep.getName(),
                            appDep.getClassifier(), appDep.getExtension()), appDep.getFile());
                }
            }
        }
    }

    private static void addDependencyIfAbsent(Configuration configuration, Dependency dependency) {
        for (Dependency existing : configuration.getDependencies()) {
            if (dependency.getGroup().equals(existing.getGroup()) && dependency.getName().equals(existing.getName())) {
                return;
            }
        }
        configuration.getDependencies().add(dependency);
    }

    private static Dependency getQuarkusBootstrapResolver(Project project, String artifactId) {
        final String pomPropsPath = "META-INF/maven/io.quarkus/" + artifactId + "/pom.properties";
        final InputStream devModePomPropsIs = DevModeMain.class.getClassLoader().getResourceAsStream(pomPropsPath);
        if (devModePomPropsIs == null) {
            throw new GradleException("Failed to locate " + pomPropsPath + " on the classpath");
        }
        final Properties devModeProps = new Properties();
        try (InputStream is = devModePomPropsIs) {
            devModeProps.load(is);
        } catch (IOException e) {
            throw new GradleException("Failed to load " + pomPropsPath + " from the classpath", e);
        }
        final String devModeGroupId = devModeProps.getProperty("groupId");
        if (devModeGroupId == null) {
            throw new GradleException("Classpath resource " + pomPropsPath + " is missing groupId");
        }
        final String devModeArtifactId = devModeProps.getProperty("artifactId");
        if (devModeArtifactId == null) {
            throw new GradleException("Classpath resource " + pomPropsPath + " is missing artifactId");
        }
        final String devModeVersion = devModeProps.getProperty("version");
        if (devModeVersion == null) {
            throw new GradleException("Classpath resource " + pomPropsPath + " is missing version");
        }
        return project.getDependencies()
                .create(String.format("%s:%s:%s", devModeGroupId, devModeArtifactId, devModeVersion));
    }

    private Dependency getQuarkusCoreDeployment(ApplicationModel appModel) {
        ResolvedDependency coreDeployment = null;
        for (ResolvedDependency d : appModel.getDependencies()) {
            if (d.isDeploymentCp() && d.getArtifactId().equals("quarkus-core-deployment")
                    && d.getGroupId().equals("io.quarkus")) {
                coreDeployment = d;
                break;
            }
        }
        if (coreDeployment == null) {
            throw new GradleException("Failed to locate io.quarkus:quarkus-core-deployment on the application build classpath");
        }
        return project.getDependencies()
                .create(String.format("%s:%s:%s", coreDeployment.getGroupId(), coreDeployment.getArtifactId(),
                        coreDeployment.getVersion()));
    }

    private void addLocalProject(ResolvedDependency project, DevModeCommandLineBuilder builder, Set<ArtifactKey> addeDeps,
            boolean root) {
        addeDeps.add(project.getKey());

        final ArtifactSources sources = project.getSources();
        if (sources == null) {
            return;
        }

        Set<Path> sourcePaths = new LinkedHashSet<>();
        Set<Path> sourceParentPaths = new LinkedHashSet<>();

        final Set<Path> classesDirs = new HashSet<>(sources.getSourceDirs().size());
        for (SourceDir src : sources.getSourceDirs()) {
            if (Files.exists(src.getDir())) {
                sourcePaths.add(src.getDir());
                sourceParentPaths.add(src.getDir().getParent());
                if (src.getOutputDir() != null) {
                    classesDirs.add(src.getOutputDir());
                }
            }
        }
        Path classesDir = classesDirs.isEmpty() ? null
                : QuarkusGradleUtils.mergeClassesDirs(classesDirs, project.getWorkspaceModule().getBuildDir(), true, false);
        Path generatedSourcesPath = sources.getSourceDirs().isEmpty() ? null
                : sources.getSourceDirs().iterator().next().getAptSourcesDir();

        final Set<Path> resourcesSrcDirs = new LinkedHashSet<>();
        // resourcesSrcDir may exist but if it's empty the resources output dir won't be created
        Path resourcesOutputDir = null;
        for (SourceDir resource : sources.getResourceDirs()) {
            resourcesSrcDirs.add(resource.getDir());
            if (resourcesOutputDir == null) {
                resourcesOutputDir = resource.getOutputDir();
            }
        }

        if (sourcePaths.isEmpty() && (resourcesOutputDir == null || !Files.exists(resourcesOutputDir)) || classesDir == null) {
            return;
        }

        final String resourcesOutputPath;
        if (resourcesOutputDir != null && Files.exists(resourcesOutputDir)) {
            resourcesOutputPath = resourcesOutputDir.toString();
            if (!Files.exists(classesDir)) {
                // currently classesDir can't be null and is expected to exist
                classesDir = resourcesOutputDir;
            }
        } else {
            // currently resources dir should exist
            resourcesOutputPath = classesDir.toString();
        }

        DevModeContext.ModuleInfo.Builder moduleBuilder = new DevModeContext.ModuleInfo.Builder()
                .setArtifactKey(project.getKey())
                .setName(project.getArtifactId())
                .setProjectDirectory(project.getWorkspaceModule().getModuleDir().getAbsolutePath())
                .setSourcePaths(PathList.from(sourcePaths))
                .setGeneratedSourcesPath(generatedSourcesPath != null ? generatedSourcesPath.toString() : null)
                .setClassesPath(classesDir.toString())
                .setResourcePaths(PathList.from(resourcesSrcDirs))
                .setResourcesOutputPath(resourcesOutputPath)
                .setSourceParents(PathList.from(sourceParentPaths))
                .setPreBuildOutputDir(project.getWorkspaceModule().getBuildDir().toPath().resolve("generated-sources")
                        .toAbsolutePath().toString())
                .setTargetDir(project.getWorkspaceModule().getBuildDir().toString());

        final ArtifactSources testSources = project.getWorkspaceModule().getTestSources();
        if (testSources != null) {
            Set<Path> testSourcePaths = new LinkedHashSet<>();

            final Set<Path> testClassesDirs = new HashSet<>(testSources.getSourceDirs().size());
            for (SourceDir src : testSources.getSourceDirs()) {
                if (Files.exists(src.getDir())) {
                    testSourcePaths.add(src.getDir());
                    if (src.getOutputDir() != null) {
                        testClassesDirs.add(src.getOutputDir());
                    }
                }
            }
            Path testClassesDir = testClassesDirs.isEmpty() ? null
                    : QuarkusGradleUtils.mergeClassesDirs(testClassesDirs, project.getWorkspaceModule().getBuildDir(), root,
                            root);

            final Set<Path> testResourcesSrcDirs = new LinkedHashSet<>();
            // resourcesSrcDir may exist but if it's empty the resources output dir won't be created
            Path testResourcesOutputDir = null;
            for (SourceDir resource : testSources.getResourceDirs()) {
                testResourcesSrcDirs.add(resource.getDir());
                if (testResourcesOutputDir == null) {
                    testResourcesOutputDir = resource.getOutputDir();
                }
            }

            if (testClassesDir != null && (!testSourcePaths.isEmpty()
                    || (testResourcesOutputDir != null && Files.exists(testResourcesOutputDir)))) {
                final String testResourcesOutputPath;
                if (testResourcesOutputDir != null && Files.exists(testResourcesOutputDir)) {
                    testResourcesOutputPath = testResourcesOutputDir.toString();
                    if (!Files.exists(testClassesDir)) {
                        // currently classesDir can't be null and is expected to exist
                        testClassesDir = testResourcesOutputDir;
                    }
                } else {
                    // currently resources dir should exist
                    testResourcesOutputPath = testClassesDir.toString();
                }
                moduleBuilder.setTestSourcePaths(PathList.from(testSourcePaths))
                        .setTestClassesPath(testClassesDir.toString())
                        .setTestResourcePaths(PathList.from(testResourcesSrcDirs))
                        .setTestResourcesOutputPath(testResourcesOutputPath);
            }
        }

        final DevModeContext.ModuleInfo wsModuleInfo = moduleBuilder.build();
        if (root) {
            builder.mainModule(wsModuleInfo);
        } else {
            builder.dependency(wsModuleInfo);
        }
    }

    public void shouldPropagateJavaCompilerArgs(boolean shouldPropagateJavaCompilerArgs) {
        this.shouldPropagateJavaCompilerArgs.set(shouldPropagateJavaCompilerArgs);
    }
}
