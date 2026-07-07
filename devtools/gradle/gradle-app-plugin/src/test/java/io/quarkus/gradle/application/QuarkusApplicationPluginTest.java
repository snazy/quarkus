package io.quarkus.gradle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.UnexpectedBuildFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.gradle.application.dsl.QuarkusApplicationBuild;
import io.quarkus.gradle.application.dsl.QuarkusApplicationExtension;
import io.quarkus.gradle.application.dsl.QuarkusApplicationRunnerOutput;
import io.quarkus.gradle.application.internal.modelgen.GenerateModelTask;
import io.quarkus.gradle.application.internal.packaging.PackageResult;
import io.quarkus.gradle.application.internal.packaging.PackageResultCodec;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentImageSource;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;
import io.quarkus.gradle.application.model.QuarkusApplicationImageBuilder;
import io.quarkus.gradle.application.model.QuarkusApplicationVariantAttributes;
import io.quarkus.gradle.application.tasks.QuarkusApplicationAotEnhancedImageBuildTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationAotEnhancedImagePushTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationBuildTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationContinuousTestTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationDeployTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationDevTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationGenerateCodeTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationImageBuildTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationImagePushTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationPackageTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationRemoteDevTask;
import io.quarkus.gradle.application.tasks.QuarkusApplicationRunTask;
import io.quarkus.gradle.tooling.dependency.DeclaredDependencyEnrichmentMode;
import io.quarkus.gradle.tooling.tasks.GeneratePomClosureTask;
import io.quarkus.runtime.LaunchMode;

class QuarkusApplicationPluginTest {

    @TempDir
    Path testProjectDir;

    @Test
    void registersQuarkusApplicationExtensionAndNamedTasks() throws IOException {
        Path projectDir = Files.createDirectory(testProjectDir.resolve("project-builder"));
        Path mainSources = Files.createDirectories(projectDir.resolve("src/main"));
        Path testSources = Files.createDirectories(projectDir.resolve("src/test"));
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.createDirectories(projectDir.resolve("src/test/java"));
        Path normalRuntime = createJar(projectDir.resolve("normal-runtime.jar"));
        Path testRuntime = createJar(projectDir.resolve("test-runtime.jar"));
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.setVersion("1.2.3");
        project.getPluginManager().apply("java");
        project.getDependencies().add("runtimeOnly", project.files(normalRuntime));
        project.getDependencies().add("testRuntimeOnly", project.files(testRuntime));
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);
        extension.getQuarkusBuildProperties().put("common", "value");
        assertThat(extension.getCodegen().getProviders().get()).containsExactly("grpc", "avdl", "avpr", "avsc");
        assertThat(extension.getCodegen().getInputNames().get()).containsExactly("proto", "avro");
        extension.codegen(codegen -> {
            codegen.getProviders().set(Arrays.asList("custom-provider", "other-provider"));
            codegen.getInputNames().set(Arrays.asList("custom-input", "other-input"));
        });
        extension.buildForkOptions(options -> {
            options.jvmArgs("-Dbuild-option=true");
            options.systemProperty("build.system.property", "true");
            options.environment("BUILD_ENV", "true");
            options.getMaxHeapSize().set("768m");
            options.getEnableAssertions().set(true);
        });
        extension.codeGenForkOptions(options -> {
            options.jvmArgs("-Dcodegen-option=true");
            options.systemProperty("codegen.system.property", "true");
            options.environment("CODEGEN_ENV", "true");
            options.getMinHeapSize().set("128m");
            options.getDefaultCharacterEncoding().set("UTF-8");
        });
        extension.dev(dev -> {
            dev.getQuarkusBuildProperties().put("dev", "value");
            dev.forkOptions(forkOptions -> {
                forkOptions.jvmArgs("-Ddev-jvm-arg=true");
                forkOptions.systemProperty("dev.system.property", "true");
            });
        });
        extension.remoteDev(remoteDev -> {
            remoteDev.getQuarkusBuildProperties().put("remote-dev", "value");
            remoteDev.forkOptions(forkOptions -> {
                forkOptions.jvmArgs("-Dremote-dev-jvm-arg=true");
                forkOptions.systemProperty("remote.dev.system.property", "true");
            });
        });
        extension.configInputs(configInputs -> configInputs.projectProperties(
                projectProperties -> projectProperties.getNames().add("quarkus.explicit.project")));
        extension.builds(builds -> builds.fastJar("app", app -> {
            app.getQuarkusBuildProperties().put("build", "value");
            app.image(image -> {
                image.getRepository().set("example/app");
                image.getBuilder().set(QuarkusApplicationImageBuilder.JIB);
                image.getQuarkusBuildProperties().put("image", "value");
            });
            app.deployments(deployments -> deployments.kubernetes("dev",
                    deployment -> deployment.getImageSource().set(QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH)));
        }));

        QuarkusApplicationPackageTask build = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusAppBuild");
        GenerateModelTask applicationModel = (GenerateModelTask) project.getTasks()
                .getByName("quarkusApplicationModel");
        GenerateModelTask devApplicationModel = (GenerateModelTask) project.getTasks()
                .getByName("quarkusApplicationDevModel");
        GenerateModelTask codegenModel = (GenerateModelTask) project.getTasks()
                .getByName("quarkusApplicationCodegenModel");
        GenerateModelTask testCodegenModel = (GenerateModelTask) project.getTasks()
                .getByName("quarkusApplicationTestCodegenModel");
        QuarkusApplicationGenerateCodeTask generateCode = (QuarkusApplicationGenerateCodeTask) project.getTasks()
                .getByName("quarkusApplicationGenerateCode");
        QuarkusApplicationGenerateCodeTask generateTestCode = (QuarkusApplicationGenerateCodeTask) project.getTasks()
                .getByName("quarkusApplicationGenerateTestCode");
        Task compileJava = project.getTasks().getByName("compileJava");
        Task compileTestJava = project.getTasks().getByName("compileTestJava");
        Task classes = project.getTasks().getByName("classes");
        Task testClasses = project.getTasks().getByName("testClasses");
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet mainSourceSet = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet testSourceSet = java.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);
        assertThat(applicationModel.getTaskDependencies().getDependencies(applicationModel))
                .extracting(Task::getName)
                .contains("classes");
        assertThat(codegenModel.getTaskDependencies().getDependencies(codegenModel)).isEmpty();
        assertThat(testCodegenModel.getTaskDependencies().getDependencies(testCodegenModel)).isEmpty();
        assertThat(applicationModel.getLaunchMode().get()).isEqualTo(LaunchMode.NORMAL);
        assertThat(devApplicationModel.getLaunchMode().get()).isEqualTo(LaunchMode.DEVELOPMENT);
        assertThat(codegenModel.getLaunchMode().get()).isEqualTo(LaunchMode.NORMAL);
        assertThat(testCodegenModel.getLaunchMode().get()).isEqualTo(LaunchMode.TEST);
        assertThat(applicationModel.getDeclaredDependencyEnrichmentMode().get())
                .isEqualTo(DeclaredDependencyEnrichmentMode.SELECTED_MODULE_POMS);
        assertThat(devApplicationModel.getDeclaredDependencyEnrichmentMode().get())
                .isEqualTo(DeclaredDependencyEnrichmentMode.NONE);
        assertThat(codegenModel.getDeclaredDependencyEnrichmentMode().get())
                .isEqualTo(DeclaredDependencyEnrichmentMode.NONE);
        assertThat(testCodegenModel.getDeclaredDependencyEnrichmentMode().get())
                .isEqualTo(DeclaredDependencyEnrichmentMode.NONE);
        assertThat(applicationModel.getApplicationModel().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus/application-model/quarkus-application-model.dat").get().getAsFile());
        GeneratePomClosureTask pomClosure = (GeneratePomClosureTask) project.getTasks()
                .getByName("quarkusApplicationModelPomClosure");
        assertThat(applicationModel.getPomClosureFile().get().getAsFile())
                .isEqualTo(pomClosure.getPomClosureFile().get().getAsFile());
        assertThat(pomClosure.getPomClosureFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus/application-model/pom-closure/quarkusApplicationModel.properties").get().getAsFile());
        assertThat(devApplicationModel.getPomClosureFile().isPresent()).isFalse();
        assertThat(codegenModel.getPomClosureFile().isPresent()).isFalse();
        assertThat(testCodegenModel.getPomClosureFile().isPresent()).isFalse();
        assertThat(codegenModel.getApplicationModel().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus/application-model/quarkus-application-codegen-model.dat").get().getAsFile());
        assertThat(testCodegenModel.getApplicationModel().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus/application-model/quarkus-application-test-codegen-model.dat").get().getAsFile());
        assertThat(generateCode.getApplicationModel().get().getAsFile())
                .isEqualTo(codegenModel.getApplicationModel().get().getAsFile());
        assertThat(generateTestCode.getApplicationModel().get().getAsFile())
                .isEqualTo(testCodegenModel.getApplicationModel().get().getAsFile());
        assertThat(generateCode.getLaunchMode().get()).isEqualTo(LaunchMode.NORMAL);
        assertThat(generateTestCode.getLaunchMode().get()).isEqualTo(LaunchMode.TEST);
        assertThat(generateCode.getGeneratedOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("generated/sources/quarkus-application/main")
                        .get().getAsFile());
        assertThat(generateTestCode.getGeneratedOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("generated/sources/quarkus-application/test")
                        .get().getAsFile());
        assertThat(generateCode.getCodegenForkOptions().getJvmArgs().get()).containsExactly("-Dcodegen-option=true");
        assertThat(generateCode.getCodegenForkOptions().getSystemProperties().get())
                .containsEntry("codegen.system.property", "true");
        assertThat(generateCode.getCodegenForkOptions().getEnvironment().get()).containsEntry("CODEGEN_ENV", "true");
        assertThat(generateCode.getCodegenForkOptions().getMinHeapSize().get()).isEqualTo("128m");
        assertThat(generateCode.getCodegenForkOptions().getDefaultCharacterEncoding().get()).isEqualTo("UTF-8");
        assertThat(mainSourceSet.getJava().getSrcDirs())
                .doesNotContain(generateCode.getGeneratedOutputDirectory().get().getAsFile());
        assertThat(testSourceSet.getJava().getSrcDirs())
                .doesNotContain(generateTestCode.getGeneratedOutputDirectory().get().getAsFile());
        assertThat(compileJava.getTaskDependencies().getDependencies(compileJava))
                .extracting(Task::getName)
                .contains("quarkusApplicationGenerateCode");
        assertThat(compileTestJava.getTaskDependencies().getDependencies(compileTestJava))
                .extracting(Task::getName)
                .contains("quarkusApplicationGenerateCode", "quarkusApplicationGenerateTestCode");
        assertThat(classes.getTaskDependencies().getDependencies(classes))
                .extracting(Task::getName)
                .contains("compileJava");
        assertThat(testClasses.getTaskDependencies().getDependencies(testClasses))
                .extracting(Task::getName)
                .contains("compileTestJava");
        assertThat(applicationModel.getOriginalClasspath().getFiles())
                .contains(normalRuntime.toFile())
                .doesNotContain(testRuntime.toFile());
        assertThat(codegenModel.getOriginalClasspath().getFiles())
                .contains(normalRuntime.toFile())
                .doesNotContain(testRuntime.toFile());
        assertThat(testCodegenModel.getOriginalClasspath().getFiles()).contains(testRuntime.toFile());
        assertThat(generateCode.getClasspath().getFiles())
                .contains(normalRuntime.toFile())
                .doesNotContain(testRuntime.toFile());
        assertThat(generateTestCode.getClasspath().getFiles()).contains(testRuntime.toFile());
        assertThat(generateCode.getSourceParentDirectories().getFiles()).containsExactly(mainSources.toFile());
        assertThat(generateTestCode.getSourceParentDirectories().getFiles()).containsExactly(testSources.toFile());
        assertThat(build.getApplicationModel().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus/application-model/quarkus-application-model.dat").get().getAsFile());
        assertThat(build.getBuildType().get()).isEqualTo(QuarkusApplicationBuildType.FAST_JAR);
        assertThat(build.getOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("quarkus-builds/app/package").get().getAsFile());
        assertThat(build.getOutputName().get()).isEqualTo("test-1.2.3");
        assertThat(build.getAdditionalDescriptorShapeProperties().get()).isEmpty();
        assertThat(build.getBuildForkOptions().getJvmArgs().get()).containsExactly("-Dbuild-option=true");
        assertThat(build.getBuildForkOptions().getSystemProperties().get())
                .containsEntry("build.system.property", "true");
        assertThat(build.getBuildForkOptions().getEnvironment().get()).containsEntry("BUILD_ENV", "true");
        assertThat(build.getBuildForkOptions().getMaxHeapSize().get()).isEqualTo("768m");
        assertThat(build.getBuildForkOptions().getEnableAssertions().get()).isTrue();
        assertThat(build.getQuarkusBuildProperties().get())
                .containsEntry("common", "value")
                .containsEntry("build", "value")
                .doesNotContainKey("image");
        assertThat(build.getGradlePropertyNames().get()).containsExactly("quarkus.explicit.project");
        assertThat(extension.getCodegen().getProviders().get()).containsExactly("custom-provider", "other-provider");
        assertThat(extension.getCodegen().getInputNames().get()).containsExactly("custom-input", "other-input");
        assertThat(generateCode.getCodegenProviders().get()).containsExactly("custom-provider", "other-provider");
        assertThat(generateCode.getCodegenInputNames().get()).containsExactly("custom-input", "other-input");
        assertThat(generateCode.getGradlePropertyNames().get()).containsExactly("quarkus.explicit.project");
        assertThat(generateTestCode.getCodegenProviders().get()).containsExactly("custom-provider", "other-provider");
        assertThat(generateTestCode.getCodegenInputNames().get()).containsExactly("custom-input", "other-input");
        assertThat(generateTestCode.getGradlePropertyNames().get()).containsExactly("quarkus.explicit.project");
        assertThat(generateTestCode.getCodegenForkOptions().getJvmArgs().get()).containsExactly("-Dcodegen-option=true");
        assertThat(generateTestCode.getCodegenForkOptions().getSystemProperties().get())
                .containsEntry("codegen.system.property", "true");
        assertThat(project.getTasks().findByName("quarkusGenerateCode")).isNull();
        assertThat(project.getTasks().findByName("quarkusGenerateCodeDev")).isNull();
        assertThat(project.getTasks().findByName("quarkusGenerateCodeTests")).isNull();

        QuarkusApplicationImageBuildTask imageBuild = (QuarkusApplicationImageBuildTask) project.getTasks()
                .getByName("quarkusAppImageBuild");
        assertThat(imageBuild.getImageTag().isPresent()).isFalse();
        assertThat(imageBuild.getImageBuilder().get()).isEqualTo(QuarkusApplicationImageBuilder.JIB);
        assertThat(imageBuild.getQuarkusBuildProperties().get())
                .containsEntry("common", "value")
                .containsEntry("build", "value")
                .containsEntry("image", "value");
        assertThat(imageBuild.getOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("quarkus-builds/app/image-build").get().getAsFile());
        assertThat(imageBuild.getTaskDependencies().getDependencies(imageBuild))
                .extracting(Task::getName)
                .doesNotContain("quarkusAppBuild");

        QuarkusApplicationImagePushTask imagePush = (QuarkusApplicationImagePushTask) project.getTasks()
                .getByName("quarkusAppImagePush");
        assertThat(imagePush)
                .isInstanceOf(QuarkusApplicationImagePushTask.class);
        assertThat(imagePush.getOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("quarkus-builds/app/image-push").get().getAsFile());
        assertThat(imagePush.getTaskDependencies().getDependencies(imagePush))
                .extracting(Task::getName)
                .doesNotContain("quarkusAppBuild");

        assertJavaRuntimeAttributes(project, "quarkusApplicationRuntimeClasspathConfiguration");
        assertJavaRuntimeAttributes(project, "quarkusApplicationTestRuntimeClasspathConfiguration");
        assertJavaRuntimeAttributes(project, "quarkusApplicationConditionalRuntimeClasspathConfiguration");
        assertJavaRuntimeAttributes(project, "quarkusApplicationTestConditionalRuntimeClasspathConfiguration");
        assertJavaRuntimeAttributes(project, "quarkusApplicationDeploymentClasspathConfiguration");
        assertJavaRuntimeAttributes(project, "quarkusApplicationTestDeploymentClasspathConfiguration");
        assertJavaRuntimeAttributes(project, "quarkusApplicationCompileOnlyConfiguration");
        assertJavaRuntimeAttributes(project, "quarkusApplicationTestCompileOnlyConfiguration");
        assertPackageElementsVariant(project, "quarkusAppPackageElements", "app", "fast-jar", "quarkusAppBuild");

        assertThat(project.getTasks().getByName("quarkusAppDeployToDev"))
                .isInstanceOf(QuarkusApplicationDeployTask.class);
        QuarkusApplicationRunTask run = (QuarkusApplicationRunTask) project.getTasks().getByName("quarkusAppRun");
        assertThat(run.getTaskDependencies().getDependencies(run))
                .extracting(Task::getName)
                .contains("quarkusAppBuild");
        assertThat(run.getPackageResultFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-build-results/app/package/package-result.properties").get().getAsFile());
        QuarkusApplicationDevTask dev = (QuarkusApplicationDevTask) project.getTasks().getByName("quarkusApplicationDev");
        assertThat(dev.getContinuousBuild().get()).isFalse();
        assertThat(dev.getTaskDependencies().getDependencies(dev))
                .extracting(Task::getName)
                .contains("classes");
        assertThat(dev.getQuarkusBuildProperties().get())
                .containsEntry("common", "value")
                .containsEntry("dev", "value")
                .doesNotContainKey("build")
                .doesNotContainKey("image");
        assertThat(dev.getDevJvmArgs().get()).containsExactly("-Ddev-jvm-arg=true");
        assertThat(dev.getJvmArguments().get()).isEmpty();
        assertThat(dev.getApplicationArguments().get()).isEmpty();
        assertThat(dev.getModules().get()).isEmpty();
        assertThat(dev.getOpenJavaLang().get()).isFalse();
        assertThat(dev.getCompilerArguments().get()).isEmpty();
        assertThat(dev.getTests().get()).isEmpty();
        assertThat(dev.getDevSystemProperties().get()).containsEntry("dev.system.property", "true");
        assertThat(dev.getApplicationClasses().getFiles()).containsAll(mainSourceSet.getOutput().getClassesDirs().getFiles());
        assertThat(dev.getApplicationResources().getFiles()).contains(mainSourceSet.getOutput().getResourcesDir());
        assertThat(dev.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-dev/dev-iteration.properties").get().getAsFile());
        QuarkusApplicationPackageTask remoteDevBuild = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusApplicationRemoteDevBuild");
        assertThat(remoteDevBuild.getBuildName().get()).isEqualTo("remoteDev");
        assertThat(remoteDevBuild.getBuildType().get()).isEqualTo(QuarkusApplicationBuildType.MUTABLE_JAR);
        assertThat(remoteDevBuild.getOutputName().get()).isEqualTo(project.getName() + "-1.2.3");
        assertThat(remoteDevBuild.getOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("quarkus-remote-dev/build").get().getAsFile());
        assertThat(remoteDevBuild.getPackageResultFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-remote-dev/build-result/package-result.properties").get().getAsFile());
        assertThat(remoteDevBuild.getQuarkusBuildProperties().get())
                .containsEntry("common", "value")
                .containsEntry("remote-dev", "value")
                .doesNotContainKey("dev")
                .doesNotContainKey("build")
                .doesNotContainKey("image");
        assertThat(remoteDevBuild.getBuildForkOptions().getJvmArgs().get())
                .containsExactly("-Dbuild-option=true", "-Dremote-dev-jvm-arg=true");
        assertThat(remoteDevBuild.getBuildForkOptions().getSystemProperties().get())
                .containsEntry("build.system.property", "true")
                .containsEntry("remote.dev.system.property", "true");
        assertThat(project.getConfigurations().findByName("quarkusRemoteDevPackageElements")).isNull();
        QuarkusApplicationRemoteDevTask remoteDev = (QuarkusApplicationRemoteDevTask) project.getTasks()
                .getByName("quarkusApplicationRemoteDev");
        assertThat(remoteDev.getTaskDependencies().getDependencies(remoteDev))
                .extracting(Task::getName)
                .contains("quarkusApplicationRemoteDevBuild");
        assertThat(remoteDev.getBuildName().get()).isEqualTo("remoteDev");
        assertThat(remoteDev.getBuildType().get()).isEqualTo(QuarkusApplicationBuildType.MUTABLE_JAR);
        assertThat(remoteDev.getOutputName().get()).isEqualTo(project.getName() + "-1.2.3");
        assertThat(remoteDev.getPackageResultFile().get().getAsFile())
                .isEqualTo(remoteDevBuild.getPackageResultFile().get().getAsFile());
        assertThat(remoteDev.getPackageOutputDirectory().get().getAsFile())
                .isEqualTo(remoteDevBuild.getOutputDirectory().get().getAsFile());
        assertThat(remoteDev.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-remote-dev/build-result/remote-dev-result.properties").get().getAsFile());
        assertThat(remoteDev.getPackageSnapshotFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-remote-dev/snapshot/package-snapshot.tsv").get().getAsFile());
        assertThat(remoteDev.getCloseReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-remote-dev/snapshot/session-closed.txt").get().getAsFile());
        assertThat(project.getTasks().getByName("quarkusAppContinuousTest"))
                .isInstanceOf(QuarkusApplicationContinuousTestTask.class);
    }

    @Test
    void wiresImageAotAndDeploymentReceiptsWithoutExecutingExternalWork() {
        Project project = ProjectBuilder.builder().build();
        project.setVersion("1.2.3");
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);
        extension.builds(builds -> builds.fastJar("app", app -> {
            app.image(image -> {
                image.getRepository().set("example/app");
                image.getBuilder().set(QuarkusApplicationImageBuilder.DOCKER);
            });
            app.aotEnhancedImage(aot -> {
                aot.getAotFile().set(project.getLayout().getProjectDirectory().file("build/aot/app.aot"));
                aot.getAotFileProducerTaskName().set("produceAot");
            });
            app.deployments(deployments -> {
                deployments.kubernetes("dev");
                deployments.openshift("prod",
                        deployment -> deployment.getImageSource()
                                .set(QuarkusApplicationDeploymentImageSource.AOT_ENHANCED_IMAGE_PUSH));
            });
        }));

        QuarkusApplicationImageBuildTask imageBuild = (QuarkusApplicationImageBuildTask) project.getTasks()
                .getByName("quarkusAppImageBuild");
        assertThat(imageBuild.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-build-results/app/image-build/image-build-result.properties").get().getAsFile());

        QuarkusApplicationImagePushTask imagePush = (QuarkusApplicationImagePushTask) project.getTasks()
                .getByName("quarkusAppImagePush");
        assertThat(imagePush.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-build-results/app/image-push/image-push-result.properties").get().getAsFile());

        QuarkusApplicationAotEnhancedImageBuildTask aotBuild = (QuarkusApplicationAotEnhancedImageBuildTask) project
                .getTasks().getByName("quarkusAppAotEnhancedImageBuild");
        assertThat(aotBuild.getBaseImageReceiptFile().get().getAsFile())
                .isEqualTo(imageBuild.getReceiptFile().get().getAsFile());
        assertThat(aotBuild.getOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("quarkus-builds/app/aot-build").get().getAsFile());
        assertThat(aotBuild.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-build-results/app/aot-build/aot-image-build-result.properties").get().getAsFile());
        assertThat(aotBuild.getAotFileProducerTaskName().get()).isEqualTo("produceAot");

        QuarkusApplicationAotEnhancedImagePushTask aotPush = (QuarkusApplicationAotEnhancedImagePushTask) project
                .getTasks().getByName("quarkusAppAotEnhancedImagePush");
        assertThat(aotPush.getBaseImageReceiptFile().get().getAsFile())
                .isEqualTo(imagePush.getReceiptFile().get().getAsFile());
        assertThat(aotPush.getOutputDirectory().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory().dir("quarkus-builds/app/aot-push").get().getAsFile());
        assertThat(aotPush.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-build-results/app/aot-push/aot-image-push-result.properties").get().getAsFile());

        QuarkusApplicationDeployTask devDeploy = (QuarkusApplicationDeployTask) project.getTasks()
                .getByName("quarkusAppDeployToDev");
        assertThat(devDeploy.getDeploymentTarget().get()).isEqualTo(QuarkusApplicationDeploymentTarget.KUBERNETES);
        assertThat(devDeploy.getImageSource().get()).isEqualTo(QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH);
        assertThat(devDeploy.getNormalImagePushReceiptFile().get().getAsFile())
                .isEqualTo(imagePush.getReceiptFile().get().getAsFile());
        assertThat(devDeploy.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-build-results/app/deployments/dev/deployment-result.properties").get().getAsFile());

        QuarkusApplicationDeployTask prodDeploy = (QuarkusApplicationDeployTask) project.getTasks()
                .getByName("quarkusAppDeployToProd");
        assertThat(prodDeploy.getDeploymentTarget().get()).isEqualTo(QuarkusApplicationDeploymentTarget.OPENSHIFT);
        assertThat(prodDeploy.getImageSource().get())
                .isEqualTo(QuarkusApplicationDeploymentImageSource.AOT_ENHANCED_IMAGE_PUSH);
        assertThat(prodDeploy.getAotEnhancedImagePushReceiptFile().get().getAsFile())
                .isEqualTo(aotPush.getReceiptFile().get().getAsFile());
        assertThat(prodDeploy.getReceiptFile().get().getAsFile())
                .isEqualTo(project.getLayout().getBuildDirectory()
                        .file("quarkus-build-results/app/deployments/prod/deployment-result.properties").get().getAsFile());
    }

    @Test
    void registersImageTasksWithoutImageConfiguration() {
        Project project = ProjectBuilder.builder().build();
        project.setVersion("1.2.3");
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);
        extension.builds(builds -> builds.fastJar("app"));

        QuarkusApplicationImageBuildTask imageBuild = (QuarkusApplicationImageBuildTask) project.getTasks()
                .getByName("quarkusAppImageBuild");
        assertThat(imageBuild.getImageReference().isPresent()).isFalse();
        assertThat(imageBuild.getImageRepository().isPresent()).isFalse();
        assertThat(imageBuild.getImageTag().isPresent()).isFalse();
        assertThat(imageBuild.getImageBuilder().isPresent()).isFalse();
        assertThat(imageBuild.getTaskDependencies().getDependencies(imageBuild))
                .extracting(Task::getName)
                .doesNotContain("quarkusAppBuild");

        QuarkusApplicationImagePushTask imagePush = (QuarkusApplicationImagePushTask) project.getTasks()
                .getByName("quarkusAppImagePush");
        assertThat(imagePush.getImageReference().isPresent()).isFalse();
        assertThat(imagePush.getImageRepository().isPresent()).isFalse();
        assertThat(imagePush.getImageTag().isPresent()).isFalse();
        assertThat(imagePush.getImageBuilder().isPresent()).isFalse();
        assertThat(imagePush.getTaskDependencies().getDependencies(imagePush))
                .extracting(Task::getName)
                .doesNotContain("quarkusAppBuild");
    }

    @Test
    void assignsHelpfulDescriptionsToRegisteredTasks() {
        Project project = ProjectBuilder.builder().build();
        project.setVersion("1.2.3");
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);
        extension.builds(builds -> {
            builds.fastJar("fast", build -> {
                build.image(image -> image.getRepository().set("example/fast"));
                build.aotEnhancedImage(aot -> aot.getAotFile().set(
                        project.getLayout().getProjectDirectory().file("build/aot/fast.aot")));
                build.deployments(deployments -> {
                    deployments.kubernetes("dev");
                    deployments.openshift("prod", deployment -> deployment.getImageSource()
                            .set(QuarkusApplicationDeploymentImageSource.AOT_ENHANCED_IMAGE_PUSH));
                });
            });
            builds.legacyJar("legacy");
            builds.mutableJar("mutable");
            builds.uberJar("uber");
            builds.nativeExecutable("native");
            builds.nativeSources("nativeSources");
        });

        assertTaskDescription(project, "quarkusApplicationModel",
                "Resolves the Quarkus application model used by named application build tasks.");
        assertTaskDescription(project, "quarkusApplicationCodegenModel",
                "Resolves the Quarkus application model used before main-source code generation.");
        assertTaskDescription(project, "quarkusApplicationTestCodegenModel",
                "Resolves the Quarkus application model used before test-source code generation.");
        assertTaskDescription(project, "quarkusApplicationGenerateCode",
                "Runs Quarkus code generators for main sources.");
        assertTaskDescription(project, "quarkusApplicationGenerateTestCode",
                "Runs Quarkus code generators for test sources.");
        assertTaskDescription(project, "quarkusApplicationDev",
                "Runs Gradle-native Quarkus dev mode using Gradle continuous build.");
        assertTaskDescription(project, "quarkusApplicationRemoteDevBuild",
                "Builds the internal mutable-jar package used by Gradle-native Quarkus remote dev.");
        assertTaskDescription(project, "quarkusApplicationRemoteDev",
                "Runs Gradle-native Quarkus remote dev using an internal mutable-jar package.");

        assertTaskDescription(project, "quarkusFastBuild", "Builds the 'fast' fast-jar Quarkus application.");
        assertTaskDescription(project, "quarkusLegacyBuild", "Builds the 'legacy' legacy-jar Quarkus application.");
        assertTaskDescription(project, "quarkusMutableBuild", "Builds the 'mutable' mutable-jar Quarkus application.");
        assertTaskDescription(project, "quarkusUberBuild", "Builds the 'uber' uber-jar Quarkus application.");
        assertTaskDescription(project, "quarkusNativeBuild",
                "Builds the 'native' native executable Quarkus application.");
        assertTaskDescription(project, "quarkusNativeSourcesBuild",
                "Generates native-image sources for the 'nativeSources' Quarkus application.");

        assertTaskDescription(project, "quarkusNativeNativeTest",
                "Runs tests against the 'native' native executable.");
        assertTaskDescription(project, "quarkusFastImageBuild",
                "Builds the container image for the 'fast' Quarkus application build.");
        assertTaskDescription(project, "quarkusFastImagePush",
                "Builds and pushes the container image for the 'fast' Quarkus application build.");
        assertTaskDescription(project, "quarkusFastAotTraining",
                "Runs AOT training for the 'fast' Quarkus application build.");
        assertTaskDescription(project, "quarkusFastAotEnhancedImageBuild",
                "Builds the AOT-enhanced container image for the 'fast' Quarkus application build.");
        assertTaskDescription(project, "quarkusFastAotEnhancedImagePush",
                "Builds and pushes the AOT-enhanced container image for the 'fast' Quarkus application build.");
        assertTaskDescription(project, "quarkusFastDeployToDev",
                "Deploys the 'fast' Quarkus application build to the 'dev' kubernetes target.");
        assertTaskDescription(project, "quarkusFastDeployToProd",
                "Deploys the 'fast' Quarkus application build to the 'prod' openshift target.");
        assertTaskDescription(project, "quarkusFastRun",
                "Runs the 'fast' Quarkus application from its package build output.");
        assertTaskDescription(project, "quarkusFastContinuousTest",
                "Reserved for future Gradle-native Quarkus continuous testing for the 'fast' application; currently fails when executed.");
        assertThat(project.getTasks().findByName("quarkusFastDev")).isNull();
        assertThat(project.getTasks().findByName("quarkusFastRemoteDev")).isNull();
        assertThat(project.getTasks().findByName("quarkusMutableRemoteDev")).isNull();
        assertThat(project.getTasks().findByName("quarkusLegacyRemoteDev")).isNull();
        assertThat(project.getTasks().findByName("quarkusUberRemoteDev")).isNull();
        assertThat(project.getTasks().findByName("quarkusNativeRun")).isNull();
        assertThat(project.getTasks().findByName("quarkusNativeSourcesRun")).isNull();
        assertThat(project.getTasks().findByName("quarkusNativeRemoteDev")).isNull();
        assertThat(project.getTasks().findByName("quarkusNativeSourcesRemoteDev")).isNull();

        assertTaskGroup(project, "quarkusFastBuild", "quarkus application");
        assertTaskGroup(project, "quarkusFastRun", "quarkus application");
        assertTaskGroup(project, "quarkusApplicationRemoteDevBuild", "quarkus application");
        assertTaskGroup(project, "quarkusApplicationRemoteDev", "quarkus application");
        assertTaskGroup(project, "quarkusFastImageBuild", "quarkus application");
        assertTaskGroup(project, "quarkusFastDeployToDev", "quarkus application");
        assertTaskGroup(project, "quarkusApplicationDev", "quarkus application");
        assertTaskGroup(project, "quarkusNativeNativeTest", "verification");
        assertTaskGroup(project, "quarkusFastAotTraining", "verification");
    }

    @Test
    void wiresArchiveNamingAndRunnerSuffixConventionsByOutputShape() {
        Project project = ProjectBuilder.builder().withName("archive-app").build();
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);
        project.setVersion("1.2.3");

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);
        extension.builds(builds -> {
            builds.fastJar("fast", fast -> assertThat(fast.getOutputName().get()).isEqualTo("archive-app-1.2.3"));
            builds.mutableJar("mutable");
            builds.legacyJar("legacy");
            builds.uberJar("uber", uber -> {
                uber.getArchiveBaseNameSuffix().set("-cli");
                uber.getArchiveRunnerSuffix().set("-exec");
                uber.getArchiveAddRunnerSuffix().set(false);
            });
            builds.nativeExecutable("native");
            builds.nativeSources("nativeSources");
        });

        Map<String, QuarkusApplicationBuild> buildsByName = new LinkedHashMap<>();
        extension.getBuilds().all(build -> buildsByName.put(build.getName(), build));

        assertThat(buildsByName.get("fast"))
                .isNotInstanceOf(QuarkusApplicationRunnerOutput.class);
        assertThat(buildsByName.get("mutable"))
                .isNotInstanceOf(QuarkusApplicationRunnerOutput.class);
        assertThat(buildsByName.get("legacy"))
                .isInstanceOf(QuarkusApplicationRunnerOutput.class);
        assertThat(buildsByName.get("uber"))
                .isInstanceOf(QuarkusApplicationRunnerOutput.class);
        assertThat(buildsByName.get("native"))
                .isInstanceOf(QuarkusApplicationRunnerOutput.class);
        assertThat(buildsByName.get("nativeSources"))
                .isInstanceOf(QuarkusApplicationRunnerOutput.class);

        QuarkusApplicationPackageTask fast = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusFastBuild");
        QuarkusApplicationPackageTask mutable = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusMutableBuild");
        QuarkusApplicationPackageTask legacy = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusLegacyBuild");
        QuarkusApplicationPackageTask uber = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusUberBuild");
        QuarkusApplicationBuildTask nativeExecutable = (QuarkusApplicationBuildTask) project.getTasks()
                .getByName("quarkusNativeBuild");
        QuarkusApplicationBuildTask nativeSources = (QuarkusApplicationBuildTask) project.getTasks()
                .getByName("quarkusNativeSourcesBuild");

        assertThat(fast.getOutputName().get()).isEqualTo("archive-app-1.2.3");
        assertThat(mutable.getOutputName().get()).isEqualTo("archive-app-1.2.3");
        assertThat(legacy.getOutputName().get()).isEqualTo("archive-app-1.2.3");
        assertThat(uber.getOutputName().get()).isEqualTo("archive-app-cli-1.2.3");
        assertThat(nativeExecutable.getOutputName().get()).isEqualTo("archive-app-1.2.3");
        assertThat(nativeSources.getOutputName().get()).isEqualTo("archive-app-1.2.3");

        assertThat(fast.getAdditionalDescriptorShapeProperties().get()).isEmpty();
        assertThat(mutable.getAdditionalDescriptorShapeProperties().get()).isEmpty();
        assertThat(legacy.getAdditionalDescriptorShapeProperties().get()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "quarkus.package.runner-suffix", "-runner",
                "quarkus.package.jar.add-runner-suffix", "true"));
        assertThat(uber.getAdditionalDescriptorShapeProperties().get()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "quarkus.package.runner-suffix", "-exec",
                "quarkus.package.jar.add-runner-suffix", "false"));
        assertThat(nativeExecutable.getAdditionalDescriptorShapeProperties().get()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "quarkus.package.runner-suffix", "-runner",
                "quarkus.package.jar.add-runner-suffix", "true"));
        assertThat(nativeSources.getAdditionalDescriptorShapeProperties().get()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "quarkus.package.runner-suffix", "-runner",
                "quarkus.package.jar.add-runner-suffix", "true"));
    }

    @Test
    void primaryJarFileIsKnownBeforePackageTaskExecutes() {
        Project project = ProjectBuilder.builder().withName("archive-app").build();
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);
        project.setVersion("1.2.3");

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);
        extension.builds(builds -> {
            builds.fastJar("fast");
            builds.mutableJar("mutable");
            builds.legacyJar("legacy");
            builds.uberJar("uber", uber -> {
                uber.getArchiveBaseNameSuffix().set("-cli");
                uber.getArchiveRunnerSuffix().set("-exec");
                uber.getArchiveAddRunnerSuffix().set(false);
            });
        });

        Path buildDirectory = project.getLayout().getBuildDirectory().get().getAsFile().toPath();
        assertPrimaryJarFile(project, "quarkusFastBuild",
                buildDirectory.resolve(Path.of("quarkus-builds", "fast", "package", "quarkus-run.jar")));
        assertPrimaryJarFile(project, "quarkusMutableBuild",
                buildDirectory.resolve(Path.of("quarkus-builds", "mutable", "package", "quarkus-run.jar")));
        assertPrimaryJarFile(project, "quarkusLegacyBuild",
                buildDirectory.resolve(Path.of("quarkus-builds", "legacy", "package", "archive-app-1.2.3-runner.jar")));
        assertPrimaryJarFile(project, "quarkusUberBuild",
                buildDirectory.resolve(Path.of("quarkus-builds", "uber", "package", "archive-app-cli-1.2.3.jar")));
    }

    @Test
    void rejectsNamedTaskCollisionsAtRegistrationTime() {
        Project project = ProjectBuilder.builder().build();
        project.getTasks().register("quarkusAppBuild");
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);

        assertThatThrownBy(() -> extension.builds(builds -> builds.fastJar("app")))
                .isInstanceOf(InvalidUserCodeException.class)
                .hasRootCauseInstanceOf(GradleException.class)
                .hasRootCauseMessage("Quarkus application task name 'quarkusAppBuild' collides with an existing task");
    }

    @Test
    void rejectsUnspecifiedProjectVersionOnlyWhenDefaultOutputNameConventionIsUsed() {
        Project project = ProjectBuilder.builder().withName("unnamed-app").build();
        project.getPluginManager().apply(QuarkusApplicationPlugin.class);

        QuarkusApplicationExtension extension = project.getExtensions().getByType(QuarkusApplicationExtension.class);
        extension.builds(builds -> {
            builds.fastJar("defaultName");
            builds.fastJar("explicitName", build -> build.getOutputName().set("explicit-unspecified"));
        });

        QuarkusApplicationPackageTask defaultName = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusDefaultNameBuild");
        QuarkusApplicationPackageTask explicitName = (QuarkusApplicationPackageTask) project.getTasks()
                .getByName("quarkusExplicitNameBuild");

        assertThatThrownBy(() -> defaultName.getOutputName().get())
                .hasRootCauseInstanceOf(GradleException.class)
                .hasRootCauseMessage("Quarkus application archiveVersion defaults to project.version, "
                        + "but project.version is unspecified. Configure project.version, archiveVersion, or outputName.");
        assertThat(explicitName.getOutputName().get()).isEqualTo("explicit-unspecified");
    }

    @Test
    void pluginIdCreatesExtensionWithConfigurationCacheAndIsolatedProjects() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle.kts"), "");
        writeString(testProjectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("io.quarkus.application")
                }

                quarkusApplication {
                    builds {
                        fastJar("app")
                    }
                }

                check(extensions.findByName("quarkusApplication") != null)
                check(tasks.findByName("quarkusAppBuild") != null)
                """);

        var result = runner("tasks").build();

        assertThat(result.task(":tasks").getOutcome()).isEqualTo(SUCCESS);
    }

    @Test
    void launchTaskOptionsAreAvailableForRunAndDevTasks() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle.kts"), "");
        writeString(testProjectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("io.quarkus.application")
                }

                quarkusApplication {
                    builds {
                        fastJar("app")
                    }
                }
                """);

        BuildResult runHelp = runner("help", "--task", "quarkusAppRun").build();
        assertThat(runHelp.getOutput())
                .contains("--jvm-args")
                .contains("--quarkus-args");

        BuildResult devHelp = runner("help", "--task", "quarkusApplicationDev").build();
        assertThat(devHelp.getOutput())
                .contains("--jvm-args")
                .contains("--quarkus-args")
                .contains("--modules")
                .contains("--open-lang-package")
                .contains("--compiler-args")
                .contains("--tests");
    }

    @Test
    void warnsWhenLegacyPluginIsAlsoApplied() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle.kts"), "");
        writeString(testProjectDir.resolve("buildSrc/build.gradle.kts"), """
                plugins {
                    `java-gradle-plugin`
                }

                gradlePlugin {
                    plugins {
                        create("legacyQuarkus") {
                            id = "io.quarkus"
                            implementationClass = "LegacyQuarkusPlugin"
                        }
                    }
                }
                """);
        writeString(testProjectDir.resolve("buildSrc/src/main/java/LegacyQuarkusPlugin.java"), """
                import org.gradle.api.Plugin;
                import org.gradle.api.Project;

                public final class LegacyQuarkusPlugin implements Plugin<Project> {
                    @Override
                    public void apply(Project project) {
                    }
                }
                """);
        writeString(testProjectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("io.quarkus.application")
                    id("io.quarkus")
                }

                val java = extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>()
                val mainSourceDirs = java.sourceSets.named("main").get().java.srcDirs
                val testSourceDirs = java.sourceSets.named("test").get().java.srcDirs

                check(mainSourceDirs.none {
                    it.invariantSeparatorsPath.contains("generated/sources/quarkus-application")
                }) {
                    "new plugin generated sources must not be added to the shared main source set"
                }
                check(testSourceDirs.none {
                    it.invariantSeparatorsPath.contains("generated/sources/quarkus-application")
                }) {
                    "new plugin generated sources must not be added to the shared test source set"
                }
                """);

        var result = runner("tasks").build();

        assertThat(result.getOutput())
                .contains("Both 'io.quarkus.application' and legacy 'io.quarkus' are applied")
                .contains("migration mode");
    }

    @Test
    void buildsTinyJvmPackagesWithPluginOwnedApplicationModelAndProviderBackedConsumers() throws IOException {
        writeTinyApplication();

        BuildResult result = runnerWithBuildCache("verifyNamedPackages").build();

        assertThat(result.task(":quarkusApplicationModelPomClosure").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusFastBuild").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusMutableBuild").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusUberBuild").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusLegacyBuild").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":verifyNamedPackages").getOutcome()).isEqualTo(SUCCESS);

        assertFastJarPackageResult("fast");
        assertMutableJarPackageResult("mutable");
        assertUberJarPackageResult("uber");
        assertLegacyJarPackageResult("legacy");
        assertThat(testProjectDir.resolve(Path.of("build", "quarkus", "application-model",
                "quarkus-application-model.dat"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("build", "quarkus", "application-model", "pom-closure",
                "quarkusApplicationModel.properties"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("build", "verification", "named-package-results.txt")))
                .hasContent("""
                        fast
                        mutable
                        uber
                        legacy
                        """);

        BuildResult secondResult = runnerWithBuildCache("verifyNamedPackages").build();

        assertThat(secondResult.task(":quarkusApplicationModelPomClosure").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusApplicationModel").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusFastBuild").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusMutableBuild").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusUberBuild").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusLegacyBuild").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":verifyNamedPackages").getOutcome()).isEqualTo(UP_TO_DATE);
    }

    @Test
    void packageElementsVariantBuildsProducerPackageTaskForProjectDependencyConsumer() throws IOException {
        writePackageVariantProducerConsumerApplication();

        BuildResult result = runner(":consumer:verifyServer").build();

        assertThat(result.task(":app:quarkusFastBuild").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":consumer:verifyServer").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("serverFile=quarkus-run.jar");
    }

    @Test
    void buildsTinyFastJarInMultiProjectBuildWithIsolatedProjects() throws IOException {
        writeMultiProjectApplication(false);

        BuildResult result = runner(":app:quarkusAppBuild").build();

        assertThat(result.task(":app:quarkusApplicationModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:quarkusAppBuild").getOutcome()).isEqualTo(SUCCESS);
        assertThat(testProjectDir.resolve(Path.of("app", "build", "quarkus-builds", "app", "package",
                "quarkus-run.jar"))).isRegularFile();
    }

    @Test
    void buildsTinyFastJarWithPlainProjectDependencyAndIsolatedProjects() throws IOException {
        writeMultiProjectApplication(true);

        BuildResult result = runner(":app:quarkusAppBuild").build();

        assertThat(result.task(":lib:jar").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:quarkusApplicationModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:quarkusAppBuild").getOutcome()).isEqualTo(SUCCESS);
        assertThat(testProjectDir.resolve(Path.of("app", "build", "quarkus-builds", "app", "package",
                "quarkus-run.jar"))).isRegularFile();
    }

    @Test
    void applicationModelsDependOnSameProjectKordampJandexTask() throws IOException {
        writeApplicationWithJandexTask("jandex");

        BuildResult result = runner("verifyJandexModelWiring").build();

        assertThat(result.task(":jandex").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationDevModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":verifyJandexModelWiring").getOutcome()).isEqualTo(SUCCESS);
    }

    @Test
    void applicationModelsDependOnSameProjectVlsiJandexTask() throws IOException {
        writeApplicationWithJandexTask("processJandexIndex");

        BuildResult result = runner("verifyJandexModelWiring").build();

        assertThat(result.task(":processJandexIndex").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationDevModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":verifyJandexModelWiring").getOutcome()).isEqualTo(SUCCESS);
    }

    @Test
    void devTaskFailsEarlyWithoutContinuousBuildInTestKitBuild() throws IOException {
        writeMultiProjectApplication(false);

        assertThatThrownBy(() -> runner(":app:quarkusApplicationDev").build())
                .isInstanceOf(UnexpectedBuildFailure.class)
                .hasMessageContaining("requires Gradle continuous build")
                .hasMessageContaining("--continuous");
    }

    @Test
    void compilesGeneratedSourcesWithPlainProjectDependencyAndIsolatedProjects() throws IOException {
        writeMultiProjectCodegenApplication();

        BuildResult result = runner(":app:compileJava").build();

        assertThat(result.task(":lib:compileJava").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:quarkusApplicationModelPomClosure")).isNull();
        assertThat(result.task(":app:quarkusApplicationCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:quarkusApplicationGenerateCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:compileJava").getOutcome()).isEqualTo(SUCCESS);
        assertThat(testProjectDir.resolve(Path.of("app", "build", "generated", "sources", "quarkus-application", "main",
                "org", "acme", "generated", "GeneratedFromLib.java"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("app", "build", "classes", "java", "main",
                "org", "acme", "generated", "GeneratedFromLib.class"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("app", "build", "classes", "java", "main",
                "org", "acme", "App.class"))).isRegularFile();

        BuildResult secondResult = runner(":app:compileJava").build();

        assertThat(secondResult.getOutput()).contains("Configuration cache entry reused.");
        assertThat(secondResult.task(":lib:compileJava").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":app:quarkusApplicationCodegenModel").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":app:quarkusApplicationGenerateCode").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":app:compileJava").getOutcome()).isEqualTo(UP_TO_DATE);
    }

    @Test
    void compilesGeneratedSourcesFromStubbedCodegenWithConfigurationCacheAndIsolatedProjects() throws IOException {
        writeStubbedCodegenApplication();

        BuildResult result = runner("compileTestJava").build();

        assertThat(result.task(":quarkusApplicationCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationTestCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationGenerateCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationGenerateTestCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":compileTestJava").getOutcome()).isEqualTo(SUCCESS);
        assertThat(testProjectDir.resolve(Path.of("build", "generated", "sources", "quarkus-application", "main",
                "org", "acme", "generated", "GeneratedMain.java"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("build", "generated", "sources", "quarkus-application", "test",
                "org", "acme", "generated", "GeneratedTest.java"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("build", "classes", "java", "main",
                "org", "acme", "generated", "GeneratedMain.class"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("build", "classes", "java", "test",
                "org", "acme", "GeneratedSourceUsage.class"))).isRegularFile();

        BuildResult secondResult = runner("compileTestJava").build();

        assertThat(secondResult.task(":quarkusApplicationCodegenModel").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusApplicationTestCodegenModel").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusApplicationGenerateCode").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":quarkusApplicationGenerateTestCode").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":compileJava").getOutcome()).isEqualTo(UP_TO_DATE);
        assertThat(secondResult.task(":compileTestJava").getOutcome()).isEqualTo(UP_TO_DATE);
    }

    @Test
    void compilesRealAvroGeneratedSourcesWithConfigurationCacheAndIsolatedProjects() throws IOException {
        writeRealAvroCodegenApplication();

        BuildResult result = runner("compileJava").build();

        assertThat(result.task(":quarkusApplicationCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationGenerateCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(SUCCESS);
        assertThat(containsFileNamed(testProjectDir.resolve(Path.of("build", "generated", "sources",
                "quarkus-application", "main")), "Greeting.java")).isTrue();
        assertThat(testProjectDir.resolve(Path.of("build", "classes", "java", "main",
                "org", "acme", "AvroUsage.class"))).isRegularFile();
        assertThat(testProjectDir.resolve(Path.of("build", "classes", "java", "main",
                "org", "acme", "quarkus", "hello", "Greeting.class"))).isRegularFile();
    }

    @Test
    void unrelatedTaskDoesNotResolveConditionalDependencyConfigurations() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'conditional-laziness'\n");
        writeString(testProjectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.quarkus.application'
                }

                configurations.matching {
                    it.name == 'quarkusApplicationConditionalRuntimeClasspathConfiguration' ||
                            it.name == 'quarkusApplicationTestConditionalRuntimeClasspathConfiguration'
                }.configureEach {
                    incoming.beforeResolve {
                        throw new RuntimeException("${name} must not resolve for unrelated tasks")
                    }
                }

                tasks.register('unrelated') {
                    doLast {
                        println 'unrelated task ran'
                    }
                }
                """);

        BuildResult result = runner("unrelated").build();

        assertThat(result.task(":unrelated").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("unrelated task ran");
    }

    @Test
    void deploymentClasspathUsesLocalExtensionDeploymentVariantWithIsolatedProjects() throws IOException {
        writeLocalExtensionApplication();

        BuildResult result = runner(":app:resolveDeploymentClasspath").build();

        assertThat(result.task(":deployment-ext:jar").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:resolveDeploymentClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput())
                .contains("deploymentFile=deployment-ext-1.0.jar")
                .doesNotContain("Could not find org.acme:runtime-ext-deployment:1.0");
    }

    @Test
    void conditionalDependencyValueSourcesIgnoreLocalExtensionRuntimeJarDescriptors() throws IOException {
        writeLocalExtensionApplication("""
                conditionalDependencies = ['org.poison:should-not-resolve::jar:1.0']
                conditionalDevDependencies = ['org.poison:should-not-resolve-dev::jar:1.0']
                """);

        BuildResult result = runner(":app:resolveRuntimeClasspath", ":app:resolveDevRuntimeClasspath").build();

        assertThat(result.task(":app:resolveRuntimeClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:resolveDevRuntimeClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput())
                .contains("runtimeFile=runtime-ext-1.0.jar")
                .contains("devRuntimeFile=runtime-ext-1.0.jar")
                .doesNotContain("org.poison:should-not-resolve")
                .doesNotContain("org.poison:should-not-resolve-dev");
    }

    @Test
    void deploymentClasspathIgnoresLocalExtensionRuntimeJarDeploymentDescriptor() throws IOException {
        writeLocalExtensionApplication("deploymentArtifact = 'org.poison:wrong-deployment:1.0'\n");

        BuildResult result = runner(":app:resolveDeploymentClasspath").build();

        assertThat(result.task(":deployment-ext:jar").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:resolveDeploymentClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput())
                .contains("deploymentFile=deployment-ext-1.0.jar")
                .doesNotContain("org.poison:wrong-deployment");
    }

    @Test
    void resolvesConditionSatisfiedRuntimeExtensionFromSyntheticDescriptors() throws IOException {
        writeSyntheticConditionalExtensionRepository(testProjectDir.resolve("repo"));
        writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'conditional-resolution'\n");
        writeString(testProjectDir.resolve("build.gradle"), """
                import org.gradle.api.DefaultTask
                import org.gradle.api.file.ConfigurableFileCollection
                import org.gradle.api.file.RegularFileProperty
                import org.gradle.api.tasks.Classpath
                import org.gradle.api.tasks.OutputFile
                import org.gradle.api.tasks.TaskAction

                plugins {
                    id 'io.quarkus.application'
                }

                repositories {
                    maven {
                        url = uri('repo')
                    }
                }

                dependencies {
                    implementation 'org.acme:parent-extension:1.0'
                    implementation 'org.condition:present:1.0'
                }

                abstract class WriteClasspath extends DefaultTask {
                    @Classpath
                    abstract ConfigurableFileCollection getClasspath()

                    @OutputFile
                    abstract RegularFileProperty getOutputFile()

                    @TaskAction
                    void write() {
                        outputFile.get().asFile.text = classpath.files*.name.sort().join('\\n') + '\\n'
                    }
                }

                tasks.register('writeRuntimeClasspath', WriteClasspath) {
                    classpath.from(configurations.named('quarkusApplicationRuntimeClasspathConfiguration'))
                    outputFile.set(layout.buildDirectory.file('resolved-runtime.txt'))
                }
                """);

        BuildResult result = runner("writeRuntimeClasspath").build();

        assertThat(result.task(":writeRuntimeClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(testProjectDir.resolve("build/resolved-runtime.txt"))
                .content()
                .contains("parent-extension-1.0.jar")
                .contains("present-1.0.jar")
                .contains("satisfied-extension-1.0.jar")
                .doesNotContain("missing-extension-1.0.jar");
    }

    @Test
    void resolvesConditionalRuntimeExtensionSatisfiedByProjectDependencyRuntimeClasspath() throws IOException {
        writeSyntheticConditionalExtensionRepository(testProjectDir.resolve("repo"));
        writeString(testProjectDir.resolve("settings.gradle"), """
                rootProject.name = 'conditional-project-runtime-resolution'
                include 'app', 'lib'
                """);
        writeString(testProjectDir.resolve("lib/build.gradle"), """
                plugins {
                    id 'java-library'
                }

                repositories {
                    maven {
                        url = uri('../repo')
                    }
                }

                dependencies {
                    implementation 'org.condition:present:1.0'
                }
                """);
        writeString(testProjectDir.resolve("app/build.gradle"), """
                import org.gradle.api.DefaultTask
                import org.gradle.api.file.ConfigurableFileCollection
                import org.gradle.api.file.RegularFileProperty
                import org.gradle.api.tasks.Classpath
                import org.gradle.api.tasks.OutputFile
                import org.gradle.api.tasks.TaskAction

                plugins {
                    id 'io.quarkus.application'
                }

                repositories {
                    maven {
                        url = uri('../repo')
                    }
                }

                dependencies {
                    implementation project(':lib')
                    implementation 'org.acme:parent-extension:1.0'
                }

                abstract class WriteClasspath extends DefaultTask {
                    @Classpath
                    abstract ConfigurableFileCollection getClasspath()

                    @OutputFile
                    abstract RegularFileProperty getOutputFile()

                    @TaskAction
                    void write() {
                        outputFile.get().asFile.text = classpath.files*.name.sort().join('\\n') + '\\n'
                    }
                }

                tasks.register('writeRuntimeClasspath', WriteClasspath) {
                    classpath.from(configurations.named('quarkusApplicationRuntimeClasspathConfiguration'))
                    outputFile.set(layout.buildDirectory.file('resolved-runtime.txt'))
                }

                tasks.register('writeDevRuntimeClasspath', WriteClasspath) {
                    classpath.from(configurations.named('quarkusApplicationDevRuntimeClasspathConfiguration'))
                    outputFile.set(layout.buildDirectory.file('resolved-dev-runtime.txt'))
                }
                """);

        BuildResult result = runner(":app:writeRuntimeClasspath", ":app:writeDevRuntimeClasspath").build();

        assertThat(result.task(":app:writeRuntimeClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":app:writeDevRuntimeClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(testProjectDir.resolve("app/build/resolved-runtime.txt"))
                .content()
                .contains("parent-extension-1.0.jar")
                .contains("present-1.0.jar")
                .contains("satisfied-extension-1.0.jar")
                .doesNotContain("missing-extension-1.0.jar");
        assertThat(testProjectDir.resolve("app/build/resolved-dev-runtime.txt"))
                .content()
                .contains("parent-extension-1.0.jar")
                .contains("present-1.0.jar")
                .contains("satisfied-extension-1.0.jar")
                .doesNotContain("missing-extension-1.0.jar");
    }

    @Test
    void resolvesConditionalDevDependenciesOnlyInDevRuntimeClasspath() throws IOException {
        writeSyntheticConditionalDevExtensionRepository(testProjectDir.resolve("repo"));
        writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'conditional-dev-resolution'\n");
        writeString(testProjectDir.resolve("build.gradle"), """
                import org.gradle.api.DefaultTask
                import org.gradle.api.file.ConfigurableFileCollection
                import org.gradle.api.file.RegularFileProperty
                import org.gradle.api.tasks.Classpath
                import org.gradle.api.tasks.OutputFile
                import org.gradle.api.tasks.TaskAction

                plugins {
                    id 'io.quarkus.application'
                }

                repositories {
                    maven {
                        url = uri('repo')
                    }
                }

                dependencies {
                    implementation 'org.acme:parent-extension:1.0'
                }

                abstract class WriteClasspath extends DefaultTask {
                    @Classpath
                    abstract ConfigurableFileCollection getClasspath()

                    @OutputFile
                    abstract RegularFileProperty getOutputFile()

                    @TaskAction
                    void write() {
                        outputFile.get().asFile.text = classpath.files*.name.sort().join('\\n') + '\\n'
                    }
                }

                tasks.register('writeRuntimeClasspath', WriteClasspath) {
                    classpath.from(configurations.named('quarkusApplicationRuntimeClasspathConfiguration'))
                    outputFile.set(layout.buildDirectory.file('resolved-runtime.txt'))
                }

                tasks.register('writeDevRuntimeClasspath', WriteClasspath) {
                    classpath.from(configurations.named('quarkusApplicationDevRuntimeClasspathConfiguration'))
                    outputFile.set(layout.buildDirectory.file('resolved-dev-runtime.txt'))
                }
                """);

        BuildResult result = runner("writeRuntimeClasspath", "writeDevRuntimeClasspath").build();

        assertThat(result.task(":writeRuntimeClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":writeDevRuntimeClasspath").getOutcome()).isEqualTo(SUCCESS);
        assertThat(testProjectDir.resolve("build/resolved-runtime.txt"))
                .content()
                .contains("parent-extension-1.0.jar")
                .doesNotContain("parent-extension-dev-1.0.jar");
        assertThat(testProjectDir.resolve("build/resolved-dev-runtime.txt"))
                .content()
                .contains("parent-extension-1.0.jar")
                .contains("parent-extension-dev-1.0.jar");
    }

    private void writeLocalExtensionApplication() throws IOException {
        writeLocalExtensionApplication("");
    }

    private void writeLocalExtensionApplication(String extensionConfiguration) throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), """
                rootProject.name = 'local-extension-application'
                include 'app', 'runtime-ext', 'deployment-ext'
                """);
        writeString(testProjectDir.resolve("app/build.gradle"), """
                plugins {
                    id 'java'
                    id 'io.quarkus.application'
                }

                version = '1.0'

                dependencies {
                    implementation project(':runtime-ext')
                }

                tasks.register('resolveDeploymentClasspath') {
                    def deploymentClasspath = configurations.named('quarkusApplicationDeploymentClasspathConfiguration')
                    inputs.files(deploymentClasspath)
                    doLast {
                        def files = deploymentClasspath.get().files
                        assert files*.name.contains('deployment-ext-1.0.jar')
                        files*.name.sort().each { println "deploymentFile=${it}" }
                    }
                }

                tasks.register('resolveRuntimeClasspath') {
                    def runtimeClasspath = configurations.named('quarkusApplicationRuntimeClasspathConfiguration')
                    inputs.files(runtimeClasspath)
                    doLast {
                        runtimeClasspath.get().files*.name.sort().each { println "runtimeFile=${it}" }
                    }
                }

                tasks.register('resolveDevRuntimeClasspath') {
                    def runtimeClasspath = configurations.named('quarkusApplicationDevRuntimeClasspathConfiguration')
                    inputs.files(runtimeClasspath)
                    doLast {
                        runtimeClasspath.get().files*.name.sort().each { println "devRuntimeFile=${it}" }
                    }
                }
                """);
        writeString(testProjectDir.resolve("runtime-ext/build.gradle"), """
                plugins {
                    id 'java'
                    id 'io.quarkus.extension'
                }

                group = 'org.acme'
                version = '1.0'

                quarkusExtension {
                    disableValidation = true
                    deploymentModule = 'deployment-ext'
                    %s
                }
                """.formatted(extensionConfiguration));
        writeString(testProjectDir.resolve("runtime-ext/src/main/java/org/acme/runtime/RuntimeExtension.java"), """
                package org.acme.runtime;

                public final class RuntimeExtension {
                }
                """);
        writeString(testProjectDir.resolve("deployment-ext/build.gradle"), """
                plugins {
                    id 'java-library'
                }

                group = 'org.acme'
                version = '1.0'
                """);
        writeString(testProjectDir.resolve("deployment-ext/src/main/java/org/acme/deployment/DeploymentExtension.java"), """
                package org.acme.deployment;

                public final class DeploymentExtension {
                }
                """);
    }

    private void writeTinyApplication() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'tiny-quarkus-app'\n");
        writeString(testProjectDir.resolve("gradle.properties"), "version = 999-SNAPSHOT\n");
        writeString(testProjectDir.resolve("build.gradle"), """
                import io.quarkus.gradle.application.tasks.QuarkusApplicationPackageTask
                import org.gradle.api.DefaultTask
                import org.gradle.api.GradleException
                import org.gradle.api.file.RegularFileProperty
                import org.gradle.api.tasks.InputFile
                import org.gradle.api.tasks.OutputFile
                import org.gradle.api.tasks.TaskAction

                plugins {
                    id 'io.quarkus.application'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:${project.property('version')}")
                    implementation "io.quarkus:quarkus-arc"
                    implementation "io.quarkus:quarkus-oidc"
                }

                quarkusApplication {
                    builds {
                        fastJar('fast')
                        mutableJar('mutable')
                        uberJar('uber')
                        legacyJar('legacy')
                    }
                }

                abstract class VerifyNamedPackages extends DefaultTask {
                    @InputFile
                    abstract RegularFileProperty getFastPackageResultFile()

                    @InputFile
                    abstract RegularFileProperty getMutablePackageResultFile()

                    @InputFile
                    abstract RegularFileProperty getUberPackageResultFile()

                    @InputFile
                    abstract RegularFileProperty getLegacyPackageResultFile()

                    @OutputFile
                    abstract RegularFileProperty getVerificationFile()

                    @TaskAction
                    void verify() {
                        def resultFiles = [
                            fast: fastPackageResultFile,
                            mutable: mutablePackageResultFile,
                            uber: uberPackageResultFile,
                            legacy: legacyPackageResultFile
                        ]
                        resultFiles.each { name, property ->
                            if (!property.get().asFile.isFile()) {
                                throw new GradleException("Missing package result file for ${name}: ${property.get().asFile}")
                            }
                        }
                        def output = verificationFile.get().asFile
                        output.parentFile.mkdirs()
                        output.text = resultFiles.keySet().join("\\n") + "\\n"
                    }
                }

                tasks.register('verifyNamedPackages', VerifyNamedPackages) {
                    fastPackageResultFile.set(tasks.named('quarkusFastBuild', QuarkusApplicationPackageTask).flatMap {
                        it.packageResultFile
                    })
                    mutablePackageResultFile.set(tasks.named('quarkusMutableBuild', QuarkusApplicationPackageTask).flatMap {
                        it.packageResultFile
                    })
                    uberPackageResultFile.set(tasks.named('quarkusUberBuild', QuarkusApplicationPackageTask).flatMap {
                        it.packageResultFile
                    })
                    legacyPackageResultFile.set(tasks.named('quarkusLegacyBuild', QuarkusApplicationPackageTask).flatMap {
                        it.packageResultFile
                    })
                    verificationFile.set(layout.buildDirectory.file('verification/named-package-results.txt'))
                }
                """);
        writeString(testProjectDir.resolve("src/main/java/org/acme/GreetingService.java"), """
                package org.acme;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class GreetingService {
                    public String hello() {
                        return "hello";
                    }
                }
                """);
    }

    private void writeApplicationWithJandexTask(String jandexTaskName) throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'jandex-app'\n");
        writeString(testProjectDir.resolve("gradle.properties"), "version = 999-SNAPSHOT\n");
        writeString(testProjectDir.resolve("build.gradle"), """
                import org.gradle.api.GradleException

                plugins {
                    id 'io.quarkus.application'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:${project.property('version')}")
                    implementation "io.quarkus:quarkus-arc"
                }

                def jandexMarker = layout.buildDirectory.file('jandex/%1$s.marker')

                tasks.register('%1$s') {
                    outputs.file(jandexMarker)
                    doLast {
                        def marker = jandexMarker.get().asFile
                        marker.parentFile.mkdirs()
                        marker.text = '%1$s'
                    }
                }

                tasks.register('verifyJandexModelWiring') {
                    dependsOn tasks.named('quarkusApplicationModel')
                    dependsOn tasks.named('quarkusApplicationDevModel')
                    inputs.file(jandexMarker)
                    doLast {
                        if (!jandexMarker.get().asFile.isFile()) {
                            throw new GradleException('Jandex marker was not produced')
                        }
                    }
                }
                """.formatted(jandexTaskName));
        writeString(testProjectDir.resolve("src/main/java/org/acme/GreetingService.java"), """
                package org.acme;

                public final class GreetingService {
                    public String hello() {
                        return "hello";
                    }
                }
                """);
    }

    private void writePackageVariantProducerConsumerApplication() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), """
                rootProject.name = 'package-variant-consumer'
                include 'app', 'consumer'
                """);
        writeString(testProjectDir.resolve("gradle.properties"), "version = 999-SNAPSHOT\n");
        writeString(testProjectDir.resolve("app/build.gradle"), """
                plugins {
                    id 'io.quarkus.application'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:${project.property('version')}")
                    implementation "io.quarkus:quarkus-arc"
                }

                quarkusApplication {
                    builds {
                        fastJar('fast')
                    }
                }
                """);
        writeString(testProjectDir.resolve("app/src/main/java/org/acme/GreetingService.java"), """
                package org.acme;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class GreetingService {
                    public String greeting() {
                        return "hello";
                    }
                }
                """);
        writeString(testProjectDir.resolve("consumer/build.gradle"), """
                import org.gradle.api.DefaultTask
                import org.gradle.api.file.ConfigurableFileCollection
                import org.gradle.api.tasks.InputFiles
                import org.gradle.api.tasks.TaskAction

                plugins {
                    id 'base'
                }

                configurations {
                    server {
                        canBeConsumed = false
                        canBeResolved = true
                    }
                }

                dependencies {
                    server project(path: ':app', configuration: 'quarkusFastPackageElements')
                }

                abstract class VerifyServer extends DefaultTask {
                    @InputFiles
                    abstract ConfigurableFileCollection getServerFiles()

                    @TaskAction
                    void verify() {
                        def files = serverFiles.files
                        assert files*.name == ['quarkus-run.jar']
                        println "serverFile=${files.first().name}"
                    }
                }

                tasks.register('verifyServer', VerifyServer) {
                    serverFiles.from(configurations.server)
                }
                """);
    }

    private void writeMultiProjectApplication(boolean projectDependency) throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), """
                rootProject.name = 'multi-project-quarkus-app'
                include 'app', 'lib'
                """);
        writeString(testProjectDir.resolve("lib/build.gradle"), """
                plugins {
                    id 'java-library'
                }
                """);
        writeString(testProjectDir.resolve("lib/src/main/java/org/acme/lib/GreetingLibrary.java"), """
                package org.acme.lib;

                public class GreetingLibrary {
                    public String message() {
                        return "hello";
                    }
                }
                """);
        writeString(testProjectDir.resolve("app/build.gradle"), """
                plugins {
                    id 'io.quarkus.application'
                }

                version = '999-SNAPSHOT'

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    %s
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:${project.version}")
                    implementation "io.quarkus:quarkus-arc"
                }

                quarkusApplication {
                    builds {
                        fastJar('app')
                    }
                }
                """.formatted(projectDependency ? "implementation project(':lib')" : ""));
        writeString(testProjectDir.resolve("app/src/main/java/org/acme/GreetingService.java"), """
                package org.acme;

                import jakarta.enterprise.context.ApplicationScoped;
                %s

                @ApplicationScoped
                public class GreetingService {
                    public String hello() {
                        return %s;
                    }
                }
                """.formatted(
                projectDependency ? "import org.acme.lib.GreetingLibrary;" : "",
                projectDependency ? "new GreetingLibrary().message()" : "\"hello\""));
    }

    private void writeMultiProjectCodegenApplication() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), """
                rootProject.name = 'multi-project-codegen-app'
                include 'app', 'lib'
                """);
        writeString(testProjectDir.resolve("lib/build.gradle"), """
                plugins {
                    id 'java-library'
                }
                """);
        writeString(testProjectDir.resolve("lib/src/main/java/org/acme/lib/GreetingLibrary.java"), """
                package org.acme.lib;

                public final class GreetingLibrary {
                    public String message() {
                        return "hello";
                    }
                }
                """);
        writeString(testProjectDir.resolve("app/build.gradle"), """
                import java.nio.file.Files

                plugins {
                    id 'io.quarkus.application'
                }

                version = '1.0'

                dependencies {
                    implementation project(':lib')
                }

                tasks.named('quarkusApplicationGenerateCode').configure {
                    doLast {
                        def sourcePackage = generatedOutputDirectory.get().asFile.toPath().resolve('org/acme/generated')
                        Files.createDirectories(sourcePackage)
                        Files.writeString(sourcePackage.resolve('GeneratedFromLib.java'), '''
                            package org.acme.generated;

                            import org.acme.lib.GreetingLibrary;

                            public final class GeneratedFromLib {
                                public static String value() {
                                    return new GreetingLibrary().message();
                                }
                            }
                            '''.stripIndent())
                    }
                }
                """);
        writeString(testProjectDir.resolve("app/src/main/java/org/acme/App.java"), """
                package org.acme;

                import org.acme.generated.GeneratedFromLib;

                public final class App {
                    public String value() {
                        return GeneratedFromLib.value();
                    }
                }
                """);
    }

    private void writeStubbedCodegenApplication() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'stubbed-codegen-app'\n");
        writeString(testProjectDir.resolve("build.gradle"), """
                import java.nio.file.Files

                plugins {
                    id 'io.quarkus.application'
                }

                version = '1.0'

                tasks.named('quarkusApplicationGenerateCode').configure {
                    doLast {
                        def sourcePackage = generatedOutputDirectory.get().asFile.toPath().resolve('org/acme/generated')
                        Files.createDirectories(sourcePackage)
                        Files.writeString(sourcePackage.resolve('GeneratedMain.java'), '''
                            package org.acme.generated;

                            public final class GeneratedMain {
                                public static String value() {
                                    return "main";
                                }
                            }
                            '''.stripIndent())
                    }
                }
                tasks.named('quarkusApplicationGenerateTestCode').configure {
                    doLast {
                        def sourcePackage = generatedOutputDirectory.get().asFile.toPath().resolve('org/acme/generated')
                        Files.createDirectories(sourcePackage)
                        Files.writeString(sourcePackage.resolve('GeneratedTest.java'), '''
                            package org.acme.generated;

                            public final class GeneratedTest {
                                public static String value() {
                                    return GeneratedMain.value() + "-test";
                                }
                            }
                            '''.stripIndent())
                    }
                }
                """);
        writeString(testProjectDir.resolve("src/main/java/org/acme/App.java"), """
                package org.acme;

                import org.acme.generated.GeneratedMain;

                public final class App {
                    public String value() {
                        return GeneratedMain.value();
                    }
                }
                """);
        writeString(testProjectDir.resolve("src/test/java/org/acme/GeneratedSourceUsage.java"), """
                package org.acme;

                import org.acme.generated.GeneratedTest;

                public final class GeneratedSourceUsage {
                    public String value() {
                        return new App().value() + GeneratedTest.value();
                    }
                }
                """);
    }

    private void writeRealAvroCodegenApplication() throws IOException {
        writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'real-avro-codegen-app'\n");
        writeString(testProjectDir.resolve("gradle.properties"), "version = 999-SNAPSHOT\n");
        writeString(testProjectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.quarkus.application'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:${project.property('version')}")
                    implementation 'io.quarkus:quarkus-avro'
                }
                """);
        writeString(testProjectDir.resolve("src/main/avro/greeting.avsc"), """
                {
                  "type": "record",
                  "namespace": "org.acme.quarkus.hello",
                  "name": "Greeting",
                  "fields": [
                    { "name": "message", "type": "string" }
                  ]
                }
                """);
        writeString(testProjectDir.resolve("src/main/java/org/acme/AvroUsage.java"), """
                package org.acme;

                import org.acme.quarkus.hello.Greeting;

                public final class AvroUsage {
                    public Greeting greeting() {
                        return Greeting.newBuilder().setMessage("hello").build();
                    }
                }
                """);
    }

    private GradleRunner runner(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments(tasks));
    }

    private GradleRunner runnerWithBuildCache(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments(argumentsWithBuildCache(tasks));
    }

    private static String[] arguments(String... tasks) {
        return Stream.concat(
                Arrays.stream(tasks),
                Stream.of(
                        "--configuration-cache",
                        "-Dorg.gradle.unsafe.isolated-projects=true",
                        "--stacktrace"))
                .toArray(String[]::new);
    }

    private static String[] argumentsWithBuildCache(String... tasks) {
        String[] arguments = new String[tasks.length + 4];
        System.arraycopy(arguments(tasks), 0, arguments, 0, tasks.length + 3);
        arguments[tasks.length + 3] = "--build-cache";
        return arguments;
    }

    private void assertFastJarPackageResult(String buildName) {
        Path output = outputRoot(buildName);
        String outputName = "tiny-quarkus-app-999-SNAPSHOT";
        var receipt = packageResult(buildName);
        assertThat(receipt.buildName()).isEqualTo(buildName);
        assertThat(receipt.buildType()).isEqualTo(QuarkusApplicationBuildType.FAST_JAR);
        assertThat(receipt.outputRoot()).isEqualTo(output);
        assertThat(receipt.outputName()).isEqualTo(outputName);
        assertThat(receipt.jarPath()).isEqualTo(output.resolve("quarkus-run.jar"));
        assertThat(receipt.originalArtifact()).isEqualTo(Optional.empty());
        assertThat(receipt.libraryDirectory()).isEqualTo(Optional.of(output.resolve("lib")));
        assertThat(receipt.mutable()).isFalse();
        assertThat(receipt.uberJar()).isFalse();
        assertThat(output.resolve("quarkus-run.jar")).isRegularFile();
        assertThat(output.resolve(Path.of("app", outputName + ".jar"))).isRegularFile();
        assertThat(output.resolve("app")).isDirectory();
        assertThat(output.resolve("lib")).isDirectory();
        assertThat(output.resolve("quarkus")).isDirectory();
        assertThat(output.resolve("package-augmentation-result.properties")).doesNotExist();
        assertThat(resultRoot(buildName).resolve("package-augmentation-result.properties")).isRegularFile();
        assertThat(output.resolve("quarkus-artifact.properties")).doesNotExist();
        assertThat(resultRoot(buildName).resolve("quarkus-artifact.properties")).isRegularFile();
    }

    private void assertMutableJarPackageResult(String buildName) {
        Path output = outputRoot(buildName);
        String outputName = "tiny-quarkus-app-999-SNAPSHOT";
        var receipt = packageResult(buildName);
        assertThat(receipt.buildName()).isEqualTo(buildName);
        assertThat(receipt.buildType()).isEqualTo(QuarkusApplicationBuildType.MUTABLE_JAR);
        assertThat(receipt.outputRoot()).isEqualTo(output);
        assertThat(receipt.outputName()).isEqualTo(outputName);
        assertThat(receipt.jarPath()).isEqualTo(output.resolve("quarkus-run.jar"));
        assertThat(receipt.originalArtifact()).isEqualTo(Optional.empty());
        assertThat(receipt.libraryDirectory()).isEqualTo(Optional.of(output.resolve("lib")));
        assertThat(receipt.mutable()).isTrue();
        assertThat(receipt.uberJar()).isFalse();
        assertThat(output.resolve("quarkus-run.jar")).isRegularFile();
        assertThat(output.resolve(Path.of("app", outputName + ".jar"))).isRegularFile();
        assertThat(output.resolve("app")).isDirectory();
        assertThat(output.resolve("lib")).isDirectory();
        assertThat(output.resolve("quarkus")).isDirectory();
    }

    private void assertUberJarPackageResult(String buildName) {
        Path output = outputRoot(buildName);
        String outputName = "tiny-quarkus-app-999-SNAPSHOT";
        var receipt = packageResult(buildName);
        assertThat(receipt.buildName()).isEqualTo(buildName);
        assertThat(receipt.buildType()).isEqualTo(QuarkusApplicationBuildType.UBER_JAR);
        assertThat(receipt.outputRoot()).isEqualTo(output);
        assertThat(receipt.outputName()).isEqualTo(outputName);
        assertThat(receipt.jarPath()).isEqualTo(output.resolve(outputName + "-runner.jar"));
        assertThat(receipt.originalArtifact()).isEqualTo(Optional.empty());
        assertThat(receipt.libraryDirectory()).isEqualTo(Optional.empty());
        assertThat(receipt.mutable()).isFalse();
        assertThat(receipt.uberJar()).isTrue();
        assertThat(output.resolve(outputName + "-runner.jar")).isRegularFile();
    }

    private void assertLegacyJarPackageResult(String buildName) {
        Path output = outputRoot(buildName);
        String outputName = "tiny-quarkus-app-999-SNAPSHOT";
        var receipt = packageResult(buildName);
        assertThat(receipt.buildName()).isEqualTo(buildName);
        assertThat(receipt.buildType()).isEqualTo(QuarkusApplicationBuildType.LEGACY_JAR);
        assertThat(receipt.outputRoot()).isEqualTo(output);
        assertThat(receipt.outputName()).isEqualTo(outputName);
        assertThat(receipt.jarPath()).isEqualTo(output.resolve(outputName + "-runner.jar"));
        assertThat(receipt.originalArtifact()).isEqualTo(Optional.empty());
        assertThat(receipt.libraryDirectory()).isEqualTo(Optional.of(output.resolve("lib")));
        assertThat(receipt.mutable()).isFalse();
        assertThat(receipt.uberJar()).isFalse();
        assertThat(output.resolve(outputName + "-runner.jar")).isRegularFile();
        assertThat(output.resolve("lib")).isDirectory();
    }

    private PackageResultCodec packageResultCodec() {
        return new PackageResultCodec();
    }

    private PackageResult packageResult(String buildName) {
        return packageResultCodec().read(resultRoot(buildName).resolve("package-result.properties"));
    }

    private Path outputRoot(String buildName) {
        return testProjectDir.resolve(Path.of("build", "quarkus-builds", buildName, "package"));
    }

    private Path resultRoot(String buildName) {
        return testProjectDir.resolve(Path.of("build", "quarkus-build-results", buildName, "package"));
    }

    private static void assertTaskDescription(Project project, String taskName, String description) {
        assertThat(project.getTasks().getByName(taskName).getDescription()).isEqualTo(description);
    }

    private static void assertTaskGroup(Project project, String taskName, String group) {
        assertThat(project.getTasks().getByName(taskName).getGroup()).isEqualTo(group);
    }

    private static void assertJavaRuntimeAttributes(Project project, String configurationName) {
        var attributes = project.getConfigurations().getByName(configurationName).getAttributes();
        assertThat(attributes.getAttribute(Category.CATEGORY_ATTRIBUTE).getName()).isEqualTo(Category.LIBRARY);
        assertThat(attributes.getAttribute(Usage.USAGE_ATTRIBUTE).getName()).isEqualTo(Usage.JAVA_RUNTIME);
        assertThat(attributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).getName())
                .isEqualTo(LibraryElements.JAR);
        assertThat(attributes.getAttribute(Bundling.BUNDLING_ATTRIBUTE).getName()).isEqualTo(Bundling.EXTERNAL);
        assertThat(attributes.getAttribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE).getName())
                .isEqualTo(TargetJvmEnvironment.STANDARD_JVM);
    }

    private static void assertPackageElementsVariant(Project project, String configurationName, String buildName,
            String buildType, String builtByTaskName) {
        Configuration configuration = project.getConfigurations().getByName(configurationName);
        assertThat(configuration.isCanBeConsumed()).isTrue();
        assertThat(configuration.isCanBeResolved()).isFalse();
        assertThat(configuration.isCanBeDeclared()).isFalse();

        var attributes = configuration.getAttributes();
        assertThat(attributes.getAttribute(Category.CATEGORY_ATTRIBUTE).getName())
                .isEqualTo(QuarkusApplicationVariantAttributes.PACKAGE_CATEGORY);
        assertThat(attributes.getAttribute(Usage.USAGE_ATTRIBUTE).getName()).isEqualTo(Usage.JAVA_RUNTIME);
        assertThat(attributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).getName())
                .isEqualTo(LibraryElements.JAR);
        assertThat(attributes.getAttribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE).getName())
                .isEqualTo(TargetJvmEnvironment.STANDARD_JVM);
        assertThat(attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE))
                .isEqualTo(ArtifactTypeDefinition.JAR_TYPE);
        assertThat(attributes.getAttribute(QuarkusApplicationVariantAttributes.BUILD_NAME_ATTRIBUTE)).isEqualTo(buildName);
        assertThat(attributes.getAttribute(QuarkusApplicationVariantAttributes.BUILD_TYPE_ATTRIBUTE)).isEqualTo(buildType);
        assertThat(configuration.getOutgoing().getArtifacts()).hasSize(1);
        assertThat(configuration.getOutgoing().getArtifacts().iterator().next().getBuildDependencies()
                .getDependencies(null))
                .extracting(Task::getName)
                .containsExactly(builtByTaskName);
    }

    private static void assertPrimaryJarFile(Project project, String taskName, Path expected) {
        QuarkusApplicationPackageTask task = (QuarkusApplicationPackageTask) project.getTasks().getByName(taskName);
        assertThat(task.getPrimaryJarFile().get().toPath()).isEqualTo(expected);
    }

    private static void writeString(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static boolean containsFileNamed(Path root, String fileName) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.getFileName().toString().equals(fileName));
        }
    }

    private static Path createJar(Path file) throws IOException {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(file))) {
            return file;
        }
    }

    private static void writeSyntheticConditionalExtensionRepository(Path repository) throws IOException {
        writeMavenArtifact(repository, "org.acme", "parent-extension", "1.0",
                """
                        conditional-dependencies=org.acme\\:satisfied-extension\\:\\:jar\\:1.0 org.acme\\:missing-extension\\:\\:jar\\:1.0
                        deployment-artifact=org.acme\\:parent-extension-deployment\\:1.0
                        """);
        writeMavenArtifact(repository, "org.condition", "present", "1.0", null);
        writeMavenArtifact(repository, "org.acme", "satisfied-extension", "1.0", """
                dependency-condition=org.condition\\:present
                deployment-artifact=org.acme\\:satisfied-extension-deployment\\:1.0
                """);
        writeMavenArtifact(repository, "org.acme", "missing-extension", "1.0", """
                dependency-condition=org.condition\\:missing
                deployment-artifact=org.acme\\:missing-extension-deployment\\:1.0
                """);
    }

    private static void writeSyntheticConditionalDevExtensionRepository(Path repository) throws IOException {
        writeMavenArtifact(repository, "org.acme", "parent-extension", "1.0",
                """
                        conditional-dev-dependencies=org.acme\\:parent-extension-dev\\:\\:jar\\:1.0
                        deployment-artifact=org.acme\\:parent-extension-deployment\\:1.0
                        """);
        writeMavenArtifact(repository, "org.acme", "parent-extension-dev", "1.0", null);
    }

    private static void writeMavenArtifact(Path repository, String groupId, String artifactId, String version,
            String extensionDescriptor) throws IOException {
        Path artifactDirectory = repository.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
        Files.createDirectories(artifactDirectory);
        String baseName = artifactId + "-" + version;
        writeString(artifactDirectory.resolve(baseName + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version));
        Path jarFile = artifactDirectory.resolve(baseName + ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarFile))) {
            if (extensionDescriptor != null) {
                jar.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
                jar.write(extensionDescriptor.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }
}
