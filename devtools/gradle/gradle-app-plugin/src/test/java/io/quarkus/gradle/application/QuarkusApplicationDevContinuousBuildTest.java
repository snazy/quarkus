package io.quarkus.gradle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusApplicationDevContinuousBuildTest {

    private static final Duration BUILD_START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration RELOAD_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path testProjectDir;

    @Test
    void devTaskReceivesIncrementalSourceChangesFromGradleContinuousBuild() throws Exception {
        writeContinuousDevApplication();

        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var tokenSource = GradleConnector.newCancellationTokenSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> build = executor.submit(() -> runContinuousBuild(tokenSource, stdout, stderr));
        Path closeReceipt = testProjectDir.resolve(Path.of("build", "quarkus-dev", "dev-session-closed.txt"));

        try {
            Path receipt = testProjectDir.resolve(Path.of("build", "quarkus-dev", "dev-iteration.properties"));
            await("initial quarkusApplicationDev baseline", BUILD_START_TIMEOUT,
                    () -> fileContains(receipt, "sequence=1", "sessionReady=true", "outcome=BASELINE_DROPPED"),
                    stdout, stderr);

            Files.writeString(testProjectDir.resolve("src/main/java/org/acme/NewGreetingService.java"), """
                    package org.acme;

                    import jakarta.enterprise.context.ApplicationScoped;

                    @ApplicationScoped
                    public class NewGreetingService {
                        public String hello() {
                            return "new";
                        }
                    }
                    """);

            await("second continuous build receipt", RELOAD_TIMEOUT,
                    () -> fileContains(receipt, "sequence=2"),
                    stdout, stderr);
            assertThat(Files.readString(receipt))
                    .contains("incremental=true")
                    .contains("outcome=PENDING,SENT_APPLIED");
        } finally {
            tokenSource.cancel();
            awaitCancellation(build, stdout, stderr);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(CANCEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
        assertThat(closeReceipt).hasContent("closed\n");
    }

    private void runContinuousBuild(CancellationTokenSource tokenSource, ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr) {
        try (ProjectConnection connection = GradleConnector.newConnector()
                .forProjectDirectory(testProjectDir.toFile())
                .connect()) {
            connection.newBuild()
                    .forTasks("quarkusApplicationDev")
                    // Gradle currently cannot combine continuous build with configuration-cache reuse. Isolated
                    // projects imply the configuration cache, so this smoke intentionally tests continuous mode alone.
                    .withArguments("--continuous", "--no-configuration-cache", "--stacktrace")
                    .withCancellationToken(tokenSource.token())
                    .setStandardOutput(stdout)
                    .setStandardError(stderr)
                    .run();
        }
    }

    private void writeContinuousDevApplication() throws IOException {
        Files.writeString(testProjectDir.resolve("settings.gradle"), "rootProject.name = 'continuous-dev-smoke'\n");
        Files.writeString(testProjectDir.resolve("gradle.properties"), "version = 999-SNAPSHOT\n");
        Files.writeString(testProjectDir.resolve("build.gradle"), """
                buildscript {
                    dependencies {
                        classpath files(%s)
                    }
                }

                apply plugin: 'io.quarkus.application'

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation enforcedPlatform("io.quarkus:quarkus-bom:${project.property('version')}")
                    implementation "io.quarkus:quarkus-arc"
                }
                """.formatted(pluginClasspathFiles()));
        Path sources = testProjectDir.resolve("src/main/java/org/acme");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("GreetingService.java"), """
                package org.acme;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class GreetingService {
                    public String hello() {
                        return "hello";
                    }
                }
                """);
        Files.writeString(sources.resolve("InitialService.java"), """
                package org.acme;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class InitialService {
                    public String hello() {
                        return "initial";
                    }
                }
                """);
    }

    private static String pluginClasspathFiles() {
        return TestKitPluginClasspath.implementationClasspath().stream()
                .map(File::getAbsolutePath)
                .map(QuarkusApplicationDevContinuousBuildTest::singleQuotedGroovyString)
                .collect(Collectors.joining(", "));
    }

    private static String singleQuotedGroovyString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static boolean fileContains(Path file, String... fragments) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            String content = Files.readString(file);
            return Arrays.stream(fragments).allMatch(content::contains);
        } catch (IOException e) {
            return false;
        }
    }

    private static void await(String description, Duration timeout, BooleanSupplier condition,
            ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("Timed out waiting for %s.%nstdout:%n%s%nstderr:%n%s",
                description, output(stdout), output(stderr));
    }

    private static void awaitCancellation(Future<?> build, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr)
            throws InterruptedException {
        try {
            build.get(CANCEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (!isExpectedCancellation(e.getCause())) {
                fail("Continuous build failed before cancellation completed.%nstdout:%n%s%nstderr:%n%s",
                        output(stdout), output(stderr), e.getCause());
            }
        } catch (TimeoutException e) {
            fail("Timed out cancelling continuous build.%nstdout:%n%s%nstderr:%n%s",
                    output(stdout), output(stderr), e);
        }
    }

    private static boolean isExpectedCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String className = current.getClass().getName();
            String message = Objects.toString(current.getMessage(), "");
            if (className.equals("org.gradle.tooling.BuildCancelledException")
                    || className.equals("org.gradle.tooling.exceptions.BuildCancelledException")
                    || message.contains("Build cancelled")
                    || message.contains("cancelled")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String output(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
    }
}
