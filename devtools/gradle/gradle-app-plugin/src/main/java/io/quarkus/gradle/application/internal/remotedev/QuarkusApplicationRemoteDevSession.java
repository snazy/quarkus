package io.quarkus.gradle.application.internal.remotedev;

import java.io.IOException;
import java.nio.file.Path;

final class QuarkusApplicationRemoteDevSession {

    private RemoteDevPackageClient client;
    private RemoteDevPackageSnapshot delivered = RemoteDevPackageSnapshot.empty();
    private long sequence;
    private boolean connected;
    private boolean closed;

    synchronized long nextSequence() {
        return ++sequence;
    }

    synchronized DeliveryResult deliver(RemoteDevPackageSnapshot current, Path packageRoot,
            Path snapshotFile, ClientConnector connector) throws IOException {
        if (closed) {
            throw new IOException("Remote dev session is closed");
        }
        if (client == null) {
            client = connector.connect();
        }
        if (!connected) {
            RemoteDevPackageClientResult connectedResult = client.connect(current.hashes());
            RemoteDevPackageDiff requested = current.requestedFiles(connectedResult.requestedPaths(), packageRoot);
            if (!requested.isEmpty()) {
                client.send(requested);
            }
            delivered = current;
            current.write(snapshotFile);
            connected = true;
            return new DeliveryResult(connectedResult.outcome(), requested.changed().size(), 0);
        }
        RemoteDevPackageDiff diff = current.diffSince(delivered, packageRoot);
        if (diff.isEmpty()) {
            return new DeliveryResult("NO_CHANGES", 0, 0);
        }
        RemoteDevPackageClientResult result = client.send(diff);
        delivered = current;
        current.write(snapshotFile);
        return new DeliveryResult(result.outcome(), diff.changed().size(), diff.deleted().size());
    }

    synchronized void close() throws IOException {
        closed = true;
        if (client != null) {
            client.close();
        }
    }

    public interface ClientConnector {
        RemoteDevPackageClient connect() throws IOException;
    }

    public record DeliveryResult(String outcome, int changed, int deleted) {
    }
}
