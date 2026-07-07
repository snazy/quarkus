package io.quarkus.extension.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

import org.assertj.core.api.Assertions;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.fs.util.ZipUtils;
import io.quarkus.gradle.testing.BaseGradleTest;
import io.quarkus.gradle.tooling.tasks.GenerateApplicationModelTask;
import io.quarkus.runtime.LaunchMode;

public class QuarkusExtensionPluginTest extends BaseGradleTest {

    @BeforeEach
    public void setupProject() throws IOException {
        writeFile("settings.gradle", "rootProject.name = 'test'");
    }

    @Test
    public void jarShouldContainsExtensionPropertiesFile() throws IOException {
        writeFile("build.gradle", TestUtils.getDefaultGradleBuildFileContent(true, Collections.emptyList(), ""));

        BuildResult jarResult = buildResult("jar");
        assertThat(jarResult.task(":jar").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(jarResult.task(":" + QuarkusExtensionPlugin.EXTENSION_DESCRIPTOR_TASK_NAME).getOutcome())
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE);

        var jarFile = testProjectDir.resolve("build/libs/test-1.0.0.jar");
        assertThat(jarFile).exists();
        assertThat(jarFile).satisfies(f -> {
            try (FileSystem jarFs = ZipUtils.newFileSystem(f)) {
                Path descriptorPath = jarFs.getPath(BootstrapConstants.DESCRIPTOR_PATH);
                assertThat(descriptorPath).exists();

                Properties extensionProperty = TestUtils.readPropertyFile(descriptorPath);
                assertThat(extensionProperty).containsEntry("deployment-artifact", "org.acme:test-deployment:1.0.0");

            } catch (IOException e) {
                Assertions.fail("Unable to read jar file");
            }
        });
    }

    @Test
    public void pluginShouldAddAnnotationProcessor() throws IOException {
        TestUtils.createExtensionProject(testProjectDir, false, Collections.emptyList(), Collections.emptyList());
        BuildResult dependencies = buildResult("build", ":runtime:dependencies", "--configuration",
                "annotationProcessor");

        assertThat(dependencies.getOutput()).contains(QuarkusExtensionPlugin.QUARKUS_ANNOTATION_PROCESSOR);
    }

    @Test
    public void pluginShouldAddAnnotationProcessorToDeploymentModule() throws IOException {
        TestUtils.createExtensionProject(testProjectDir, false, Collections.emptyList(), Collections.emptyList());
        BuildResult dependencies = buildResult("build", ":deployment:dependencies", "--configuration",
                "annotationProcessor");
        assertThat(dependencies.getOutput()).contains(QuarkusExtensionPlugin.QUARKUS_ANNOTATION_PROCESSOR);
    }

    @Test
    public void deploymentTestsShouldUseGeneratedApplicationModel() throws IOException {
        TestUtils.createExtensionProject(testProjectDir, false, Collections.emptyList(), Collections.emptyList());
        var deploymentBuildFile = testProjectDir.resolve("deployment/build.gradle");
        writeFile(deploymentBuildFile,
                TestUtils.getDefaultDeploymentBuildFileContent(Collections.emptyList()) +
                        "dependencies {\n" +
                        "testImplementation(\"org.junit.jupiter:junit-jupiter-api:5.10.3\")\n" +
                        "testRuntimeOnly(\"org.junit.jupiter:junit-jupiter-engine:5.10.3\")\n" +
                        "}\n");
        var deploymentTestFile = testProjectDir.resolve("deployment/src/test/java/deployment/ModelTest.java");
        writeFile(deploymentTestFile,
                "package deployment;\n" +
                        "import static org.junit.jupiter.api.Assertions.assertNotNull;\n" +
                        "import org.junit.jupiter.api.Test;\n" +
                        "class ModelTest {\n" +
                        "    @Test\n" +
                        "    void serializedApplicationModelIsConfigured() {\n" +
                        "        assertNotNull(System.getProperty(\"" + BootstrapConstants.SERIALIZED_TEST_APP_MODEL + "\"));\n"
                        +
                        "    }\n" +
                        "}\n");

        BuildResult testResult = buildResult(":deployment:test");

        assertThat(testResult.task(":deployment:" + GenerateApplicationModelTask.taskName(LaunchMode.TEST)).getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(testResult.task(":deployment:test").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(testProjectDir.resolve("deployment/build/quarkus/application-model/quarkus-app-test-model.dat"))
                .exists();
    }

    @Test
    public void generatedApplicationModelTaskShouldNotReportConfigurationCacheProblems() throws IOException {
        TestUtils.createExtensionProject(testProjectDir, false, Collections.emptyList(), Collections.emptyList());

        String taskName = ":deployment:" + GenerateApplicationModelTask.taskName(LaunchMode.TEST);
        BuildResult result = buildResult(taskName);

        assertThat(result.task(taskName).getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).doesNotContain("Task `" + taskName + "`");
        assertThat(result.getOutput()).doesNotContain("GenerateApplicationModelTask");
    }

    @Test
    public void directRuntimeChildProjectShouldRequireDeploymentArtifact() throws IOException {
        var runtimeModule = testProjectDir.resolve("runtime");
        writeFile(runtimeModule.resolve("build.gradle"),
                TestUtils.getDefaultGradleBuildFileContent(true, Collections.emptyList(), ""));
        var runtimeClass = runtimeModule.resolve("src/main/java/runtime/Test.java");
        writeFile(runtimeClass, "package runtime; public class Test {}\n");
        writeFile("settings.gradle", "include 'runtime'\n");

        BuildResult result = buildAndFailResult(":runtime:" + QuarkusExtensionPlugin.EXTENSION_DESCRIPTOR_TASK_NAME);

        assertThat(result.getOutput())
                .contains("The project ':runtime' must not be named 'runtime' "
                        + "and be a direct child project of the root project")
                .contains("Set 'deploymentArtifact' on the 'QuarkusExtensionConfiguration'.");
    }

    @Test
    public void deploymentTestShouldGenerateApplicationModelWithComponentVariants() throws IOException {
        createExtensionProjectWithDeploymentTest();

        BuildResult test = buildResult(":deployment:test");

        assertThat(test.task(":deployment:test").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(test.getOutput()).doesNotContain("cannot choose between the following variants");
        assertDeploymentTestApplicationModelMarker();
    }

    @Test
    public void deploymentTestShouldGenerateApplicationModelWithoutComponentVariants() throws IOException {
        createExtensionProjectWithDeploymentTest();

        BuildResult test = buildResult(":deployment:test", "-PdisableQuarkusComponentVariants=true");

        assertThat(test.task(":deployment:test").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertDeploymentTestApplicationModelMarker();
    }

    @Test
    public void noArgApplicationModelBuilderShouldResolveDeploymentProjectWithComponentVariants() throws IOException {
        createExtensionProjectWithDeploymentTest();

        // This covers the legacy live tooling API from an ad hoc build-script task, not normal plugin task wiring.
        BuildResult model = buildResult(":runtime:resolveDeploymentTestApplicationModel", "--no-configuration-cache");

        assertThat(model.task(":runtime:resolveDeploymentTestApplicationModel").getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(model.getOutput()).contains("resolved deployment test application model");
    }

    @Test
    public void deploymentClasspathShouldResolveLocalExtensionDeploymentProject() throws IOException {
        createExtensionProjectWithLocalDeploymentForApplication();

        BuildResult result = buildResult(":extension:resolveAppDeploymentClasspath", "--no-configuration-cache");

        assertThat(result.task(":extension:resolveAppDeploymentClasspath").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).contains("deployment classpath resolved");
    }

    private void createExtensionProjectWithDeploymentTest() throws IOException {
        var runtimeModule = testProjectDir.resolve("runtime");
        var runtimeClass = runtimeModule.resolve("src/main/java/runtime/Test.java");
        var deploymentModule = testProjectDir.resolve("deployment");
        var deploymentClass = deploymentModule.resolve("src/main/java/deployment/Test.java");
        var deploymentTest = deploymentModule.resolve("src/test/java/deployment/GeneratedModelTest.java");

        writeFile(runtimeModule.resolve("build.gradle"), runtimeBuildFile());
        writeFile(runtimeClass, "package runtime; public class Test {}\n");

        writeFile(deploymentModule.resolve("build.gradle"), deploymentBuildFile());
        writeFile(deploymentClass, "package deployment; public class Test {}\n");
        writeFile(deploymentTest, """
                package deployment;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import java.nio.file.Files;
                import java.nio.file.Path;

                import org.junit.jupiter.api.Test;

                public class GeneratedModelTest {

                    @Test
                    public void serializedTestApplicationModelIsAvailable() throws Exception {
                        String model = System.getProperty("quarkus-internal-test.serialized-app-model.path");
                        assertNotNull(model);
                        Files.writeString(Path.of(System.getProperty("model.marker.file")), "true");
                    }
                }
                """);

        writeFile("settings.gradle", "include 'runtime', 'deployment'\n");
    }

    private void createExtensionProjectWithLocalDeploymentForApplication() throws IOException {
        var runtimeModule = testProjectDir.resolve("extension");
        var runtimeClass = runtimeModule.resolve("src/main/java/extension/Test.java");
        var deploymentModule = testProjectDir.resolve("deployment");
        var deploymentClass = deploymentModule.resolve("src/main/java/deployment/Test.java");
        var appModule = testProjectDir.resolve("app");
        var appClass = appModule.resolve("src/main/java/app/App.java");

        writeFile(runtimeModule.resolve("build.gradle"), localRuntimeBuildFile());
        writeFile(runtimeClass, "package extension; public class Test {}\n");

        writeFile(deploymentModule.resolve("build.gradle"), localDeploymentBuildFile());
        writeFile(deploymentClass, "package deployment; public class Test {}\n");

        writeFile(appModule.resolve("build.gradle"), appBuildFile());
        writeFile(appClass, "package app; public class App {}\n");
        writeFile("settings.gradle", "include 'extension', 'deployment', 'app'\n");
    }

    private String runtimeBuildFile() throws IOException {
        return """
                plugins {
                    id 'java'
                    id 'io.quarkus.extension'
                }

                group = 'org.acme'
                version = '1.0.0'

                repositories {
                    mavenCentral()
                    mavenLocal()
                }

                quarkusExtension {
                    disableValidation = true
                    deploymentArtifact = "org.acme:test-deployment:1.0.0"
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:%1$s")
                    implementation "io.quarkus:quarkus-arc"
                }

                tasks.register("resolveDeploymentTestApplicationModel") {
                    dependsOn(":deployment:testClasses")
                    doLast {
                        def mode = io.quarkus.runtime.LaunchMode.TEST
                        io.quarkus.gradle.tooling.ToolingUtils.create(project(":deployment"), mode)
                        println "resolved deployment test application model"
                    }
                }
                """.formatted(TestUtils.getCurrentQuarkusVersion());
    }

    private String localRuntimeBuildFile() throws IOException {
        return """
                import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder
                import io.quarkus.runtime.LaunchMode

                plugins {
                    id 'java'
                    id 'io.quarkus.extension'
                }

                group = 'org.acme'
                version = '1.0.0'

                repositories {
                    mavenCentral()
                    mavenLocal()
                }

                quarkusExtension {
                    disableValidation = true
                    deploymentModule = ":deployment"
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:%1$s")
                    implementation "io.quarkus:quarkus-arc"
                }

                tasks.register("resolveAppDeploymentClasspath") {
                    def appProject = project(":app")
                    ApplicationDeploymentClasspathBuilder.initConfigurations(appProject)
                    def classpath = new ApplicationDeploymentClasspathBuilder(appProject, LaunchMode.NORMAL)
                    dependsOn(classpath.getDeploymentConfiguration())
                    doLast {
                        classpath.getDeploymentConfiguration().files
                        println "deployment classpath resolved"
                    }
                }
                """.formatted(TestUtils.getCurrentQuarkusVersion());
    }

    private String appBuildFile() throws IOException {
        return """
                plugins {
                    id 'java'
                }

                group = 'org.acme'
                version = '1.0.0'

                repositories {
                    mavenCentral()
                    mavenLocal()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:%1$s")
                    implementation project(":extension")
                }
                """.formatted(TestUtils.getCurrentQuarkusVersion());
    }

    private String localDeploymentBuildFile() throws IOException {
        return """
                plugins {
                    id 'io.quarkus.extension.deployment'
                }

                group = 'org.acme'
                version = '1.0.0'

                repositories {
                    mavenCentral()
                    mavenLocal()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:%1$s")
                    implementation "io.quarkus:quarkus-arc-deployment"
                    implementation project(":extension")
                }
                """.formatted(TestUtils.getCurrentQuarkusVersion());
    }

    private String deploymentBuildFile() throws IOException {
        return """
                plugins {
                    id 'io.quarkus.extension.deployment'
                }

                group = 'org.acme'
                version = '1.0.0'

                repositories {
                    mavenCentral()
                    mavenLocal()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:%1$s")
                    implementation "io.quarkus:quarkus-arc-deployment"
                    implementation project(":runtime")
                    testImplementation "org.junit.jupiter:junit-jupiter-api"
                    testRuntimeOnly "org.junit.jupiter:junit-jupiter-engine"
                }

                test {
                    def markerFile = layout.buildDirectory.file("model-marker.txt").get().asFile
                    systemProperty "model.marker.file", markerFile.absolutePath
                }
                """.formatted(TestUtils.getCurrentQuarkusVersion());
    }

    private void assertDeploymentTestApplicationModelMarker() throws IOException {
        Path marker = testProjectDir.resolve("deployment/build/model-marker.txt");
        assertThat(marker).exists();
        assertThat(Files.readString(marker)).isEqualTo("true");
    }
}
