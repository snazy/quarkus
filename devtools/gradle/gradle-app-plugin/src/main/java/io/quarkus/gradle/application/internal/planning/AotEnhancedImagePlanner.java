package io.quarkus.gradle.application.internal.planning;

import io.quarkus.gradle.application.model.QuarkusApplicationAotEnhancedImageDescriptor;
import io.quarkus.gradle.application.model.QuarkusApplicationImageDescriptor;

public final class AotEnhancedImagePlanner {

    public AotEnhancedImagePlan plan(QuarkusApplicationImageDescriptor image,
            QuarkusApplicationAotEnhancedImageDescriptor aotEnhanced) {
        String baseReference = image.effectiveReference();
        return new AotEnhancedImagePlan(
                aotEnhanced.aotFile(),
                baseReference,
                enhancedReference(image, aotEnhanced),
                aotEnhanced.producer().isPresent());
    }

    private static String enhancedReference(QuarkusApplicationImageDescriptor image,
            QuarkusApplicationAotEnhancedImageDescriptor aotEnhanced) {
        return aotEnhanced.imageReference()
                .orElseGet(() -> {
                    String repository = aotEnhanced.repository().orElse(image.repository());
                    String tag = aotEnhanced.tag().orElse(image.tag() + aotEnhanced.imageSuffix());
                    return repository + ":" + tag;
                });
    }
}
