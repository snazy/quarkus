package io.quarkus.gradle.application.internal.execution;

import java.nio.file.Path;
import java.util.Optional;

import io.quarkus.gradle.application.internal.image.BuiltContainerImage;
import io.quarkus.gradle.application.model.QuarkusApplicationImageBuilder;

public record AotEnhancedImageRequest(
        BuildRequest build,
        ImageOperation operation,
        BuiltContainerImage baseImage,
        Path baseImageReceiptFile,
        Path aotFile,
        String enhancedImageReference,
        Optional<QuarkusApplicationImageBuilder> builder,
        Path receiptFile) {

    public AotEnhancedImageRequest {
        if (build == null) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires a build request");
        }
        if (operation == null) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires an operation");
        }
        if (baseImage == null) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires a base image");
        }
        if (baseImage.reference().isEmpty()) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires a base image reference");
        }
        if (baseImage.workingDirectory().isEmpty()) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires a base image working directory");
        }
        if (baseImageReceiptFile == null) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires a base image receipt file");
        }
        if (aotFile == null) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires an AOT file");
        }
        if (enhancedImageReference == null || enhancedImageReference.isBlank()) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires an enhanced image reference");
        }
        builder = builder == null ? Optional.empty() : builder;
        if (receiptFile == null) {
            throw new IllegalArgumentException("Quarkus application AOT image request requires a receipt file");
        }
    }
}
