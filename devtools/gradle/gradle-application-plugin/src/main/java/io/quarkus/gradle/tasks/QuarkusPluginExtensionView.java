package io.quarkus.gradle.tasks;

import static io.quarkus.gradle.tasks.AbstractQuarkusExtension.QUARKUS_PROFILE;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.process.JavaForkOptions;

import io.quarkus.gradle.extension.QuarkusPluginExtension;

/**
 * Configuration cache compatible view of Quarkus extension
 */
public abstract class QuarkusPluginExtensionView {
    private final DeprecatedGradleDslUsageReporter deprecatedDslUsageReporter;

    @Inject
    public QuarkusPluginExtensionView(ProviderFactory providerFactory, QuarkusPluginExtension extension,
            FileCollection mainSourceDirectories) {
        this.deprecatedDslUsageReporter = extension.deprecatedDslUsageReporterInternal();
        getNativeBuild().set(extension.getNativeBuild());
        getCacheLargeArtifacts().set(extension.getCacheLargeArtifacts());
        getCleanupBuildOutput().set(extension.getCleanupBuildOutput());
        getFinalName().set(extension.getFinalName());
        getCodeGenForkOptions().set(providerFactory.provider(() -> extension.codeGenForkOptions));
        getBuildForkOptions().set(providerFactory.provider(() -> extension.buildForkOptions));
        getIgnoredEntries().set(extension.getIgnoredEntries());
        getMainResources().setFrom(mainSourceDirectories);
        getQuarkusBuildProperties().set(extension.getQuarkusBuildProperties());
        getQuarkusRelevantProjectProperties().set(providerFactory.gradlePropertiesPrefixedBy("quarkus."));
        getQuarkusProfileSystemVariable().set(providerFactory.systemProperty(QUARKUS_PROFILE));
        getQuarkusProfileEnvVariable().set(providerFactory.environmentVariable("QUARKUS_PROFILE"));
        getCachingRelevantProperties().set(extension.getCachingRelevantProperties());
        getForcedProperties().set(extension.getForcedProperties());
        getNativeArguments().set(extension.getNativeArguments());
        getProjectProperties().set(AbstractQuarkusExtension.quarkusRelevantProperties(providerFactory));
    }

    DeprecatedGradleDslUsageReporter deprecatedDslUsageReporter() {
        return deprecatedDslUsageReporter;
    }

    @Input
    @Optional
    public abstract Property<Boolean> getNativeBuild();

    @Input
    public abstract Property<Boolean> getCacheLargeArtifacts();

    @Input
    public abstract ListProperty<String> getCachingRelevantProperties();

    @Input
    public abstract Property<Boolean> getCleanupBuildOutput();

    @Input
    public abstract Property<String> getFinalName();

    @Input
    public abstract MapProperty<String, Object> getProjectProperties();

    @Nested
    public abstract ListProperty<Action<? super JavaForkOptions>> getCodeGenForkOptions();

    @Nested
    public abstract ListProperty<Action<? super JavaForkOptions>> getBuildForkOptions();

    @Input
    public abstract ListProperty<String> getIgnoredEntries();

    @Input
    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    @Input
    public abstract MapProperty<String, String> getQuarkusRelevantProjectProperties();

    @Internal
    public abstract ConfigurableFileCollection getMainResources();

    @Input
    @Optional
    public abstract Property<String> getQuarkusProfileSystemVariable();

    @Input
    @Optional
    public abstract Property<String> getQuarkusProfileEnvVariable();

    @Input
    @Optional
    public abstract MapProperty<String, String> getForcedProperties();

    @Input
    @Optional
    public abstract MapProperty<String, String> getNativeArguments();

}
