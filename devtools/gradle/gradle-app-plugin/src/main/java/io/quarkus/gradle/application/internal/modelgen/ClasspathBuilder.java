package io.quarkus.gradle.application.internal.modelgen;

import java.util.Map;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;

import io.quarkus.gradle.extension.ExtensionConstants;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.runtime.LaunchMode;

public final class ClasspathBuilder {

    private static final String RUNTIME_CONFIGURATION_NAME = "quarkusApplicationRuntimeClasspathConfiguration";
    private static final String DEV_RUNTIME_CONFIGURATION_NAME = "quarkusApplicationDevRuntimeClasspathConfiguration";
    private static final String TEST_RUNTIME_CONFIGURATION_NAME = "quarkusApplicationTestRuntimeClasspathConfiguration";
    private static final String CONDITIONAL_RUNTIME_CONFIGURATION_NAME = "quarkusApplicationConditionalRuntimeClasspathConfiguration";
    private static final String DEV_CONDITIONAL_RUNTIME_CONFIGURATION_NAME = "quarkusApplicationDevConditionalRuntimeClasspathConfiguration";
    private static final String TEST_CONDITIONAL_RUNTIME_CONFIGURATION_NAME = "quarkusApplicationTestConditionalRuntimeClasspathConfiguration";
    private static final String DEPLOYMENT_CONFIGURATION_NAME = "quarkusApplicationDeploymentClasspathConfiguration";
    private static final String TEST_DEPLOYMENT_CONFIGURATION_NAME = "quarkusApplicationTestDeploymentClasspathConfiguration";
    private static final String COMPILE_ONLY_CONFIGURATION_NAME = "quarkusApplicationCompileOnlyConfiguration";
    private static final String TEST_COMPILE_ONLY_CONFIGURATION_NAME = "quarkusApplicationTestCompileOnlyConfiguration";
    private static final String PLATFORM_PROPERTIES_CONFIGURATION_NAME = "quarkusApplicationPlatformProperties";

    private final Project project;

    public ClasspathBuilder(Project project) {
        this.project = project;
        setUpRuntimeConfiguration(RUNTIME_CONFIGURATION_NAME, CONDITIONAL_RUNTIME_CONFIGURATION_NAME, LaunchMode.NORMAL,
                getRawRuntimeConfiguration());
        setUpRuntimeConfiguration(DEV_RUNTIME_CONFIGURATION_NAME, DEV_CONDITIONAL_RUNTIME_CONFIGURATION_NAME,
                LaunchMode.DEVELOPMENT, getRawCompileClasspathConfiguration(), getRawRuntimeConfiguration());
        setUpRuntimeConfiguration(TEST_RUNTIME_CONFIGURATION_NAME, TEST_CONDITIONAL_RUNTIME_CONFIGURATION_NAME, LaunchMode.TEST,
                getRawTestRuntimeConfiguration());
        setUpDeploymentConfiguration(DEPLOYMENT_CONFIGURATION_NAME, getRuntimeConfiguration());
        setUpDeploymentConfiguration(TEST_DEPLOYMENT_CONFIGURATION_NAME, getTestRuntimeConfiguration());
        setUpCompileOnlyConfiguration(COMPILE_ONLY_CONFIGURATION_NAME,
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME);
        setUpCompileOnlyConfiguration(TEST_COMPILE_ONLY_CONFIGURATION_NAME,
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME);
        setUpPlatformPropertiesConfiguration();
    }

    public FileCollection getOriginalRuntimeClasspathAsInput() {
        return project.files(getRawRuntimeConfiguration(), getRuntimeConfiguration());
    }

    public FileCollection getOriginalTestRuntimeClasspathAsInput() {
        return project.files(getRawTestRuntimeConfiguration(), getTestRuntimeConfiguration());
    }

    public FileCollection getOriginalDevRuntimeClasspathAsInput() {
        return project.files(getRawCompileClasspathConfiguration(), getRawRuntimeConfiguration(), getDevRuntimeConfiguration());
    }

    public Configuration getRuntimeConfiguration() {
        return project.getConfigurations().getByName(RUNTIME_CONFIGURATION_NAME);
    }

    public Configuration getDevRuntimeConfiguration() {
        return project.getConfigurations().getByName(DEV_RUNTIME_CONFIGURATION_NAME);
    }

    public Configuration getTestRuntimeConfiguration() {
        return project.getConfigurations().getByName(TEST_RUNTIME_CONFIGURATION_NAME);
    }

    public Configuration getDeploymentConfiguration() {
        return project.getConfigurations().getByName(DEPLOYMENT_CONFIGURATION_NAME);
    }

    public Configuration getTestDeploymentConfiguration() {
        return project.getConfigurations().getByName(TEST_DEPLOYMENT_CONFIGURATION_NAME);
    }

    public Configuration getCompileOnlyConfiguration() {
        return project.getConfigurations().getByName(COMPILE_ONLY_CONFIGURATION_NAME);
    }

    public Configuration getTestCompileOnlyConfiguration() {
        return project.getConfigurations().getByName(TEST_COMPILE_ONLY_CONFIGURATION_NAME);
    }

    public Configuration getPlatformPropertiesConfiguration() {
        return project.getConfigurations().getByName(PLATFORM_PROPERTIES_CONFIGURATION_NAME);
    }

    private Configuration getRawRuntimeConfiguration() {
        return project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
    }

    private Configuration getRawTestRuntimeConfiguration() {
        return project.getConfigurations().getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME);
    }

    private Configuration getRawCompileClasspathConfiguration() {
        return project.getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
    }

    private void setUpRuntimeConfiguration(
            String runtimeConfigurationName,
            String conditionalRuntimeConfigurationName,
            LaunchMode launchMode,
            Configuration... rawRuntimeConfigurations) {
        setUpConditionalRuntimeConfiguration(conditionalRuntimeConfigurationName, rawRuntimeConfigurations);
        if (project.getConfigurations().findByName(runtimeConfigurationName) != null) {
            return;
        }
        project.getConfigurations().resolvable(runtimeConfigurationName, configuration -> {
            configuration.setCanBeConsumed(false);
            setJavaRuntimeAttributes(configuration.getAttributes());
            configuration.extendsFrom(rawRuntimeConfigurations);

            DependencyHandler dependencyHandler = project.getDependencies();
            var satisfiedConditionalDependencies = project.getProviders().of(
                    SatisfiedConditionalDependencyCoordinatesValueSource.class,
                    spec -> {
                        spec.getParameters().getRuntimeComponentKeys()
                                .set(componentKeys(rawRuntimeConfigurations));
                        spec.getParameters().getConditionalArtifactRecords()
                                .set(artifactRecords(project.getConfigurations()
                                        .getByName(conditionalRuntimeConfigurationName)));
                    });
            configuration.getDependencies().addAllLater(satisfiedConditionalDependencies
                    .map(coordinates -> coordinates.stream()
                            .map(dependencyHandler::create)
                            .toList()));
            if (launchMode == LaunchMode.DEVELOPMENT) {
                var conditionalDevDependencies = project.getProviders().of(ConditionalDevDependencyCoordinatesValueSource.class,
                        spec -> configureExternalRuntimeArtifacts(spec.getParameters().getRuntimeArtifacts(),
                                rawRuntimeConfigurations));
                configuration.getDependencies().addAllLater(conditionalDevDependencies
                        .map(coordinates -> coordinates.stream()
                                .map(dependencyHandler::create)
                                .toList()));
            }
        });
    }

    private void setUpConditionalRuntimeConfiguration(String configurationName, Configuration... rawRuntimeConfigurations) {
        if (project.getConfigurations().findByName(configurationName) != null) {
            return;
        }
        project.getConfigurations().resolvable(configurationName, configuration -> {
            configuration.setCanBeConsumed(false);
            setJavaRuntimeAttributes(configuration.getAttributes());

            DependencyHandler dependencyHandler = project.getDependencies();
            var conditionalDependencies = project.getProviders().of(ConditionalDependencyCoordinatesValueSource.class,
                    spec -> configureExternalRuntimeArtifacts(spec.getParameters().getRuntimeArtifacts(),
                            rawRuntimeConfigurations));
            configuration.getDependencies().addAllLater(conditionalDependencies
                    .map(coordinates -> coordinates.stream()
                            .map(dependencyHandler::create)
                            .toList()));
        });
    }

    private void configureExternalRuntimeArtifacts(org.gradle.api.file.ConfigurableFileCollection runtimeArtifacts,
            Configuration... rawRuntimeConfigurations) {
        for (Configuration rawRuntimeConfiguration : rawRuntimeConfigurations) {
            runtimeArtifacts.from(externalRuntimeArtifactFiles(rawRuntimeConfiguration));
        }
    }

    private void setUpDeploymentConfiguration(String configurationName, Configuration runtimeConfiguration) {
        if (project.getConfigurations().findByName(configurationName) != null) {
            return;
        }
        project.getConfigurations().resolvable(configurationName, configuration -> {
            configuration.setCanBeConsumed(false);
            setJavaRuntimeAttributes(configuration.getAttributes());
            DependencyHandler dependencyHandler = project.getDependencies();
            ObjectFactory objects = project.getObjects();
            var deploymentArtifacts = project.getProviders().of(DeploymentArtifactsValueSource.class,
                    spec -> spec.getParameters().getRuntimeArtifacts()
                            .from(externalRuntimeArtifactFiles(runtimeConfiguration)));
            configuration.getDependencies().addAllLater(deploymentArtifacts
                    .zip(localDeploymentDependencySpecs(runtimeConfiguration),
                            (externalSpecs, localSpecs) -> java.util.stream.Stream.concat(
                                    externalSpecs.stream(),
                                    java.util.stream.StreamSupport.stream(localSpecs.spliterator(), false))
                                    .distinct()
                                    .sorted()
                                    .toList())
                    .map(specs -> specs
                            .stream()
                            .map(spec -> deploymentDependency(dependencyHandler, objects, spec))
                            .toList()));
        });
    }

    private Provider<Iterable<String>> componentKeys(Configuration... configurations) {
        return project.provider(() -> {
            java.util.Set<String> keys = new java.util.TreeSet<>();
            for (Configuration configuration : configurations) {
                java.util.Set<ComponentIdentifier> visited = new java.util.HashSet<>();
                collectComponentKeys(configuration.getIncoming().getResolutionResult().getRootComponent().get(), visited, keys);
            }
            return keys;
        });
    }

    private static void collectComponentKeys(
            ResolvedComponentResult component,
            java.util.Set<ComponentIdentifier> visited,
            java.util.Set<String> keys) {
        if (!visited.add(component.getId())) {
            return;
        }
        if (component.getId() instanceof ModuleComponentIdentifier module) {
            keys.add(ConditionalDependencyResolver.serializeKey(ArtifactCoords.of(
                    module.getGroup(), module.getModule(), ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_JAR,
                    module.getVersion()).getKey()));
        }
        for (DependencyResult dependency : component.getDependencies()) {
            if (dependency instanceof ResolvedDependencyResult resolved) {
                collectComponentKeys(resolved.getSelected(), visited, keys);
            }
        }
    }

    private Provider<Iterable<String>> artifactRecords(Configuration configuration) {
        return configuration.getIncoming().getArtifacts().getResolvedArtifacts()
                .zip(configuration.getIncoming().getResolutionResult().getRootComponent(),
                        (artifacts, root) -> artifacts.stream()
                                .filter(artifact -> directlyRequested(root, artifact))
                                .flatMap(artifact -> artifactRecord(artifact).stream())
                                .sorted()
                                .toList());
    }

    private static boolean directlyRequested(ResolvedComponentResult root, ResolvedArtifactResult artifact) {
        ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
        for (DependencyResult dependency : root.getDependencies()) {
            if (dependency instanceof ResolvedDependencyResult resolved
                    && resolved.getSelected().getId().equals(componentIdentifier)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Optional<String> artifactRecord(ResolvedArtifactResult artifact) {
        if (!(artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier componentIdentifier)) {
            return java.util.Optional.empty();
        }
        String type = artifact.getVariant().getAttributes().getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE);
        if (type == null || type.isBlank()) {
            type = ArtifactCoords.TYPE_JAR;
        }
        return java.util.Optional.of(new ArtifactRecord(
                componentIdentifier.getGroup(),
                componentIdentifier.getModule(),
                componentIdentifier.getVersion(),
                ArtifactCoords.DEFAULT_CLASSIFIER,
                type,
                artifact.getFile()).serialize());
    }

    private FileCollection externalRuntimeArtifactFiles(Configuration configuration) {
        return configuration.getIncoming()
                .artifactView(view -> view.componentFilter(ModuleComponentIdentifier.class::isInstance))
                .getFiles();
    }

    private Provider<Iterable<String>> localDeploymentDependencySpecs(Configuration configuration) {
        return configuration.getIncoming().getResolutionResult().getRootComponent()
                .map(root -> {
                    java.util.Set<ComponentIdentifier> visited = new java.util.HashSet<>();
                    java.util.Set<String> specs = new java.util.TreeSet<>();
                    collectLocalDeploymentDependencySpecs(root, visited, specs);
                    return specs;
                });
    }

    private static void collectLocalDeploymentDependencySpecs(
            ResolvedComponentResult component,
            java.util.Set<ComponentIdentifier> visited,
            java.util.Set<String> specs) {
        if (!visited.add(component.getId())) {
            return;
        }
        if (component.getId() instanceof ProjectComponentIdentifier projectComponent
                && selectedExtensionRuntimeVariant(component)) {
            specs.add(DeploymentDependencySpec.project(projectComponent.getProjectPath()).serialize());
        }
        for (DependencyResult dependency : component.getDependencies()) {
            if (dependency instanceof ResolvedDependencyResult resolved) {
                collectLocalDeploymentDependencySpecs(resolved.getSelected(), visited, specs);
            }
        }
    }

    private static boolean selectedExtensionRuntimeVariant(ResolvedComponentResult component) {
        for (ResolvedVariantResult variant : component.getVariants()) {
            Boolean extensionRuntime = variant.getAttributes()
                    .getAttribute(ExtensionConstants.EXTENSION_RUNTIME_ATTRIBUTE);
            if (Boolean.TRUE.equals(extensionRuntime)) {
                return true;
            }
        }
        return false;
    }

    private static Dependency deploymentDependency(DependencyHandler dependencyHandler, ObjectFactory objects,
            String serializedSpec) {
        DeploymentDependencySpec spec = DeploymentDependencySpec.deserialize(serializedSpec);
        if (spec.external()) {
            return dependencyHandler.create(spec.value());
        }
        ProjectDependency dependency = (ProjectDependency) dependencyHandler.project(Map.of("path", spec.value()));
        dependency.attributes(attributes -> {
            attributes.attribute(Category.CATEGORY_ATTRIBUTE,
                    objects.named(Category.class, ExtensionConstants.EXTENSION_DEPLOYMENT_DEPENDENCY_CATEGORY));
            attributes.attribute(ExtensionConstants.EXTENSION_DEPLOYMENT_DEPENDENCY_ATTRIBUTE, true);
        });
        return dependency;
    }

    private void setUpCompileOnlyConfiguration(String configurationName, String... extendsFrom) {
        if (project.getConfigurations().findByName(configurationName) != null) {
            return;
        }
        project.getConfigurations().resolvable(configurationName, configuration -> {
            configuration.setCanBeConsumed(false);
            setJavaRuntimeAttributes(configuration.getAttributes());
            for (String parent : extendsFrom) {
                configuration.extendsFrom(project.getConfigurations().getByName(parent));
            }
        });
    }

    private void setJavaRuntimeAttributes(AttributeContainer attributes) {
        ObjectFactory objects = project.getObjects();
        attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements.class, LibraryElements.JAR));
        attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
        attributes.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
    }

    private void setUpPlatformPropertiesConfiguration() {
        if (project.getConfigurations().findByName(PLATFORM_PROPERTIES_CONFIGURATION_NAME) != null) {
            return;
        }
        project.getConfigurations().resolvable(PLATFORM_PROPERTIES_CONFIGURATION_NAME, configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setTransitive(false);
        });
    }
}
