package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

public abstract class QuarkusUberJarOutput extends QuarkusApplicationRunnerOutput {

    @Inject
    public QuarkusUberJarOutput(String name, ProjectLayout layout, ObjectFactory objects) {
        super(name, QuarkusApplicationBuildType.UBER_JAR, objects, layout);
    }
}
