package io.quarkus.gradle.tasks.devmode;

import io.quarkus.deployment.dev.QuarkusDevModeLauncher;
import io.quarkus.gradle.tasks.QuarkusTask;

public class DevProjectState {

    private final QuarkusDevModeLauncher runner;

    public DevProjectState(QuarkusTask task, QuarkusDevModeLauncher runner) {
        this.runner = runner;
    }

    public QuarkusDevModeLauncher getRunner() {
        return runner;
    }
}
