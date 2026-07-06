package io.quarkus.gradle.tooling;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.Project;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.file.copy.DefaultCopySpec;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.SourceSetOutput;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.composite.IncludedBuildInternal;
import org.gradle.internal.composite.IncludedRootBuild;

import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultArtifactSources;
import io.quarkus.bootstrap.workspace.LazySourceDir;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

public class ProjectDescriptorBuilder {

    public static Provider<DefaultProjectDescriptor> buildForApp(Project project) {
        final Map<Project, WorkspaceModule.Mutable> modules = buildWorkspaceModules(project);
        modules.forEach(ProjectDescriptorBuilder::initModuleAfterEvaluation);
        return project.getProviders().provider(() -> {
            modules.forEach(ProjectDescriptorBuilder::refreshModuleId);
            return new DefaultProjectDescriptor(modules.get(project), modulesById(modules));
        });
    }

    /**
     * Builds an application descriptor without registering an {@code afterEvaluate} callback.
     * Use this when the caller is already running late enough in project configuration that
     * registering another evaluation callback is not allowed.
     */
    public static Provider<DefaultProjectDescriptor> buildForCurrentAppState(Project project) {
        final Map<Project, WorkspaceModule.Mutable> modules = buildWorkspaceModules(project);
        modules.forEach((moduleProject, module) -> {
            ProjectDescriptorBuilder.refreshModuleId(moduleProject, module);
            ProjectDescriptorBuilder.initSourceDirs(moduleProject, module);
        });
        return project.getProviders().provider(() -> new DefaultProjectDescriptor(modules.get(project), modulesById(modules)));
    }

    private static Map<Project, WorkspaceModule.Mutable> buildWorkspaceModules(Project project) {
        Map<Project, WorkspaceModule.Mutable> modules = new LinkedHashMap<>();
        addWorkspaceModules(project.getRootProject().getAllprojects(), modules);

        for (IncludedBuild includedBuild : project.getGradle().getIncludedBuilds()) {
            if (includedBuild instanceof IncludedRootBuild) {
                continue;
            }
            if (includedBuild instanceof IncludedBuildInternal internal) {
                addWorkspaceModules(internal.getTarget().getMutableModel().getRootProject().getAllprojects(), modules);
            }
        }
        return modules;
    }

    private static void addWorkspaceModules(Iterable<Project> projects, Map<Project, WorkspaceModule.Mutable> modules) {
        for (Project moduleProject : projects) {
            if (modules.containsKey(moduleProject)) {
                continue;
            }
            WorkspaceModule.Mutable module = new ProjectDescriptorBuilder(moduleProject).moduleBuilder;
            modules.put(moduleProject, module);
        }
    }

    private static void initModuleAfterEvaluation(Project project, WorkspaceModule.Mutable module) {
        if (project.getState().getExecuted()) {
            refreshModuleId(project, module);
            initSourceDirs(project, module);
        } else {
            project.afterEvaluate(evaluated -> {
                refreshModuleId(evaluated, module);
                initSourceDirs(evaluated, module);
            });
        }
    }

    private static Map<WorkspaceModuleId, WorkspaceModule.Mutable> modulesById(Map<Project, WorkspaceModule.Mutable> modules) {
        Map<WorkspaceModuleId, WorkspaceModule.Mutable> modulesById = new LinkedHashMap<>();
        for (WorkspaceModule.Mutable module : modules.values()) {
            modulesById.put(module.getId(), module);
        }
        return modulesById;
    }

    private static void refreshModuleId(Project project, WorkspaceModule.Mutable module) {
        module.setModuleId(WorkspaceModuleId.of(String.valueOf(project.getGroup()), project.getName(),
                String.valueOf(project.getVersion())));
    }

    public static void initSourceDirs(Project project, WorkspaceModule.Mutable result) {
        final SourceSetContainer srcSets = project.getExtensions().findByType(SourceSetContainer.class);
        if (srcSets == null) {
            return;
        }
        // Here we are iterating through the JARs that will be produced, collecting directories that will be used as sources
        // of their content. Then we are figuring out which source directories would be processed to produce the content of the JARs.
        // It has to be configureEach instead of forEach, apparently to avoid concurrent collection modification in some cases.
        project.getTasks().withType(Jar.class).configureEach(jarTask -> {
            final String classifier = jarTask.getArchiveClassifier().get();

            final List<File> classesDirs = new ArrayList<>(2);
            final List<File> resourcesOutputDirs = new ArrayList<>(2);
            collectSourceSetOutput(((DefaultCopySpec) jarTask.getRootSpec()), classesDirs, resourcesOutputDirs);

            final List<SourceDir> sourceDirs = new ArrayList<>();
            final List<SourceDir> resourceDirs = new ArrayList<>(2);
            for (SourceSet srcSet : srcSets) {
                for (var classesDir : srcSet.getOutput().getClassesDirs().getFiles()) {
                    if (classesDirs.contains(classesDir)) {
                        for (var srcDir : srcSet.getAllJava().getSrcDirs()) {
                            sourceDirs.add(new LazySourceDir(srcDir.toPath(), classesDir.toPath(),
                                    findGeneratedSourceDir(classesDir, srcSet)));
                        }
                    }
                }

                if (resourcesOutputDirs.contains(srcSet.getOutput().getResourcesDir())) {
                    var resourcesTarget = srcSet.getOutput().getResourcesDir().toPath();
                    for (var dir : srcSet.getResources().getSrcDirs()) {
                        resourceDirs.add(new LazySourceDir(dir.toPath(), resourcesTarget));
                    }
                }
            }

            if (!sourceDirs.isEmpty() || !resourceDirs.isEmpty()) {
                result.addArtifactSources(new DefaultArtifactSources(classifier, sourceDirs, resourceDirs));
            }
        });

        // This is for the test sources and resources since, by default, they won't be put in JARs
        project.getTasks().withType(Test.class).configureEach(testTask -> {
            for (SourceSet srcSet : srcSets) {
                String classifier = null;
                List<SourceDir> testSourcesDirs = new ArrayList<>(6);
                List<SourceDir> testResourcesDirs = new ArrayList<>(2);
                for (var classesDir : srcSet.getOutput().getClassesDirs().getFiles()) {
                    if (testTask.getTestClassesDirs().contains(classesDir)) {
                        if (classifier == null) {
                            classifier = sourceSetNameToClassifier(srcSet.getName());
                            if (result.hasSources(classifier)) {
                                // this source set should already be present in the module
                                break;
                            }
                        }
                        for (var srcDir : srcSet.getAllJava().getSrcDirs()) {
                            testSourcesDirs.add(new LazySourceDir(srcDir.toPath(), classesDir.toPath(),
                                    findGeneratedSourceDir(classesDir, srcSet)));
                        }
                    }
                }
                if (classifier != null && !testSourcesDirs.isEmpty()) {
                    if (srcSet.getOutput().getResourcesDir() != null) {
                        final Path resourcesOutputDir = srcSet.getOutput().getResourcesDir().toPath();
                        for (var dir : srcSet.getResources().getSrcDirs()) {
                            testResourcesDirs.add(new LazySourceDir(dir.toPath(), resourcesOutputDir));
                        }
                    }
                    result.addArtifactSources(new DefaultArtifactSources(classifier, testSourcesDirs, testResourcesDirs));
                }
            }
        });
    }

    private static String sourceSetNameToClassifier(String sourceSetName) {
        if (SourceSet.TEST_SOURCE_SET_NAME.equals(sourceSetName)) {
            return ArtifactSources.TEST;
        }
        var sb = new StringBuilder(sourceSetName.length() + 2);
        for (int i = 0; i < sourceSetName.length(); ++i) {
            char original = sourceSetName.charAt(i);
            char lowerCase = Character.toLowerCase(original);
            if (original != lowerCase) {
                sb.append('-');
            }
            sb.append(lowerCase);
        }
        return sb.toString();
    }

    private static Path findGeneratedSourceDir(File classesDir, SourceSet sourceSet) {
        if (classesDir.getParentFile() == null) {
            return null;
        }
        String language = classesDir.getParentFile().getName();
        String sourceSetName = classesDir.getName();
        for (File generatedDir : sourceSet.getOutput().getGeneratedSourcesDirs().getFiles()) {
            if (generatedDir.getParentFile() == null) {
                continue;
            }
            if (generatedDir.getName().equals(sourceSetName)
                    && generatedDir.getParentFile().getName().equals(language)) {
                return generatedDir.toPath();
            }
        }
        return null;
    }

    private static void collectSourceSetOutput(DefaultCopySpec spec, List<File> classesDir, List<File> resourcesDir) {
        for (var paths : spec.getSourcePaths()) {
            if (paths instanceof SourceSetOutput sso) {
                classesDir.addAll(sso.getClassesDirs().getFiles());
                resourcesDir.add(sso.getResourcesDir());
            }
        }
        for (var child : spec.getChildren()) {
            collectSourceSetOutput((DefaultCopySpec) child, classesDir, resourcesDir);
        }
    }

    private final WorkspaceModule.Mutable moduleBuilder;

    private ProjectDescriptorBuilder(Project project) {
        this.moduleBuilder = WorkspaceModule.builder()
                .setModuleId(WorkspaceModuleId.of(String.valueOf(project.getGroup()), project.getName(),
                        String.valueOf(project.getVersion())))
                .setModuleDir(project.getLayout().getProjectDirectory().getAsFile().toPath())
                .setBuildDir(project.getLayout().getBuildDirectory().get().getAsFile().toPath())
                .setBuildFile(project.getBuildFile().toPath());
    }
}
