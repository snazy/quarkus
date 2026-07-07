package io.quarkus.gradle.application.tasks;

import java.util.List;
import java.util.Map;

import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.execution.RunRequest;
import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

@DisableCachingByDefault(because = "Quarkus run starts a foreground application process")
public abstract class QuarkusApplicationRunTask extends QuarkusApplicationBuildTask
        implements QuarkusApplicationLaunchOptions {

    private static final String QUARKUS_LAUNCH_DEVMODE = "QUARKUS_LAUNCH_DEVMODE";
    private static final String LIVE_RELOAD_PASSWORD = "quarkus.live-reload.password";
    private static final String DISABLE_CONSOLE_INPUT = "-Dquarkus.console.disable-input=true";
    private static final String DISABLE_CONTINUOUS_TESTING = "-Dquarkus.test.continuous-testing=disabled";

    private transient String liveReloadPassword;

    public QuarkusApplicationRunTask() {
        getJvmArguments().convention(List.of());
        getApplicationArguments().convention(List.of());
        getRunTarget().convention(getProviders().systemProperty("quarkus.run.target"));
        getEnableRemoteDev().convention(false);
        getWorkingDirectory().convention(getProject().getLayout().getProjectDirectory());
        getOutputs().upToDateWhen(task -> false);
        notCompatibleWithConfigurationCache(
                "Quarkus run starts a foreground process and may use transient command-line credentials.");
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getPackageResultFile();

    @Internal
    public abstract DirectoryProperty getWorkingDirectory();

    @Input
    public String getWorkingDirectoryPath() {
        return getWorkingDirectory().get().getAsFile().getAbsolutePath();
    }

    @Input
    @Optional
    public abstract Property<String> getRunTarget();

    @Input
    public abstract Property<Boolean> getEnableRemoteDev();

    @Option(description = "Start a mutable-jar package run as the remote-dev server side", option = "enable-remote-dev")
    public void enableRemoteDev(boolean enableRemoteDev) {
        getEnableRemoteDev().set(enableRemoteDev);
    }

    @Option(description = "Remote-dev live-reload password for mutable-jar remote-dev server startup", option = "live-reload-password")
    public void liveReloadPassword(String liveReloadPassword) {
        this.liveReloadPassword = liveReloadPassword;
    }

    @Override
    @Internal
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void runApplication() {
        validateRemoteDevOptions();
        Map<String, String> environment = runEnvironment();
        buildOperations().run(new RunRequest(
                buildRequest(Map.of()),
                getPackageResultFile().get().getAsFile().toPath(),
                java.util.Optional.ofNullable(getRunTarget().getOrNull()),
                runJvmArguments(),
                getApplicationArguments().get(),
                environment,
                getWorkingDirectory().get().getAsFile().toPath()));
    }

    private List<String> runJvmArguments() {
        List<String> arguments = getJvmArguments().get();
        if (!getEnableRemoteDev().get()) {
            return arguments;
        }
        List<String> remoteDevArguments = new java.util.ArrayList<>(arguments.size() + 3);
        remoteDevArguments.addAll(arguments);
        remoteDevArguments.add(DISABLE_CONSOLE_INPUT);
        remoteDevArguments.add(DISABLE_CONTINUOUS_TESTING);
        if (liveReloadPassword != null && !liveReloadPassword.isBlank()) {
            remoteDevArguments.add("-D" + LIVE_RELOAD_PASSWORD + "=" + liveReloadPassword);
        }
        return remoteDevArguments;
    }

    private void validateRemoteDevOptions() {
        if (!getEnableRemoteDev().get() && liveReloadPassword != null && !liveReloadPassword.isBlank()) {
            throw new GradleException("Task '" + getPath()
                    + "' cannot use --live-reload-password without --enable-remote-dev.");
        }
    }

    private Map<String, String> runEnvironment() {
        if (!getEnableRemoteDev().get()) {
            return Map.of();
        }
        if (getBuildType().get() != QuarkusApplicationBuildType.MUTABLE_JAR) {
            throw new GradleException("Task '" + getPath()
                    + "' cannot use --enable-remote-dev because it runs a '" + getBuildType().get()
                    + "' package. Remote-dev server startup requires a mutable-jar run task.");
        }
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put(QUARKUS_LAUNCH_DEVMODE, "true");
        return environment;
    }
}
