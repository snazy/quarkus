package io.quarkus.gradle.application.dsl;

import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;

public abstract class QuarkusApplicationDev {

    private final QuarkusApplicationDevForkOptions forkOptions;

    @Inject
    public QuarkusApplicationDev(ObjectFactory objects) {
        this.forkOptions = objects.newInstance(QuarkusApplicationDevForkOptions.class);
        getQuarkusBuildProperties().convention(Map.of());
    }

    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    public QuarkusApplicationDevForkOptions getForkOptions() {
        return forkOptions;
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void forkOptions(Action<? super QuarkusApplicationDevForkOptions> action) {
        action.execute(forkOptions);
    }
}
