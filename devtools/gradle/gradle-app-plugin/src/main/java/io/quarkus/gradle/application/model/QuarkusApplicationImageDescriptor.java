package io.quarkus.gradle.application.model;

import java.util.Optional;

public record QuarkusApplicationImageDescriptor(String repository, String tag, QuarkusApplicationImageBuilder builder) {

    public QuarkusApplicationImageDescriptor {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("Image repository must not be empty");
        }
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("Image tag must not be empty");
        }
    }

    public String effectiveReference() {
        return repository + ":" + tag;
    }

    public Optional<QuarkusApplicationImageBuilder> optionalBuilder() {
        return Optional.ofNullable(builder);
    }
}
