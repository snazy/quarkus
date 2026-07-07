package io.quarkus.gradle.application.tasks;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "AOT training is reserved until Gradle test-suite integration is implemented")
public abstract class QuarkusApplicationAotTrainingTask extends QuarkusApplicationTask {

    @OutputFile
    public abstract RegularFileProperty getAotFile();

    @TaskAction
    public void runAotTraining() {
        failUnimplementedTask();
    }
}
