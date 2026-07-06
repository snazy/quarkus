package io.quarkus.gradle.tasks.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Map;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class ForcedPropertieBuildServiceTest {

    @Test
    void propertiesShouldBeReturnedAsImmutableSnapshots() {
        ForcedPropertieBuildService service = createService();

        service.put("quarkus.native.enabled", "true");
        Map<String, String> properties = service.getProperties();

        service.put("quarkus.container-image.build", "true");

        assertThat(properties).containsOnly(Map.entry("quarkus.native.enabled", "true"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> properties.put("quarkus.container-image.push", "true"));
        assertThat(service.getProperties()).containsOnly(
                Map.entry("quarkus.native.enabled", "true"),
                Map.entry("quarkus.container-image.build", "true"));
    }

    private static ForcedPropertieBuildService createService() {
        Project project = ProjectBuilder.builder().build();
        return project.getGradle().getSharedServices()
                .registerIfAbsent("forcedPropertiesService-test", ForcedPropertieBuildService.class, spec -> {
                })
                .get();
    }
}
