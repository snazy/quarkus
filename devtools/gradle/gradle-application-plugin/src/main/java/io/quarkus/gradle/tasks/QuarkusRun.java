package io.quarkus.gradle.tasks;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.builditem.DevServicesLauncherConfigResultBuildItem;
import io.quarkus.deployment.cmd.RunCommandActionResultBuildItem;
import io.quarkus.deployment.cmd.StartDevServicesAndRunCommandHandler;
import io.smallrye.common.process.ProcessBuilder;

@DisableCachingByDefault(because = "Not cacheable")
public abstract class QuarkusRun extends QuarkusBuildTask {
    @Inject
    public QuarkusRun() {
        this("Quarkus runs target application");
    }

    public QuarkusRun(String description) {
        super(description, false);

        getCompilationOutput().from(
                QuarkusGradleUtils.getSourceSet(getProject(), SourceSet.MAIN_SOURCE_SET_NAME).getOutput().getClassesDirs());
        getWorkingDirectory().convention(getProjectDir().map(Directory::getAsFile));
    }

    /**
     * The JVM classes directory (compilation output)
     */
    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCompilationOutput();

    @Input
    public abstract Property<File> getWorkingDirectory();

    @Input
    @Option(description = "Set JVM arguments", option = "jvm-args")
    public abstract ListProperty<String> getJvmArguments();

    @TaskAction
    public void runQuarkus() {
        ApplicationModel appModel = resolveAppModelForBuild();
        Properties sysProps = new Properties();
        sysProps.putAll(effectiveProvider().buildEffectiveConfiguration(appModel, Map.of()).getQuarkusValues());
        try (CuratedApplication curatedApplication = QuarkusBootstrap.builder()
                .setBaseClassLoader(getClass().getClassLoader())
                .setExistingModel(appModel)
                .setTargetDirectory(getBuildDir().getAsFile().get().toPath())
                .setBaseName(getExtensionView().getFinalName().get())
                .setBuildSystemProperties(sysProps)
                .setAppArtifact(appModel.getAppArtifact())
                .setLocalProjectDiscovery(false)
                .setIsolateDeployment(true)
                .setMode(QuarkusBootstrap.Mode.RUN)
                .build().bootstrap()) {

            AugmentAction action = curatedApplication.createAugmentor();
            AtomicReference<Boolean> exists = new AtomicReference<>();
            AtomicReference<String> tooMany = new AtomicReference<>();
            String target = getProviderFactory().systemProperty("quarkus.run.target").getOrNull();
            action.performCustomBuild(StartDevServicesAndRunCommandHandler.class.getName(),
                    new Consumer<Map<String, List<?>>>() {
                        @Override
                        public void accept(Map<String, List<?>> cmds) {
                            List<?> cmd = null;
                            if (target != null) {
                                cmd = cmds.get(target);
                                if (cmd == null) {
                                    exists.set(false);
                                    return;
                                }
                            } else if (cmds.size() == 1) { // defaults to pure java run
                                cmd = cmds.values().iterator().next();
                            } else if (cmds.size() == 2) { // choose not default
                                for (Map.Entry<String, List<?>> entry : cmds.entrySet()) {
                                    if (entry.getKey().equals("java"))
                                        continue;
                                    cmd = entry.getValue();
                                    break;
                                }
                            } else if (cmds.size() > 2) {
                                tooMany.set(String.join(" ", cmds.keySet()));
                                return;
                            } else {
                                throw new RuntimeException("Should never reach this!");
                            }
                            @SuppressWarnings("unchecked")
                            List<String> args = (List<String>) requireNonNull(cmd).get(0);
                            if (getJvmArguments().isPresent() && !getJvmArguments().get().isEmpty()) {
                                args.addAll(1, getJvmArguments().get());
                            }

                            getLogger().info("Executing \"{}\"", String.join(" ", args));
                            Path wd = (Path) cmd.get(1);
                            File wdir = wd != null ? wd.toFile() : getWorkingDirectory().get();

                            // this was all very touchy to get the process outputing to console and exiting cleanly
                            // change at your own risk

                            // We cannot use getProject().exec() as contrl-c is not processed correctly
                            // and the spawned process will not shutdown
                            //
                            // This also requires running with --no-daemon as control-c doesn't seem to trigger the shutdown hook
                            // this poor gradle behavior is a long known issue with gradle
                            ProcessBuilder.newBuilder(args.get(0))
                                    .arguments(args.subList(1, args.size()))
                                    .directory(wdir.toPath())
                                    .error().consumeLinesWith(1024, System.out::println)
                                    .output().consumeLinesWith(1024, System.out::println)
                                    .whileRunning(ph -> {
                                        if (!ph.isAlive()) {
                                            return;
                                        }
                                        Thread hook = new Thread(() -> {
                                            if (ph.supportsNormalTermination()) {
                                                ph.destroy();
                                            }
                                            // give it some grace
                                            ph.waitUninterruptiblyFor(5, TimeUnit.SECONDS);
                                            // nuke it
                                            io.smallrye.common.process.ProcessUtil.destroyAllForcibly(ph);
                                        }, "Command termination hook");
                                        Runtime.getRuntime().addShutdownHook(hook);
                                        try {
                                            ph.waitUninterruptiblyFor();
                                        } finally {
                                            Runtime.getRuntime().removeShutdownHook(hook);
                                        }
                                    })
                                    .run();
                        }
                    },
                    RunCommandActionResultBuildItem.class.getName(), DevServicesLauncherConfigResultBuildItem.class.getName());
            if (target != null && !exists.get()) {
                getLogger().error("quarkus.run.target {} is not found", target);
                return;
            }
            if (tooMany.get() != null) {
                getLogger().error(
                        "Too many installed extensions support quarkus:run.  Use -Dquarkus.run.target=<target> to choose");
                getLogger().error("Extensions: {}", tooMany.get());
            }
        } catch (BootstrapException e) {
            throw new GradleException("Failed to run application", e);
        }
    }

}
