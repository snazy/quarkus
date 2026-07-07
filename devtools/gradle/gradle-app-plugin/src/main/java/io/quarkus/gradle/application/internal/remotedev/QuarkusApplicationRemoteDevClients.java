package io.quarkus.gradle.application.internal.remotedev;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class QuarkusApplicationRemoteDevClients {

    private static final Map<String, RemoteDevPackageClient> clients = new HashMap<>();

    private QuarkusApplicationRemoteDevClients() {
    }

    static synchronized void replace(String id, RemoteDevPackageClient replacement) {
        close(id);
        try {
            replacement.startChangePolling();
            clients.put(id, replacement);
        } catch (IOException | RuntimeException e) {
            close(replacement);
            throw new IllegalStateException("Failed to start Quarkus remote dev change polling", e);
        }
    }

    static synchronized RemoteDevPackageClient get(String id) {
        return clients.get(id);
    }

    static synchronized void close(String id) {
        close(clients.remove(id));
    }

    private static void close(RemoteDevPackageClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to close Quarkus remote dev client", e);
        }
    }
}
