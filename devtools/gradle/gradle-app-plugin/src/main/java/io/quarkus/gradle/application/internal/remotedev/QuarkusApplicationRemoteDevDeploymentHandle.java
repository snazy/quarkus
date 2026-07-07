package io.quarkus.gradle.application.internal.remotedev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;

import org.gradle.deployment.internal.Deployment;
import org.gradle.deployment.internal.DeploymentHandle;

public class QuarkusApplicationRemoteDevDeploymentHandle implements DeploymentHandle {

    private final Path closeReceiptFile;
    private final String clientId;
    private boolean running;

    @Inject
    public QuarkusApplicationRemoteDevDeploymentHandle(Path closeReceiptFile, String clientId) {
        this.closeReceiptFile = closeReceiptFile;
        this.clientId = clientId;
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void start(Deployment deployment) {
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        Exception failure = null;
        try {
            QuarkusApplicationRemoteDevClients.close(clientId);
        } catch (RuntimeException e) {
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
            throw new IllegalStateException("Failed to stop Quarkus remote dev", failure);
        }
    }

    private void writeCloseReceipt() throws IOException {
        Files.createDirectories(closeReceiptFile.getParent());
        Files.writeString(closeReceiptFile, "closed\n", StandardCharsets.UTF_8);
    }
}
