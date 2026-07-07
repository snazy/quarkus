package io.quarkus.gradle.application.internal.modelgen;

import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.addFileDependencies;
import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.clearFlag;
import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.isFlagOn;
import static io.quarkus.gradle.tooling.ApplicationModelBuilderSupport.processQuarkusDependency;
import static io.quarkus.gradle.tooling.dependency.DependencyUtils.getKey;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.DefaultApplicationModel;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultArtifactSources;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.gradle.tooling.GradlePomResolver;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.gradle.tooling.dependency.DeclaredDependencyEnrichmentMode;
import io.quarkus.gradle.tooling.dependency.DeclaredDepsResult;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.gradle.tooling.dependency.PomClosureResult;
import io.quarkus.gradle.tooling.dependency.PomClosureResultCodec;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.LaunchMode;

@DisableCachingByDefault(because = "The serialized application model contains resolved file-system paths and is not relocatable")
public abstract class GenerateModelTask extends DefaultTask {

    /* @formatter:off */
    private static final byte COLLECT_TOP_EXTENSION_RUNTIME_NODES = 0b001;
    private static final byte COLLECT_DIRECT_DEPS =                 0b010;
    private static final byte COLLECT_RELOADABLE_MODULES =          0b100;
    /* @formatter:on */

    private final ResolvedClasspath compileOnlyClasspath;
    private final ResolvedClasspath deploymentClasspath;

    public GenerateModelTask() {
        compileOnlyClasspath = getObjects().newInstance(ResolvedClasspath.class);
        deploymentClasspath = getObjects().newInstance(ResolvedClasspath.class);
        getDeclaredDependencyEnrichmentMode().convention(DeclaredDependencyEnrichmentMode.NONE);
    }

    @Inject
    protected abstract ObjectFactory getObjects();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    @Input
    public abstract Property<LaunchMode> getLaunchMode();

    @Input
    public abstract Property<String> getProjectGroup();

    @Input
    public abstract Property<String> getProjectName();

    @Input
    public abstract Property<String> getProjectVersion();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getProjectBuildFile();

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Internal
    public abstract DirectoryProperty getBuildDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApplicationClassesDirectories();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApplicationResourcesDirectories();

    @Input
    public abstract ListProperty<String> getApplicationSourceDirectoryPaths();

    @Input
    public abstract ListProperty<String> getApplicationResourceSourceDirectoryPaths();

    @CompileClasspath
    public abstract ConfigurableFileCollection getOriginalClasspath();

    @CompileClasspath
    public abstract ConfigurableFileCollection getDeploymentClasspathFiles();

    @Nested
    public abstract ResolvedClasspath getPlatformConfiguration();

    @Nested
    public abstract ResolvedClasspath getAppClasspath();

    @Internal
    public ResolvedClasspath getDeploymentClasspath() {
        return deploymentClasspath;
    }

    @Internal
    public ResolvedClasspath getCompileOnlyClasspath() {
        return compileOnlyClasspath;
    }

    @Nested
    public abstract PlatformInfo getPlatformInfo();

    @Input
    public abstract ListProperty<String> getMavenLocalRepositoryRoots();

    @Input
    public abstract Property<DeclaredDependencyEnrichmentMode> getDeclaredDependencyEnrichmentMode();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPomClosureFile();

    @OutputFile
    public abstract RegularFileProperty getApplicationModel();

    @TaskAction
    public void execute() throws IOException {
        WorkspaceModule.Mutable workspaceModule = workspaceModule();
        final ResolvedDependencyBuilder appArtifact = getProjectArtifact(workspaceModule);

        final ApplicationModelBuilder modelBuilder = new ApplicationModelBuilder()
                .setAppArtifact(appArtifact)
                .setPlatformImports(getPlatformInfo().resolvePlatformImports())
                .addReloadableWorkspaceModule(appArtifact.getKey());

        collectDependencies(getAppClasspath(), modelBuilder, workspaceModule);
        collectExtensionDependencies(getDeploymentClasspath(), modelBuilder);
        collectCompileOnlyDependencies(getCompileOnlyClasspath(), modelBuilder);

        if (getDeclaredDependencyEnrichmentMode().get() == DeclaredDependencyEnrichmentMode.SELECTED_MODULE_POMS) {
            Map<ArtifactKey, DeclaredDepsResult> declaredDependencies = collectExternalDeclaredDependencies();
            DependencyDataCollector.setDirectDeps(appArtifact, modelBuilder, declaredDependencies, getLogger());
            for (ResolvedDependencyBuilder dep : modelBuilder.getDependencies()) {
                DependencyDataCollector.setDirectDeps(dep, modelBuilder, declaredDependencies, getLogger());
            }
        }

        DefaultApplicationModel model = modelBuilder.build();
        ToolingUtils.serializeAppModel(model, getApplicationModel().get().getAsFile().toPath());
    }

    private WorkspaceModule.Mutable workspaceModule() {
        WorkspaceModule.Mutable module = WorkspaceModule.builder()
                .setModuleId(WorkspaceModuleId.of(getProjectGroup().get(), getProjectName().get(), getProjectVersion().get()))
                .setModuleDir(getProjectDirectory().get().getAsFile().toPath())
                .setBuildDir(getBuildDirectory().get().getAsFile().toPath())
                .setBuildFile(getProjectBuildFile().get().getAsFile().toPath());
        Path sourceOutputDir = firstPath(getApplicationClassesDirectories().getFiles());
        Path resourceOutputDir = firstPath(getApplicationResourcesDirectories().getFiles());
        if (sourceOutputDir != null || resourceOutputDir != null) {
            module.addArtifactSources(new DefaultArtifactSources(
                    ArtifactSources.MAIN,
                    sourceOutputDir == null ? List.of()
                            : sourceDirs(getApplicationSourceDirectoryPaths().get(),
                                    sourceOutputDir),
                    resourceOutputDir == null ? List.of()
                            : sourceDirs(getApplicationResourceSourceDirectoryPaths().get(),
                                    resourceOutputDir)));
        }
        return module;
    }

    private static List<SourceDir> sourceDirs(List<String> directories, Path outputDir) {
        return directories.stream()
                .map(Path::of)
                .sorted()
                .map(sourceDir -> SourceDir.of(sourceDir, outputDir))
                .toList();
    }

    private static Path firstPath(Set<File> directories) {
        return directories.stream()
                .map(File::toPath)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private List<File> mavenLocalRepositoryRoots() {
        return getMavenLocalRepositoryRoots().get().stream()
                .filter(root -> !root.isBlank())
                .map(File::new)
                .toList();
    }

    private Map<ArtifactKey, DeclaredDepsResult> collectExternalDeclaredDependencies() throws IOException {
        PomClosureResult pomClosure = PomClosureResultCodec.read(getPomClosureFile().get().getAsFile().toPath());
        var collector = new DependencyDataCollector(
                new GradlePomResolver(pomClosure.resolvedPoms(), pomClosure.missingPoms(), mavenLocalRepositoryRoots()),
                getProviderFactory().systemPropertiesPrefixedBy("")::get);
        return new HashMap<>(collector.collectExternalDeclaredDependencies(getLogger(),
                DependencyDataCollector.externalModuleDeclaredDependencyInputs(Stream.concat(
                        getAppClasspath().getResolvedArtifacts().get().stream(),
                        getDeploymentClasspath().getResolvedArtifacts().get().stream()).toList())));
    }

    private ResolvedDependencyBuilder getProjectArtifact(WorkspaceModule.Mutable module) {
        ModuleVersionIdentifier moduleVersion = getAppClasspath().getRoot().get().getModuleVersion();
        ResolvedDependencyBuilder appArtifact = ResolvedDependencyBuilder.newInstance()
                .setGroupId(moduleVersion.getGroup())
                .setArtifactId(moduleVersion.getName())
                .setVersion(moduleVersion.getVersion());

        module.setModuleId(
                WorkspaceModuleId.of(appArtifact.getGroupId(), appArtifact.getArtifactId(), appArtifact.getVersion()));

        final PathList.Builder paths = PathList.builder();
        collectExistingDirs(getApplicationClassesDirectories().getFiles(), paths);
        collectExistingDirs(getApplicationResourcesDirectories().getFiles(), paths);
        PathList resolvedPaths = paths.build();
        if (!resolvedPaths.isEmpty()) {
            appArtifact.setResolvedPaths(resolvedPaths);
            appArtifact.setReloadable().setWorkspaceModule();
        } else {
            appArtifact.setResolvedPaths(PathList.empty());
        }

        return appArtifact.setWorkspaceModule(module);
    }

    private static void collectExistingDirs(Set<File> directories, PathList.Builder paths) {
        for (File directory : directories) {
            if (directory.exists()) {
                paths.add(directory.toPath());
            }
        }
    }

    private void collectDependencies(ResolvedClasspath classpath,
            ApplicationModelBuilder modelBuilder, WorkspaceModule.Mutable wsModule) {
        final Map<ComponentIdentifier, List<ResolvedArtifact>> artifacts = classpath
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
                        processedModules, flags);
            }
        });
        Set<File> fileDependencies = new HashSet<>(classpath.getAllResolvedFiles().getFiles());

        fileDependencies.removeAll(alreadyCollectedFiles);
        addFileDependencies(modelBuilder, fileDependencies);
    }

    private static void collectDependencies(
            ResolvedDependencyResult resolvedDependency,
            ApplicationModelBuilder modelBuilder,
            Map<ComponentIdentifier, List<ResolvedArtifact>> resolvedArtifacts,
            WorkspaceModule.Mutable parentModule,
            Set<File> collectedArtifactFiles,
            Set<ModuleVersionIdentifier> processedModules,
            byte flags) {
        final ModuleVersionIdentifier moduleId = getModuleVersion(resolvedDependency);
        if (!processedModules.add(moduleId)) {
            return;
        }
        final List<ResolvedArtifact> artifacts = getResolvedModuleArtifacts(resolvedArtifacts,
                resolvedDependency.getSelected().getId());
        if (artifacts.isEmpty()) {
            final byte finalFlags = flags;
            resolvedDependency.getSelected().getDependencies().forEach((Consumer<DependencyResult>) dependencyResult -> {
                if (dependencyResult instanceof ResolvedDependencyResult result) {
                    collectDependencies(result, modelBuilder, resolvedArtifacts,
                            null,
                            collectedArtifactFiles,
                            processedModules, finalFlags);
                }
            });
            return;
        }

        byte newFlags = flags;
        for (ResolvedArtifact artifact : artifacts) {
            collectedArtifactFiles.add(artifact.file);
            final ArtifactKey artifactKey = getKey(
                    moduleId.getGroup(),
                    moduleId.getName(),
                    moduleId.getVersion(),
                    artifact.file,
                    artifact.type);
            if (!isDependency(artifact)
                    || modelBuilder.getDependency(artifactKey) != null
                    || isApplicationRoot(modelBuilder, artifactKey)) {
                continue;
            }

            final ArtifactCoords depCoords = new GACTV(artifactKey, moduleId.getVersion());
            ResolvedDependencyBuilder depBuilder = ResolvedDependencyBuilder.newInstance()
                    .setCoords(depCoords)
                    .setRuntimeCp()
                    .setDeploymentCp()
                    .setResolvedPath(artifact.file.toPath());
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
                newFlags = clearFlag(newFlags, COLLECT_RELOADABLE_MODULES);
            }
            modelBuilder.addDependency(depBuilder);
        }

        flags = newFlags;
        for (DependencyResult dependency : resolvedDependency.getSelected().getDependencies()) {
            if (dependency instanceof ResolvedDependencyResult result) {
                collectDependencies(result, modelBuilder, resolvedArtifacts, null,
                        collectedArtifactFiles,
                        processedModules, flags);
            }
        }
    }

    private static boolean isApplicationRoot(ApplicationModelBuilder modelBuilder, ArtifactKey artifactKey) {
        return modelBuilder.getApplicationArtifact().getKey().equals(artifactKey);
    }

    private static ModuleVersionIdentifier getModuleVersion(ResolvedDependencyResult resolvedDependency) {
        return Objects.requireNonNull(resolvedDependency.getSelected().getModuleVersion());
    }

    private static boolean isDependency(ResolvedArtifact a) {
        return a.file.getName().endsWith(ArtifactCoords.TYPE_JAR)
                || a.file.getName().endsWith(".exe")
                || a.file.isDirectory();
    }

    private static void collectExtensionDependencies(ResolvedClasspath classpath,
            ApplicationModelBuilder modelBuilder) {
        Map<ComponentIdentifier, List<ResolvedArtifact>> artifacts = classpath
                .resolvedArtifactsByComponentIdentifier();
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
            Map<ComponentIdentifier, List<ResolvedArtifact>> resolvedArtifacts,
            Set<ModuleVersionIdentifier> processedModules,
            boolean clearReloadableFlag) {
        final ModuleVersionIdentifier moduleId = getModuleVersion(resolvedDependency);
        if (!processedModules.add(moduleId)) {
            if (clearReloadableFlag) {
                clearReloadableWorkspaceModule(resolvedDependency, modelBuilder, resolvedArtifacts);
            }
            return;
        }
        List<ResolvedArtifact> artifacts = getResolvedModuleArtifacts(resolvedArtifacts,
                resolvedDependency.getSelected().getId());
        if (artifacts.isEmpty()) {
            return;
        }

        final ModuleVersionIdentifier moduleVersionIdentifier = getModuleVersion(resolvedDependency);
        boolean clearReloadableFlagChildren = clearReloadableFlag;
        for (ResolvedArtifact artifact : artifacts) {
            ArtifactKey artifactKey = getKey(
                    moduleVersionIdentifier.getGroup(),
                    moduleVersionIdentifier.getName(),
                    moduleVersionIdentifier.getVersion(),
                    artifact.file,
                    artifact.type);
            if (!isDependency(artifact)
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
            Map<ComponentIdentifier, List<ResolvedArtifact>> resolvedArtifacts) {
        final ModuleVersionIdentifier moduleVersionIdentifier = getModuleVersion(resolvedDependency);
        for (ResolvedArtifact artifact : getResolvedModuleArtifacts(resolvedArtifacts,
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

    private static void collectCompileOnlyDependencies(ResolvedClasspath classpath,
            ApplicationModelBuilder modelBuilder) {
        final Map<ComponentIdentifier, List<ResolvedArtifact>> artifacts = classpath
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
            Map<ComponentIdentifier, List<ResolvedArtifact>> resolvedArtifacts,
            Set<ModuleVersionIdentifier> processedModules) {
        final ModuleVersionIdentifier moduleId = getModuleVersion(resolvedDependency);
        if (!processedModules.add(moduleId)) {
            return;
        }
        final List<ResolvedArtifact> artifacts = getResolvedModuleArtifacts(resolvedArtifacts,
                resolvedDependency.getSelected().getId());
        if (artifacts.isEmpty()) {
            return;
        }

        boolean skip = true;
        for (ResolvedArtifact artifact : artifacts) {
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

    private static List<ResolvedArtifact> getResolvedModuleArtifacts(
            Map<ComponentIdentifier, List<ResolvedArtifact>> artifacts, ComponentIdentifier moduleId) {
        return artifacts.getOrDefault(moduleId, List.of());
    }

    private static ResolvedDependencyBuilder toDependency(ArtifactCoords artifactCoords, File file, int... flags) {
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
