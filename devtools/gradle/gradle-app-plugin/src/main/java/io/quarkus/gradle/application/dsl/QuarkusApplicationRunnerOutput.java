package io.quarkus.gradle.application.dsl;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

public abstract class QuarkusApplicationRunnerOutput extends QuarkusApplicationBuild {

    protected QuarkusApplicationRunnerOutput(String name, QuarkusApplicationBuildType buildType, ObjectFactory objects,
            ProjectLayout layout) {
        super(name, buildType, objects, layout);
    }

    public abstract Property<String> getArchiveRunnerSuffix();

    public abstract Property<Boolean> getArchiveAddRunnerSuffix();
}
