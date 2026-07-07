package io.quarkus.gradle.application.dsl;

import java.util.List;
import java.util.Map;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

public abstract class QuarkusApplicationForkOptions {

    public QuarkusApplicationForkOptions() {
        getJvmArgs().convention(List.of());
        getSystemProperties().convention(Map.of());
        getEnvironment().convention(Map.of());
        getEnableAssertions().convention(false);
        getDebug().convention(false);
    }

    @Input
    public abstract ListProperty<String> getJvmArgs();

    @Input
    public abstract MapProperty<String, String> getSystemProperties();

    @Input
    public abstract MapProperty<String, String> getEnvironment();

    @Input
    @Optional
    public abstract Property<String> getMinHeapSize();

    @Input
    @Optional
    public abstract Property<String> getMaxHeapSize();

    @Input
    public abstract Property<Boolean> getEnableAssertions();

    @Input
    public abstract Property<Boolean> getDebug();

    @Input
    @Optional
    public abstract Property<String> getDefaultCharacterEncoding();

    @SuppressWarnings("unused") // publicly documented DSL
    public void jvmArgs(String... args) {
        getJvmArgs().addAll(args);
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void systemProperty(String name, String value) {
        getSystemProperties().put(name, value);
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void systemProperties(Map<String, String> properties) {
        getSystemProperties().putAll(properties);
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void environment(String name, String value) {
        getEnvironment().put(name, value);
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void environment(Map<String, String> environment) {
        getEnvironment().putAll(environment);
    }
}
