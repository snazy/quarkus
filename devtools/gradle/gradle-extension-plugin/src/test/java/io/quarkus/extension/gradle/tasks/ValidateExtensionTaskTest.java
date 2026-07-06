package io.quarkus.extension.gradle.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import io.quarkus.extension.gradle.QuarkusExtensionPlugin;
import io.quarkus.extension.gradle.TestUtils;
import io.quarkus.gradle.testing.BaseGradleTest;

public class ValidateExtensionTaskTest extends BaseGradleTest {

    @Test
    public void shouldValidateExtensionDependencies() throws IOException {
        TestUtils.createExtensionProjectWithLocalDeployment(testProjectDir, false, List.of("io.quarkus:quarkus-jdbc-h2"),
                List.of("io.quarkus:quarkus-jdbc-h2-deployment"));

        BuildResult validationResult = buildResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME);

        assertThat(validationResult.task(":runtime:" + QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME).getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    public void shouldDetectMissingExtensionDependency() throws IOException {
        TestUtils.createExtensionProjectWithLocalDeployment(testProjectDir, false, List.of("io.quarkus:quarkus-jdbc-h2"),
                List.of());

        BuildResult validationResult = buildAndFailResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME);

        assertThat(validationResult.task(":runtime:" + QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME).getOutcome())
                .isEqualTo(TaskOutcome.FAILED);
        assertThat(validationResult.getOutput()).contains("Quarkus Extension Dependency Verification Error");
        assertThat(validationResult.getOutput())
                .contains("The following deployment artifact(s) were found to be missing in the deployment module:");
        assertThat(validationResult.getOutput()).contains("- io.quarkus:quarkus-jdbc-h2-deployment");
    }

    @Test
    public void shouldDetectInvalidRuntimeDependency() throws IOException {
        TestUtils.createExtensionProjectWithLocalDeployment(testProjectDir, false,
                List.of("io.quarkus:quarkus-core", "io.quarkus:quarkus-core-deployment"), List.of());

        BuildResult validationResult = buildAndFailResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME);

        assertThat(validationResult.task(":runtime:" + QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME).getOutcome())
                .isEqualTo(TaskOutcome.FAILED);
        assertThat(validationResult.getOutput()).contains("Quarkus Extension Dependency Verification Error");
        assertThat(validationResult.getOutput())
                .contains("The following deployment artifact(s) appear on the runtime classpath:");
        assertThat(validationResult.getOutput()).contains("- io.quarkus:quarkus-core-deployment");
    }

    @Test
    public void shouldSkipValidationWhenDisabled() throws IOException {
        TestUtils.createExtensionProjectWithLocalDeployment(testProjectDir, true,
                List.of("io.quarkus:quarkus-core", "io.quarkus:quarkus-core-deployment"), List.of());

        BuildResult validationResult = buildResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME);

        assertThat(validationResult.task(":runtime:" + QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME).getOutcome())
                .isEqualTo(TaskOutcome.SKIPPED);
    }

    @Test
    public void shouldValidateExtensionWithParallelExecution() throws IOException {
        TestUtils.createExtensionProjectWithLocalDeployment(testProjectDir, false, List.of("io.quarkus:quarkus-jdbc-h2"),
                List.of("io.quarkus:quarkus-jdbc-h2-deployment"));

        BuildResult validationResult = buildResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME, "--parallel");

        assertThat(validationResult.task(":runtime:" + QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME).getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);

        // Verify no unsafe configuration resolution errors in output
        assertThat(validationResult.getOutput())
                .doesNotContain("was attempted without an exclusive lock");
    }

    @Test
    public void shouldValidateExtensionWithoutConfigurationCacheSerializationProblems() throws IOException {
        TestUtils.createExtensionProjectWithLocalDeployment(testProjectDir, false, List.of("io.quarkus:quarkus-jdbc-h2"),
                List.of("io.quarkus:quarkus-jdbc-h2-deployment"));

        BuildResult firstValidationResult = buildResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME);
        BuildResult secondValidationResult = buildResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME);

        assertThat(firstValidationResult.task(":runtime:" + QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME).getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(firstValidationResult.getOutput())
                .contains("Configuration cache entry stored")
                .doesNotContain("cannot serialize object of type")
                .doesNotContain("DefaultProject");
        assertThat(secondValidationResult.getOutput())
                .contains("Reusing configuration cache.")
                .doesNotContain("cannot serialize object of type")
                .doesNotContain("DefaultProject");
    }

    @Test
    public void shouldDetectInvalidRuntimeDependencyWithParallelExecution() throws IOException {
        TestUtils.createExtensionProjectWithLocalDeployment(testProjectDir, false,
                List.of("io.quarkus:quarkus-jdbc-h2", "io.quarkus:quarkus-jdbc-h2-deployment"),
                List.of());

        BuildResult validationResult = buildAndFailResult(QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME, "--parallel");

        assertThat(validationResult.task(":runtime:" + QuarkusExtensionPlugin.VALIDATE_EXTENSION_TASK_NAME).getOutcome())
                .isEqualTo(TaskOutcome.FAILED);
        assertThat(validationResult.getOutput()).contains("Quarkus Extension Dependency Verification Error");
        assertThat(validationResult.getOutput())
                .contains("The following deployment artifact(s) appear on the runtime classpath:");
        assertThat(validationResult.getOutput()).contains("- io.quarkus:quarkus-core-deployment");
    }
}
