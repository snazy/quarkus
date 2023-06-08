package io.quarkus.gradle.tasks.devmode;

import static java.util.Collections.emptyMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.deployment.internal.Deployment;
import org.gradle.deployment.internal.DeploymentHandle;
import org.gradle.work.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContinuousDeploymentHandle implements DeploymentHandle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContinuousDeploymentHandle.class);
    private volatile Process process;
    private volatile Thread ioHandler;
    private volatile PrintStream consoleStdout;
    private volatile InputStream consoleStdin;
    private final ContinuousExecSpec continuousExecSpec;

    @Inject
    public ContinuousDeploymentHandle(DevProjectState devProjectState) {
        this.continuousExecSpec = new ContinuousExecSpec(devProjectState);
    }

    @Override
    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    @Override
    public void start(Deployment deployment) {
        List<String> args = continuousExecSpec.getDevProjectState().getRunner().args();
        Map<String, String> env = emptyMap();

        stop();

        consoleStdout = System.out;
        consoleStdin = System.in;

        LOGGER.info("Starting process: {}", String.join(" ", args));
        LOGGER.info("Process environmaent: {}",
                env.isEmpty() ? "<none>"
                        : env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining("\n  ", "\n  ", "")));
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.environment().putAll(env);
        processBuilder.redirectErrorStream(true);
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new GradleException("Failed to start process " + String.join(" ", args), e);
        }

        consoleStdout.println("Quarkus dev process started with PID " + process.pid());

        // Note: ProcessBuilder.inheritIO() doesn't work here :(
        ioHandler = new Thread(this::handleProcessIO, "quarkusDev I/O handler");
        ioHandler.setDaemon(true);
        ioHandler.start();
    }

    private void handleProcessIO() {
        try {
            Process p = process;
            InputStream processStdout = p.getInputStream();
            OutputStream processStdin = p.getOutputStream();
            PrintStream consoleStdout = this.consoleStdout;
            InputStream consoleStdin = this.consoleStdin;

            byte[] buf = new byte[1024];

            consoleStdout.println("Redirecting I/O for Quarkus dev process " + p.pid());

            while (p.isAlive()) {
                boolean any = false;
                int av = processStdout.available();
                if (av > 0) {
                    int rd = processStdout.read(buf, 0, Math.min(av, buf.length));
                    consoleStdout.write(buf, 0, rd);
                    any = true;
                }

                av = consoleStdin.available();
                if (av > 0) {
                    int rd = consoleStdin.read(buf, 0, Math.min(av, buf.length));
                    processStdin.write(buf, 0, rd);
                    processStdin.flush();
                    any = true;
                }

                if (!any) {
                    Thread.sleep(200);
                }
            }

            consoleStdout.println("Quarkus dev process stopped");

        } catch (Exception e) {
            LOGGER.error("Quarkus dev I/O handler failed!", e);
        }
    }

    @Override
    public void stop() {
        try {
            Process p = process;
            if (p != null) {
                process.destroy();
                try {
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        throw new GradleException("Quarkus dev process did not stop within 30 seconds");
                    }
                } catch (InterruptedException e) {
                    throw new GradleException("Wait-for Quarkus dev process failed", e);
                } finally {
                    process.destroyForcibly();
                }
            }
        } finally {
            process = null;
            ioHandler = null;
        }
    }

    public ContinuousExecSpec getContinuousExecSpec() {
        return continuousExecSpec;
    }

    public void reload(Collection<FileChange> changes) throws InterruptedException {
        String paths = changes.stream()
                .map(FileChange::getNormalizedPath)
                .collect(Collectors.joining(","));

        LOGGER.info("Quarkus continuous build reload, paths: {}", paths);
    }
}
