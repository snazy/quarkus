package io.quarkus.gradle.application.tasks;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

@DisableCachingByDefault(because = "Base named application task has no standalone cacheable behavior")
public abstract class QuarkusApplicationTask extends QuarkusApplicationBaseTask {

    @Input
    public abstract Property<String> getBuildName();

    @Input
    public abstract Property<QuarkusApplicationBuildType> getBuildType();

    @Input
    @Optional
    public abstract Property<String> getOutputName();

    @Internal
    public abstract DirectoryProperty getOutputDirectory();

}
