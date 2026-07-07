package io.quarkus.gradle.extension;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;

public abstract class QuarkusDiagnostics {

    public static final String LEGACY_TASK_USAGE_PROPERTY = "quarkus.diagnostics.legacy-task-usage";

    @Inject
    public QuarkusDiagnostics(Project project) {
        getLegacyTaskUsage().convention(project.getProviders()
                .gradleProperty(LEGACY_TASK_USAGE_PROPERTY)
                .map(QuarkusLegacyTaskUsageLevel::of)
                .orElse(QuarkusLegacyTaskUsageLevel.OFF));
    }

    @Internal
    public abstract Property<QuarkusLegacyTaskUsageLevel> getLegacyTaskUsage();
}
