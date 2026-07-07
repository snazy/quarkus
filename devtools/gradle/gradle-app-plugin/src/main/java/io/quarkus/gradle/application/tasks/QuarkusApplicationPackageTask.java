package io.quarkus.gradle.application.tasks;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.packaging.PackageResultCodec;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

@DisableCachingByDefault(because = "Quarkus package builds are not build-cacheable yet")
public abstract class QuarkusApplicationPackageTask extends QuarkusApplicationBuildTask {

    @Input
    public abstract MapProperty<String, String> getManifestAttributes();

    @OutputFile
    public abstract RegularFileProperty getPackageResultFile();

    @Internal
    public Provider<File> getPrimaryJarFile() {
        return getOutputDirectory().flatMap(outputDirectory -> getBuildType().flatMap(buildType -> getOutputName()
                .flatMap(outputName -> getAdditionalDescriptorShapeProperties().map(properties -> primaryJarFile(
                        outputDirectory.getAsFile(), buildType, outputName, properties)))));
    }

    @TaskAction
    public void buildPackage() {
        Path packageResultFile = getPackageResultFile().get().getAsFile().toPath();
        Path augmentResultFile = packageResultFile.resolveSibling("package-augmentation-result.properties");
        var result = buildOperations().buildPackage(buildRequest(Map.of()), augmentResultFile);
        new PackageResultCodec().write(packageResultFile, result);
    }

    private static File primaryJarFile(File outputDirectory, QuarkusApplicationBuildType buildType, String outputName,
            Map<String, String> properties) {
        return switch (buildType) {
            case FAST_JAR, MUTABLE_JAR -> new File(outputDirectory, "quarkus-run.jar");
            case LEGACY_JAR, UBER_JAR -> new File(outputDirectory, outputName + runnerSuffix(properties) + ".jar");
            case NATIVE_EXECUTABLE, NATIVE_SOURCES -> throw new IllegalStateException(
                    "Build type " + buildType + " does not produce a primary JAR file");
        };
    }

    private static String runnerSuffix(Map<String, String> properties) {
        if (!Boolean.parseBoolean(properties.getOrDefault("quarkus.package.jar.add-runner-suffix", "true"))) {
            return "";
        }
        return properties.getOrDefault("quarkus.package.runner-suffix", "-runner");
    }
}
