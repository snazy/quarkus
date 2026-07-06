package io.quarkus.gradle.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AdditionalForcedPropertiesTest {

    @Test
    void nativeArgumentsShouldBeNormalizedAndOverriddenByTaskProperties() {
        Map<String, String> properties = AdditionalForcedProperties.of(
                Map.of(
                        "containerBuild", "true",
                        "quarkus.native.builderImage", "builder-from-native-args"),
                Map.of(
                        "quarkus.native.builder-image", "builder-from-task",
                        "quarkus.container-image.build", "true"));

        assertThat(properties).containsOnly(
                Map.entry("quarkus.native.container-build", "true"),
                Map.entry("quarkus.native.builder-image", "builder-from-task"),
                Map.entry("quarkus.container-image.build", "true"));
    }
}
