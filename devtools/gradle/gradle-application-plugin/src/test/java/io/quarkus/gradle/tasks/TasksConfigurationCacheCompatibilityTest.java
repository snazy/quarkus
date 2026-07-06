package io.quarkus.gradle.tasks;

import static io.quarkus.gradle.QuarkusPlugin.DEPLOY_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_BUILD_APP_PARTS_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_BUILD_DEP_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_BUILD_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_GENERATE_CODE_DEV_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_GENERATE_CODE_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_GENERATE_CODE_TESTS_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_GO_OFFLINE_TASK_NAME;
import static io.quarkus.gradle.QuarkusPlugin.QUARKUS_SHOW_EFFECTIVE_CONFIG_TASK_NAME;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.quarkus.gradle.testing.BaseGradleTest;
import io.quarkus.gradle.tooling.tasks.GenerateApplicationModelTask;
import io.quarkus.runtime.LaunchMode;

public class TasksConfigurationCacheCompatibilityTest extends BaseGradleTest {

    private static Stream<String> compatibleTasks() {
        return Stream.of(
                QUARKUS_GENERATE_CODE_TASK_NAME,
                QUARKUS_GENERATE_CODE_TESTS_TASK_NAME,
                QUARKUS_GENERATE_CODE_DEV_TASK_NAME,
                QUARKUS_BUILD_DEP_TASK_NAME,
                QUARKUS_BUILD_APP_PARTS_TASK_NAME,
                QUARKUS_SHOW_EFFECTIVE_CONFIG_TASK_NAME,
                QUARKUS_BUILD_TASK_NAME,
                QUARKUS_GO_OFFLINE_TASK_NAME,
                "build");
    }

    private static Stream<String> nonCompatibleQuarkusBuildTasks() {
        return Stream.of(DEPLOY_TASK_NAME);
    }

    @ParameterizedTest
    @MethodSource("compatibleTasks")
    public void configurationCacheIsReusedTest(String taskName) throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        buildResult(":help", "--configuration-cache");

        BuildResult firstBuild = buildResult(taskName, "--configuration-cache");
        assertTrue(firstBuild.getOutput().contains("Configuration cache entry stored"));

        BuildResult secondBuild = buildResult(taskName, "--configuration-cache");
        assertTrue(secondBuild.getOutput().contains("Reusing configuration cache."));
    }

    @ParameterizedTest
    @MethodSource("compatibleTasks")
    public void configurationCacheIsReusedWhenProjectIsolationIsUsedTest(String taskName)
            throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        buildResult(":help", "--configuration-cache");

        BuildResult firstBuild = buildResult(taskName, "-Dorg.gradle.unsafe.isolated-projects=true");
        assertTrue(firstBuild.getOutput().contains("Configuration cache entry stored"));

        BuildResult secondBuild = buildResult(taskName, "-Dorg.gradle.unsafe.isolated-projects=true");
        assertTrue(secondBuild.getOutput().contains("Reusing configuration cache."));
    }

    @ParameterizedTest
    @MethodSource("nonCompatibleQuarkusBuildTasks")
    public void quarkusBuildTasksNonCompatibleWithConfigurationCacheNotFail(String taskName)
            throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        BuildResult build = buildResult(taskName, "--no-configuration-cache");
        assertTrue(build.getOutput().contains("BUILD SUCCESSFUL"));

    }

    @ParameterizedTest
    @MethodSource("nonCompatibleQuarkusBuildTasks")
    public void quarkusBuildTasksNonCompatibleWithConfigurationCacheNotFailWhenUsingConfigurationCache(String taskName)
            throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        BuildResult build = buildResult(taskName, "--no-configuration-cache");
        assertTrue(build.getOutput().contains("BUILD SUCCESSFUL"));

    }

    /**
     * The {@code Task.project} accessor is deprecated when invoked at execution time and is scheduled for removal in
     * Gradle 10. Tasks must therefore not call {@code getProject()} from their {@code @TaskAction} (or anything it
     * reaches). This guards against reintroducing such a call in the (non configuration cache compatible) deploy task.
     */
    @ParameterizedTest
    @MethodSource("nonCompatibleQuarkusBuildTasks")
    public void tasksDoNotInvokeTaskProjectAtExecutionTime(String taskName) throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        BuildResult build = buildResult(taskName, Arrays.asList("--no-configuration-cache", "--warning-mode", "all"));
        assertTrue(build.getOutput().contains("BUILD SUCCESSFUL"));
        assertFalse(build.getOutput().contains("Invocation of Task.project at execution time has been deprecated"),
                "Tasks must not invoke Task.project at execution time (removed in Gradle 10)");
    }

    @Test
    public void configurationCacheIsReusedWhenUnrelatedSystemPropertyChanges() throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        buildResult(":help", "--configuration-cache");

        // First build: store configuration cache entry with -Da=1
        BuildResult firstBuild = buildResult(QUARKUS_BUILD_TASK_NAME,
                Arrays.asList("--configuration-cache", "-Da=1"));
        assertTrue(firstBuild.getOutput().contains("Configuration cache entry stored"),
                "First build should store configuration cache entry");

        // Second build: change unrelated system property to -Da=2
        // The configuration cache should still be reused because the ValueSource filters
        // out non-quarkus system properties from its result.
        BuildResult secondBuild = buildResult(QUARKUS_BUILD_TASK_NAME,
                Arrays.asList("--configuration-cache", "-Da=2"));
        assertTrue(secondBuild.getOutput().contains("Reusing configuration cache."),
                "Configuration cache should be reused when only unrelated system properties change");
    }

    @Test
    public void quarkusGoOfflineRunsApplicationModelTasks()
            throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        BuildResult build = buildResult(QUARKUS_GO_OFFLINE_TASK_NAME,
                List.of("--configuration-cache"));

        assertTrue(build.getOutput().contains("BUILD SUCCESSFUL"));
        assertTrue(build.task(":" + GenerateApplicationModelTask.taskName(LaunchMode.NORMAL)) != null);
        assertTrue(build.task(":" + GenerateApplicationModelTask.taskName(LaunchMode.DEVELOPMENT)) != null);
        assertTrue(build.task(":" + GenerateApplicationModelTask.taskName(LaunchMode.TEST)) != null);
    }

    @Test
    public void dryRunDoesNotResolveDeploymentConfigurationsAndConfigurationCacheDryRunDoesNotPoisonRealBuild()
            throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("io/quarkus/gradle/tasks/configurationcache/main");
        FileUtils.copyDirectory(new File(url.toURI()), testProjectDir.toFile());
        FileUtils.copyFile(new File("../gradle.properties"), testProjectDir.resolve("gradle.properties").toFile());

        var initScript = testProjectDir.resolve("log-resolution.init.gradle.kts");
        Files.writeString(initScript, """
                allprojects {
                    configurations.configureEach {
                        incoming.beforeResolve {
                            println("QUARKUS_TEST_RESOLVED:${project.path}:${name}")
                        }
                    }
                }
                """);

        // Configuration-cache dry-run may materialize modeled resolution-result task inputs while storing the
        // task graph. Use a plain dry-run for the strict "graph calculation does not resolve deployment
        // configurations" assertion, then separately verify that configuration-cache dry-run does not poison a real build.
        var dryRunBuild = buildResult("test", List.of("--dry-run", "--no-configuration-cache", "-I", initScript.toString()));

        assertTrue(dryRunBuild.getOutput().contains(":quarkusGenerateAppModel SKIPPED"));
        assertTrue(dryRunBuild.getOutput().contains(":quarkusGenerateCode SKIPPED"));
        assertTrue(dryRunBuild.getOutput().contains(":quarkusGenerateTestAppModel SKIPPED"));
        assertTrue(dryRunBuild.getOutput().contains(":quarkusGenerateCodeTests SKIPPED"));
        assertFalse(dryRunBuild.getOutput()
                .contains("QUARKUS_TEST_RESOLVED:::quarkusProdRuntimeClasspathConfigurationDeployment"),
                dryRunBuild.getOutput());
        assertFalse(dryRunBuild.getOutput()
                .contains("QUARKUS_TEST_RESOLVED:::quarkusTestRuntimeClasspathConfigurationDeployment"),
                dryRunBuild.getOutput());

        var configurationCacheDryRunBuild = buildResult("test", List.of("--dry-run", "-I", initScript.toString()));

        assertTrue(configurationCacheDryRunBuild.getOutput().contains(":quarkusGenerateAppModel SKIPPED"));
        assertTrue(configurationCacheDryRunBuild.getOutput().contains(":quarkusGenerateCode SKIPPED"));
        assertTrue(configurationCacheDryRunBuild.getOutput().contains(":quarkusGenerateTestAppModel SKIPPED"));
        assertTrue(configurationCacheDryRunBuild.getOutput().contains(":quarkusGenerateCodeTests SKIPPED"));
        assertTrue(configurationCacheDryRunBuild.getOutput().contains("Configuration cache entry stored"));

        var realBuild = buildResult("test", List.of("-I", initScript.toString()));

        assertTrue(realBuild.getOutput().contains("BUILD SUCCESSFUL"));
        assertTrue(realBuild.task(":" + GenerateApplicationModelTask.taskName(LaunchMode.NORMAL)) != null);
        assertTrue(realBuild.task(":" + GenerateApplicationModelTask.taskName(LaunchMode.TEST)) != null);
    }

}
