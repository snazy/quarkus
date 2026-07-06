package io.quarkus.gradle.tooling.dependency;

import java.io.Serial;
import java.io.Serializable;

import io.quarkus.maven.dependency.ArtifactCoords;

public class DeclaredDependency implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String groupId;
    private final String artifactId;
    private final String classifier;
    private final String type;
    private final String version;
    private final String scope;
    private final boolean optional;

    DeclaredDependency(org.apache.maven.model.Dependency dep) {
        this.groupId = dep.getGroupId();
        this.artifactId = dep.getArtifactId();
        this.classifier = DependencyDataCollector.defaultIfNull(dep.getClassifier(), ArtifactCoords.DEFAULT_CLASSIFIER);
        this.type = DependencyDataCollector.defaultIfNull(dep.getType(), ArtifactCoords.TYPE_JAR);
        this.version = dep.getVersion();
        this.scope = DependencyDataCollector.defaultIfNull(dep.getScope(),
                io.quarkus.maven.dependency.Dependency.SCOPE_COMPILE);
        this.optional = Boolean.parseBoolean(dep.getOptional());
    }

    DeclaredDependency(String groupId, String artifactId, String version,
            String classifier, String type, String scope, boolean optional) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.classifier = classifier;
        this.type = type;
        this.scope = scope;
        this.optional = optional;
    }

    String getGroupId() {
        return groupId;
    }

    String getArtifactId() {
        return artifactId;
    }

    String getClassifier() {
        return classifier;
    }

    String getType() {
        return type;
    }

    String getVersion() {
        return version;
    }

    String getScope() {
        return scope;
    }

    boolean isOptional() {
        return optional;
    }
}
