package io.quarkus.gradle.application.internal.codegen.worker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.gradle.api.provider.ProviderFactory;
import org.gradle.process.JavaForkOptions;
import org.gradle.util.GradleVersion;
import org.gradle.workers.ProcessWorkerSpec;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.gradle.application.internal.codegen.CodegenOperations;
import io.quarkus.gradle.application.internal.codegen.CodegenRequest;
import io.quarkus.gradle.application.internal.execution.worker.ForkOptionsSnapshot;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.smallrye.common.os.OS;

public final class WorkerBackedCodegenOperations implements CodegenOperations {

    private static final List<String> WORKER_CODEGEN_FORK_OPTIONS = List.of("quarkus.", "platform.quarkus.",
            "gradle.quarkus.");

    private final WorkerExecutor workerExecutor;
    private final ProviderFactory providers;
    private final ForkOptionsSnapshot codegenForkOptions;
    private final String pathEnvironment;
    private final String gradleWorkerMaxHeap;

    public WorkerBackedCodegenOperations(WorkerExecutor workerExecutor, ProviderFactory providers,
            ForkOptionsSnapshot codegenForkOptions, String pathEnvironment, String gradleWorkerMaxHeap) {
        this.workerExecutor = workerExecutor;
        this.providers = providers;
        this.codegenForkOptions = codegenForkOptions;
        this.pathEnvironment = pathEnvironment;
        this.gradleWorkerMaxHeap = gradleWorkerMaxHeap;
    }

    @Override
    public void generate(CodegenRequest request) {
        WorkerCodegenSubmission submission = workerCodegenSubmission(request);
        WorkQueue workQueue = workQueue(request.effectiveConfig().quarkusWorkerValues());
        workQueue.submit(CodegenWorker.class, params -> {
            params.getBuildSystemProperties().putAll(submission.buildSystemProperties());
            params.getForkedSystemProperties().putAll(submission.forkedSystemProperties());
            params.getProcessIsolated().set(submission.processIsolated());
            params.getBaseName().set(submission.baseName());
            params.getTargetDirectory().set(submission.targetDirectory().toFile());
            params.getAppModel().set(submission.appModel());
            params.getGradleVersion().set(submission.gradleVersion());
            params.getSourceDirectories().setFrom(request.sourceParentDirectories());
            params.getOutputPath().set(request.generatedSourcesDirectory().toFile());
            params.getLaunchMode().set(request.launchMode());
        });
        workQueue.await();
    }

    WorkerCodegenSubmission workerCodegenSubmission(CodegenRequest request) {
        return new WorkerCodegenSubmission(
                request.buildSystemProperties(),
                request.effectiveConfig().quarkusWorkerValues(),
                isWorkerProcessIsolated(),
                request.buildSystemProperties().getOrDefault("quarkus.package.output-name", request.projectDisplayName()),
                request.buildDirectory(),
                resolveAppModel(request.appModel()),
                GradleVersion.current().getVersion());
    }

    private static ApplicationModel resolveAppModel(Path appModel) {
        try {
            return ToolingUtils.deserializeAppModel(appModel);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isWorkerProcessIsolated() {
        return !(providers.systemProperty("org.gradle.debug").map(Boolean::parseBoolean).getOrElse(false) ||
                providers.systemProperty("gradle.quarkus.gradle-worker.no-process").map(Boolean::parseBoolean)
                        .getOrElse(false));
    }

    private WorkQueue workQueue(Map<String, String> configMap) {
        if (!isWorkerProcessIsolated()) {
            return workerExecutor.classLoaderIsolation();
        }
        return workerExecutor.processIsolation(processWorkerSpec -> configureProcessWorkerSpec(processWorkerSpec,
                configMap));
    }

    private void configureProcessWorkerSpec(ProcessWorkerSpec processWorkerSpec, Map<String, String> configMap) {
        JavaForkOptions forkOptions = processWorkerSpec.getForkOptions();
        codegenForkOptions.applyTo(forkOptions);

        String userDir = configMap.get("user.dir");
        if (userDir != null) {
            forkOptions.systemProperty("user.dir", userDir);
        }

        if (gradleWorkerMaxHeap != null && forkOptions.getAllJvmArgs().stream().noneMatch(arg -> arg.startsWith("-Xmx"))) {
            forkOptions.jvmArgs("-Xmx" + gradleWorkerMaxHeap);
        }

        if (OS.current() == OS.WINDOWS) {
            String java = forkOptions.getExecutable();
            Path javaBinPath = Paths.get(java).getParent().toAbsolutePath();
            String javaBin = javaBinPath.toString();
            String javaHome = javaBinPath.getParent().toString();
            forkOptions.environment("JAVA_HOME", javaHome);
            forkOptions.environment("PATH", javaBin + File.pathSeparator + (pathEnvironment == null ? "" : pathEnvironment));
        }

        configMap.entrySet().stream()
                .filter(entry -> WORKER_CODEGEN_FORK_OPTIONS.stream().anyMatch(entry.getKey().toLowerCase()::startsWith))
                .forEach(entry -> forkOptions.systemProperty(entry.getKey(), entry.getValue()));
    }

    record WorkerCodegenSubmission(
            Map<String, String> buildSystemProperties,
            Map<String, String> forkedSystemProperties,
            boolean processIsolated,
            String baseName,
            Path targetDirectory,
            ApplicationModel appModel,
            String gradleVersion) {
    }
}
