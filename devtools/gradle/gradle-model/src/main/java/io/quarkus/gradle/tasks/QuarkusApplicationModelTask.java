package io.quarkus.gradle.tasks;

import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.addFileDependencies;
import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.clearFlag;
import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.collectDestinationDirs;
import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.isFlagOn;
import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.processQuarkusDependency;
import static io.quarkus.gradle.tooling.dependency.DependencyUtils.getKey;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.DefaultApplicationModel;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.gradle.tooling.DefaultProjectDescriptor;
import io.quarkus.gradle.tooling.GradlePomResolver;
import io.quarkus.gradle.tooling.ProjectDescriptor;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.gradle.tooling.dependency.DeclaredDepsResult;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.LaunchMode;

@DisableCachingByDefault(because = "The serialized application model contains resolved file-system paths and is not relocatable")
public abstract class QuarkusApplicationModelTask extends QuarkusBaseTask {

    /* @formatter:off */
    private static final byte COLLECT_TOP_EXTENSION_RUNTIME_NODES = 0b001;
    private static final byte COLLECT_DIRECT_DEPS =                 0b010;
    private static final byte COLLECT_RELOADABLE_MODULES =          0b100;
    /* @formatter:on */

    public static final String QUARKUS_PROJECT_DESCRIPTOR_ARTIFACT_TYPE = "quarkus-project-descriptor";

    private final QuarkusResolvedClasspath compileOnlyClasspath;
    private final QuarkusResolvedClasspath deploymentClasspath;

    @Internal
    public abstract RegularFileProperty getProjectBuildFile();

    /**
     * Used just to track original classpath as an input, since resolving quarkus classpath is kinda expensive,
     * and we don't want to do that if task is up-to-date
     */
    @CompileClasspath
    public abstract ConfigurableFileCollection getOriginalClasspath();

    /**
     * Tracks deployment artifacts as a Gradle-native file input for local up-to-date checks.
     * The resolved graph itself is consumed through the lazy resolution-result properties below.
     */
    @CompileClasspath
    public abstract ConfigurableFileCollection getDeploymentClasspathFiles();

    @Nested
    public abstract QuarkusResolvedClasspath getPlatformConfiguration();

    @Nested
    public abstract QuarkusResolvedClasspath getAppClasspath();

    @Internal
    public QuarkusResolvedClasspath getDeploymentClasspath() {
        return deploymentClasspath;
    }

    @Internal
    public QuarkusResolvedClasspath getCompileOnlyClasspath() {
        return compileOnlyClasspath;
    }

    @Nested
    public abstract QuarkusPlatformInfo getPlatformInfo();

    @Input
    public abstract Property<LaunchMode> getLaunchMode();

    @Input
    public abstract Property<String> getTypeModel();

    /**
     * If any project task changes, we will invalidate this task anyway
     */
    @Input
    public abstract Property<DefaultProjectDescriptor> getProjectDescriptor();

    @Internal
    public abstract MapProperty<ArtifactKey, DeclaredDepsResult> getDeclaredDependencies();

    @OutputFile
    public abstract RegularFileProperty getApplicationModel();

    public QuarkusApplicationModelTask() {
        compileOnlyClasspath = getObjects().newInstance(QuarkusResolvedClasspath.class);
        deploymentClasspath = getObjects().newInstance(QuarkusResolvedClasspath.class);
        getProjectBuildFile().set(getProject().getBuildFile());
    }

    @TaskAction
    public void execute() throws IOException {
        final DefaultProjectDescriptor projectDescriptor = getProjectDescriptor().get();

        final ResolvedDependencyBuilder appArtifact = getProjectArtifact(projectDescriptor);

        final ApplicationModelBuilder modelBuilder = new ApplicationModelBuilder()
                .setAppArtifact(appArtifact)
                .setPlatformImports(getPlatformInfo().resolvePlatformImports())
                .addReloadableWorkspaceModule(appArtifact.getKey());

        collectDependencies(getAppClasspath(), modelBuilder, projectDescriptor.getWorkspaceModule(), projectDescriptor);
        collectExtensionDependencies(getDeploymentClasspath(), modelBuilder);
        collectCompileOnlyDependencies(getCompileOnlyClasspath(), modelBuilder);

        var declaredDependencies = new HashMap<>(getDeclaredDependencies().get());
        var collector = new DependencyDataCollector(
                new GradlePomResolver(getDependencyHandler(), getMavenLocalRepositoryRoots()),
                getProviderFactory().systemPropertiesPrefixedBy("")::get);
        declaredDependencies.putAll(collector.collectExternalDeclaredDependencies(getLogger(),
                DependencyDataCollector.externalModuleDeclaredDependencyInputs(Stream.concat(
                        getAppClasspath().getResolvedArtifacts().get().stream(),
                        getDeploymentClasspath().getResolvedArtifacts().get().stream()).toList())));
        DependencyDataCollector.setDirectDeps(appArtifact, modelBuilder, declaredDependencies, getLogger());
        for (ResolvedDependencyBuilder dep : modelBuilder.getDependencies()) {
            DependencyDataCollector.setDirectDeps(dep, modelBuilder, declaredDependencies, getLogger());
        }

        DefaultApplicationModel model = modelBuilder.build();
        ToolingUtils.serializeAppModel(model, getApplicationModel().get().getAsFile().toPath());
    }

    private List<File> getMavenLocalRepositoryRoots() {
        String mavenRepoLocal = getProviderFactory().systemProperty("maven.repo.local").getOrNull();
        if (mavenRepoLocal == null || mavenRepoLocal.isBlank()) {
            return List.of();
        }
        return List.of(new File(mavenRepoLocal));
    }

    private ResolvedDependencyBuilder getProjectArtifact(DefaultProjectDescriptor projectDescriptor) {
        ModuleVersionIdentifier moduleVersion = getAppClasspath().getRoot().get().getModuleVersion();
        ResolvedDependencyBuilder appArtifact = ResolvedDependencyBuilder.newInstance()
                .setGroupId(moduleVersion.getGroup())
                .setArtifactId(moduleVersion.getName())
                .setVersion(moduleVersion.getVersion());

        WorkspaceModule.Mutable module = projectDescriptor.getWorkspaceModule();
        // TODO this is necessary for now to set the proper ID, since the group ID and the version don't have proper values in the descriptor
        module.setModuleId(
                WorkspaceModuleId.of(appArtifact.getGroupId(), appArtifact.getArtifactId(), appArtifact.getVersion()));

        var mainSources = module.getMainSources();
        if (mainSources != null) {
            final PathList.Builder paths = PathList.builder();
            collectDestinationDirs(module.getMainSources().getSourceDirs(), paths);
            collectDestinationDirs(module.getMainSources().getResourceDirs(), paths);
            appArtifact.setResolvedPaths(paths.build());
            appArtifact.setReloadable().setWorkspaceModule();
        } else {
            appArtifact.setResolvedPaths(PathList.empty());
        }

        return appArtifact.setWorkspaceModule(module);
    }

    private void collectDependencies(QuarkusResolvedClasspath classpath, ApplicationModelBuilder modelBuilder,
            WorkspaceModule.Mutable wsModule, ProjectDescriptor projectDescriptor) {
        final Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> artifacts = classpath
                .resolvedArtifactsByComponentIdentifier();

        Set<File> alreadyCollectedFiles = new HashSet<>(artifacts.size());
        final Set<ModuleVersionIdentifier> processedModules = new HashSet<>();
        classpath.getRoot().get().getDependencies().forEach(d -> {
            if (d instanceof ResolvedDependencyResult resolved) {
                byte flags = (byte) (COLLECT_TOP_EXTENSION_RUNTIME_NODES | COLLECT_DIRECT_DEPS);
                final LaunchMode launchMode = getLaunchMode().get();
                if (!launchMode.equals(LaunchMode.NORMAL)) {
                    flags |= COLLECT_RELOADABLE_MODULES;
                }
                collectDependencies(resolved, modelBuilder, artifacts, wsModule, alreadyCollectedFiles,
                        processedModules, flags, projectDescriptor);
            }
        });
        Set<File> fileDependencies = new HashSet<>(classpath.getAllResolvedFiles().getFiles());

        fileDependencies.removeAll(alreadyCollectedFiles);
        addFileDependencies(modelBuilder, fileDependencies);
    }

    private static void collectDependencies(
            ResolvedDependencyResult resolvedDependency,
            ApplicationModelBuilder modelBuilder,
            Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> resolvedArtifacts,
            WorkspaceModule.Mutable parentModule,
            Set<File> collectedArtifactFiles,
            Set<ModuleVersionIdentifier> processedModules,
            byte flags,
            ProjectDescriptor projectDescriptor) {
        final ModuleVersionIdentifier moduleId = getModuleVersion(resolvedDependency);
        if (!processedModules.add(moduleId)) {
            return;
        }
        var projectModule = projectDescriptor
                .getWorkspaceModuleOrNull(WorkspaceModuleId.of(moduleId.getGroup(), moduleId.getName(), moduleId.getVersion()));
        final List<QuarkusResolvedArtifact> artifacts = getResolvedModuleArtifacts(resolvedArtifacts,
                resolvedDependency.getSelected().getId());
        if (artifacts.isEmpty()) {
            final byte finalFlags = flags;
            resolvedDependency.getSelected().getDependencies().forEach((Consumer<DependencyResult>) dependencyResult -> {
                if (dependencyResult instanceof ResolvedDependencyResult result) {
                    collectDependencies(result, modelBuilder, resolvedArtifacts,
                            projectModule,
                            collectedArtifactFiles,
                            processedModules, finalFlags, projectDescriptor);
                }
            });
            return;
        }

        byte newFlags = flags;
        for (QuarkusResolvedArtifact artifact : artifacts) {
            collectedArtifactFiles.add(artifact.file);
            final ArtifactKey artifactKey = getKey(
                    moduleId.getGroup(),
                    moduleId.getName(),
                    moduleId.getVersion(),
                    artifact.file,
                    artifact.type);
            if (!isDependency(artifact)
                    || modelBuilder.getDependency(artifactKey) != null
                    // test fixtures depend on the default jar artifact, which could be the root one
                    || isApplicationRoot(modelBuilder, artifactKey)) {
                continue;
            }

            final ArtifactCoords depCoords = new GACTV(artifactKey, moduleId.getVersion());
            ResolvedDependencyBuilder depBuilder = ResolvedDependencyBuilder.newInstance()
                    .setCoords(depCoords)
                    .setRuntimeCp()
                    .setDeploymentCp()
                    .setResolvedPath(artifact.file.toPath())
                    .setWorkspaceModule(projectModule);
            if (isFlagOn(flags, COLLECT_DIRECT_DEPS)) {
                depBuilder.setDirect(true);
                newFlags = clearFlag(newFlags, COLLECT_DIRECT_DEPS);
            }
            if (parentModule != null) {
                parentModule.addDependency(new ArtifactDependency(depCoords));
            }

            if (processQuarkusDependency(depBuilder, modelBuilder)) {
                if (isFlagOn(flags, COLLECT_TOP_EXTENSION_RUNTIME_NODES)) {
                    depBuilder.setFlags(DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT);
                    newFlags = clearFlag(newFlags, COLLECT_TOP_EXTENSION_RUNTIME_NODES);
                }
            }
            if (isFlagOn(flags, COLLECT_RELOADABLE_MODULES)) {
                if (!depBuilder.isRuntimeExtensionArtifact()
                        && projectModule != null) {
                    depBuilder.setReloadable();
                    modelBuilder.addReloadableWorkspaceModule(artifactKey);
                } else {
                    newFlags = clearFlag(newFlags, COLLECT_RELOADABLE_MODULES);
                }
            }
            modelBuilder.addDependency(depBuilder);
        }

        flags = newFlags;
        for (DependencyResult dependency : resolvedDependency.getSelected().getDependencies()) {
            if (dependency instanceof ResolvedDependencyResult result) {
                collectDependencies(result, modelBuilder, resolvedArtifacts, projectModule,
                        collectedArtifactFiles,
                        processedModules, flags, projectDescriptor);
            }
        }
    }

    private static boolean isApplicationRoot(ApplicationModelBuilder modelBuilder, ArtifactKey artifactKey) {
        return modelBuilder.getApplicationArtifact().getKey().equals(artifactKey);
    }

    private static ModuleVersionIdentifier getModuleVersion(ResolvedDependencyResult resolvedDependency) {
        return Objects.requireNonNull(resolvedDependency.getSelected().getModuleVersion());
    }

    private static boolean isDependency(QuarkusResolvedArtifact a) {
        return a.file.getName().endsWith(ArtifactCoords.TYPE_JAR)
                || a.file.getName().endsWith(".exe")
                || a.file.isDirectory();
    }

    private static void collectExtensionDependencies(QuarkusResolvedClasspath classpath, ApplicationModelBuilder modelBuilder) {
        Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> artifacts = classpath.resolvedArtifactsByComponentIdentifier();
        final Set<ModuleVersionIdentifier> processedModules = new HashSet<>();
        classpath.getRoot().get().getDependencies().forEach(d -> {
            if (d instanceof ResolvedDependencyResult result) {
                collectExtensionDependencies(result, modelBuilder, artifacts, processedModules, false);
            }
        });
    }

    private static void collectExtensionDependencies(
            ResolvedDependencyResult resolvedDependency,
            ApplicationModelBuilder modelBuilder,
            Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> resolvedArtifacts,
            Set<ModuleVersionIdentifier> processedModules,
            boolean clearReloadableFlag) {
        final ModuleVersionIdentifier moduleId = getModuleVersion(resolvedDependency);
        if (!processedModules.add(moduleId)) {
            if (clearReloadableFlag) {
                clearReloadableWorkspaceModule(resolvedDependency, modelBuilder, resolvedArtifacts);
            }
            return;
        }
        List<QuarkusResolvedArtifact> artifacts = getResolvedModuleArtifacts(resolvedArtifacts,
                resolvedDependency.getSelected().getId());
        if (artifacts.isEmpty()) {
            return;
        }

        final ModuleVersionIdentifier moduleVersionIdentifier = getModuleVersion(resolvedDependency);
        boolean clearReloadableFlagChildren = clearReloadableFlag;
        for (QuarkusResolvedArtifact artifact : artifacts) {
            ArtifactKey artifactKey = getKey(
                    moduleVersionIdentifier.getGroup(),
                    moduleVersionIdentifier.getName(),
                    moduleVersionIdentifier.getVersion(),
                    artifact.file,
                    artifact.type);
            if (!isDependency(artifact)
                    // test fixtures depend on the default jar artifact, which could be the root one
                    || isApplicationRoot(modelBuilder, artifactKey)) {
                continue;
            }

            ResolvedDependencyBuilder dep = modelBuilder.getDependency(artifactKey);
            if (dep == null) {
                ArtifactCoords artifactCoords = new GACTV(artifactKey, moduleVersionIdentifier.getVersion());
                dep = toDependency(artifactCoords, artifact.file);
                modelBuilder.addDependency(dep);
            }
            dep.setDeploymentCp();
            if (clearReloadableFlag) {
                clearReloadableWorkspaceModule(modelBuilder, dep);
            } else if (!dep.isReloadable()) {
                clearReloadableFlagChildren = true;
            }
        }

        for (DependencyResult d : resolvedDependency.getSelected().getDependencies()) {
            if (d instanceof ResolvedDependencyResult result) {
                collectExtensionDependencies(result, modelBuilder, resolvedArtifacts, processedModules,
                        clearReloadableFlagChildren);
            }
        }
    }

    private static void clearReloadableWorkspaceModule(
            ResolvedDependencyResult resolvedDependency,
            ApplicationModelBuilder modelBuilder,
            Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> resolvedArtifacts) {
        final ModuleVersionIdentifier moduleVersionIdentifier = getModuleVersion(resolvedDependency);
        for (QuarkusResolvedArtifact artifact : getResolvedModuleArtifacts(resolvedArtifacts,
                resolvedDependency.getSelected().getId())) {
            ArtifactKey artifactKey = getKey(
                    moduleVersionIdentifier.getGroup(),
                    moduleVersionIdentifier.getName(),
                    moduleVersionIdentifier.getVersion(),
                    artifact.file,
                    artifact.type);
            ResolvedDependencyBuilder dep = modelBuilder.getDependency(artifactKey);
            if (dep != null) {
                clearReloadableWorkspaceModule(modelBuilder, dep);
            }
        }
    }

    private static void clearReloadableWorkspaceModule(ApplicationModelBuilder modelBuilder, ResolvedDependencyBuilder dep) {
        dep.clearFlag(DependencyFlags.RELOADABLE);
        modelBuilder.removeReloadableWorkspaceModule(dep.getKey());
    }

    private static void collectCompileOnlyDependencies(QuarkusResolvedClasspath classpath,
            ApplicationModelBuilder modelBuilder) {
        final Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> artifacts = classpath
                .resolvedArtifactsByComponentIdentifier();
        final Set<ModuleVersionIdentifier> processedModules = new HashSet<>();
        classpath.getRoot().get().getDependencies().forEach(d -> {
            if (d instanceof ResolvedDependencyResult resolved) {
                collectCompileOnlyDependencies(resolved, modelBuilder, artifacts, processedModules);
            }
        });
    }

    private static void collectCompileOnlyDependencies(
            ResolvedDependencyResult resolvedDependency,
            ApplicationModelBuilder modelBuilder,
            Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> resolvedArtifacts,
            Set<ModuleVersionIdentifier> processedModules) {
        final ModuleVersionIdentifier moduleId = getModuleVersion(resolvedDependency);
        if (!processedModules.add(moduleId)) {
            return;
        }
        final List<QuarkusResolvedArtifact> artifacts = getResolvedModuleArtifacts(resolvedArtifacts,
                resolvedDependency.getSelected().getId());
        if (artifacts.isEmpty()) {
            return;
        }

        boolean skip = true;
        for (QuarkusResolvedArtifact artifact : artifacts) {
            if (!isDependency(artifact)) {
                continue;
            }
            final ArtifactKey artifactKey = getKey(
                    moduleId.getGroup(),
                    moduleId.getName(),
                    moduleId.getVersion(),
                    artifact.file,
                    artifact.type);
            if (isApplicationRoot(modelBuilder, artifactKey)) {
                continue;
            }

            ResolvedDependencyBuilder dep = modelBuilder.getDependency(artifactKey);
            if (dep == null) {
                ArtifactCoords artifactCoords = new GACTV(artifactKey, moduleId.getVersion());
                dep = toDependency(artifactCoords, artifact.file);
                modelBuilder.addDependency(dep);
            }
            if (!dep.isFlagSet(DependencyFlags.COMPILE_ONLY)) {
                skip = false;
                dep.setFlags(DependencyFlags.COMPILE_ONLY);
            }
        }

        if (!skip) {
            for (DependencyResult dependency : resolvedDependency.getSelected().getDependencies()) {
                if (dependency instanceof ResolvedDependencyResult result) {
                    collectCompileOnlyDependencies(result, modelBuilder, resolvedArtifacts, processedModules);
                }
            }
        }
    }

    private static List<QuarkusResolvedArtifact> getResolvedModuleArtifacts(
            Map<ComponentIdentifier, List<QuarkusResolvedArtifact>> artifacts, ComponentIdentifier moduleId) {
        return artifacts.getOrDefault(moduleId, List.of());
    }

    static ResolvedDependencyBuilder toDependency(ArtifactCoords artifactCoords, File file, int... flags) {
        int allFlags = 0;
        for (int f : flags) {
            allFlags |= f;
        }
        return ResolvedDependencyBuilder.newInstance()
                .setCoords(artifactCoords)
                .setResolvedPaths(PathList.of(file.toPath()))
                .setFlags(allFlags);
    }

}
