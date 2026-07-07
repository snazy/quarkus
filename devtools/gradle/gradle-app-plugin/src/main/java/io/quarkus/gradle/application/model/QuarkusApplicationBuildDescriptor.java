package io.quarkus.gradle.application.model;

public record QuarkusApplicationBuildDescriptor(String name, QuarkusApplicationBuildType type) {

    public QuarkusApplicationBuildDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Quarkus application build descriptor requires a name");
        }
        if (type == null) {
            throw new IllegalArgumentException("Quarkus application build descriptor requires a type");
        }
    }

    public static QuarkusApplicationBuildDescriptor of(String name, QuarkusApplicationBuildType type) {
        return new QuarkusApplicationBuildDescriptor(name, type);
    }
}
