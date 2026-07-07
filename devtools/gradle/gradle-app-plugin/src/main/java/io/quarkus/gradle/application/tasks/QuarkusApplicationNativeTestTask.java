package io.quarkus.gradle.application.tasks;

import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Native test task is reserved until Gradle test-suite integration is implemented")
public abstract class QuarkusApplicationNativeTestTask extends QuarkusApplicationTask {

    @TaskAction
    public void runNativeTests() {
        failUnimplementedTask();
    }
}
