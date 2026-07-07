package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

public abstract class QuarkusMutableJarOutput extends QuarkusApplicationBuild {

    @Inject
    public QuarkusMutableJarOutput(String name, ProjectLayout layout, ObjectFactory objects) {
        super(name, QuarkusApplicationBuildType.MUTABLE_JAR, objects, layout);
    }
}
