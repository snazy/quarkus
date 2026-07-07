package io.quarkus.gradle.application.dsl;

import static java.util.Objects.requireNonNull;

import org.gradle.api.Named;
import org.gradle.api.provider.Property;
import org.jspecify.annotations.NonNull;

import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentImageSource;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;

public abstract class QuarkusApplicationDeployment implements Named {

    private final String name;
    private final QuarkusApplicationDeploymentTarget target;

    protected QuarkusApplicationDeployment(String name, QuarkusApplicationDeploymentTarget target) {
        this.name = requireNonNull(name, "name");
        this.target = requireNonNull(target, "target");
        getImageSource().convention(QuarkusApplicationDeploymentImageSource.NORMAL_IMAGE_PUSH);
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    public QuarkusApplicationDeploymentTarget getTarget() {
        return target;
    }

    public abstract Property<QuarkusApplicationDeploymentImageSource> getImageSource();

    public abstract Property<String> getImageReference();
}
