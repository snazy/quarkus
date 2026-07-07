package io.quarkus.gradle.extension;

import java.util.Locale;

import org.gradle.api.GradleException;

public enum QuarkusLegacyTaskUsageLevel {
    OFF,
    WARN,
    FAIL;

    public static QuarkusLegacyTaskUsageLevel of(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new GradleException("Unsupported quarkus.diagnostics.legacy-task-usage value '" + value
                    + "'. Supported values are: off, warn, fail.", e);
        }
    }
}
