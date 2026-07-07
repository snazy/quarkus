package io.quarkus.gradle.tooling.tasks;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.dependency.LocalComponentOutputViews;
import io.quarkus.gradle.tasks.QuarkusApplicationModelTask;
import io.quarkus.gradle.tooling.DefaultProjectDescriptor;
import io.quarkus.gradle.tooling.dependency.DeclaredDependencyEnrichmentMode;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.runtime.LaunchMode;

public final class IsolatedApplicationModelTaskConfigurator {

    private IsolatedApplicationModelTaskConfigurator() {
    }

    /**
     * Registers an application-model task path that avoids configuration-time producer-project model access.
     * <p>
     * This path uses Gradle artifact views to capture local component class/resource outputs. It intentionally avoids
     * declared dependency enrichment until the deployment classpath resolver is isolated-project safe for that path.
     */
    public static TaskProvider<GenerateApplicationModelTask> registerGenerateApplicationModelTask(Project project,
            Provider<DefaultProjectDescriptor> projectDescriptor,
            ApplicationDeploymentClasspathBuilder classpath,
            DependencyDataCollector dependencyDataCollector,
            LaunchMode launchMode,
            LocalComponentOutputViews localComponentOutputs) {
        var appModelTask = project.getTasks().register(GenerateApplicationModelTask.taskName(launchMode),
                GenerateApplicationModelTask.class, launchMode);
        appModelTask.configure(task -> {
            ApplicationModelTaskConfigurator.configure(project, task, projectDescriptor, classpath, dependencyDataCollector,
                    launchMode, false, false);
            task.getDeclaredDependencyEnrichmentMode().set(DeclaredDependencyEnrichmentMode.NONE);
            configureLocalComponentOutputs(task, localComponentOutputs);
        });
        return appModelTask;
    }

    private static void configureLocalComponentOutputs(QuarkusApplicationModelTask task,
            LocalComponentOutputViews localComponentOutputs) {
        task.getLocalClassOutputArtifacts().set(localComponentOutputs.classArtifacts());
        task.getLocalResourceOutputArtifacts().set(localComponentOutputs.resourceArtifacts());
        task.getLocalComponentOutputFiles().from(localComponentOutputs.classFiles(), localComponentOutputs.resourceFiles());
    }
}
