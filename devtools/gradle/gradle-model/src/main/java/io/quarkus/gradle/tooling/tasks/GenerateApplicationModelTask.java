package io.quarkus.gradle.tooling.tasks;

import javax.inject.Inject;

import io.quarkus.gradle.tasks.QuarkusApplicationModelTask;
import io.quarkus.runtime.LaunchMode;

/**
 * Shared task type for Gradle plugins that need to serialize a Quarkus application model for a specific launch mode.
 */
public abstract class GenerateApplicationModelTask extends QuarkusApplicationModelTask {

    @Inject
    public GenerateApplicationModelTask(LaunchMode launchMode) {
        getLaunchMode().set(launchMode);
        getApplicationModel().convention(getLaunchMode()
                .flatMap(mode -> getProject().getLayout().getBuildDirectory()
                        .file(ApplicationModelTaskConfigurator.applicationModelPath(mode, false))));
    }

    public static String taskName(LaunchMode launchMode) {
        return switch (launchMode) {
            case NORMAL -> "quarkusGenerateAppModel";
            case DEVELOPMENT -> "quarkusGenerateDevAppModel";
            case TEST -> "quarkusGenerateTestAppModel";
            default -> throw new IllegalArgumentException("Unsupported launch mode " + launchMode);
        };
    }
}
