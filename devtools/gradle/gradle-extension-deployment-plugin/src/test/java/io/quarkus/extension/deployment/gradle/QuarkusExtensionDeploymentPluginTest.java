package io.quarkus.extension.deployment.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.gradle.testing.BaseGradleTest;

public class QuarkusExtensionDeploymentPluginTest extends BaseGradleTest {
    @Test
    public void pluginCanBeResolvedFromPluginClasspath() throws IOException {
        writeFile("settings.gradle", "rootProject.name = 'test'\n");
        writeFile("build.gradle",
                "plugins {\n" +
                        "    id '" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID + "'\n" +
                        "}\n");

        var result = buildResult("help");

        assertThat(result.task(":help").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    public void pluginAddsAnnotationProcessor() throws IOException {
        writeFile("settings.gradle", "rootProject.name = 'test'\n");
        writeFile("build.gradle",
                "plugins {\n" +
                        "    id '" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID + "'\n" +
                        "}\n" +
                        "repositories {\n" +
                        "    mavenCentral()\n" +
                        "    mavenLocal()\n" +
                        "}\n" +
                        "dependencies {\n" +
                        "    implementation enforcedPlatform('io.quarkus:quarkus-bom:" + getCurrentQuarkusVersion() + "')\n" +
                        "    implementation 'io.quarkus:quarkus-arc-deployment'\n" +
                        "}\n");

        var result = buildResult("dependencies", "--configuration", "annotationProcessor");

        assertThat(result.task(":dependencies").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).contains("io.quarkus:quarkus-extension-processor");
    }

    @Test
    public void deploymentTestsUseGeneratedApplicationModel() throws IOException {
        writeFile("settings.gradle",
                "rootProject.name = 'test'\n" +
                        "include 'runtime', 'deployment'\n");
        var runtimeProjectDir = testProjectDir.resolve("runtime");
        var deploymentProjectDir = testProjectDir.resolve("deployment");
        writeFile(runtimeProjectDir.resolve("build.gradle"),
                "plugins {\n" +
                        "    id 'java'\n" +
                        "}\n" +
                        "group = 'org.acme'\n" +
                        "version = '1.0.0'\n" +
                        "repositories {\n" +
                        "    mavenCentral()\n" +
                        "    mavenLocal()\n" +
                        "}\n" +
                        "dependencies {\n" +
                        "    implementation enforcedPlatform('io.quarkus:quarkus-bom:" + getCurrentQuarkusVersion() + "')\n" +
                        "    implementation 'io.quarkus:quarkus-arc'\n" +
                        "}\n");
        var runtimeTestFile = runtimeProjectDir.resolve("src/main/java/runtime/Test.java");
        writeFile(runtimeTestFile, "package runtime; public class Test {}\n");

        writeFile(deploymentProjectDir.resolve("build.gradle"),
                "plugins {\n" +
                        "    id '" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID + "'\n" +
                        "}\n" +
                        "group = 'org.acme'\n" +
                        "version = '1.0.0'\n" +
                        "repositories {\n" +
                        "    mavenCentral()\n" +
                        "    mavenLocal()\n" +
                        "}\n" +
                        "dependencies {\n" +
                        "    implementation enforcedPlatform('io.quarkus:quarkus-bom:" + getCurrentQuarkusVersion() + "')\n" +
                        "    implementation 'io.quarkus:quarkus-arc-deployment'\n" +
                        "    implementation project(':runtime')\n" +
                        "    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.3'\n" +
                        "    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.3'\n" +
                        "}\n");
        var deploymentTestFile = deploymentProjectDir.resolve("src/test/java/deployment/ModelTest.java");
        writeFile(deploymentTestFile,
                "package deployment;\n" +
                        "import static org.junit.jupiter.api.Assertions.assertTrue;\n" +
                        "import java.nio.file.Files;\n" +
                        "import java.nio.file.Path;\n" +
                        "import org.junit.jupiter.api.Test;\n" +
                        "class ModelTest {\n" +
                        "    @Test\n" +
                        "    void serializedApplicationModelIsConfigured() throws Exception {\n" +
                        "        assertTrue(Files.exists(Path.of(System.getProperty(\""
                        + BootstrapConstants.SERIALIZED_TEST_APP_MODEL + "\"))));\n" +
                        "    }\n" +
                        "}\n");

        var result = buildResult(":deployment:test");

        assertThat(result.task(":deployment:quarkusGenerateTestAppModel").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":deployment:test").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    public void pluginPublishesSelectableDeploymentMarkerVariant() throws IOException {
        writeFile("settings.gradle",
                """
                        rootProject.name = 'test'
                        include 'runtime', 'deployment'
                        """);
        var runtimeProjectDir = testProjectDir.resolve("runtime");
        var deploymentProjectDir = testProjectDir.resolve("deployment");
        writeFile(deploymentProjectDir.resolve("build.gradle"),
                "plugins {\n" +
                        "    id 'java'\n" +
                        "    id '" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID + "'\n" +
                        "}\n");
        writeFile(runtimeProjectDir.resolve("build.gradle"),
                "def deploymentAttr = Attribute.of('" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID + "', Boolean)\n" +
                        "def markerCategory = objects.named(org.gradle.api.attributes.Category, '" +
                        QuarkusExtensionDeploymentPlugin.MARKER_CATEGORY + "')\n" +
                        "configurations {\n" +
                        "    deploymentMarker {\n" +
                        "        canBeConsumed = false\n" +
                        "        canBeResolved = true\n" +
                        "        attributes {\n" +
                        "            attribute(org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE, markerCategory)\n" +
                        "            attribute(deploymentAttr, true)\n" +
                        "        }\n" +
                        "    }\n" +
                        "}\n" +
                        "dependencies {\n" +
                        "    deploymentMarker project(':deployment')\n" +
                        "}\n" +
                        "def deploymentMarkerFiles = configurations.deploymentMarker\n" +
                        "tasks.register('resolveDeploymentMarker') {\n" +
                        "    inputs.files(deploymentMarkerFiles)\n" +
                        "    doLast {\n" +
                        "        def files = deploymentMarkerFiles.files\n" +
                        "        assert files.size() == 1\n" +
                        "        println 'deploymentMarker=' + files.first().text.trim()\n" +
                        "    }\n" +
                        "}\n");

        var result = buildResult(":runtime:resolveDeploymentMarker");

        assertThat(result.task(":deployment:" + QuarkusExtensionDeploymentPlugin.MARKER_TASK_NAME).getOutcome())
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE);
        assertThat(result.task(":runtime:resolveDeploymentMarker").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).contains("deploymentMarker=" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID);
    }

    @Test
    public void markerTaskRestoresOutputFromBuildCache() throws IOException {
        writeFile("settings.gradle",
                """
                        rootProject.name = 'test'
                        buildCache { local { directory = file('local-build-cache') } }
                        """);
        writeFile("build.gradle",
                "plugins {\n" +
                        "    id '" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID + "'\n" +
                        "}\n");

        String markerTask = ":" + QuarkusExtensionDeploymentPlugin.MARKER_TASK_NAME;
        var firstRun = buildResult("clean", markerTask);

        assertThat(firstRun.task(markerTask).getOutcome()).isEqualTo(TaskOutcome.SUCCESS);

        var secondRun = buildResult("clean", markerTask);

        assertThat(secondRun.task(markerTask).getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
        assertThat(testProjectDir
                .resolve("build/quarkus/extension-deployment-marker/" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID))
                .content().isEqualTo(QuarkusExtensionDeploymentPlugin.PLUGIN_ID + System.lineSeparator());
    }

    @Test
    public void markerResolutionFailsWhenDeploymentPluginIsMissing() throws IOException {
        writeFile("settings.gradle",
                """
                        rootProject.name = 'test'
                        include 'runtime', 'deployment'
                        """);
        var runtimeProjectDir = testProjectDir.resolve("runtime");
        var deploymentProjectDir = testProjectDir.resolve("deployment");
        writeFile(deploymentProjectDir.resolve("build.gradle"),
                """
                        plugins {
                            id 'java'
                        }
                        """);
        writeFile(runtimeProjectDir.resolve("build.gradle"),
                "def deploymentAttr = Attribute.of('" + QuarkusExtensionDeploymentPlugin.PLUGIN_ID + "', Boolean)\n" +
                        "def markerCategory = objects.named(org.gradle.api.attributes.Category, '" +
                        QuarkusExtensionDeploymentPlugin.MARKER_CATEGORY + "')\n" +
                        "configurations {\n" +
                        "    deploymentMarker {\n" +
                        "        canBeConsumed = false\n" +
                        "        canBeResolved = true\n" +
                        "        attributes {\n" +
                        "            attribute(org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE, markerCategory)\n" +
                        "            attribute(deploymentAttr, true)\n" +
                        "        }\n" +
                        "    }\n" +
                        "}\n" +
                        "dependencies {\n" +
                        "    deploymentMarker project(':deployment')\n" +
                        "}\n" +
                        "def deploymentMarkerFiles = configurations.deploymentMarker\n" +
                        "tasks.register('resolveDeploymentMarker') {\n" +
                        "    inputs.files(deploymentMarkerFiles)\n" +
                        "    doLast {\n" +
                        "        deploymentMarkerFiles.files\n" +
                        "    }\n" +
                        "}\n");

        var result = buildAndFailResult(Map.of(), ":runtime:resolveDeploymentMarker");

        assertThat(result.getOutput()).contains("No matching variant", QuarkusExtensionDeploymentPlugin.PLUGIN_ID);
    }

    private static String getCurrentQuarkusVersion() throws IOException {
        var gradlePropsFile = Paths.get("").toAbsolutePath().normalize().getParent().resolve("gradle.properties");
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(gradlePropsFile)) {
            props.load(is);
        }
        return props.getProperty("version");
    }
}
