package io.quarkus.gradle.testing;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public abstract class BaseGradleTest {

    private static final String CONFIGURATION_CACHE = "--configuration-cache";
    private static final String NO_CONFIGURATION_CACHE = "--no-configuration-cache";

    @TempDir
    protected Path testProjectDir;

    protected BaseGradleTest() {
    }

    public static List<String> defaultGradleArguments(String... arguments) {
        return defaultGradleArguments(Arrays.asList(arguments));
    }

    public static List<String> defaultGradleArguments(List<String> arguments) {
        List<String> gradleArguments = new ArrayList<>(arguments);
        if (!gradleArguments.contains(CONFIGURATION_CACHE) && !gradleArguments.contains(NO_CONFIGURATION_CACHE)) {
            gradleArguments.add(CONFIGURATION_CACHE);
        }
        return gradleArguments;
    }

    public void writeFile(String fileName, String content) throws IOException {
        writeFile(testProjectDir.resolve(fileName), content);
    }

    public static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(file)) {
            writer.write(content);
        }
    }

    protected BuildResult buildResult(String task, String... extraArgs) {
        return buildResult(task, List.of(extraArgs));
    }

    protected BuildResult buildResult(String task, List<String> extraArgs) {
        return buildResult(task, extraArgs, Map.of());
    }

    protected BuildResult buildResult(String task, List<String> extraArgs, Map<String, String> env) {
        List<String> args = new ArrayList<>();
        args.add(task);
        args.add("--info");
        args.add("--stacktrace");
        args.add("--build-cache");
        args.addAll(extraArgs);
        return buildResult(env, args);
    }

    protected BuildResult buildResult(Map<String, String> env, String... args) {
        return buildResult(env, List.of(args));
    }

    protected BuildResult buildResult(Map<String, String> env, List<String> args) {
        return prepareBuild(env, args).build();
    }

    protected BuildResult buildAndFailResult(String... args) {
        return buildAndFailResult(Map.of(), List.of(args));
    }

    protected BuildResult buildAndFailResult(Map<String, String> env, String... args) {
        return buildAndFailResult(env, List.of(args));
    }

    protected BuildResult buildAndFailResult(Map<String, String> env, List<String> args) {
        List<String> gradleArguments = new ArrayList<>();
        gradleArguments.add("--info");
        gradleArguments.add("--stacktrace");
        gradleArguments.addAll(args);
        return prepareBuild(env, gradleArguments).buildAndFail();
    }

    protected GradleRunner prepareBuild(Map<String, String> env, List<String> args) {
        GradleRunner gradleRunner = GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(defaultGradleArguments(args));
        if (!env.isEmpty()) {
            gradleRunner.withEnvironment(env);
        }
        return gradleRunner;
    }
}
