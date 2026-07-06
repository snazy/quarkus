package io.quarkus.gradle.tasks;

import static io.quarkus.gradle.QuarkusPlugin.IMAGE_CHECK_REQUIREMENTS_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import io.quarkus.gradle.testing.BaseGradleTest;

public class ImageCheckRequirementsTaskTest extends BaseGradleTest {

    @Test
    public void shouldRestoreOutputFromBuildCache() throws IOException, URISyntaxException {
        prepareGradleBuildProject("""
                implementation("io.quarkus:quarkus-container-image-docker")
                """);

        BuildResult firstRun = buildResult("clean", IMAGE_CHECK_REQUIREMENTS_NAME);
        assertThat(firstRun.task(":" + IMAGE_CHECK_REQUIREMENTS_NAME).getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(builderName()).isEqualTo("docker");

        Files.deleteIfExists(builderNamePath());

        BuildResult secondRun = buildResult("clean", IMAGE_CHECK_REQUIREMENTS_NAME);
        assertThat(secondRun.task(":" + IMAGE_CHECK_REQUIREMENTS_NAME).getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
        assertThat(builderName()).isEqualTo("docker");
    }

    @Test
    public void shouldUseContainerImageBuilderSystemPropertyAsTaskInput() throws IOException, URISyntaxException {
        prepareGradleBuildProject("""
                implementation("io.quarkus:quarkus-container-image-docker")
                implementation("io.quarkus:quarkus-container-image-jib")
                """);

        BuildResult firstRun = buildResult(IMAGE_CHECK_REQUIREMENTS_NAME, "-Dquarkus.container-image.builder=docker");
        assertThat(firstRun.task(":" + IMAGE_CHECK_REQUIREMENTS_NAME).getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(builderName()).isEqualTo("docker");

        BuildResult secondRun = buildResult(IMAGE_CHECK_REQUIREMENTS_NAME, "-Dquarkus.container-image.builder=jib");
        assertThat(secondRun.task(":" + IMAGE_CHECK_REQUIREMENTS_NAME).getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(builderName()).isEqualTo("jib");
    }

    private void prepareGradleBuildProject(String additionalDependencies) throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        Path buildFile = testProjectDir.resolve("build.gradle.kts");
        String buildScript = Files.readString(buildFile);
        buildScript = buildScript.replace("implementation(\"jakarta.inject:jakarta.inject-api:2.0.1\")",
                "implementation(\"jakarta.inject:jakarta.inject-api:2.0.1\")\n" + additionalDependencies);
        Files.writeString(buildFile, buildScript);
    }

    private String builderName() throws IOException {
        return Files.readString(builderNamePath());
    }

    private Path builderNamePath() {
        return testProjectDir.resolve("build/quarkus/image-name");
    }
}
