package io.quarkus.gradle.application.tasks;

import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Reserved continuous test task fails immediately and does not produce reusable outputs")
public abstract class QuarkusApplicationContinuousTestTask extends QuarkusApplicationLaunchTask {

    @TaskAction
    public void runContinuousTests() {
        failReservedLaunchTask();
    }
}
