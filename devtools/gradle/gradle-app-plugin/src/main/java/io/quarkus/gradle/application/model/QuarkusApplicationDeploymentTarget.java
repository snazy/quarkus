package io.quarkus.gradle.application.model;

public enum QuarkusApplicationDeploymentTarget {
    KUBERNETES("kubernetes"),
    OPENSHIFT("openshift"),
    KNATIVE("knative"),
    KIND("kind"),
    MINIKUBE("minikube");

    private final String quarkusDeployTarget;

    QuarkusApplicationDeploymentTarget(String quarkusDeployTarget) {
        this.quarkusDeployTarget = quarkusDeployTarget;
    }

    public String quarkusDeployTarget() {
        return quarkusDeployTarget;
    }
}
