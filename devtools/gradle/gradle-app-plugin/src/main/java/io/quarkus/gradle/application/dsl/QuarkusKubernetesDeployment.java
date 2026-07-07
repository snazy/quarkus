package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;

public abstract class QuarkusKubernetesDeployment extends QuarkusApplicationDeployment {

    @Inject
    public QuarkusKubernetesDeployment(String name) {
        super(name, QuarkusApplicationDeploymentTarget.KUBERNETES);
    }
}
