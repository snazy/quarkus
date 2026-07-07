package io.quarkus.gradle.application.dsl;

import java.util.List;
import java.util.Map;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;

public abstract class QuarkusApplicationDevForkOptions {

    public QuarkusApplicationDevForkOptions() {
        getJvmArgs().convention(List.of());
        getSystemProperties().convention(Map.of());
    }

    public abstract ListProperty<String> getJvmArgs();

    public abstract MapProperty<String, String> getSystemProperties();

    @SuppressWarnings("unused") // publicly documented DSL
    public void jvmArgs(String... args) {
        getJvmArgs().addAll(args);
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void systemProperty(String name, String value) {
        getSystemProperties().put(name, value);
    }
}
