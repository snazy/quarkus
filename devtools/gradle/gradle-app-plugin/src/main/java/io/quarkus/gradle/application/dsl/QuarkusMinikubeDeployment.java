package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;

public abstract class QuarkusMinikubeDeployment extends QuarkusApplicationDeployment {

    @Inject
    public QuarkusMinikubeDeployment(String name) {
        super(name, QuarkusApplicationDeploymentTarget.MINIKUBE);
    }
}
