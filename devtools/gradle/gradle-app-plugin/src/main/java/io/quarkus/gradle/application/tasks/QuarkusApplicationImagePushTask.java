package io.quarkus.gradle.application.tasks;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.execution.ImageOperation;

@DisableCachingByDefault(because = "Container image push mutates external container image state")
public abstract class QuarkusApplicationImagePushTask extends QuarkusApplicationImageTask {

    public QuarkusApplicationImagePushTask() {
        getOperationKind().convention(ImageOperation.PUSH);
    }

    @Input
    public abstract Property<ImageOperation> getOperationKind();

    @OutputFile
    public abstract RegularFileProperty getReceiptFile();

    @TaskAction
    public void pushImage() {
        executeImageOperation(getOperationKind().get(), getReceiptFile().get().getAsFile().toPath());
    }
}
