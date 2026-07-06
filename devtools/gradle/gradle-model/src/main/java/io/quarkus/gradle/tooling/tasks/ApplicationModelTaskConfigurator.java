package io.quarkus.gradle.tooling.tasks;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.tasks.QuarkusApplicationModelTask;
import io.quarkus.gradle.tooling.DefaultProjectDescriptor;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.runtime.LaunchMode;

public final class ApplicationModelTaskConfigurator {

    private ApplicationModelTaskConfigurator() {
    }

    public static TaskProvider<GenerateApplicationModelTask> registerGenerateApplicationModelTask(Project project,
            Provider<DefaultProjectDescriptor> projectDescriptor,
            ApplicationDeploymentClasspathBuilder classpath,
            DependencyDataCollector dependencyDataCollector,
            LaunchMode launchMode) {
        var appModelTask = project.getTasks().register(GenerateApplicationModelTask.taskName(launchMode),
                GenerateApplicationModelTask.class, launchMode);
        appModelTask.configure(task -> configure(project, task, projectDescriptor, classpath, dependencyDataCollector,
                launchMode, false));
        return appModelTask;
    }

    public static void configure(Project project, QuarkusApplicationModelTask task,
            Provider<DefaultProjectDescriptor> projectDescriptor,
            ApplicationDeploymentClasspathBuilder classpath,
            DependencyDataCollector dependencyDataCollector,
            LaunchMode launchMode, boolean buildModel) {
        task.getProjectDescriptor().set(projectDescriptor);
        task.getLaunchMode().set(launchMode);
        task.getDeclaredDependencies().putAll(
                dependencyDataCollector.collectProjectDeclaredDependencies(project, launchMode == LaunchMode.TEST));
        task.getTypeModel().set(task.getPath());
        task.getApplicationModel()
                .set(project.getLayout().getBuildDirectory().file(applicationModelPath(launchMode, buildModel)));
        task.getOriginalClasspath().setFrom(classpath.getOriginalRuntimeClasspathAsInput());
        task.getAppClasspath().configureFrom(classpath.getRuntimeConfigurationWithoutResolvingDeployment());
        task.getPlatformConfiguration().configureFrom(classpath.getPlatformConfiguration());
        task.getPlatformInfo().configureFrom(classpath.getPlatformPropertiesConfiguration());
        task.getCompileOnlyClasspath().configureFrom(classpath.getCompileOnlyWithoutResolvingDeployment());
        task.getDeploymentClasspath().configureFrom(classpath.getDeploymentConfiguration());
        task.getDeploymentClasspathFiles()
                .from(classpath.getDeploymentConfiguration().getIncoming().getArtifacts().getArtifactFiles());
    }

    public static String applicationModelPath(LaunchMode launchMode, boolean buildModel) {
        return switch (launchMode) {
            case TEST -> {
                if (buildModel) {
                    throw new IllegalArgumentException("BUILD_MODEL mode is not supported for LaunchMode.TEST");
                }
                yield "quarkus/application-model/quarkus-app-test-model.dat";
            }
            case DEVELOPMENT -> {
                if (buildModel) {
                    throw new IllegalArgumentException("BUILD_MODEL mode is not supported for LaunchMode.DEVELOPMENT");
                }
                yield "quarkus/application-model/quarkus-app-dev-model.dat";
            }
            case NORMAL -> {
                yield buildModel ? "quarkus/application-model/quarkus-app-model-build.dat"
                        : "quarkus/application-model/quarkus-app-model.dat";
            }
            case RUN -> {
                throw new IllegalArgumentException("RUN mode is not supported for application model generation");
            }
        };
    }
}
