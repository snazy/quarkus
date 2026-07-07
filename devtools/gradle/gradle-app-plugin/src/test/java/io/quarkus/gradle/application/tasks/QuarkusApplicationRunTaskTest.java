package io.quarkus.gradle.application.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import io.quarkus.gradle.application.internal.deployment.DeploymentResult;
import io.quarkus.gradle.application.internal.execution.AotEnhancedImageRequest;
import io.quarkus.gradle.application.internal.execution.BuildOperations;
import io.quarkus.gradle.application.internal.execution.BuildRequest;
import io.quarkus.gradle.application.internal.execution.DeploymentRequest;
import io.quarkus.gradle.application.internal.execution.ImageRequest;
import io.quarkus.gradle.application.internal.execution.RunRequest;
import io.quarkus.gradle.application.internal.image.BuiltContainerImage;
import io.quarkus.gradle.application.internal.nativeimage.NativeResult;
import io.quarkus.gradle.application.internal.packaging.PackageResult;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

class QuarkusApplicationRunTaskTest {

    @Test
    void enableRemoteDevAddsLaunchEnvironmentForMutableJarRun() {
        CapturingBuildOperations operations = new CapturingBuildOperations();
        QuarkusApplicationRunTask task = runTask(QuarkusApplicationBuildType.MUTABLE_JAR, operations);
        task.getJvmArguments().set(List.of("-Xmx128m"));
        task.enableRemoteDev(true);
        task.liveReloadPassword("changeit");

        task.runApplication();

        assertThat(operations.request.environment()).containsExactly(Map.entry("QUARKUS_LAUNCH_DEVMODE", "true"));
        assertThat(operations.request.jvmArguments()).containsExactly(
                "-Xmx128m",
                "-Dquarkus.console.disable-input=true",
                "-Dquarkus.test.continuous-testing=disabled",
                "-Dquarkus.live-reload.password=changeit");
        assertThat(operations.request.build().operationForcedProperties()).isEmpty();
        assertThat(operations.request.build().effectiveConfig().fullValues())
                .doesNotContainKey("quarkus.live-reload.password");
    }

    @Test
    void enableRemoteDevRejectsNonMutableJarRun() {
        QuarkusApplicationRunTask task = runTask(QuarkusApplicationBuildType.FAST_JAR, new CapturingBuildOperations());
        task.enableRemoteDev(true);

        assertThatThrownBy(task::runApplication)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("--enable-remote-dev")
                .hasMessageContaining("mutable-jar run task");
    }

    @Test
    void liveReloadPasswordRequiresRemoteDevServerRun() {
        QuarkusApplicationRunTask task = runTask(QuarkusApplicationBuildType.MUTABLE_JAR, new CapturingBuildOperations());
        task.liveReloadPassword("changeit");

        assertThatThrownBy(task::runApplication)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("--live-reload-password")
                .hasMessageContaining("--enable-remote-dev");
    }

    @Test
    void normalRunUsesEmptyEnvironment() {
        CapturingBuildOperations operations = new CapturingBuildOperations();
        QuarkusApplicationRunTask task = runTask(QuarkusApplicationBuildType.FAST_JAR, operations);

        task.runApplication();

        assertThat(operations.request.environment()).isEmpty();
        assertThat(operations.request.jvmArguments()).isEmpty();
        assertThat(operations.request.build().operationForcedProperties()).isEmpty();
    }

    private static QuarkusApplicationRunTask runTask(QuarkusApplicationBuildType buildType,
            CapturingBuildOperations operations) {
        Project project = ProjectBuilder.builder().build();
        QuarkusApplicationRunTask task = project.getTasks().register("quarkusAppRun", QuarkusApplicationRunTask.class).get();
        task.getBuildName().set("app");
        task.getBuildType().set(buildType);
        task.getApplicationName().set("app");
        task.getApplicationVersion().set("1.0");
        task.getQuarkusBuildProperties().set(Map.of());
        task.getGradleBuildDirectory().set(project.getLayout().getBuildDirectory());
        task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("quarkus-builds/app/package"));
        task.getApplicationModel().set(project.getLayout().getBuildDirectory().file("app-model.dat"));
        task.getPackageResultFile().set(project.getLayout().getBuildDirectory().file("package-result.properties"));
        task.getRuntimeClasspath().setFrom(List.of());
        task.getSourceDirectories().setFrom(List.of());
        task.getOperations().set(operations);
        return task;
    }

    private static final class CapturingBuildOperations implements BuildOperations {

        private RunRequest request;

        @Override
        public void build(BuildRequest request) {
        }

        @Override
        public PackageResult buildPackage(BuildRequest request, Path augmentResultFile) {
            return null;
        }

        @Override
        public NativeResult buildNative(BuildRequest request, Path augmentResultFile) {
            return null;
        }

        @Override
        public BuiltContainerImage buildAotEnhancedImage(AotEnhancedImageRequest request) {
            return null;
        }

        @Override
        public BuiltContainerImage pushAotEnhancedImage(AotEnhancedImageRequest request) {
            return null;
        }

        @Override
        public BuiltContainerImage buildImage(ImageRequest request) {
            return null;
        }

        @Override
        public BuiltContainerImage pushImage(ImageRequest request) {
            return null;
        }

        @Override
        public DeploymentResult deploy(DeploymentRequest request) {
            return null;
        }

        @Override
        public void run(RunRequest request) {
            this.request = request;
        }
    }
}
