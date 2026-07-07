package io.quarkus.gradle.application.tasks;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

import io.quarkus.gradle.application.dsl.QuarkusApplicationForkOptions;
import io.quarkus.gradle.application.internal.config.EffectiveConfigPlan;
import io.quarkus.gradle.application.internal.config.EffectiveConfigPlanner;
import io.quarkus.gradle.application.internal.config.EffectiveConfigRequest;
import io.quarkus.gradle.application.internal.config.ShapeExpectation;
import io.quarkus.gradle.application.internal.config.ShapeValidator;
import io.quarkus.gradle.application.internal.execution.BuildOperations;
import io.quarkus.gradle.application.internal.execution.BuildRequest;
import io.quarkus.gradle.application.internal.execution.worker.ForkOptionsSnapshot;
import io.quarkus.gradle.application.internal.execution.worker.WorkerBackedBuildOperations;
import io.quarkus.gradle.application.internal.planning.OutputLayoutPlanner;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildDescriptor;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

@DisableCachingByDefault(because = "Quarkus application augmentation is not build-cacheable yet")
public abstract class QuarkusApplicationBuildTask extends QuarkusApplicationTask {

    public QuarkusApplicationBuildTask() {
        getPathEnvironment().set(getProviders().environmentVariable("PATH"));
        getGradleWorkerMaxHeap().set(getProviders().systemProperty("gradle.quarkus.gradle-worker.max-heap"));
        getAdditionalDescriptorShapeProperties().convention(Map.of());
    }

    @Input
    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    @Input
    public abstract MapProperty<String, String> getAdditionalDescriptorShapeProperties();

    @Input
    public abstract Property<String> getApplicationName();

    @Input
    public abstract Property<String> getApplicationVersion();

    @Internal
    public abstract DirectoryProperty getGradleBuildDirectory();

    @Override
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getApplicationModel();

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceDirectories();

    @Internal
    protected abstract Property<BuildOperations> getOperations();

    @Nested
    public abstract QuarkusApplicationForkOptions getBuildForkOptions();

    @Internal
    protected abstract Property<String> getPathEnvironment();

    @Internal
    protected abstract Property<String> getGradleWorkerMaxHeap();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    protected BuildRequest buildRequest(Map<String, String> operationForcedProperties) {
        warnIfLegacyAmbientConfigCaptureEnabled();
        QuarkusApplicationBuildDescriptor descriptor = descriptor();
        Path outputRoot = getOutputDirectory().get().getAsFile().toPath();
        var layout = new OutputLayoutPlanner().plan(
                getGradleBuildDirectory().get().getAsFile().toPath(), descriptor, outputRoot);
        EffectiveConfigPlan effectiveConfig = effectiveConfig(operationForcedProperties);
        new ShapeValidator().validate(new ShapeExpectation(
                getBuildName().get(), getPath(), effectiveConfig.descriptorShapeValues()), effectiveConfig.fullValues());
        return new BuildRequest(
                descriptor,
                outputRoot,
                getApplicationModel().get().getAsFile().toPath(),
                getRuntimeClasspath().getFiles().stream().map(File::toPath).toList(),
                getSourceDirectories().getFiles(),
                effectiveConfig,
                effectiveConfig.buildSystemProperties(),
                operationForcedProperties,
                true,
                layout);
    }

    protected Map<String, String> descriptorShapeProperties() {
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        QuarkusApplicationBuildType type = getBuildType().get();
        properties.put("quarkus.package.output-directory", getOutputDirectory().get().getAsFile().toPath().toString());
        properties.put("quarkus.package.output-name", getOutputName().getOrElse(getBuildName().get()));
        properties.put("quarkus.package.jar.enabled", Boolean.toString(type.isJar()));
        properties.put("quarkus.native.enabled", Boolean.toString(type.isNativeOutput()));
        if (type.isNativeOutput()) {
            properties.put("quarkus.native.sources-only", Boolean.toString(type.isNativeSources()));
        }
        type.jarType().ifPresent(jarType -> properties.put("quarkus.package.jar.type", jarType));
        properties.putAll(getAdditionalDescriptorShapeProperties().get());
        return properties;
    }

    protected EffectiveConfigPlan effectiveConfig(Map<String, String> operationForcedProperties) {
        Map<String, String> forced = new java.util.LinkedHashMap<>(operationForcedProperties);
        forced.putAll(descriptorShapeProperties());
        EffectiveConfigPlan plan = new EffectiveConfigPlanner().plan(
                new EffectiveConfigRequest(
                        Map.of(),
                        getApplicationName().get(),
                        getApplicationVersion().get(),
                        getSourceDirectories().getFiles(),
                        getQuarkusBuildProperties().get(),
                        Map.of(),
                        forced,
                        Map.of(),
                        gradleProperties(),
                        environmentVariables(),
                        systemProperties(),
                        Map.of(),
                        null));
        if (!getLegacyAmbientConfigCapture().getOrElse(false)) {
            return plan;
        }
        return new EffectiveConfigPlan(
                plan.fullValues(),
                plan.quarkusWorkerValues(),
                plan.fullValues(),
                plan.descriptorShapeValues());
    }

    private QuarkusApplicationBuildDescriptor descriptor() {
        return QuarkusApplicationBuildDescriptor.of(getBuildName().get(), getBuildType().get());
    }

    protected BuildOperations buildOperations() {
        BuildOperations configured = getOperations().getOrNull();
        if (configured != null) {
            return configured;
        }
        return new WorkerBackedBuildOperations(
                getWorkerExecutor(),
                getProviders(),
                ForkOptionsSnapshot.from(getBuildForkOptions()),
                getPathEnvironment().getOrNull(),
                getGradleWorkerMaxHeap().getOrNull());
    }
}
