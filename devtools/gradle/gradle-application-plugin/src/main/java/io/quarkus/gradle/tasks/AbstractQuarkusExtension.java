package io.quarkus.gradle.tasks;

import static io.quarkus.gradle.tasks.QuarkusGradleUtils.getSourceSet;
import static java.util.Collections.emptyList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.java.archives.Attributes;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.process.JavaForkOptions;

import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.gradle.dsl.Manifest;

/**
 * This base class exists to hide internal properties, make those only available in the {@link io.quarkus.gradle.tasks}
 * package and to the {@link io.quarkus.gradle.extension.QuarkusPluginExtension} class itself.
 */
public abstract class AbstractQuarkusExtension {
    private static final String MANIFEST_SECTIONS_PROPERTY_PREFIX = "quarkus.package.jar.manifest.sections";
    private static final String MANIFEST_ATTRIBUTES_PROPERTY_PREFIX = "quarkus.package.jar.manifest.attributes";

    protected static final String QUARKUS_PROFILE = "quarkus.profile";
    private final Property<BaseConfig> baseConfigProperty;
    private final DeprecatedGradleDslUsageReporter deprecatedDslUsageReporter = new DeprecatedGradleDslUsageReporter();
    protected final List<Action<? super JavaForkOptions>> codeGenForkOptions;
    protected final List<Action<? super JavaForkOptions>> buildForkOptions;

    protected AbstractQuarkusExtension(Project project) {
        this.baseConfigProperty = project.getObjects().property(BaseConfig.class);
        getFinalName().convention(project.provider(() -> String.format("%s-%s", project.getName(), project.getVersion())));
        getCachingRelevantProperties().value(List.of("quarkus[.].*", "platform[.]quarkus[.].*"));
        getIgnoredEntries().convention(
                project.provider(() -> baseConfig().packageConfig().jar().userConfiguredIgnoredEntries().orElse(emptyList())));
        getBaseConfig().value(project.provider(this::buildBaseConfig));
        getSourceDirectories()
                .from(getSourceSet(project, SourceSet.MAIN_SOURCE_SET_NAME).getResources().getSourceDirectories());
        this.codeGenForkOptions = new ArrayList<>();
        this.buildForkOptions = new ArrayList<>();
    }

    @Internal
    protected abstract ConfigurableFileCollection getSourceDirectories();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    private BaseConfig buildBaseConfig() {
        // Using a ValueSource to construct the "base config" map. The ValueSource wraps all
        // SmallRyeConfig construction (which internally calls System.getProperties()) in an
        // opaque boundary, so Gradle's configuration cache does not track individual system
        // property accesses as inputs. Only the final result map is compared between builds.
        Set<File> resourcesDirs = getSourceDirectories().getFiles();

        var providers = getProviderFactory();

        // Filter project properties to quarkus-relevant ones to avoid tracking all project
        // properties as configuration cache inputs.
        Map<String, String> filteredProjectProperties = quarkusRelevantProperties(providers).get();

        Provider<Map<String, String>> configMapProvider = providers
                .of(QuarkusConfigValueSource.class, spec -> {
                    var params = spec.getParameters();
                    params.getBuildProperties().set(getQuarkusBuildProperties());
                    params.getProjectProperties().set(filteredProjectProperties);
                    params.getSourceDirectories().set(resourcesDirs);
                    params.getProfile().set(quarkusProfile());
                    params.getJavaHome().set(providers.systemProperty("java.home"));
                    params.getUserHome().set(providers.systemProperty("user.home"));
                });

        return new BaseConfig(configMapProvider.get());
    }

    /**
     * Returns only quarkus-relevant project properties, to avoid registering all project
     * properties as configuration cache inputs.
     *
     * Uses {@code gradlePropertiesPrefixedBy} rather than {@code Project.getProperties()}: the latter
     * enumerates the whole project property bag and is not allowed under Isolated Projects, while the
     * former returns only the matching keys and is configuration-cache friendly.
     */
    static Provider<Map<String, String>> quarkusRelevantProperties(ProviderFactory providers) {
        // gradlePropertiesPrefixedBy is configuration-cache and Isolated-Projects friendly, unlike
        // Project.getProperties() which is not allowed under Isolated Projects and deprecated.
        var quarkusProjectProperties = providers.gradlePropertiesPrefixedBy("quarkus.");
        var platformQuarkusProjectProperties = providers.gradlePropertiesPrefixedBy("platform.quarkus.");
        return quarkusProjectProperties.zip(platformQuarkusProjectProperties,
                (quarkus, platform) -> {
                    Map<String, String> merged = new HashMap<>(quarkus);
                    merged.putAll(platform);
                    return merged;
                });
    }

    @Internal
    Property<BaseConfig> getBaseConfig() {
        return baseConfigProperty;
    }

    /**
     * Internal diagnostic delegate. Not intended as build-script DSL.
     */
    public DeprecatedGradleDslUsageReporter deprecatedDslUsageReporterInternal() {
        return deprecatedDslUsageReporter;
    }

    BaseConfig baseConfig() {
        getBaseConfig().finalizeValue();
        return getBaseConfig().get();
    }

    @Internal
    protected abstract Property<String> getFinalName();

    @Internal
    protected abstract ListProperty<String> getCachingRelevantProperties();

    @Internal
    protected abstract ListProperty<String> getIgnoredEntries();

    @Internal
    protected abstract MapProperty<String, String> getQuarkusBuildProperties();

    @Internal
    protected abstract MapProperty<String, String> getForcedProperties();

    @Internal
    protected abstract MapProperty<String, String> getNativeArguments();

    @Input
    public abstract Property<Boolean> getNativeBuild();

    public Manifest manifest() {
        return baseConfig().manifest();
    }

    public Map<String, Attributes> getAttributes() {
        return manifest().getSections();
    }

    public PackageConfig packageConfig() {
        return baseConfig().packageConfig();
    }

    public Map<String, String> cachingRelevantProperties(List<String> propertyPatterns) {
        return baseConfig().cachingRelevantProperties(propertyPatterns, getProviderFactory());
    }

    public NativeConfig nativeConfig() {
        return baseConfig().nativeConfig();
    }

    private String quarkusProfile() {
        // Use Gradle Provider API for CC-compatible single property lookups
        var providers = getProviderFactory();
        String profile = providers.systemProperty(QUARKUS_PROFILE).getOrNull();
        if (profile == null) {
            profile = providers.environmentVariable("QUARKUS_PROFILE").getOrNull();
        }
        if (profile == null) {
            profile = getQuarkusBuildProperties().getting(QUARKUS_PROFILE).getOrNull();
        }
        if (profile == null) {
            // gradleProperty instead of Project.getProperties().get(...), which is not allowed under
            // Isolated Projects.
            profile = providers.gradleProperty(QUARKUS_PROFILE).getOrNull();
        }
        if (profile == null) {
            profile = "prod";
        }
        return profile;
    }

    protected static String toManifestAttributeKey(String key) {
        if (key.contains("\"")) {
            throw new GradleException("Manifest entry name " + key + " is invalid. \" characters are not allowed.");
        }
        return String.format("%s.\"%s\"", MANIFEST_ATTRIBUTES_PROPERTY_PREFIX, key);
    }

    protected static String toManifestSectionAttributeKey(String section, String key) {
        if (section.contains("\"")) {
            throw new GradleException("Manifest section name " + section + " is invalid. \" characters are not allowed.");
        }
        if (key.contains("\"")) {
            throw new GradleException("Manifest entry name " + key + " is invalid. \" characters are not allowed.");
        }
        return String.format("%s.\"%s\".\"%s\"", MANIFEST_SECTIONS_PROPERTY_PREFIX, section,
                key);
    }
}
