package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

public abstract class QuarkusLegacyJarOutput extends QuarkusApplicationRunnerOutput {

    @Inject
    public QuarkusLegacyJarOutput(String name, ProjectLayout layout, ObjectFactory objects) {
        super(name, QuarkusApplicationBuildType.LEGACY_JAR, objects, layout);
    }
}
