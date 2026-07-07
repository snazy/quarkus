package io.quarkus.gradle.application.internal.image;

import java.util.Optional;

import io.quarkus.gradle.application.model.QuarkusApplicationImageBuilder;

public final class AotEnhancedContainerImageResultFactory {

    public static final String RESULT_TYPE = "aot-container-image";

    public BuiltContainerImage image(BuiltContainerImage baseImage, Optional<QuarkusApplicationImageBuilder> builder,
            boolean pushed, String enhancedImageReference) {
        return new BuiltContainerImage(
                RESULT_TYPE,
                builder,
                pushed,
                Optional.of(enhancedImageReference),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                baseImage.workingDirectory(),
                baseImage.outputDirectory());
    }
}
