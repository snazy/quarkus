package io.quarkus.gradle.application.internal.dev;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.List;

import io.quarkus.deployment.dev.BuildOutputChangeStatus;
import io.quarkus.deployment.dev.BuildOutputChanges;
import io.quarkus.deployment.dev.BuildOutputChangesServer;
import io.quarkus.deployment.dev.BuildOutputChangesTransports;

final class QuarkusApplicationDevSession implements AutoCloseable {

    private final BuildOutputChangesPolicy policy = new BuildOutputChangesPolicy();
    private BuildOutputChangesServer buildOutputChangesServer;
    private QuarkusApplicationDevProcessHandle process;
    private long sequence;
    private boolean started;
    private boolean ready;
    private boolean closed;

    synchronized boolean startIfNeeded(QuarkusApplicationDevProcessLauncher launcher) throws Exception {
        return startIfNeeded(launcher, true);
    }

    synchronized boolean startIfNeededWithoutConnectionWait(QuarkusApplicationDevProcessLauncher launcher) throws Exception {
        return startIfNeeded(launcher, false);
    }

    private boolean startIfNeeded(QuarkusApplicationDevProcessLauncher launcher, boolean waitForConnection) throws Exception {
        requireNonNull(launcher, "launcher");
        assertOpen();
        if (started) {
            return false;
        }
        BuildOutputChangesServer server = BuildOutputChangesTransports.createTcpServer();
        try {
            process = requireNonNull(launcher.launch(server.transport()), "process");
            if (waitForConnection) {
                server.send(connectionProbe());
            }
            buildOutputChangesServer = server;
        } catch (Exception e) {
            if (process != null) {
                try {
                    process.close();
                } catch (Exception closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                process = null;
            }
            try {
                server.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        started = true;
        return true;
    }

    synchronized BuildOutputChangesPolicy.Result acceptStartupBaseline(BuildOutputChanges changes) {
        assertOpen();
        return policy.acceptStartupBaseline(changes);
    }

    synchronized BuildOutputChangesPolicy.Result acceptRestartRequired(long sequence) {
        assertOpen();
        return policy.acceptRestartRequired(sequence);
    }

    synchronized long nextSequence() {
        assertOpen();
        return ++sequence;
    }

    synchronized void markReady() {
        assertOpen();
        ready = true;
    }

    synchronized boolean isReady() {
        return ready;
    }

    synchronized BuildOutputChangesPolicy.Result accept(BuildOutputChanges changes) {
        assertOpen();
        if (!ready) {
            return policy.acceptStartupBaseline(changes);
        }
        return policy.accept(changes);
    }

    synchronized BuildOutputChangesPolicy.Result deliver(BuildOutputChangesPolicy.Sender sender) {
        assertOpen();
        if (!ready) {
            return policy.deliver(ignored -> {
                throw new IllegalStateException("Dev session must not deliver reload batches before it is ready");
            });
        }
        return policy.deliver(sender);
    }

    synchronized BuildOutputChangesPolicy.Result deliver() {
        assertOpen();
        if (!ready) {
            return policy.deliver(ignored -> {
                throw new IllegalStateException("Dev session must not deliver reload batches before it is ready");
            });
        }
        if (buildOutputChangesServer == null) {
            return policy.discardPending("Dev session has no build-output changes server");
        }
        return policy.deliver(buildOutputChangesServer::send);
    }

    synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        Exception failure = null;
        if (process != null) {
            try {
                process.close();
            } catch (Exception e) {
                failure = e;
            }
        }
        if (buildOutputChangesServer != null) {
            try {
                buildOutputChangesServer.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static BuildOutputChanges connectionProbe() {
        // Sending goes through the authenticated transport and waits for the
        // Quarkus dev process to respond. Sequence 0 is stale for the runtime
        // processor, so this message proves connectivity without becoming a
        // reloadable build-output update.
        return new BuildOutputChanges(0, BuildOutputChangeStatus.BUILD_FAILED, List.of(), List.of(), null, null, null, null,
                false, false);
    }

    private void assertOpen() {
        if (closed) {
            throw new IllegalStateException("Dev session is closed");
        }
    }
}
