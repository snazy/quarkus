package io.quarkus.gradle.tasks.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

public abstract class ForcedPropertieBuildService implements BuildService<BuildServiceParameters.None> {

    private final ConcurrentMap<String, String> properties = new ConcurrentHashMap<>();

    public Map<String, String> getProperties() {
        return Map.copyOf(properties);
    }

    public void put(String key, String value) {
        properties.put(key, value);
    }

    public void putAll(Map<String, String> properties) {
        this.properties.putAll(properties);
    }

}
