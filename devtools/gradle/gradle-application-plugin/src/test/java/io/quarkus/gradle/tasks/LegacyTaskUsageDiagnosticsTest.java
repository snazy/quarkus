package io.quarkus.gradle.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;

import io.quarkus.gradle.extension.QuarkusDiagnostics;
import io.quarkus.gradle.testing.BaseGradleTest;

class LegacyTaskUsageDiagnosticsTest extends BaseGradleTest {

    @Test
    void doesNotReportLegacyTaskUsageByDefault() throws IOException {
        writeMinimalBuild();

        buildResult("quarkusBuild", List.of("--dry-run", "--no-configuration-cache"));

        assertThat(report()).doesNotExist();
    }

    @Test
    void warnsAndWritesReportForDirectLegacyTaskUsage() throws IOException {
        writeMinimalBuild();

        BuildResult result = buildResult("quarkusBuild",
                List.of("--dry-run", "--no-configuration-cache", "-P" + QuarkusDiagnostics.LEGACY_TASK_USAGE_PROPERTY
                        + "=warn"));

        assertThat(result.getOutput())
                .contains("Legacy Quarkus Gradle application task usage detected")
                .contains("quarkusBuild");
        assertThat(Files.readString(report()))
                .contains("Legacy Quarkus Gradle application task usage detected")
                .contains("- quarkusBuild")
                .contains("Apply io.quarkus.application, register an explicit named output under quarkusApplication.builds");
    }

    @Test
    void warnsAndWritesReportForTransitiveLegacyTaskUsage() throws IOException {
        writeMinimalBuild();

        BuildResult result = buildResult("build",
                List.of("--dry-run", "--no-configuration-cache", "-P" + QuarkusDiagnostics.LEGACY_TASK_USAGE_PROPERTY
                        + "=warn"));

        assertThat(result.getOutput()).contains("quarkusBuild");
        assertThat(Files.readString(report())).contains("- quarkusBuild");
    }

    @Test
    void failLevelFailsAndWritesReport() throws IOException {
        writeMinimalBuild();

        BuildResult result = buildAndFailResult("quarkusBuild", "--dry-run", "--no-configuration-cache",
                "-P" + QuarkusDiagnostics.LEGACY_TASK_USAGE_PROPERTY + "=fail");

        assertThat(result.getOutput())
                .contains("Legacy Quarkus Gradle application task usage detected")
                .contains("quarkusBuild");
        assertThat(Files.readString(report())).contains("- quarkusBuild");
    }

    private void writeMinimalBuild() throws IOException {
        writeFile("settings.gradle", "rootProject.name = 'legacy-task-diagnostics'\n");
        writeFile("build.gradle", """
                plugins {
                    id 'io.quarkus'
                }
                """);
    }

    private Path report() {
        return testProjectDir.resolve("build").resolve(LegacyTaskUsageDiagnostics.REPORT_PATH);
    }
}
