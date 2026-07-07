package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

public abstract class QuarkusNativeOutput extends QuarkusApplicationRunnerOutput {

    @Inject
    public QuarkusNativeOutput(String name, ProjectLayout layout, ObjectFactory objects) {
        super(name, QuarkusApplicationBuildType.NATIVE_EXECUTABLE, objects, layout);
    }
}
