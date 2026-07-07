package io.quarkus.gradle.application.dsl;

import org.gradle.api.Action;

/** Plugin internal use only. */
public final class PluginInternalHelper {
    private PluginInternalHelper() {
    }

    public static void whenAotEnhancedImageConfigured(QuarkusApplicationBuild build,
            Action<? super QuarkusApplicationBuild> action) {
        build.whenAotEnhancedImageConfigured(action);
    }
}
