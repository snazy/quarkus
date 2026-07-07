package io.quarkus.gradle.application.internal.execution;

import java.nio.file.Path;

import io.quarkus.gradle.application.internal.deployment.DeploymentResult;
import io.quarkus.gradle.application.internal.image.BuiltContainerImage;
import io.quarkus.gradle.application.internal.nativeimage.NativeResult;
import io.quarkus.gradle.application.internal.packaging.PackageResult;

public interface BuildOperations {

    void build(BuildRequest request);

    PackageResult buildPackage(BuildRequest request, Path augmentResultFile);

    NativeResult buildNative(BuildRequest request, Path augmentResultFile);

    BuiltContainerImage buildAotEnhancedImage(AotEnhancedImageRequest request);

    BuiltContainerImage pushAotEnhancedImage(AotEnhancedImageRequest request);

    BuiltContainerImage buildImage(ImageRequest request);

    BuiltContainerImage pushImage(ImageRequest request);

    DeploymentResult deploy(DeploymentRequest request);

    void run(RunRequest request);
}
