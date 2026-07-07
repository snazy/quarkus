package io.quarkus.gradle.application.tasks;

import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.execution.ImageOperation;

@DisableCachingByDefault(because = "AOT-enhanced image build mutates external container image state")
public abstract class QuarkusApplicationAotEnhancedImageBuildTask extends QuarkusApplicationAotEnhancedImageTask {

    @TaskAction
    public void buildAotEnhancedImage() {
        executeAotEnhancedImageOperation(ImageOperation.BUILD);
    }
}
