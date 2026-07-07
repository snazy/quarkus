package io.quarkus.gradle.application.tasks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Base application task has no standalone cacheable behavior")
public abstract class QuarkusApplicationBaseTask extends DefaultTask {

    static final String LEGACY_AMBIENT_CONFIG_CAPTURE_PROPERTY = "quarkusBuildLegacyAmbientConfigCapture";
    private static final String LEGACY_AMBIENT_CONFIG_CAPTURE_REASON = "legacy ambient config capture is enabled";

    public QuarkusApplicationBaseTask() {
        setGroup("build");

        Provider<Boolean> legacyAmbientConfigCapture = getProviders()
                .gradleProperty(LEGACY_AMBIENT_CONFIG_CAPTURE_PROPERTY)
                .map(Boolean::parseBoolean)
                .orElse(false);
        getLegacyAmbientConfigCapture().convention(legacyAmbientConfigCapture);

        disableConfigurationCacheIfLegacyAmbientConfigCaptureEnabled();
        getOutputs().doNotCacheIf(LEGACY_AMBIENT_CONFIG_CAPTURE_REASON,
                task -> getLegacyAmbientConfigCapture().getOrElse(false));
        getOutputs().upToDateWhen(task -> !getLegacyAmbientConfigCapture().getOrElse(false));
    }

    @Internal
    public abstract Property<Boolean> getLegacyAmbientConfigCapture();

    @Input
    public abstract SetProperty<String> getGradlePropertyPrefixes();

    @Input
    public abstract SetProperty<String> getGradlePropertyNames();

    @Input
    public abstract SetProperty<String> getSystemPropertyPrefixes();

    @Input
    public abstract SetProperty<String> getSystemPropertyNames();

    @Input
    public abstract SetProperty<String> getEnvironmentVariablePrefixes();

    @Input
    public abstract SetProperty<String> getEnvironmentVariableNames();

    @Inject
    protected abstract ProviderFactory getProviders();

    protected final void failUnimplementedTask() {
        warnIfLegacyAmbientConfigCaptureEnabled();
        throw new GradleException("Task '" + getPath()
                + "' is part of the new named Quarkus application task model, but execution is not implemented yet.");
    }

    protected void warnIfLegacyAmbientConfigCaptureEnabled() {
        if (getLegacyAmbientConfigCapture().getOrElse(false)) {
            getLogger().warn("""
                    Legacy ambient config capture is enabled for Quarkus application tasks.
                    All environment variables, JVM system properties, and Gradle project properties may affect task execution.
                    Configuration-cache reuse, build caching, and up-to-date checks are disabled for these tasks.
                    Prefer declaring configInputs prefixes/names or quarkusBuildProperties.
                    """);
        }
    }

    private void disableConfigurationCacheIfLegacyAmbientConfigCaptureEnabled() {
        if (getLegacyAmbientConfigCapture().getOrElse(false)) {
            notCompatibleWithConfigurationCache(
                    "Legacy ambient config capture reads all Gradle properties, JVM system properties, and environment variables.");
        }
    }

    Map<String, String> gradleProperties() {
        if (getLegacyAmbientConfigCapture().getOrElse(false)) {
            return getProviders().gradlePropertiesPrefixedBy("").get();
        }
        return filteredMapEntries(
                getGradlePropertyPrefixes().get(),
                getGradlePropertyNames().get(),
                getProviders()::gradlePropertiesPrefixedBy,
                getProviders()::gradleProperty);
    }

    Map<String, String> environmentVariables() {
        if (getLegacyAmbientConfigCapture().getOrElse(false)) {
            return getProviders().environmentVariablesPrefixedBy("").get();
        }
        return filteredMapEntries(
                getEnvironmentVariablePrefixes().get(),
                getEnvironmentVariableNames().get(),
                getProviders()::environmentVariablesPrefixedBy,
                getProviders()::environmentVariable);
    }

    Map<String, String> systemProperties() {
        if (getLegacyAmbientConfigCapture().getOrElse(false)) {
            return getProviders().systemPropertiesPrefixedBy("").get();
        }
        return filteredMapEntries(
                getSystemPropertyPrefixes().get(),
                getSystemPropertyNames().get(),
                getProviders()::systemPropertiesPrefixedBy,
                getProviders()::systemProperty);
    }

    private Map<String, String> filteredMapEntries(Set<String> prefixes, Set<String> names,
            Function<String, Provider<Map<String, String>>> prefixedPropertiesProvider,
            Function<String, Provider<String>> propertyProvider) {
        var result = new LinkedHashMap<String, String>();
        for (var prefix : prefixes) {
            result.putAll(prefixedPropertiesProvider.apply(prefix).get());
        }
        for (var name : names) {
            String value = propertyProvider.apply(name).getOrNull();
            if (value != null) {
                result.put(name, value);
            }
        }
        return result;
    }
}
