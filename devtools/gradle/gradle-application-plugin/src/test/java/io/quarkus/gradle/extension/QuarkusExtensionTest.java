package io.quarkus.gradle.extension;

import static io.quarkus.gradle.QuarkusPlugin.EXTENSION_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

public class QuarkusExtensionTest {
    @Test
    public void extensionInstantiates() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        QuarkusPluginExtension extension = project.getExtensions().create(EXTENSION_NAME, QuarkusPluginExtension.class,
                project);
    }

    @Test
    void prefixesBuildProperty() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");
        QuarkusPluginExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, QuarkusPluginExtension.class, project);

        extension.set("test.args", "value");

        assertThat(extension.getQuarkusBuildProperties().get()).containsExactly(entry("quarkus.test.args", "value"));
    }

    @Test
    void diagnosticsDefaultToOffAndCanBeConfigured() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");
        QuarkusPluginExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, QuarkusPluginExtension.class, project);

        assertThat(extension.getDiagnostics().getLegacyTaskUsage().get()).isEqualTo(QuarkusLegacyTaskUsageLevel.OFF);

        extension.diagnostics(diagnostics -> diagnostics.getLegacyTaskUsage().set(QuarkusLegacyTaskUsageLevel.WARN));

        assertThat(extension.getDiagnostics().getLegacyTaskUsage().get()).isEqualTo(QuarkusLegacyTaskUsageLevel.WARN);
    }
}
