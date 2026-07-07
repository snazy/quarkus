package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;

public abstract class QuarkusKindDeployment extends QuarkusApplicationDeployment {

    @Inject
    public QuarkusKindDeployment(String name) {
        super(name, QuarkusApplicationDeploymentTarget.KIND);
    }
}
