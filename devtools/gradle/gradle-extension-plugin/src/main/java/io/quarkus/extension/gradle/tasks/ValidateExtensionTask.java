package io.quarkus.extension.gradle.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedModuleVersion;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.extension.gradle.QuarkusExtensionConfiguration;
import io.quarkus.fs.util.ZipUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;

@DisableCachingByDefault(because = "Not cacheable")
public abstract class ValidateExtensionTask extends DefaultTask {

    @Inject
    public ValidateExtensionTask(QuarkusExtensionConfiguration quarkusExtensionConfiguration,
            Configuration runtimeModuleClasspath) {
        setDescription("Validate extension dependencies");
        setGroup("quarkus");

        getRuntimeModuleArtifacts().set(getProviders().provider(
                () -> artifactIds(runtimeModuleClasspath.getResolvedConfiguration().getResolvedArtifacts())));
        getRuntimeExtensionDeploymentArtifacts().set(getProviders().provider(
                () -> runtimeExtensionDeploymentArtifacts(
                        runtimeModuleClasspath.getResolvedConfiguration().getResolvedArtifacts())));
        getDeploymentModuleArtifacts().convention(Collections.emptyList());
        getValidationDisabled().set(quarkusExtensionConfiguration.getDisableValidation());
        getLocalDeploymentValidationEnabled().convention(true);

        this.onlyIf(t -> !getValidationDisabled().get());
    }

    @Inject
    public abstract ProviderFactory getProviders();

    @Inject
    public abstract ObjectFactory getObjects();

    @Input
    public abstract ListProperty<String> getRuntimeModuleArtifacts();

    @Input
    public abstract ListProperty<String> getDeploymentModuleArtifacts();

    @Input
    public abstract ListProperty<String> getRuntimeExtensionDeploymentArtifacts();

    @Input
    public abstract Property<Boolean> getValidationDisabled();

    @Input
    public abstract Property<Boolean> getLocalDeploymentValidationEnabled();

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getDeploymentMarker();

    public void setDeploymentModuleClasspath(Configuration deploymentModuleClasspath,
            Provider<Boolean> localDeploymentValidationEnabled) {
        getLocalDeploymentValidationEnabled().set(localDeploymentValidationEnabled);
        getDeploymentModuleArtifacts().set(getProject().getProviders().provider(() -> {
            if (!shouldValidateLocalDeployment()) {
                return Collections.emptyList();
            }
            return componentIds(deploymentModuleClasspath.getIncoming().getResolutionResult().getAllComponents());
        }));
    }

    public void setDeploymentMarker(Configuration deploymentMarker, Provider<Boolean> localDeploymentValidationEnabled) {
        getLocalDeploymentValidationEnabled().set(localDeploymentValidationEnabled);
        getDeploymentMarker().from(getProviders().provider(() -> {
            if (!shouldValidateLocalDeployment()) {
                return getObjects().fileCollection();
            }
            return deploymentMarker;
        }));
    }

    private boolean shouldValidateLocalDeployment() {
        return !getValidationDisabled().get() && getLocalDeploymentValidationEnabled().get();
    }

    private static List<String> artifactIds(Set<ResolvedArtifact> artifacts) {
        return artifacts.stream()
                .map(artifact -> {
                    ResolvedModuleVersion moduleVersion = artifact.getModuleVersion();
                    return moduleVersion.getId().getGroup() + ':'
                            + moduleVersion.getId().getName() + ':'
                            + moduleVersion.getId().getVersion() + ':'
                            + artifact.getClassifier() + ':'
                            + artifact.getExtension();
                })
                .sorted()
                .collect(Collectors.toList());
    }

    private static List<String> componentIds(Set<ResolvedComponentResult> components) {
        return components.stream()
                .map(ResolvedComponentResult::getModuleVersion)
                .filter(Objects::nonNull)
                .map(moduleVersion -> moduleVersion.getGroup() + ':'
                        + moduleVersion.getName() + ':'
                        + moduleVersion.getVersion() + "::jar")
                .sorted()
                .collect(Collectors.toList());
    }

    private static List<String> runtimeExtensionDeploymentArtifacts(Set<ResolvedArtifact> runtimeArtifacts) {
        List<String> runtimeExtensions = new ArrayList<>();
        for (ResolvedArtifact resolvedArtifact : runtimeArtifacts) {
            ArtifactKey deploymentKey = deploymentArtifactKeyOrNull(resolvedArtifact);
            if (deploymentKey != null) {
                runtimeExtensions.add(deploymentKey.getGroupId() + ':' + deploymentKey.getArtifactId());
            }
        }
        Collections.sort(runtimeExtensions);
        return runtimeExtensions;
    }

    private static ArtifactKey deploymentArtifactKeyOrNull(ResolvedArtifact artifact) {
        try {
            var artifactFile = artifact.getFile();
            if (!artifactFile.exists()) {
                return null;
            }

            if (artifactFile.isDirectory()) {
                Path descriptorPath = artifactFile.toPath().resolve(BootstrapConstants.DESCRIPTOR_PATH);
                if (Files.isRegularFile(descriptorPath)) {
                    return readDeploymentArtifactKey(descriptorPath);
                }
            } else if (ArtifactCoords.TYPE_JAR.equals(artifact.getExtension())) {
                try (FileSystem artifactFileSystem = ZipUtils.newFileSystem(artifactFile.toPath())) {
                    Path descriptorPath = artifactFileSystem.getPath(BootstrapConstants.DESCRIPTOR_PATH);
                    if (Files.exists(descriptorPath)) {
                        return readDeploymentArtifactKey(descriptorPath);
                    }
                }
            }
            return null;
        } catch (IOException e) {
            throw new GradleException("Failed to read " + artifact.getFile(), e);
        }
    }

    private static ArtifactKey readDeploymentArtifactKey(Path descriptorPath) throws IOException {
        Properties descriptor = new Properties();
        try (InputStream inputStream = Files.newInputStream(descriptorPath)) {
            descriptor.load(inputStream);
        }
        String deploymentArtifact = descriptor.getProperty(BootstrapConstants.PROP_DEPLOYMENT_ARTIFACT);
        if (deploymentArtifact == null) {
            return null;
        }
        ArtifactCoords deploymentCoords = ArtifactCoords.fromString(deploymentArtifact);
        return ArtifactKey.ga(deploymentCoords.getGroupId(), deploymentCoords.getArtifactId());
    }

    @TaskAction
    public void validateExtension() {
        if (shouldValidateLocalDeployment()) {
            getDeploymentMarker().getFiles();
        }
        List<ArtifactKey> deploymentModuleKeys = artifactKeys(getRuntimeExtensionDeploymentArtifacts().get());
        List<ArtifactKey> invalidRuntimeArtifacts = findExtensionInConfiguration(getRuntimeModuleArtifacts().get(),
                deploymentModuleKeys);

        if (shouldValidateLocalDeployment()) {
            List<ArtifactKey> existingDeploymentModuleKeys = findExtensionInConfiguration(getDeploymentModuleArtifacts().get(),
                    deploymentModuleKeys);
            deploymentModuleKeys.removeAll(existingDeploymentModuleKeys);
        } else {
            deploymentModuleKeys.clear();
        }

        boolean hasErrors = !invalidRuntimeArtifacts.isEmpty() || !deploymentModuleKeys.isEmpty();

        if (hasErrors) {
            printValidationErrors(invalidRuntimeArtifacts, deploymentModuleKeys);
        }
    }

    private static List<ArtifactKey> artifactKeys(List<String> artifactIds) {
        return artifactIds.stream()
                .map(ValidateExtensionTask::toArtifactKey)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<ArtifactKey> findExtensionInConfiguration(List<String> deploymentArtifacts,
            List<ArtifactKey> extensions) {
        List<ArtifactKey> foundExtensions = new ArrayList<>();

        for (String deploymentArtifact : deploymentArtifacts) {
            ArtifactKey key = toArtifactKey(deploymentArtifact);
            if (extensions.contains(key)) {
                foundExtensions.add(key);
            }
        }
        return foundExtensions;
    }

    private void printValidationErrors(List<ArtifactKey> invalidRuntimeArtifacts,
            List<ArtifactKey> missingDeploymentArtifacts) {
        Logger log = getLogger();
        log.error("Quarkus Extension Dependency Verification Error");

        if (!invalidRuntimeArtifacts.isEmpty()) {
            log.error("The following deployment artifact(s) appear on the runtime classpath: ");
            for (ArtifactKey invalidRuntimeArtifact : invalidRuntimeArtifacts) {
                log.error("- {}", invalidRuntimeArtifact);
            }
        }

        if (!missingDeploymentArtifacts.isEmpty()) {
            log.error("The following deployment artifact(s) were found to be missing in the deployment module: ");
            for (ArtifactKey missingDeploymentArtifact : missingDeploymentArtifacts) {
                log.error("- {}", missingDeploymentArtifact);
            }
        }

        throw new GradleException("Quarkus Extension Dependency Verification Error. See logs below");
    }

    private static ArtifactKey toArtifactKey(String artifactId) {
        int firstSeparator = artifactId.indexOf(':');
        int secondSeparator = artifactId.indexOf(':', firstSeparator + 1);
        if (secondSeparator < 0) {
            return ArtifactKey.ga(artifactId.substring(0, firstSeparator), artifactId.substring(firstSeparator + 1));
        }
        return ArtifactKey.ga(artifactId.substring(0, firstSeparator),
                artifactId.substring(firstSeparator + 1, secondSeparator));
    }
}
