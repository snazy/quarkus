package io.quarkus.gradle.application.internal.config;

import java.util.Map;

public record EffectiveConfigPlan(
        Map<String, String> fullValues,
        Map<String, String> quarkusWorkerValues,
        Map<String, String> buildSystemProperties,
        Map<String, String> descriptorShapeValues) {

    public EffectiveConfigPlan {
        fullValues = Map.copyOf(fullValues);
        quarkusWorkerValues = Map.copyOf(quarkusWorkerValues);
        buildSystemProperties = Map.copyOf(buildSystemProperties);
        descriptorShapeValues = Map.copyOf(descriptorShapeValues);
    }
}
