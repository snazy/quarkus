package io.quarkus.gradle.application;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

import io.quarkus.gradle.application.dsl.QuarkusApplicationExtension;

public final class QuarkusApplicationPlugin implements Plugin<Project> {

    public static final String ID = "io.quarkus.application";
    static final String EXTENSION_NAME = "quarkusApplication";
    private static final String LEGACY_PLUGIN_ID = "io.quarkus";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        QuarkusApplicationExtension extension = project.getExtensions().create(EXTENSION_NAME,
                QuarkusApplicationExtension.class, project.getObjects(), project.getProviders(), project.getLayout(),
                project.getName(), project.provider(() -> project.getVersion().toString()));
        new TaskRegistration().register(project, extension);
        project.getPlugins().withId(LEGACY_PLUGIN_ID, ignored -> project.getLogger().warn(
                "Both 'io.quarkus.application' and legacy 'io.quarkus' are applied to this project. "
                        + "This is supported as migration mode, but legacy tasks do not inherit the new plugin's "
                        + "Gradle configuration-cache and isolated-project compatibility guarantees."));
    }
}
