package io.quarkus.gradle.application.tasks;

import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.execution.ImageOperation;

@DisableCachingByDefault(because = "AOT-enhanced image push mutates external container image state")
public abstract class QuarkusApplicationAotEnhancedImagePushTask extends QuarkusApplicationAotEnhancedImageTask {

    @TaskAction
    public void pushAotEnhancedImage() {
        executeAotEnhancedImageOperation(ImageOperation.PUSH);
    }
}
