package io.quarkus.gradle.application.tasks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class QuarkusApplicationLaunchTaskTest {

    @Test
    void continuousTestTaskFailsAsReservedUntilGradleNativeIntegrationExists() {
        assertReservedTaskFailure(QuarkusApplicationContinuousTestTask.class, "quarkusAppContinuousTest");
    }

    private static void assertReservedTaskFailure(Class<? extends QuarkusApplicationLaunchTask> taskType,
            String taskName) {
        Project project = ProjectBuilder.builder().build();
        QuarkusApplicationLaunchTask task = project.getTasks().register(taskName, taskType).get();

        assertThatThrownBy(reservedTaskAction(task))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("reserved by io.quarkus.application")
                .hasMessageContaining("Gradle-native continuous-test integration is not implemented yet");
    }

    private static ThrowingCallable reservedTaskAction(QuarkusApplicationLaunchTask task) {
        if (task instanceof QuarkusApplicationContinuousTestTask continuousTestTask) {
            return continuousTestTask::runContinuousTests;
        }
        throw new IllegalArgumentException("Unsupported reserved launch task type: " + task.getClass());
    }
}
