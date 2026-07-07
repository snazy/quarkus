package io.quarkus.gradle.application.dsl;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

import io.quarkus.gradle.application.model.QuarkusApplicationImageBuilder;

public abstract class QuarkusApplicationImage {

    public QuarkusApplicationImage() {
    }

    public abstract Property<String> getImageReference();

    public abstract Property<String> getRepository();

    public abstract Property<String> getTag();

    public abstract Property<QuarkusApplicationImageBuilder> getBuilder();

    public abstract MapProperty<String, String> getQuarkusBuildProperties();
}
