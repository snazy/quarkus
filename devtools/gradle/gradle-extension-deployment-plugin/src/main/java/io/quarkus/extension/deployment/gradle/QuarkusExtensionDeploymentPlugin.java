package io.quarkus.extension.deployment.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

import io.quarkus.gradle.GradleVersionSupport;
import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.extension.AnnotationProcessorDependencyConfigurator;
import io.quarkus.gradle.extension.ExtensionConstants;
import io.quarkus.gradle.tooling.DefaultProjectDescriptor;
import io.quarkus.gradle.tooling.ProjectDescriptorBuilder;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.gradle.tooling.tasks.ApplicationModelTaskConfigurator;
import io.quarkus.gradle.tooling.tasks.GenerateApplicationModelTask;
import io.quarkus.runtime.LaunchMode;

public class QuarkusExtensionDeploymentPlugin implements Plugin<Project> {

    public static final String PLUGIN_ID = ExtensionConstants.EXTENSION_DEPLOYMENT_PLUGIN_ID;
    public static final String MARKER_ELEMENTS_CONFIGURATION_NAME = ExtensionConstants.EXTENSION_DEPLOYMENT_MARKER_ELEMENTS_CONFIGURATION_NAME;
    public static final String MARKER_TASK_NAME = ExtensionConstants.EXTENSION_DEPLOYMENT_MARKER_TASK_NAME;
    public static final String MARKER_CATEGORY = ExtensionConstants.EXTENSION_DEPLOYMENT_MARKER_CATEGORY;

    @Override
    public void apply(Project project) {
        GradleVersionSupport.requireMinimumGradleVersion();

        project.getPluginManager().apply(JavaPlugin.class);
        ApplicationDeploymentClasspathBuilder.initConfigurations(project);
        new AnnotationProcessorDependencyConfigurator().configure(project);
        registerTestApplicationModel(project);
        registerMarkerVariant(project);
    }

    private void registerMarkerVariant(Project project) {
        TaskProvider<QuarkusExtensionDeploymentMarkerTask> markerTask = project.getTasks().register(MARKER_TASK_NAME,
                QuarkusExtensionDeploymentMarkerTask.class, task -> task.getMarkerFile()
                        .convention(project.getLayout().getBuildDirectory()
                                .file("quarkus/extension-deployment-marker/" + PLUGIN_ID)));

        Configuration markerElements = project.getConfigurations().create(MARKER_ELEMENTS_CONFIGURATION_NAME);
        markerElements.setCanBeConsumed(true);
        markerElements.setCanBeResolved(false);
        markerElements.setDescription("Marker variant identifying this project as a Quarkus extension deployment module.");
        markerElements.getAttributes().attribute(Category.CATEGORY_ATTRIBUTE,
                project.getObjects().named(Category.class, MARKER_CATEGORY));
        markerElements.getAttributes().attribute(ExtensionConstants.EXTENSION_DEPLOYMENT_ATTRIBUTE, true);
        markerElements.getOutgoing().artifact(markerTask.flatMap(QuarkusExtensionDeploymentMarkerTask::getMarkerFile));
    }

    private void registerTestApplicationModel(Project project) {
        Provider<DefaultProjectDescriptor> projectDescriptor = ProjectDescriptorBuilder.buildForApp(project);
        ApplicationDeploymentClasspathBuilder testClasspath = new ApplicationDeploymentClasspathBuilder(project,
                LaunchMode.TEST);
        DependencyDataCollector dependencyDataCollector = new DependencyDataCollector(project.getDependencies(),
                project.getProviders());

        TaskProvider<GenerateApplicationModelTask> generateTestAppModelTask = ApplicationModelTaskConfigurator
                .registerGenerateApplicationModelTask(project, projectDescriptor, testClasspath, dependencyDataCollector,
                        LaunchMode.TEST);

        project.getTasks().withType(Test.class).configureEach(test -> {
            test.useJUnitPlatform();
            test.dependsOn(generateTestAppModelTask);
            SerializedTestApplicationModelArgumentProvider argumentProvider = project.getObjects()
                    .newInstance(SerializedTestApplicationModelArgumentProvider.class);
            argumentProvider.getApplicationModel()
                    .set(generateTestAppModelTask.flatMap(GenerateApplicationModelTask::getApplicationModel));
            test.getJvmArgumentProviders().add(argumentProvider);
        });
    }
}
