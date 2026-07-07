package io.quarkus.gradle.application.internal.planning;

public record AotEnhancedImagePlan(String aotFile, String baseReference, String enhancedReference,
        boolean hasProducer) {
}
