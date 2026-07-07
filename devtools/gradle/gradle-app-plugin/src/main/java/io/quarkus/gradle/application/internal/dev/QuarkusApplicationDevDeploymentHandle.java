package io.quarkus.gradle.application.internal.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;

import org.gradle.deployment.internal.Deployment;
import org.gradle.deployment.internal.DeploymentHandle;

import io.quarkus.deployment.dev.BuildOutputChanges;

/**
 * Gradle has no public build-session-scoped service for continuous builds.
 * This handle intentionally uses Gradle's internal deployment API and keeps the
 * session ownership boundary narrow so it can be replaced when Gradle exposes a
 * public alternative.
 */
public class QuarkusApplicationDevDeploymentHandle implements DeploymentHandle {

    private final QuarkusApplicationDevSession session = new QuarkusApplicationDevSession();
    private final String configFingerprint;
    private final GradleNativeDevModeLauncher.Parameters launchParameters;
    private final Path closeReceiptFile;
    private boolean running;

    @Inject
    public QuarkusApplicationDevDeploymentHandle(String configFingerprint,
            GradleNativeDevModeLauncher.Parameters launchParameters,
            Path closeReceiptFile) {
        this.configFingerprint = configFingerprint;
        this.launchParameters = launchParameters;
        this.closeReceiptFile = closeReceiptFile;
    }

    public String configFingerprint() {
        return configFingerprint;
    }

    public synchronized long nextSequence() {
        return session.nextSequence();
    }

    public synchronized boolean ready() {
        return session.isReady();
    }

    public synchronized String acceptStartupBaselineOutcome(BuildOutputChanges changes) {
        return session.acceptStartupBaseline(changes).outcome().name();
    }

    public synchronized String acceptReadyChangesOutcome(BuildOutputChanges changes) {
        return session.accept(changes).outcome().name();
    }

    public synchronized String deliverReadyChangesOutcome() {
        return session.deliver().outcome().name();
    }

    public synchronized String acceptRestartRequiredOutcome(long sequence) {
        return session.acceptRestartRequired(sequence).outcome().name();
    }

    @Override
    public synchronized boolean isRunning() {
        return running && !session.isClosed();
    }

    @Override
    public synchronized void start(Deployment deployment) {
        try {
            boolean started = session
                    .startIfNeeded(transport -> GradleNativeDevModeLauncher.launch(launchParameters, transport));
            if (started) {
                session.markReady();
            }
            running = true;
        } catch (Exception e) {
            running = false;
            throw new IllegalStateException("Failed to launch Quarkus dev mode", e);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        Exception failure = null;
        try {
            session.close();
        } catch (Exception e) {
            failure = e;
        }
        try {
            writeCloseReceipt();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to stop Quarkus dev mode", failure);
        }
    }

    private void writeCloseReceipt() throws IOException {
        Files.createDirectories(closeReceiptFile.getParent());
        Files.writeString(closeReceiptFile, "closed\n", StandardCharsets.UTF_8);
    }
}
