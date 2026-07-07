package io.quarkus.gradle.application.tasks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.execution.AotEnhancedImageRequest;
import io.quarkus.gradle.application.internal.execution.ImageOperation;
import io.quarkus.gradle.application.internal.image.BuiltContainerImage;
import io.quarkus.gradle.application.internal.image.BuiltContainerImageResultCodec;

@DisableCachingByDefault(because = "AOT-enhanced image tasks mutate external container image state")
public abstract class QuarkusApplicationAotEnhancedImageTask extends QuarkusApplicationImageTask {

    private final BuiltContainerImageResultCodec resultCodec = new BuiltContainerImageResultCodec();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getAotFile();

    @Input
    @Optional
    public abstract Property<String> getAotFileProducerTaskName();

    @Input
    @Optional
    public abstract Property<String> getImageReference();

    @Input
    @Optional
    public abstract Property<String> getAotImageRepository();

    @Input
    @Optional
    public abstract Property<String> getAotImageTag();

    @Input
    public abstract Property<String> getImageSuffix();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBaseImageReceiptFile();

    @OutputFile
    public abstract RegularFileProperty getReceiptFile();

    protected void executeAotEnhancedImageOperation(ImageOperation operation) {
        Path baseReceipt = getBaseImageReceiptFile().get().getAsFile().toPath();
        if (!Files.isRegularFile(baseReceipt)) {
            throw new GradleException("AOT-enhanced image task requires base image receipt " + baseReceipt
                    + ", but the file does not exist");
        }
        BuiltContainerImage baseImage = resultCodec.read(baseReceipt);
        String baseReference = baseImage.reference()
                .orElseThrow(() -> new GradleException("Base image receipt " + baseReceipt
                        + " does not contain an image reference"));
        baseImage.workingDirectory()
                .orElseThrow(() -> new GradleException("Base image receipt " + baseReceipt
                        + " does not contain an image working directory"));

        if (!getAotFile().isPresent()) {
            throw new GradleException("AOT-enhanced image task requires an AOT file");
        }
        Path aotFile = getAotFile().get().getAsFile().toPath();
        if (!Files.isRegularFile(aotFile)) {
            throw new GradleException("AOT-enhanced image task requires AOT file " + aotFile
                    + ", but the file does not exist");
        }
        validateSuffixOnlyAotImageReference();

        Map<String, String> operationForcedProperties = aotImageOperationProperties(operation);
        BuiltContainerImage image = switch (operation) {
            case BUILD -> buildOperations().buildAotEnhancedImage(request(operation, baseImage, baseReceipt, aotFile,
                    operationForcedProperties, enhancedReference(baseReference)));
            case PUSH -> buildOperations().pushAotEnhancedImage(request(operation, baseImage, baseReceipt, aotFile,
                    operationForcedProperties, enhancedReference(baseReference)));
        };
        resultCodec.write(getReceiptFile().get().getAsFile().toPath(), image);
    }

    private AotEnhancedImageRequest request(ImageOperation operation,
            BuiltContainerImage baseImage, Path baseReceipt, Path aotFile, Map<String, String> operationForcedProperties,
            String enhancedReference) {
        return new AotEnhancedImageRequest(
                buildRequest(operationForcedProperties),
                operation,
                baseImage,
                baseReceipt,
                aotFile,
                enhancedReference,
                java.util.Optional.ofNullable(getImageBuilder().getOrNull()),
                getReceiptFile().get().getAsFile().toPath());
    }

    private Map<String, String> aotImageOperationProperties(ImageOperation operation) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("quarkus.container-image.build", "true");
        properties.put("quarkus.container-image.push", Boolean.toString(operation == ImageOperation.PUSH));
        if (getImageBuilder().isPresent()) {
            properties.put("quarkus.container-image.builder", getImageBuilder().get().quarkusBuilderName());
        }
        properties.put("quarkus.container-image.aot-image-suffix", getImageSuffix().get());
        return properties;
    }

    private String enhancedReference(String baseReference) {
        return baseReference + getImageSuffix().get();
    }

    private void validateSuffixOnlyAotImageReference() {
        if (getImageReference().isPresent() || getAotImageRepository().isPresent() || getAotImageTag().isPresent()) {
            throw new GradleException("AOT-enhanced image execution currently supports only imageSuffix because Quarkus "
                    + "container-image processors derive the enhanced image as original image plus suffix. "
                    + "Remove imageReference, repository, and tag overrides for this task.");
        }
    }
}
