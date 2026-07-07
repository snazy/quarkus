package io.quarkus.gradle.application.dsl;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public abstract class QuarkusApplicationAotEnhancedImage {

    public abstract RegularFileProperty getAotFile();

    public abstract Property<String> getAotFileProducerTaskName();

    public abstract Property<String> getRepository();

    public abstract Property<String> getTag();

    public abstract Property<String> getImageReference();

    public abstract Property<String> getImageSuffix();
}
