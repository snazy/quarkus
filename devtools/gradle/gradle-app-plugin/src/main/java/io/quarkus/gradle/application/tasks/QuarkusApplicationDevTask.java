package io.quarkus.gradle.application.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.deployment.internal.DeploymentRegistry;
import org.gradle.work.ChangeType;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.work.FileChange;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import io.quarkus.deployment.dev.BuildOutputChangeKind;
import io.quarkus.deployment.dev.BuildOutputChangeStatus;
import io.quarkus.deployment.dev.BuildOutputChanges;
import io.quarkus.gradle.application.internal.config.EffectiveConfigPlan;
import io.quarkus.gradle.application.internal.config.EffectiveConfigPlanner;
import io.quarkus.gradle.application.internal.config.EffectiveConfigRequest;
import io.quarkus.gradle.application.internal.dev.GradleDevBuildResult;
import io.quarkus.gradle.application.internal.dev.GradleDevFileChange;
import io.quarkus.gradle.application.internal.dev.GradleDevOutputChangeMapper;
import io.quarkus.gradle.application.internal.dev.GradleDevOutputScope;
import io.quarkus.gradle.application.internal.dev.GradleDevOutputSnapshot;
import io.quarkus.gradle.application.internal.dev.GradleNativeDevModeLauncher;
import io.quarkus.gradle.application.internal.dev.QuarkusApplicationDevDeploymentHandle;
import io.quarkus.gradle.application.internal.dev.QuarkusApplicationDevDeployments;

@DisableCachingByDefault(because = "Gradle-native dev mode is long-lived and does not produce reusable outputs")
public abstract class QuarkusApplicationDevTask extends QuarkusApplicationLaunchTask
        implements QuarkusApplicationLaunchOptions {

    public QuarkusApplicationDevTask() {
        getJvmArguments().convention(List.of());
        getApplicationArguments().convention(List.of());
        getModules().convention(List.of());
        getOpenJavaLang().convention(false);
        getCompilerArguments().convention(List.of());
        getTests().convention(List.of());
        getOutputs().upToDateWhen(task -> false);
    }

    @Input
    public abstract Property<Boolean> getContinuousBuild();

    @Input
    public abstract Property<String> getApplicationName();

    @Input
    public abstract Property<String> getApplicationVersion();

    @Input
    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    @Input
    public abstract ListProperty<String> getDevJvmArgs();

    @Input
    @Option(description = "Modules to add to the application", option = "modules")
    public abstract ListProperty<String> getModules();

    @Input
    @Option(description = "Open Java Lang module", option = "open-lang-package")
    public abstract Property<Boolean> getOpenJavaLang();

    @Input
    @Option(description = "Additional parameters to pass to javac when recompiling changed source files", option = "compiler-args")
    public abstract ListProperty<String> getCompilerArguments();

    @Input
    @Option(description = "Sets test class or method name to be included (for continuous testing), '*' is supported.", option = "tests")
    public abstract ListProperty<String> getTests();

    @Input
    public abstract MapProperty<String, String> getDevSystemProperties();

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Internal
    public abstract DirectoryProperty getBuildDirectory();

    @Internal
    public abstract RegularFileProperty getCloseReceiptFile();

    @Internal
    public abstract RegularFileProperty getOutputSnapshotFile();

    @Internal
    public abstract RegularFileProperty getApplicationModel();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceDirectories();

    @Classpath
    public abstract ConfigurableFileCollection getDevModeClasspath();

    @Incremental
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public abstract ConfigurableFileCollection getApplicationClasses();

    @Incremental
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public abstract ConfigurableFileCollection getApplicationResources();

    @Incremental
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public abstract ConfigurableFileCollection getDependencyClasses();

    @Incremental
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public abstract ConfigurableFileCollection getDependencyResources();

    @Incremental
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public abstract ConfigurableFileCollection getRuntimeJarsWithoutOutputVariants();

    @OutputFile
    public abstract RegularFileProperty getReceiptFile();

    @Inject
    public abstract DeploymentRegistry getDeploymentRegistry();

    @TaskAction
    public final void executeDevIteration(InputChanges inputChanges) throws IOException {
        warnIfLegacyAmbientConfigCaptureEnabled();
        validateContinuousBuild();
        executeDeploymentDevIteration(inputChanges);
    }

    private void executeDeploymentDevIteration(InputChanges inputChanges) throws IOException {
        GradleNativeDevModeLauncher.Parameters launchParameters = launchParameters();
        String configFingerprint = QuarkusApplicationDevDeployments.configFingerprint(launchParameters);
        String deploymentId = QuarkusApplicationDevDeployments.deploymentId(
                getProjectDirectory().get().getAsFile().toPath(), getPath());
        QuarkusApplicationDevDeployments.AcquiredHandle acquired = QuarkusApplicationDevDeployments.getOrStart(
                getDeploymentRegistry(), deploymentId, new QuarkusApplicationDevDeployments.Parameters(configFingerprint,
                        launchParameters, getCloseReceiptFile().get().getAsFile().toPath()));
        QuarkusApplicationDevDeploymentHandle session = acquired.handle();
        long sequence = session.nextSequence();
        boolean ready = session.ready() && !acquired.started();
        var observed = observedChanges(inputChanges, ready);
        var buildChanges = toBuildOutputChanges(sequence, observed.incremental(), observed.changes(),
                observed.runtimeJarChanges());
        String outcome = acceptChanges(session, buildChanges, ready, observed.incremental(), observed.runtimeJarChanges());
        writeReceipt(sequence, observed.incremental(), observed.changes().size(), observed.runtimeJarChanges(), outcome,
                session.ready());
    }

    private ObservedDevChanges observedChanges(InputChanges inputChanges, boolean ready) throws IOException {
        if (ready && !inputChanges.isIncremental()) {
            return snapshotChanges();
        }
        var changes = new ArrayList<GradleDevFileChange>();
        collectChanges(inputChanges, getApplicationClasses(), GradleDevOutputScope.MAIN_CLASSES, changes);
        collectChanges(inputChanges, getApplicationResources(), GradleDevOutputScope.MAIN_RESOURCES, changes);
        collectChanges(inputChanges, getDependencyClasses(), GradleDevOutputScope.DEPENDENCY_CLASSES, changes);
        collectChanges(inputChanges, getDependencyResources(), GradleDevOutputScope.DEPENDENCY_RESOURCES, changes);
        int runtimeJarChanges = 0;
        if (inputChanges.isIncremental()) {
            runtimeJarChanges = collectChanges(inputChanges, getRuntimeJarsWithoutOutputVariants(),
                    GradleDevOutputScope.RUNTIME_JARS, changes);
        }
        writeSnapshot(inputChanges.isIncremental(), changes);
        return new ObservedDevChanges(inputChanges.isIncremental(), changes, runtimeJarChanges);
    }

    private ObservedDevChanges snapshotChanges() throws IOException {
        Path snapshotFile = getOutputSnapshotFile().get().getAsFile().toPath();
        GradleDevOutputSnapshot current = currentSnapshot();
        GradleDevOutputSnapshot previous = GradleDevOutputSnapshot.read(snapshotFile);
        current.write(snapshotFile);
        if (previous.isEmpty()) {
            return new ObservedDevChanges(false, List.of(), 0);
        }
        List<GradleDevFileChange> changes = current.changesSince(previous);
        return new ObservedDevChanges(true, changes, current.runtimeJarChangesSince(previous));
    }

    private void writeCurrentSnapshot() throws IOException {
        currentSnapshot().write(getOutputSnapshotFile().get().getAsFile().toPath());
    }

    private void writeSnapshot(boolean incremental, List<GradleDevFileChange> changes) throws IOException {
        if (!incremental) {
            writeCurrentSnapshot();
            return;
        }
        Path snapshotFile = getOutputSnapshotFile().get().getAsFile().toPath();
        GradleDevOutputSnapshot previous = previousSnapshotOrEmpty(snapshotFile);
        if (previous.isEmpty()) {
            writeCurrentSnapshot();
            return;
        }
        previous.updatedBy(changes).write(snapshotFile);
    }

    private static GradleDevOutputSnapshot previousSnapshotOrEmpty(Path snapshotFile) {
        try {
            return GradleDevOutputSnapshot.read(snapshotFile);
        } catch (IOException e) {
            return GradleDevOutputSnapshot.captureEmpty();
        }
    }

    private GradleDevOutputSnapshot currentSnapshot() throws IOException {
        return GradleDevOutputSnapshot.capture(snapshotRoots());
    }

    private List<GradleDevOutputSnapshot.Root> snapshotRoots() {
        var roots = new ArrayList<GradleDevOutputSnapshot.Root>();
        addSnapshotRoots(roots, GradleDevOutputScope.MAIN_CLASSES, getApplicationClasses());
        addSnapshotRoots(roots, GradleDevOutputScope.MAIN_RESOURCES, getApplicationResources());
        addSnapshotRoots(roots, GradleDevOutputScope.DEPENDENCY_CLASSES, getDependencyClasses());
        addSnapshotRoots(roots, GradleDevOutputScope.DEPENDENCY_RESOURCES, getDependencyResources());
        addSnapshotRoots(roots, GradleDevOutputScope.RUNTIME_JARS, getRuntimeJarsWithoutOutputVariants());
        return roots;
    }

    private static void addSnapshotRoots(List<GradleDevOutputSnapshot.Root> target, GradleDevOutputScope scope,
            ConfigurableFileCollection roots) {
        for (var root : roots.getFiles()) {
            target.add(new GradleDevOutputSnapshot.Root(scope, root));
        }
    }

    private static BuildOutputChanges toBuildOutputChanges(long sequence, boolean incremental,
            List<GradleDevFileChange> changes, int runtimeJarChanges) {
        boolean forceRestart = runtimeJarChanges > 0;
        return GradleDevOutputChangeMapper.toBuildOutputChanges(new GradleDevBuildResult(sequence,
                BuildOutputChangeStatus.BUILD_SUCCEEDED, changes, runtimeJarFailureSummary(runtimeJarChanges), null, false,
                forceRestart || !incremental));
    }

    private void validateContinuousBuild() {
        if (!getContinuousBuild().getOrElse(false)) {
            throw new GradleException("Task '" + getPath()
                    + "' requires Gradle continuous build. Run it as './gradlew " + getPath()
                    + " --continuous' so Gradle owns source/resource compilation and can feed successful output changes "
                    + "to Quarkus dev mode.");
        }
    }

    private GradleNativeDevModeLauncher.Parameters launchParameters() {
        EffectiveConfigPlan effectiveConfig = effectiveConfig();
        Map<String, String> quarkusBuildProperties = new LinkedHashMap<>(effectiveConfig.buildSystemProperties());
        quarkusBuildProperties.putAll(getQuarkusBuildProperties().get());
        quarkusBuildProperties.putAll(gradleProperties());
        quarkusBuildProperties.putAll(systemProperties());
        return new GradleNativeDevModeLauncher.Parameters(
                getApplicationModel().get().getAsFile().toPath(),
                getDevModeClasspath().getFiles(),
                getProjectDirectory().get().getAsFile().toPath(),
                getBuildDirectory().get().getAsFile().toPath(),
                getApplicationName().get(),
                getApplicationVersion().get(),
                quarkusBuildProperties,
                getDevJvmArgs().get(),
                getJvmArguments().get(),
                getApplicationArguments().get(),
                getModules().get(),
                getOpenJavaLang().get(),
                getCompilerArguments().get(),
                getTests().get(),
                getDevSystemProperties().get());
    }

    private EffectiveConfigPlan effectiveConfig() {
        EffectiveConfigPlan plan = new EffectiveConfigPlanner().plan(
                new EffectiveConfigRequest(
                        Map.of(),
                        getApplicationName().get(),
                        getApplicationVersion().get(),
                        getSourceDirectories().getFiles(),
                        getQuarkusBuildProperties().get(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        gradleProperties(),
                        environmentVariables(),
                        systemProperties(),
                        Map.of(),
                        "dev"));
        if (!getLegacyAmbientConfigCapture().getOrElse(false)) {
            return plan;
        }
        return new EffectiveConfigPlan(
                plan.fullValues(),
                plan.quarkusWorkerValues(),
                plan.fullValues(),
                plan.descriptorShapeValues());
    }

    private static int collectChanges(InputChanges inputChanges, ConfigurableFileCollection roots,
            GradleDevOutputScope scope, List<GradleDevFileChange> target) {
        int count = 0;
        for (FileChange change : inputChanges.getFileChanges(roots)) {
            Path changedPath = change.getFile().toPath().normalize();
            Path outputRoot = outputRootFor(changedPath, roots);
            target.add(new GradleDevFileChange(scope, outputRoot, changedPath, changeKind(change.getChangeType())));
            count++;
        }
        return count;
    }

    private static Path outputRootFor(Path changedPath, ConfigurableFileCollection roots) {
        for (var root : roots.getFiles()) {
            Path rootPath = root.toPath().normalize();
            if (changedPath.startsWith(rootPath)) {
                return rootPath;
            }
        }
        throw new IllegalArgumentException("Changed path is not under a declared dev output root: " + changedPath);
    }

    private static BuildOutputChangeKind changeKind(ChangeType changeType) {
        return switch (changeType) {
            case ADDED -> BuildOutputChangeKind.ADDED;
            case MODIFIED -> BuildOutputChangeKind.MODIFIED;
            case REMOVED -> BuildOutputChangeKind.DELETED;
        };
    }

    private static String runtimeJarFailureSummary(int runtimeJarChanges) {
        if (runtimeJarChanges == 0) {
            return null;
        }
        return "Runtime jar dependency changes require restarting quarkusApplicationDev.";
    }

    private record ObservedDevChanges(boolean incremental, List<GradleDevFileChange> changes, int runtimeJarChanges) {
    }

    private static String acceptChanges(QuarkusApplicationDevDeploymentHandle session, BuildOutputChanges buildChanges,
            boolean ready, boolean incremental, int runtimeJarChanges) {
        if (!ready) {
            return session.acceptStartupBaselineOutcome(buildChanges);
        }
        if (!incremental || runtimeJarChanges > 0) {
            return session.acceptRestartRequiredOutcome(buildChanges.sequence());
        }
        String accepted = session.acceptReadyChangesOutcome(buildChanges);
        String delivered = session.deliverReadyChangesOutcome();
        return accepted + "," + delivered;
    }

    private void writeReceipt(long sequence, boolean incremental, int observedChanges, int runtimeJarChanges, String outcome,
            boolean ready) throws IOException {
        var receipt = getReceiptFile().get().getAsFile().toPath();
        Files.createDirectories(receipt.getParent());
        Files.writeString(receipt,
                "sequence=" + sequence + "\n"
                        + "incremental=" + incremental + "\n"
                        + "observedChanges=" + observedChanges + "\n"
                        + "runtimeJarChanges=" + runtimeJarChanges + "\n"
                        + "sessionReady=" + ready + "\n"
                        + "outcome=" + outcome + "\n",
                StandardCharsets.UTF_8);
    }
}
