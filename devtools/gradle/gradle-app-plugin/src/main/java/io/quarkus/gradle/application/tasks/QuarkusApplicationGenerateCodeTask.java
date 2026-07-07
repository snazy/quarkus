package io.quarkus.gradle.application.tasks;

import java.io.File;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

import io.quarkus.gradle.application.dsl.QuarkusApplicationForkOptions;
import io.quarkus.gradle.application.internal.codegen.CodegenOperations;
import io.quarkus.gradle.application.internal.codegen.CodegenRequest;
import io.quarkus.gradle.application.internal.codegen.worker.WorkerBackedCodegenOperations;
import io.quarkus.gradle.application.internal.config.EffectiveConfigPlan;
import io.quarkus.gradle.application.internal.config.EffectiveConfigPlanner;
import io.quarkus.gradle.application.internal.config.EffectiveConfigRequest;
import io.quarkus.gradle.application.internal.execution.worker.ForkOptionsSnapshot;
import io.quarkus.runtime.LaunchMode;

@DisableCachingByDefault(because = "Quarkus code generation cacheability is not reviewed for the new application plugin yet")
public abstract class QuarkusApplicationGenerateCodeTask extends QuarkusApplicationBaseTask {

    public QuarkusApplicationGenerateCodeTask() {
        getPathEnvironment().set(getProviders().environmentVariable("PATH"));
        getGradleWorkerMaxHeap().set(getProviders().systemProperty("gradle.quarkus.gradle-worker.max-heap"));
    }

    @Input
    public abstract Property<LaunchMode> getLaunchMode();

    @Input
    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    @Input
    public abstract Property<String> getApplicationName();

    @Input
    public abstract Property<String> getApplicationVersion();

    @Input
    public abstract ListProperty<String> getCodegenProviders();

    @Input
    public abstract ListProperty<String> getCodegenInputNames();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getApplicationModel();

    @CompileClasspath
    public abstract ConfigurableFileCollection getClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceParentDirectories();

    @OutputDirectory
    public abstract DirectoryProperty getGeneratedOutputDirectory();

    @Internal
    public abstract DirectoryProperty getBuildDirectory();

    @Nested
    public abstract QuarkusApplicationForkOptions getCodegenForkOptions();

    @Internal
    protected abstract Property<String> getPathEnvironment();

    @Internal
    protected abstract Property<String> getGradleWorkerMaxHeap();

    @Internal // Only for testing purposes
    protected abstract Property<CodegenOperations> getOperations();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    public void generateCode() {
        CodegenRequest request = codegenRequest();
        codegenOperations().generate(request);
    }

    CodegenRequest codegenRequest() {
        warnIfLegacyAmbientConfigCaptureEnabled();
        EffectiveConfigPlan effectiveConfig = effectiveConfig();
        return new CodegenRequest(
                getApplicationModel().get().getAsFile().toPath(),
                getLaunchMode().get(),
                getSourceParentDirectories().getFiles(),
                getGeneratedOutputDirectory().get().getAsFile().toPath(),
                getBuildDirectory().get().getAsFile().toPath(),
                getApplicationName().get(),
                getCodegenProviders().get(),
                getCodegenInputNames().get(),
                getClasspath().getFiles().stream().map(File::toPath).toList(),
                effectiveConfig,
                effectiveConfig.buildSystemProperties());
    }

    private EffectiveConfigPlan effectiveConfig() {
        EffectiveConfigPlan plan = new EffectiveConfigPlanner().plan(
                new EffectiveConfigRequest(
                        Map.of(),
                        getApplicationName().get(),
                        getApplicationVersion().get(),
                        getSourceParentDirectories().getFiles(),
                        getQuarkusBuildProperties().get(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        gradleProperties(),
                        environmentVariables(),
                        systemProperties(),
                        Map.of(),
                        getLaunchMode().get() == LaunchMode.TEST ? "test" : "prod"));
        if (!getLegacyAmbientConfigCapture().getOrElse(false)) {
            return plan;
        }
        return new EffectiveConfigPlan(
                plan.fullValues(),
                plan.quarkusWorkerValues(),
                plan.fullValues(),
                plan.descriptorShapeValues());
    }

    private CodegenOperations codegenOperations() {
        CodegenOperations configured = getOperations().getOrNull();
        if (configured != null) {
            return configured;
        }
        return new WorkerBackedCodegenOperations(
                getWorkerExecutor(),
                getProviders(),
                ForkOptionsSnapshot.from(getCodegenForkOptions()),
                getPathEnvironment().getOrNull(),
                getGradleWorkerMaxHeap().getOrNull());
    }
}
