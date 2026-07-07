package io.quarkus.gradle.application.model;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

public record QuarkusApplicationDeploymentDescriptor(String name,
        QuarkusApplicationDeploymentTarget target, QuarkusApplicationDeploymentImageSource imageSource,
        Optional<String> imageReference) {

    public QuarkusApplicationDeploymentDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Quarkus application deployment requires a name");
        }
        if (target == null) {
            throw new IllegalArgumentException("Quarkus application deployment requires a target");
        }
        if (imageSource == null) {
            throw new IllegalArgumentException("Quarkus application deployment requires an image source");
        }
        requireNonNull(imageReference, "imageReference");
        if (imageSource == QuarkusApplicationDeploymentImageSource.EXISTING_IMAGE && imageReference.isEmpty()) {
            throw new IllegalArgumentException("Existing-image deployments require an image reference");
        }
    }

    public static QuarkusApplicationDeploymentDescriptor of(String name, QuarkusApplicationDeploymentTarget target) {
        return new QuarkusApplicationDeploymentDescriptor(name, target,
                QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH, Optional.empty());
    }
}
