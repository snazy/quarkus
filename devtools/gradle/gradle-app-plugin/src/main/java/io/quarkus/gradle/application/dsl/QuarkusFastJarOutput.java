package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

public abstract class QuarkusFastJarOutput extends QuarkusApplicationBuild {

    @Inject
    public QuarkusFastJarOutput(String name, ProjectLayout layout, ObjectFactory objects) {
        super(name, QuarkusApplicationBuildType.FAST_JAR, objects, layout);
    }
}
