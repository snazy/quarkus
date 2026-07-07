package io.quarkus.gradle.application.internal.execution.worker;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public interface BuildAotEnhancedImageForApplicationWorkerParams extends QuarkusParams {

    Property<String> getOriginalContainerImage();

    Property<String> getContainerWorkingDirectory();

    RegularFileProperty getAotFile();

    RegularFileProperty getAotImageResultFile();
}
