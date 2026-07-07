package io.quarkus.gradle.application.internal.dev;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.ConfiguredClassLoading;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.devmode.DependenciesFilter;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.PathsCollection;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.dev.DevModeCommandLine;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathList;

public final class GradleNativeDevModeLauncher {

    private GradleNativeDevModeLauncher() {
    }

    static QuarkusApplicationDevProcessHandle launch(Parameters parameters,
            DevModeContext.ExternalBuildOutputTransport transport) throws Exception {
        ApplicationModel model = ToolingUtils.deserializeAppModel(parameters.applicationModel());
        DevModeCommandLine runner = buildCommandLine(parameters, model, transport);
        Process process = new ProcessBuilder(runner.getArguments())
                .directory(parameters.projectDirectory().toFile())
                .start();
        return new ProcessHandle(process,
                startOutputPump(process.getInputStream(), System.out),
                startOutputPump(process.getErrorStream(), System.err));
    }

    private static DevModeCommandLine buildCommandLine(Parameters parameters, ApplicationModel model,
            DevModeContext.ExternalBuildOutputTransport transport) throws Exception {
        var builder = DevModeCommandLine.builder(null)
                .projectDir(parameters.projectDirectory().toFile())
                .buildDir(parameters.buildDirectory().toFile())
                .outputDir(parameters.buildDirectory().toFile())
                .buildSystemProperties(parameters.quarkusBuildProperties())
                .applicationName(parameters.applicationName())
                .applicationVersion(parameters.applicationVersion())
                .extensionDevModeConfig(model.getExtensionDevModeConfig())
                .entryPointCustomizer(context -> {
                    context.setBuildUpdateSource(DevModeContext.BuildUpdateSource.EXTERNAL_BUILD_TOOL);
                    context.setExternalBuildOutputTransport(transport);
                });
        builder.jvmArgs("-Dquarkus.console.basic=true")
                .jvmArgs("-Dio.quarkus.force-color-support=true")
                .jvmArgs("-Dquarkus.console.disable-input=true")
                .jvmArgs("-Dquarkus.test.continuous-testing=disabled")
                .jvmArgs("-Dgradle.project.path=" + parameters.projectDirectory().toAbsolutePath())
                .jvmArgs("-Dquarkus.live-reload.instrumentation=false");
        for (Map.Entry<String, String> entry : parameters.devSystemProperties().entrySet()) {
            builder.jvmArgs("-D" + entry.getKey() + "=" + entry.getValue());
        }
        for (String jvmArg : parameters.devJvmArgs()) {
            builder.jvmArgs(jvmArg);
        }
        for (String jvmArg : parameters.jvmArguments()) {
            builder.jvmArgs(jvmArg);
        }
        if (!parameters.applicationArguments().isEmpty()) {
            builder.applicationArgs(String.join(" ", parameters.applicationArguments()));
        }
        if (parameters.openJavaLang()) {
            builder.addOpens("java.base/java.lang=ALL-UNNAMED");
        }
        if (!parameters.modules().isEmpty()) {
            builder.addModules(parameters.modules());
        }
        if (!parameters.compilerArguments().isEmpty()) {
            builder.compilerOptions("java", parameters.compilerArguments());
        }
        if (!parameters.tests().isEmpty()) {
            builder.jvmArgs("-Dquarkus-internal.test.specific-selection=gradle:"
                    + String.join(",", parameters.tests()));
        }
        builder.jvmArgs("-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + parameters.applicationModel().toAbsolutePath());

        Set<ArtifactKey> localDependencies = new LinkedHashSet<>();
        for (ResolvedDependency dependency : DependenciesFilter.getReloadableModules(model)) {
            addLocalModule(builder, dependency, localDependencies,
                    model.getAppArtifact().getWorkspaceModule().getId()
                            .equals(dependency.getWorkspaceModule().getId()));
        }
        for (File dependency : parameters.devModeClasspath()) {
            builder.classpathEntry(ArtifactKey.of("io.quarkus.gradle.application", dependency.getName(), null, "jar"),
                    dependency);
        }
        Set<Path> resourceDirs = new HashSet<>();
        if (model.getApplicationModule() != null && model.getApplicationModule().getMainSources() != null) {
            for (SourceDir resourceDir : model.getApplicationModule().getMainSources().getResourceDirs()) {
                resourceDirs.add(resourceDir.getOutputDir());
            }
        }
        Collection<ArtifactKey> configuredParentFirst = ConfiguredClassLoading.builder()
                .setApplicationModel(model)
                .setApplicationRoot(PathsCollection.from(resourceDirs))
                .setMode(QuarkusBootstrap.Mode.DEV)
                .build()
                .getParentFirstArtifacts();
        for (ResolvedDependency dependency : model.getDependencies()) {
            if (!localDependencies.contains(dependency.getKey()) && configuredParentFirst.contains(dependency.getKey())) {
                addDependencyClasspathEntries(builder, dependency);
            }
        }
        return builder.build();
    }

    private static void addDependencyClasspathEntries(io.quarkus.deployment.dev.DevModeCommandLineBuilder builder,
            ResolvedDependency dependency) {
        for (Path path : dependency.getResolvedPaths()) {
            File file = path.toFile();
            if (file.exists()) {
                builder.classpathEntry(dependency.getKey(), file);
            }
        }
    }

    private static void addLocalModule(io.quarkus.deployment.dev.DevModeCommandLineBuilder builder,
            ResolvedDependency dependency, Set<ArtifactKey> localDependencies, boolean root) {
        localDependencies.add(dependency.getKey());
        ArtifactSources sources = dependency.getSources();
        if (sources == null) {
            return;
        }
        WorkspaceModule module = dependency.getWorkspaceModule();
        if (module == null) {
            return;
        }
        Set<Path> sourcePaths = new LinkedHashSet<>();
        Set<Path> sourceParents = new LinkedHashSet<>();
        Path classesDir = null;
        for (SourceDir source : sources.getSourceDirs()) {
            if (Files.exists(source.getDir())) {
                sourcePaths.add(source.getDir());
                sourceParents.add(source.getDir().getParent());
                if (classesDir == null) {
                    classesDir = source.getOutputDir();
                }
            }
        }
        Path resourcesOutputDir = null;
        Set<Path> resourcePaths = new LinkedHashSet<>();
        for (SourceDir resource : sources.getResourceDirs()) {
            resourcePaths.add(resource.getDir());
            if (resourcesOutputDir == null) {
                resourcesOutputDir = resource.getOutputDir();
            }
        }
        if (classesDir == null) {
            classesDir = resourcesOutputDir;
        }
        if (classesDir == null) {
            return;
        }
        Path resourcesOutputPath = resourcesOutputDir == null ? classesDir : resourcesOutputDir;
        DevModeContext.ModuleInfo moduleInfo = new DevModeContext.ModuleInfo.Builder()
                .setArtifactKey(dependency.getKey())
                .setName(dependency.getArtifactId())
                .setProjectDirectory(module.getModuleDir().getAbsolutePath())
                .setSourcePaths(PathList.from(sourcePaths))
                .setClassesPath(classesDir.toString())
                .setResourcePaths(PathList.from(resourcePaths))
                .setResourcesOutputPath(resourcesOutputPath.toString())
                .setSourceParents(PathList.from(sourceParents))
                .setPreBuildOutputDir(module.getBuildDir().toPath().resolve("generated-sources").toAbsolutePath().toString())
                .setTargetDir(module.getBuildDir().toString())
                .build();
        if (root) {
            builder.mainModule(moduleInfo);
        } else {
            builder.dependency(moduleInfo);
        }
    }

    public record Parameters(
            Path applicationModel,
            Collection<File> devModeClasspath,
            Path projectDirectory,
            Path buildDirectory,
            String applicationName,
            String applicationVersion,
            Map<String, String> quarkusBuildProperties,
            List<String> devJvmArgs,
            List<String> jvmArguments,
            List<String> applicationArguments,
            List<String> modules,
            boolean openJavaLang,
            List<String> compilerArguments,
            List<String> tests,
            Map<String, String> devSystemProperties) {
        public Parameters {
            applicationModel = applicationModel.normalize();
            projectDirectory = projectDirectory.normalize();
            buildDirectory = buildDirectory.normalize();
            devModeClasspath = List.copyOf(devModeClasspath);
            quarkusBuildProperties = Map.copyOf(quarkusBuildProperties);
            devJvmArgs = List.copyOf(devJvmArgs);
            jvmArguments = List.copyOf(jvmArguments);
            applicationArguments = List.copyOf(applicationArguments);
            modules = List.copyOf(modules);
            compilerArguments = List.copyOf(compilerArguments);
            tests = List.copyOf(tests);
            devSystemProperties = Map.copyOf(devSystemProperties);
        }
    }

    private static Thread startOutputPump(InputStream stream, PrintStream target) {
        Thread thread = new Thread(() -> {
            try (stream;
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.println("[quarkus-dev] " + line);
                }
            } catch (IOException e) {
                target.println("[quarkus-dev] stopped reading dev process output: " + e.getMessage());
            }
        }, "quarkus-application-dev-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static final class ProcessHandle implements QuarkusApplicationDevProcessHandle {

        private final Process process;
        private final List<Thread> outputPumps;

        private ProcessHandle(Process process, Thread outputPump, Thread errorPump) {
            this.process = process;
            this.outputPumps = List.of(outputPump, errorPump);
        }

        @Override
        public void close() throws IOException {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IOException("Timed out while forcibly stopping Quarkus dev mode process");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping Quarkus dev mode process", e);
            }
            joinOutputPumps();
        }

        private void joinOutputPumps() throws IOException {
            List<InterruptedException> interruptions = new ArrayList<>();
            for (Thread outputPump : outputPumps) {
                try {
                    outputPump.join(1000);
                    if (outputPump.isAlive()) {
                        throw new IOException("Timed out while stopping Quarkus dev mode output pump "
                                + outputPump.getName());
                    }
                } catch (InterruptedException e) {
                    interruptions.add(e);
                    Thread.currentThread().interrupt();
                }
            }
            if (!interruptions.isEmpty()) {
                IOException failure = new IOException("Interrupted while stopping Quarkus dev mode output pumps");
                interruptions.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }
}
