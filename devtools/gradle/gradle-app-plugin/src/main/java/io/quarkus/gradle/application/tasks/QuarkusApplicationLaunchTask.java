package io.quarkus.gradle.application.tasks;

import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.model.QuarkusApplicationLaunchKind;

@DisableCachingByDefault(because = "Reserved launch tasks fail immediately and do not produce reusable outputs")
public abstract class QuarkusApplicationLaunchTask extends QuarkusApplicationTask {

    @Input
    public abstract Property<QuarkusApplicationLaunchKind> getLaunchKind();

    protected final void failReservedLaunchTask() {
        throw new GradleException("Task '" + getPath()
                + "' is reserved by io.quarkus.application, but Gradle-native continuous-test integration "
                + "is not implemented yet.");
    }
}
