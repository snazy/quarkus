package io.quarkus.gradle.application.tasks;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.deployment.DeploymentConfigValidator;
import io.quarkus.gradle.application.internal.deployment.DeploymentImageSourceRequest;
import io.quarkus.gradle.application.internal.deployment.DeploymentImageSourceResolution;
import io.quarkus.gradle.application.internal.deployment.DeploymentImageSourceResolver;
import io.quarkus.gradle.application.internal.deployment.DeploymentResult;
import io.quarkus.gradle.application.internal.deployment.DeploymentResultCodec;
import io.quarkus.gradle.application.internal.execution.DeploymentRequest;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentImageSource;
import io.quarkus.gradle.application.model.QuarkusApplicationDeploymentTarget;

@DisableCachingByDefault(because = "Deployments mutate external cluster state")
public abstract class QuarkusApplicationDeployTask extends QuarkusApplicationBuildTask {

    private final DeploymentImageSourceResolver imageSourceResolver = new DeploymentImageSourceResolver();
    private final DeploymentConfigValidator configValidator = new DeploymentConfigValidator();
    private final DeploymentResultCodec resultCodec = new DeploymentResultCodec();

    public QuarkusApplicationDeployTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    @Input
    public abstract Property<String> getDeploymentName();

    @Input
    public abstract Property<QuarkusApplicationDeploymentTarget> getDeploymentTarget();

    @Input
    public abstract Property<QuarkusApplicationDeploymentImageSource> getImageSource();

    @Input
    @Optional
    public abstract Property<String> getImageReference();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getNormalImagePushReceiptFile();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getAotEnhancedImagePushReceiptFile();

    @OutputFile
    public abstract RegularFileProperty getReceiptFile();

    @TaskAction
    public void deployApplication() {
        DeploymentImageSourceResolution image = imageSourceResolver.resolve(
                new DeploymentImageSourceRequest(
                        getImageSource().get(),
                        java.util.Optional.ofNullable(getImageReference().getOrNull()),
                        optionalPath(getNormalImagePushReceiptFile()),
                        optionalPath(getAotEnhancedImagePushReceiptFile())));
        Map<String, String> operationForcedProperties = deploymentOperationProperties(image);
        validateUnforcedConfig(image);
        DeploymentRequest request = new DeploymentRequest(
                buildRequest(operationForcedProperties),
                getDeploymentName().get(),
                getDeploymentTarget().get(),
                getImageSource().get(),
                image.imageReference(),
                getReceiptFile().get().getAsFile().toPath());
        DeploymentResult result = buildOperations().deploy(request);
        resultCodec.write(request.receiptFile(), result);
    }

    private void validateUnforcedConfig(DeploymentImageSourceResolution image) {
        configValidator.validate(
                getBuildName().get(),
                getDeploymentName().get(),
                getDeploymentTarget().get(),
                getImageSource().get(),
                image.imageReference(),
                effectiveConfig(Map.of()).fullValues());
    }

    private Map<String, String> deploymentOperationProperties(DeploymentImageSourceResolution image) {
        Map<String, String> properties = new LinkedHashMap<>();
        String target = getDeploymentTarget().get().quarkusDeployTarget();
        properties.put("quarkus.deploy.target", target);
        properties.put("quarkus." + target + ".deploy", "true");
        properties.put("quarkus.kubernetes.deployment-target", target);
        properties.putAll(image.forcedProperties());
        return properties;
    }

    private static java.util.Optional<Path> optionalPath(RegularFileProperty file) {
        if (!file.isPresent()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(file.get().getAsFile().toPath());
    }
}
