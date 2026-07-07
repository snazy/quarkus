package io.quarkus.gradle.application.model;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

public record QuarkusApplicationAotEnhancedImageDescriptor(String aotFile, Optional<String> producer,
        Optional<String> repository, Optional<String> tag, Optional<String> imageReference, String imageSuffix) {

    public QuarkusApplicationAotEnhancedImageDescriptor {
        if (aotFile == null || aotFile.isBlank()) {
            throw new IllegalArgumentException("AOT-enhanced image requires an AOT file");
        }
        requireNonNull(producer, "producer");
        requireNonNull(repository, "repository");
        requireNonNull(tag, "tag");
        requireNonNull(imageReference, "imageReference");
        imageSuffix = imageSuffix == null ? "-aot" : imageSuffix;
        if (imageReference.isPresent() && (repository.isPresent() || tag.isPresent())) {
            throw new IllegalArgumentException("AOT-enhanced image reference cannot be combined with repository or tag");
        }
    }

    public static QuarkusApplicationAotEnhancedImageDescriptor producedBy(String aotFile, String producer) {
        return new QuarkusApplicationAotEnhancedImageDescriptor(aotFile, Optional.of(producer), Optional.empty(),
                Optional.empty(), Optional.empty(), "-aot");
    }

    public static QuarkusApplicationAotEnhancedImageDescriptor aotFileFrom(String producer, String aotFile) {
        return producedBy(aotFile, producer);
    }
}
