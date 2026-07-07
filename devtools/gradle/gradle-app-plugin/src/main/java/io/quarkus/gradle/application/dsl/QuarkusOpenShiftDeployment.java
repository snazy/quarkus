package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;

public abstract class QuarkusOpenShiftDeployment extends QuarkusApplicationDeployment {

    @Inject
    public QuarkusOpenShiftDeployment(String name) {
        super(name, QuarkusApplicationDeploymentTarget.OPENSHIFT);
    }
}
