package io.quarkus.gradle.application.internal.execution.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.gradle.api.GradleException;

public final class ForegroundProcessRunner {

    private static final long STOP_TIMEOUT_SECONDS = 5;

    public void run(RunCommand command, Path defaultWorkingDirectory, Map<String, String> environment) {
        Path workingDirectory = command.workingDirectory().orElse(defaultWorkingDirectory);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command.arguments())
                    .directory(workingDirectory.toFile())
                    .redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.environment().putAll(environment);
            process = builder.start();
        } catch (IOException e) {
            throw new GradleException("Failed to launch Quarkus run command '" + command.name() + "'", e);
        }

        Thread outputForwarder = forwardOutput(process.getInputStream(), System.out, command.name() + "-stdout");
        Thread errorForwarder = forwardOutput(process.getErrorStream(), System.err, command.name() + "-stderr");
        Thread shutdownHook = new Thread(() -> stop(process), "quarkus-run-process-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            int exitCode = process.waitFor();
            waitForOutput(outputForwarder, errorForwarder);
            if (exitCode != 0) {
                throw new GradleException("Quarkus run command '" + command.name()
                        + "' exited with status " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(process);
            throw new GradleException("Interrupted while waiting for Quarkus run command '" + command.name() + "'", e);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already in progress and the hook is running.
            }
        }
    }

    private static Thread forwardOutput(InputStream stream, PrintStream target, String threadName) {
        Thread thread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.println(line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    target.println("Failed to read Quarkus run command output: " + e.getMessage());
                }
            }
        }, "quarkus-run-" + threadName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void waitForOutput(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void stop(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
