package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;

public abstract class QuarkusKnativeDeployment extends QuarkusApplicationDeployment {

    @Inject
    public QuarkusKnativeDeployment(String name) {
        super(name, QuarkusApplicationDeploymentTarget.KNATIVE);
    }
}
