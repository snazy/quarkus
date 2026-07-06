package io.quarkus.gradle.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Collection;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.runtime.LaunchMode;

class ApplicationDeploymentClasspathBuilderTest {

    @Test
    void platformImportsShouldBeSharedByBuildersForTheSameProjectAndMode() {
        Project project = createProject();

        ApplicationDeploymentClasspathBuilder firstBuilder = createBuilder(project);
        ApplicationDeploymentClasspathBuilder secondBuilder = createBuilder(project);

        PlatformImports firstPlatformImports = firstBuilder.getPlatformImports();
        PlatformImports secondPlatformImports = secondBuilder.getPlatformImports();

        assertThat(firstPlatformImports).isSameAs(secondPlatformImports);

        Collection<ArtifactCoords> importedPlatformBoms = firstPlatformImports.getImportedPlatformBoms();

        addPlatformDescriptor(project);

        assertThat(importedPlatformBoms).isEmpty();
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(importedPlatformBoms::clear);
        assertThat(firstPlatformImports.getImportedPlatformBoms())
                .extracting(Object::toString)
                .containsExactly("io.quarkus.platform:first::pom:1.0.0");
        assertThat(secondPlatformImports.getImportedPlatformBoms())
                .extracting(Object::toString)
                .containsExactly("io.quarkus.platform:first::pom:1.0.0");
    }

    @Test
    void platformImportsShouldNotBeSharedBetweenProjects() {
        Project firstProject = createProject();
        Project secondProject = createProject();
        ApplicationDeploymentClasspathBuilder firstBuilder = createBuilder(firstProject);
        ApplicationDeploymentClasspathBuilder secondBuilder = createBuilder(secondProject);

        PlatformImports firstPlatformImports = firstBuilder.getPlatformImports();
        PlatformImports secondPlatformImports = secondBuilder.getPlatformImports();

        assertThat(firstPlatformImports).isNotSameAs(secondPlatformImports);

        addPlatformDescriptor(firstProject);

        assertThat(firstPlatformImports.getImportedPlatformBoms())
                .extracting(Object::toString)
                .containsExactly("io.quarkus.platform:first::pom:1.0.0");
        assertThat(secondPlatformImports.getImportedPlatformBoms()).isEmpty();
    }

    @Test
    void platformImportsShouldBeSerializableForToolingModels() throws Exception {
        Project project = createProject();
        ApplicationDeploymentClasspathBuilder builder = createBuilder(project);

        PlatformImports platformImports = builder.getPlatformImports();
        addPlatformDescriptor(project);

        try (var bytes = new ByteArrayOutputStream();
                var output = new ObjectOutputStream(bytes)) {
            output.writeObject(platformImports);
        }
    }

    private static Project createProject() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply(JavaPlugin.class);
        ApplicationDeploymentClasspathBuilder.initConfigurations(project);
        return project;
    }

    private static ApplicationDeploymentClasspathBuilder createBuilder(Project project) {
        return new ApplicationDeploymentClasspathBuilder(project, LaunchMode.NORMAL);
    }

    private static void addPlatformDescriptor(Project project) {
        project.getGradle().getSharedServices()
                .registerIfAbsent(PlatformImportsBuildService.NAME, PlatformImportsBuildService.class, spec -> {
                })
                .get()
                .addPlatformDescriptor(PlatformImportsBuildService.key(project.getPath(), platformConfigurationName()),
                        "io.quarkus.platform", "first-quarkus-platform-descriptor", null, "json", "1.0.0");
    }

    private static String platformConfigurationName() {
        return ToolingUtils.toPlatformConfigurationName(
                ApplicationDeploymentClasspathBuilder.getFinalRuntimeConfigName(LaunchMode.NORMAL));
    }
}
