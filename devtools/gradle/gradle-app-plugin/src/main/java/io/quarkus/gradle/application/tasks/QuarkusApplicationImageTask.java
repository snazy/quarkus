package io.quarkus.gradle.application.tasks;

import java.util.Map;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.execution.ImageOperation;
import io.quarkus.gradle.application.internal.execution.ImageRequest;
import io.quarkus.gradle.application.internal.image.BuiltContainerImage;
import io.quarkus.gradle.application.internal.image.BuiltContainerImageResultCodec;
import io.quarkus.gradle.application.internal.image.ContainerImageTarget;
import io.quarkus.gradle.application.model.QuarkusApplicationImageBuilder;

@DisableCachingByDefault(because = "Container image tasks mutate external container image state")
public abstract class QuarkusApplicationImageTask extends QuarkusApplicationBuildTask {

    private final BuiltContainerImageResultCodec resultCodec = new BuiltContainerImageResultCodec();

    @Input
    @Optional
    public abstract Property<String> getImageReference();

    @Input
    @Optional
    public abstract Property<String> getImageRepository();

    @Input
    @Optional
    public abstract Property<String> getImageTag();

    @Input
    @Optional
    public abstract Property<QuarkusApplicationImageBuilder> getImageBuilder();

    @Input
    public abstract MapProperty<String, String> getImageQuarkusBuildProperties();

    protected void executeImageOperation(ImageOperation operation,
            java.nio.file.Path receiptFile) {
        Map<String, String> operationForcedProperties = imageOperationProperties(operation);
        ImageRequest request = new ImageRequest(
                buildRequest(operationForcedProperties),
                operation,
                containerImageTarget(),
                java.util.Optional.ofNullable(getImageBuilder().getOrNull()),
                getQuarkusBuildProperties().get(),
                getImageQuarkusBuildProperties().get(),
                receiptFile,
                java.util.Optional.empty(),
                java.util.Optional.empty());

        BuiltContainerImage image = switch (operation) {
            case BUILD -> buildOperations().buildImage(request);
            case PUSH -> buildOperations().pushImage(request);
        };
        resultCodec.write(receiptFile, image);
    }

    private Map<String, String> imageOperationProperties(ImageOperation operation) {
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        properties.put("quarkus.container-image.build",
                Boolean.toString(operation == ImageOperation.BUILD));
        properties.put("quarkus.container-image.push",
                Boolean.toString(operation == ImageOperation.PUSH));
        if (getImageBuilder().isPresent()) {
            properties.put("quarkus.container-image.builder", getImageBuilder().get().quarkusBuilderName());
        }
        if (getImageReference().isPresent()) {
            properties.put("quarkus.container-image.image", getImageReference().get());
        } else if (getImageRepository().isPresent()) {
            properties.put("quarkus.container-image.image", repositoryImageReference());
        } else if (getImageTag().isPresent()) {
            properties.put("quarkus.container-image.tag", getImageTag().get());
        }
        return properties;
    }

    private java.util.Optional<ContainerImageTarget> containerImageTarget() {
        if (getImageReference().isPresent()) {
            return java.util.Optional.of(new ContainerImageTarget(getImageReference().get()));
        }
        if (getImageRepository().isPresent()) {
            return java.util.Optional.of(new ContainerImageTarget(repositoryImageReference()));
        }
        return java.util.Optional.empty();
    }

    private String repositoryImageReference() {
        String tag = getImageTag().getOrElse(defaultImageTag());
        return getImageRepository().get() + ":" + tag;
    }

    private String defaultImageTag() {
        String version = getApplicationVersion().getOrNull();
        if (version == null || "unspecified".equals(version)) {
            throw new IllegalArgumentException(
                    "Image tag defaults to project.version, but project.version is unspecified. "
                            + "Configure image.tag or project.version.");
        }
        return version;
    }
}
