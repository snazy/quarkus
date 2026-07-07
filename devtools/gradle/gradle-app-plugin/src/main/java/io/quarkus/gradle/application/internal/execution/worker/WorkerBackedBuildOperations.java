package io.quarkus.gradle.application.internal.execution.worker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.gradle.api.GradleException;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.process.JavaForkOptions;
import org.gradle.util.GradleVersion;
import org.gradle.workers.ProcessWorkerSpec;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import io.quarkus.bootstrap.app.ArtifactResult;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.builditem.DevServicesAdditionalConfigBuildItem;
import io.quarkus.deployment.builditem.DevServicesCustomizerBuildItem;
import io.quarkus.deployment.builditem.DevServicesLauncherConfigResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesNetworkIdBuildItem;
import io.quarkus.deployment.builditem.DevServicesRegistryBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.cmd.RunCommandActionResultBuildItem;
import io.quarkus.gradle.application.internal.deployment.DeploymentResult;
import io.quarkus.gradle.application.internal.deployment.DeploymentResultCodec;
import io.quarkus.gradle.application.internal.execution.AotEnhancedImageRequest;
import io.quarkus.gradle.application.internal.execution.AugmentResultCodec;
import io.quarkus.gradle.application.internal.execution.BuildOperations;
import io.quarkus.gradle.application.internal.execution.BuildRequest;
import io.quarkus.gradle.application.internal.execution.DeploymentRequest;
import io.quarkus.gradle.application.internal.execution.ImageRequest;
import io.quarkus.gradle.application.internal.execution.RunRequest;
import io.quarkus.gradle.application.internal.execution.run.ForegroundProcessRunner;
import io.quarkus.gradle.application.internal.execution.run.RunCommand;
import io.quarkus.gradle.application.internal.execution.run.RunCommandResult;
import io.quarkus.gradle.application.internal.execution.run.RunCommandResultHandler;
import io.quarkus.gradle.application.internal.execution.run.RunCommandSelector;
import io.quarkus.gradle.application.internal.image.AotEnhancedContainerImageResultFactory;
import io.quarkus.gradle.application.internal.image.BuiltContainerImage;
import io.quarkus.gradle.application.internal.image.BuiltContainerImageExtractor;
import io.quarkus.gradle.application.internal.image.ImageExtractionRequest;
import io.quarkus.gradle.application.internal.nativeimage.NativeResult;
import io.quarkus.gradle.application.internal.nativeimage.NativeResultFactory;
import io.quarkus.gradle.application.internal.packaging.PackageResult;
import io.quarkus.gradle.application.internal.packaging.PackageResultCodec;
import io.quarkus.gradle.application.internal.packaging.PackageResultFactory;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.smallrye.common.os.OS;

public final class WorkerBackedBuildOperations implements BuildOperations {

    private static final String QUARKUS_ARTIFACT_PROPERTIES = "quarkus-artifact.properties";
    private static final String QUARKUS_APPLICATION_NAME = "quarkus.application.name";
    private static final String QUARKUS_APPLICATION_VERSION = "quarkus.application.version";
    private static final String AOT_SUCCESS = "success";
    private static final String AOT_CONTAINER_IMAGE = "container.image";
    private static final Runnable NO_RUN_DEV_SERVICES_TO_CLOSE = () -> {
    };
    private static final List<String> WORKER_BUILD_FORK_OPTIONS = List.of("quarkus.", "platform.quarkus.",
            "gradle.quarkus.");

    private final WorkerExecutor workerExecutor;
    private final ProviderFactory providers;
    private final ForkOptionsSnapshot buildForkOptions;
    private final String pathEnvironment;
    private final String gradleWorkerMaxHeap;

    public WorkerBackedBuildOperations(WorkerExecutor workerExecutor, ProviderFactory providers,
            ForkOptionsSnapshot buildForkOptions, String pathEnvironment, String gradleWorkerMaxHeap) {
        this.workerExecutor = workerExecutor;
        this.providers = providers;
        this.buildForkOptions = buildForkOptions;
        this.pathEnvironment = pathEnvironment;
        this.gradleWorkerMaxHeap = gradleWorkerMaxHeap;
    }

    @Override
    public void build(BuildRequest request) {
        executeProductionBuild(request, Optional.empty());
    }

    @Override
    public PackageResult buildPackage(BuildRequest request, Path augmentResultFile) {
        executeProductionBuild(request, Optional.of(augmentResultFile));
        relocateQuarkusArtifactMetadata(request, augmentResultFile.resolveSibling(QUARKUS_ARTIFACT_PROPERTIES));
        var augmentResult = new AugmentResultCodec().read(augmentResultFile);
        return new PackageResultFactory().fromAugmentResult(request, augmentResult);
    }

    @Override
    public NativeResult buildNative(BuildRequest request, Path augmentResultFile) {
        executeProductionBuild(request, Optional.of(augmentResultFile));
        relocateQuarkusArtifactMetadata(request, augmentResultFile.resolveSibling(QUARKUS_ARTIFACT_PROPERTIES));
        var augmentResult = new AugmentResultCodec().read(augmentResultFile);
        return new NativeResultFactory().fromAugmentResult(request, augmentResult);
    }

    @Override
    public BuiltContainerImage buildAotEnhancedImage(AotEnhancedImageRequest request) {
        return executeProductionAotEnhancedImage(request, false);
    }

    @Override
    public BuiltContainerImage pushAotEnhancedImage(AotEnhancedImageRequest request) {
        return executeProductionAotEnhancedImage(request, true);
    }

    @Override
    public BuiltContainerImage buildImage(ImageRequest request) {
        return executeProductionImage(request, false);
    }

    @Override
    public BuiltContainerImage pushImage(ImageRequest request) {
        return executeProductionImage(request, true);
    }

    @Override
    public DeploymentResult deploy(DeploymentRequest request) {
        WorkerBuildSubmission submission = workerBuildSubmission(request.build(), Optional.empty());
        WorkQueue workQueue = workQueue(request.build().effectiveConfig().quarkusWorkerValues());
        workQueue.submit(DeployWorker.class, params -> {
            params.getBuildSystemProperties().putAll(submission.buildSystemProperties());
            params.getForkedSystemProperties().putAll(submission.forkedSystemProperties());
            params.getProcessIsolated().set(submission.processIsolated());
            params.getBaseName().set(submission.baseName());
            params.getTargetDirectory().set(submission.targetDirectory().toFile());
            params.getAppModel().set(submission.appModel());
            params.getGradleVersion().set(submission.gradleVersion());
            params.getBuildName().set(request.build().descriptor().name());
            params.getDeploymentName().set(request.deploymentName());
            params.getDeploymentTarget().set(request.target().quarkusDeployTarget());
            params.getImageSource().set(request.imageSource().name());
            params.getImageReference().set(request.imageReference());
            params.getDeploymentResultFile().set(request.receiptFile().toFile());
        });
        workQueue.await();
        return new DeploymentResultCodec().read(request.receiptFile());
    }

    @Override
    public void run(RunRequest request) {
        if (!Files.isRegularFile(request.packageResultFile())) {
            throw new GradleException("Cannot run Quarkus application '" + request.build().descriptor().name()
                    + "' because package result file does not exist: " + request.packageResultFile());
        }
        PackageResult packageResult = new PackageResultCodec().read(request.packageResultFile());

        BuildRequest build = request.build();
        WorkerBuildSubmission submission = workerBuildSubmission(build, Optional.empty());
        try (CuratedApplication curatedApplication = QuarkusBootstrap.builder()
                .setBaseClassLoader(getClass().getClassLoader())
                .setExistingModel(submission.appModel())
                .setTargetDirectory(submission.targetDirectory())
                .setBaseName(submission.baseName())
                .setBuildSystemProperties(runProperties(submission.buildSystemProperties()))
                .setAppArtifact(submission.appModel().getAppArtifact())
                .setLocalProjectDiscovery(false)
                .setIsolateDeployment(true)
                .setDependencyInfoProvider(() -> null)
                .setMode(QuarkusBootstrap.Mode.RUN)
                .build().bootstrap()) {
            AugmentAction action = curatedApplication.createAugmentor();
            AtomicReference<RunCommandResult> result = new AtomicReference<>(
                    new RunCommandResult(Map.of(), NO_RUN_DEV_SERVICES_TO_CLOSE));
            action.performCustomBuild(
                    RunCommandResultHandler.class.getName(),
                    new Consumer<RunCommandResult>() {
                        @Override
                        public void accept(RunCommandResult runResult) {
                            result.set(runResult);
                        }
                    },
                    RunCommandActionResultBuildItem.class.getName(),
                    DevServicesLauncherConfigResultBuildItem.class.getName(),
                    DevServicesRegistryBuildItem.class.getName(),
                    DevServicesResultBuildItem.class.getName(),
                    DevServicesCustomizerBuildItem.class.getName(),
                    DevServicesAdditionalConfigBuildItem.class.getName(),
                    DevServicesNetworkIdBuildItem.class.getName());
            RunCommand command = new RunCommandSelector().withArguments(
                    new RunCommandSelector().select(result.get().runCommands(), request.runTarget()),
                    request.jvmArguments(),
                    request.applicationArguments());
            command = withPackageJar(command, packageResult);
            Throwable runFailure = null;
            try {
                new ForegroundProcessRunner().run(command, request.workingDirectory(), request.environment());
            } catch (RuntimeException | Error t) {
                runFailure = t;
                throw t;
            } finally {
                closeRunDevServices(result.get(), build.descriptor().name(), runFailure);
            }
        } catch (Exception e) {
            if (e instanceof GradleException gradleException) {
                throw gradleException;
            }
            throw new GradleException("Failed to run Quarkus application '" + build.descriptor().name() + "'", e);
        }
    }

    private static RunCommand withPackageJar(RunCommand command, PackageResult packageResult) {
        if (!"java".equals(command.name())) {
            return command;
        }
        List<String> arguments = command.arguments().stream()
                .filter(argument -> !argument.startsWith("-D" + QUARKUS_APPLICATION_NAME + "="))
                .filter(argument -> !argument.startsWith("-D" + QUARKUS_APPLICATION_VERSION + "="))
                .collect(Collectors.toCollection(ArrayList::new));
        int jarIndex = arguments.indexOf("-jar");
        if (jarIndex < 0 || jarIndex + 1 >= arguments.size()) {
            return command;
        }
        arguments.set(jarIndex + 1, packageResult.jarPath().toAbsolutePath().toString());
        return new RunCommand(command.name(), arguments, command.workingDirectory(), command.startedExpression(),
                command.needsLogfile(), command.logFile());
    }

    private static void closeRunDevServices(RunCommandResult result, String buildName, Throwable runFailure) {
        try {
            result.closeDevServices().run();
        } catch (Throwable closeFailure) {
            if (runFailure != null) {
                runFailure.addSuppressed(closeFailure);
                return;
            }
            throw new GradleException("Failed to stop Dev Services for Quarkus application '" + buildName + "'",
                    closeFailure);
        }
    }

    private BuiltContainerImage executeProductionAotEnhancedImage(AotEnhancedImageRequest request,
            boolean pushed) {
        Path resultFile = request.receiptFile().getParent()
                .resolve(request.operation().name().toLowerCase(Locale.ROOT) + "-aot-result.properties");
        WorkerBuildSubmission submission = workerBuildSubmission(request.build(), Optional.empty());
        WorkQueue workQueue = workQueue(request.build().effectiveConfig().quarkusWorkerValues());
        workQueue.submit(BuildAotEnhancedImageForApplicationWorker.class, params -> {
            params.getBuildSystemProperties().putAll(submission.buildSystemProperties());
            params.getForkedSystemProperties().putAll(submission.forkedSystemProperties());
            params.getProcessIsolated().set(submission.processIsolated());
            params.getBaseName().set(submission.baseName());
            params.getTargetDirectory().set(submission.targetDirectory().toFile());
            params.getAppModel().set(submission.appModel());
            params.getGradleVersion().set(submission.gradleVersion());
            params.getOriginalContainerImage().set(request.baseImage().reference().get());
            params.getContainerWorkingDirectory().set(request.baseImage().workingDirectory().get());
            params.getAotFile().set(request.aotFile().toFile());
            params.getAotImageResultFile().set(resultFile.toFile());
        });
        workQueue.await();
        Map<String, String> result = readAotResult(resultFile);
        if (!Boolean.parseBoolean(result.getOrDefault(AOT_SUCCESS, "false"))) {
            throw new GradleException("Quarkus AOT-enhanced image operation for '"
                    + request.build().descriptor().name() + "' did not produce an image result");
        }
        String containerImage = result.get(AOT_CONTAINER_IMAGE);
        if (containerImage == null || containerImage.isBlank()) {
            throw new GradleException("Quarkus AOT-enhanced image operation for '"
                    + request.build().descriptor().name() + "' did not report an image reference");
        }
        return new AotEnhancedContainerImageResultFactory()
                .image(request.baseImage(), request.builder(), pushed, containerImage);
    }

    private BuiltContainerImage executeProductionImage(ImageRequest request, boolean pushed) {
        Path augmentResultFile = request.receiptFile().getParent()
                .resolve(request.operation().name().toLowerCase(Locale.ROOT) + "-augmentation-result.properties");
        executeProductionBuild(request.build(), Optional.of(augmentResultFile));
        relocateQuarkusArtifactMetadata(request.build(), augmentResultFile.resolveSibling(QUARKUS_ARTIFACT_PROPERTIES));
        var artifactResults = new AugmentResultCodec().readArtifactResults(augmentResultFile);
        return new BuiltContainerImageExtractor().extract(new ImageExtractionRequest(
                request.target(),
                request.builder(),
                pushed,
                artifactResults,
                request.jibDigestFile(),
                request.jibImageIdFile()))
                .orElseThrow(
                        () -> new GradleException(missingContainerImageResultMessage(request, artifactResults)));
    }

    static String missingContainerImageResultMessage(ImageRequest request, List<ArtifactResult> artifactResults) {
        return "Quarkus image operation for '" + request.build().descriptor().name()
                + "' did not produce a container image result. Observed augmentation result types: "
                + artifactResultTypes(artifactResults)
                + ". Ensure the application includes a Quarkus container image builder extension "
                + "(for example quarkus-container-image-jib, quarkus-container-image-docker, "
                + "quarkus-container-image-podman, quarkus-container-image-buildpack, or "
                + "quarkus-container-image-openshift) and, if needed, configure image.builder or "
                + "quarkus.container-image.builder.";
    }

    private static String artifactResultTypes(List<ArtifactResult> artifactResults) {
        if (artifactResults.isEmpty()) {
            return "none";
        }
        return artifactResults.stream()
                .map(ArtifactResult::getType)
                .map(type -> type == null || type.isBlank() ? "<missing>" : type)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private void executeProductionBuild(BuildRequest request, Optional<Path> augmentResultFile) {
        WorkerBuildSubmission submission = workerBuildSubmission(request, augmentResultFile);
        WorkQueue workQueue = workQueue(request.effectiveConfig().quarkusWorkerValues());
        workQueue.submit(BuildWorker.class, params -> {
            params.getBuildSystemProperties().putAll(submission.buildSystemProperties());
            params.getForkedSystemProperties().putAll(submission.forkedSystemProperties());
            params.getProcessIsolated().set(submission.processIsolated());
            params.getBaseName().set(submission.baseName());
            params.getTargetDirectory().set(submission.targetDirectory().toFile());
            params.getAppModel().set(submission.appModel());
            params.getGradleVersion().set(submission.gradleVersion());
            submission.augmentResultFile().ifPresent(path -> params.getAugmentResultFile().set(path.toFile()));
        });
        workQueue.await();
    }

    private static void relocateQuarkusArtifactMetadata(BuildRequest request, Path target) {
        Path source = request.outputRoot().resolve(QUARKUS_ARTIFACT_PROPERTIES);
        if (!Files.exists(source)) {
            return;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new GradleException("Failed to relocate " + QUARKUS_ARTIFACT_PROPERTIES + " from "
                    + source + " to " + target, e);
        }
    }

    WorkerBuildSubmission workerBuildSubmission(BuildRequest request, Optional<Path> augmentResultFile) {
        return new WorkerBuildSubmission(
                request.buildSystemProperties(),
                request.effectiveConfig().quarkusWorkerValues(),
                isWorkerProcessIsolated(),
                request.buildSystemProperties().getOrDefault("quarkus.package.output-name",
                        request.descriptor().name()),
                request.outputRoot(),
                resolveAppModel(request.appModel()),
                GradleVersion.current().getVersion(),
                augmentResultFile);
    }

    private static ApplicationModel resolveAppModel(Path appModel) {
        try {
            return ToolingUtils.deserializeAppModel(appModel);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Properties properties(Map<String, String> values) {
        Properties properties = new Properties();
        properties.putAll(values);
        return properties;
    }

    private static Properties runProperties(Map<String, String> values) {
        Properties properties = properties(values);
        properties.remove(QUARKUS_APPLICATION_NAME);
        properties.remove(QUARKUS_APPLICATION_VERSION);
        return properties;
    }

    private static Map<String, String> readAotResult(Path resultFile) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(resultFile)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new GradleException("Failed to read AOT enhanced image result " + resultFile, e);
        }
        return properties.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString(),
                        entry -> entry.getValue().toString()));
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
        buildForkOptions.applyTo(forkOptions);

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
                .filter(entry -> WORKER_BUILD_FORK_OPTIONS.stream().anyMatch(entry.getKey().toLowerCase()::startsWith))
                .forEach(entry -> forkOptions.systemProperty(entry.getKey(), entry.getValue()));
    }

    record WorkerBuildSubmission(
            Map<String, String> buildSystemProperties,
            Map<String, String> forkedSystemProperties,
            boolean processIsolated,
            String baseName,
            Path targetDirectory,
            ApplicationModel appModel,
            String gradleVersion,
            Optional<Path> augmentResultFile) {
    }
}
