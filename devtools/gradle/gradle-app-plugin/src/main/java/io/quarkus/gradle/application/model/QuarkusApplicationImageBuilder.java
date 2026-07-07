package io.quarkus.gradle.application.model;

public enum QuarkusApplicationImageBuilder {
    JIB("jib"),
    DOCKER("docker"),
    PODMAN("podman"),
    OPENSHIFT("openshift"),
    BUILDPACK("buildpack");

    private final String quarkusBuilderName;

    QuarkusApplicationImageBuilder(String quarkusBuilderName) {
        this.quarkusBuilderName = quarkusBuilderName;
    }

    public String quarkusBuilderName() {
        return quarkusBuilderName;
    }
}
