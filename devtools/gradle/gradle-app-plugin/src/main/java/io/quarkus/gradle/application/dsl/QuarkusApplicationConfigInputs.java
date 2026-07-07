package io.quarkus.gradle.application.dsl;

import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;

public abstract class QuarkusApplicationConfigInputs {

    private final QuarkusApplicationConfigInputSet projectProperties;
    private final QuarkusApplicationConfigInputSet systemProperties;
    private final QuarkusApplicationConfigInputSet environmentVariables;

    @Inject
    public QuarkusApplicationConfigInputs(ObjectFactory objects, ProviderFactory providers) {
        this.projectProperties = objects.newInstance(QuarkusApplicationConfigInputSet.class);
        this.systemProperties = objects.newInstance(QuarkusApplicationConfigInputSet.class);
        this.environmentVariables = objects.newInstance(QuarkusApplicationConfigInputSet.class);

        projectProperties.getPrefixes().convention(Set.of("quarkus.", "platform.quarkus.", "smallrye.config."));
        projectProperties.getNames().convention(Set.of());
        systemProperties.getPrefixes().convention(Set.of("quarkus.", "platform.quarkus.", "smallrye.config."));
        systemProperties.getNames().convention(Set.of());
        environmentVariables.getPrefixes().convention(Set.of("QUARKUS_", "PLATFORM_QUARKUS_", "SMALLRYE_CONFIG_"));
        environmentVariables.getNames().convention(Set.of());
        getLegacyAmbientConfigCapture().convention(
                providers.gradleProperty("quarkusBuildLegacyAmbientConfigCapture")
                        .map(Boolean::parseBoolean)
                        .orElse(false));
    }

    public QuarkusApplicationConfigInputSet getProjectProperties() {
        return projectProperties;
    }

    public void projectProperties(Action<? super QuarkusApplicationConfigInputSet> action) {
        action.execute(projectProperties);
    }

    public QuarkusApplicationConfigInputSet getSystemProperties() {
        return systemProperties;
    }

    public void systemProperties(Action<? super QuarkusApplicationConfigInputSet> action) {
        action.execute(systemProperties);
    }

    public QuarkusApplicationConfigInputSet getEnvironmentVariables() {
        return environmentVariables;
    }

    public void environmentVariables(Action<? super QuarkusApplicationConfigInputSet> action) {
        action.execute(environmentVariables);
    }

    public abstract Property<Boolean> getLegacyAmbientConfigCapture();
}
