package io.quarkus.gradle.tasks;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.process.JavaForkOptions;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.ProcessWorkerSpec;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import io.quarkus.gradle.extension.QuarkusPluginExtension;
import io.smallrye.common.os.OS;

@DisableCachingByDefault(because = "Not cacheable")
public abstract class QuarkusTask extends QuarkusBaseTask {
    private static final List<String> WORKER_BUILD_FORK_OPTIONS = List.of("quarkus.", "platform.quarkus.", "gradle.quarkus.");

    private final transient QuarkusPluginExtension extension;

    QuarkusTask(String description) {
        this(description, false);
    }

    QuarkusTask(String description, boolean configurationCacheCompatible) {
        this(description,
                configurationCacheCompatible ? null : "The Quarkus Plugin isn't compatible with the configuration cache");
    }

    QuarkusTask(String description, String notConfigCacheCompatibleReason) {
        setDescription(description);
        setGroup("quarkus");
        this.extension = getProject().getExtensions().findByType(QuarkusPluginExtension.class);
        getProjectDir().convention(getProject().getLayout().getProjectDirectory());
        getBuildDir().convention(getProject().getLayout().getBuildDirectory());

        getPathEnvironment().set(getProject().getProviders().environmentVariable("PATH"));
        getGradleWorkerMaxHeap().set(getProject().getProviders().systemProperty("gradle.quarkus.gradle-worker.max-heap"));

        // Calling this method tells Gradle that it should not fail the build. Side effect is that the configuration
        // cache will be at least degraded, but the build will not fail.
        if (notConfigCacheCompatibleReason != null) {
            notCompatibleWithConfigurationCache(notConfigCacheCompatibleReason);
        }
    }

    @Internal
    protected abstract DirectoryProperty getBuildDir();

    @Internal
    protected abstract DirectoryProperty getProjectDir();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @Internal
    protected abstract Property<String> getPathEnvironment();

    @Internal
    protected abstract Property<String> getGradleWorkerMaxHeap();

    QuarkusPluginExtension extension() {
        return extension;
    }

    /**
     * Whether Quarkus workers run in a forked JVM (process isolation) rather than in-process in the
     * Gradle daemon (class-loader isolation).
     */
    @Internal
    boolean isWorkerProcessIsolated() {
        // Use process isolation by default, unless Gradle's started with its debugging system property or the
        // system property `gradle.quarkus.gradle-worker.no-process` is set to `true`.
        return !(getProviderFactory().systemProperty("org.gradle.debug").map(Boolean::parseBoolean).getOrElse(false) ||
                getProviderFactory().systemProperty("gradle.quarkus.gradle-worker.no-process").map(Boolean::parseBoolean)
                        .getOrElse(false));
    }

    WorkQueue workQueue(Map<String, String> configMap, List<Action<? super JavaForkOptions>> forkOptionsSupplier) {
        WorkerExecutor workerExecutor = getWorkerExecutor();

        if (!isWorkerProcessIsolated()) {
            return workerExecutor.classLoaderIsolation();
        }

        return workerExecutor.processIsolation(processWorkerSpec -> configureProcessWorkerSpec(processWorkerSpec,
                configMap, forkOptionsSupplier));
    }

    private void configureProcessWorkerSpec(ProcessWorkerSpec processWorkerSpec, Map<String, String> configMap,
            List<Action<? super JavaForkOptions>> customizations) {
        JavaForkOptions forkOptions = processWorkerSpec.getForkOptions();
        customizations.forEach(a -> a.execute(forkOptions));

        // Propagate user.dir to load config sources that use it (instead of the worker user.dir)
        String userDir = configMap.get("user.dir");
        if (userDir != null) {
            forkOptions.systemProperty("user.dir", userDir);
        }

        String quarkusWorkerMaxHeap = getGradleWorkerMaxHeap().getOrNull();
        if (quarkusWorkerMaxHeap != null && forkOptions.getAllJvmArgs().stream().noneMatch(arg -> arg.startsWith("-Xmx"))) {
            forkOptions.jvmArgs("-Xmx" + quarkusWorkerMaxHeap);
        }

        // Unlike, for example, `JavaExec`, which augments the inherited environment, the fork-options for
        // Gradle workers do _not_ inherit the environment. So we have to explicitly pass the whole environment here.
        // This task-execution time call to `getProviderFactory().environmentVariablesPrefixedBy()` does not
        // affect Gradle's build-cache - it's pure runtime-evaluation.
        forkOptions.environment(getProviderFactory().environmentVariablesPrefixedBy("").get());

        if (OS.current() == OS.WINDOWS) {
            // On Windows, gRPC code generation is sometimes(?) unable to find "java.exe". Feels (not proven) that
            // the grpc code generation tool looks up "java.exe" instead of consulting the 'JAVA_HOME' environment.
            // Might be, that Gradle's process isolation "loses" some information down to the worker process, so add
            // both JAVA_HOME and updated PATH environment from the 'java' executable chosen by Gradle (could be from
            // a different toolchain than the one running the build, in theory at least).
            // Linux is fine though, so no need to add a hack for Linux.
            String java = forkOptions.getExecutable();
            Path javaBinPath = Paths.get(java).getParent().toAbsolutePath();
            String javaBin = javaBinPath.toString();
            String javaHome = javaBinPath.getParent().toString();
            forkOptions.environment("JAVA_HOME", javaHome);
            forkOptions.environment("PATH",
                    javaBin + File.pathSeparator + getPathEnvironment().getOrElse(""));
        }

        // It's kind of a "very big hammer" here, but this way we ensure that all necessary properties
        // "quarkus.*" from all configuration sources
        // are (forcefully) used in the Quarkus build - even properties defined on the QuarkusPluginExtension.
        // This prevents that settings from e.g. a application.properties takes precedence over an explicit
        // setting in Gradle project properties, the Quarkus extension or even via the environment or system
        // properties.
        // see https://github.com/quarkusio/quarkus/issues/33321 why not all properties are passed as system properties
        // Note that we MUST NOT mess with the system properties of the JVM running the build! And that is the
        // main reason why build and code generation happen in a separate process.
        configMap.entrySet().stream()
                .filter(e -> WORKER_BUILD_FORK_OPTIONS.stream().anyMatch(e.getKey().toLowerCase()::startsWith))
                .forEach(e -> forkOptions.systemProperty(e.getKey(), e.getValue()));

        // populate worker classpath with additional content?
        // or maybe remove some dependencies from the plugin and make those exclusively available to the worker?
        // processWorkerSpec.getClasspath().from();
    }
}
