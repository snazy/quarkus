package io.quarkus.gradle.application.internal.modelgen;

record DeploymentDependencySpec(String kind, String value) implements Comparable<DeploymentDependencySpec> {

    private static final String EXTERNAL = "external";
    private static final String PROJECT = "project";
    private static final String SEPARATOR = "\t";

    static DeploymentDependencySpec external(String dependencyNotation) {
        return new DeploymentDependencySpec(EXTERNAL, dependencyNotation);
    }

    static DeploymentDependencySpec project(String projectPath) {
        return new DeploymentDependencySpec(PROJECT, projectPath);
    }

    boolean external() {
        return EXTERNAL.equals(kind);
    }

    boolean project() {
        return PROJECT.equals(kind);
    }

    String serialize() {
        return kind + SEPARATOR + value;
    }

    static DeploymentDependencySpec deserialize(String value) {
        String[] parts = value.split(SEPARATOR, -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid deployment dependency spec: " + value);
        }
        DeploymentDependencySpec spec = new DeploymentDependencySpec(parts[0], parts[1]);
        if (!spec.external() && !spec.project()) {
            throw new IllegalArgumentException("Unsupported deployment dependency spec kind: " + parts[0]);
        }
        return spec;
    }

    @Override
    public int compareTo(DeploymentDependencySpec other) {
        int kindComparison = kind.compareTo(other.kind);
        if (kindComparison != 0) {
            return kindComparison;
        }
        return value.compareTo(other.value);
    }
}
