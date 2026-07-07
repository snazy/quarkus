package io.quarkus.gradle.application.internal.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildDescriptor;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentDescriptor;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;
import io.quarkus.gradle.application.model.QuarkusApplicationLaunchDescriptor;

class TaskNamePlannerTest {

    private final TaskNamePlanner planner = new TaskNamePlanner();

    @Test
    void derivesNamesFromRegisteredBuildName() {
        var names = planner.taskNames(QuarkusApplicationBuildDescriptor.of("native1",
                QuarkusApplicationBuildType.NATIVE_EXECUTABLE));

        assertThat(names.build()).isEqualTo("quarkusNative1Build");
        assertThat(names.run()).isEqualTo("quarkusNative1Run");
        assertThat(names.imageBuild()).isEqualTo("quarkusNative1ImageBuild");
        assertThat(names.imagePush()).isEqualTo("quarkusNative1ImagePush");
        assertThat(names.aotTraining()).isEqualTo("quarkusNative1AotTraining");
        assertThat(names.aotEnhancedImageBuild()).isEqualTo("quarkusNative1AotEnhancedImageBuild");
        assertThat(names.aotEnhancedImagePush()).isEqualTo("quarkusNative1AotEnhancedImagePush");
        assertThat(names.nativeTest()).isEqualTo("quarkusNative1NativeTest");
    }

    @Test
    void derivesDeployAndContinuousTestNames() {
        var build = QuarkusApplicationBuildDescriptor.of("app", QuarkusApplicationBuildType.FAST_JAR);
        var deployment = QuarkusApplicationDeploymentDescriptor.of("dev", QuarkusApplicationDeploymentTarget.KUBERNETES);

        assertThat(planner.deployTaskName(build, deployment)).isEqualTo("quarkusAppDeployToDev");
        assertThat(planner.continuousTestTaskName(QuarkusApplicationLaunchDescriptor.continuousTest()))
                .isEqualTo("quarkusContinuousTest");
        assertThat(planner.continuousTestTaskName(QuarkusApplicationLaunchDescriptor.continuousTest("dev")))
                .isEqualTo("quarkusDevContinuousTest");
    }

    @Test
    void rejectsNormalizedBuildNameCollisions() {
        assertThatThrownBy(() -> planner.validateBuildNames(List.of(
                QuarkusApplicationBuildDescriptor.of("native-main", QuarkusApplicationBuildType.FAST_JAR),
                QuarkusApplicationBuildDescriptor.of("nativeMain", QuarkusApplicationBuildType.NATIVE_EXECUTABLE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("native-main")
                .hasMessageContaining("nativeMain");
    }

    @Test
    void rejectsTaskNameCollisionsWithExistingTasks() {
        assertThatThrownBy(() -> planner.validateTaskNameCollisions(
                List.of(QuarkusApplicationBuildDescriptor.of("app", QuarkusApplicationBuildType.FAST_JAR)),
                List.of("quarkusAppBuild")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quarkusAppBuild");
    }

    @Test
    void ignoresNativeRunTaskNameCollisionsBecauseNativeBuildsDoNotRegisterRunTasks() {
        planner.validateTaskNameCollisions(
                List.of(QuarkusApplicationBuildDescriptor.of("native", QuarkusApplicationBuildType.NATIVE_EXECUTABLE)),
                List.of("quarkusNativeRun"));
    }

    @Test
    void rejectsDeploymentNameCollisionsWithinBuild() {
        assertThatThrownBy(() -> planner.validateDeploymentNames(List.of(
                QuarkusApplicationDeploymentDescriptor.of("prod-main", QuarkusApplicationDeploymentTarget.KUBERNETES),
                QuarkusApplicationDeploymentDescriptor.of("prodMain", QuarkusApplicationDeploymentTarget.OPENSHIFT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prod-main")
                .hasMessageContaining("prodMain");
    }
}
