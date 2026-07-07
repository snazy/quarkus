package io.quarkus.gradle.application.internal.remotedev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusApplicationRemoteDevSessionTest {

    @TempDir
    Path directory;

    @Test
    void initialConnectSendsOnlyServerRequestedFilesAndStoresBaseline() throws Exception {
        Path packageRoot = Files.createDirectories(directory.resolve("package"));
        Files.createDirectories(packageRoot.resolve("lib/deployment"));
        Files.writeString(packageRoot.resolve("app.properties"), "ignored");
        Files.writeString(packageRoot.resolve("lib/deployment/appmodel.dat"), "requested");
        RemoteDevPackageSnapshot snapshot = RemoteDevPackageSnapshot.capture(packageRoot);
        FakeClient client = new FakeClient(Set.of("lib/deployment/appmodel.dat"));
        QuarkusApplicationRemoteDevSession session = new QuarkusApplicationRemoteDevSession();

        QuarkusApplicationRemoteDevSession.DeliveryResult result = session.deliver(snapshot, packageRoot,
                directory.resolve("snapshot.tsv"), () -> client);

        assertThat(result.outcome()).isEqualTo("CONNECTED");
        assertThat(result.changed()).isEqualTo(1);
        assertThat(client.connectedHashes).containsKeys("app.properties", "lib/deployment/appmodel.dat");
        assertThat(client.sentChanges).containsExactly(List.of("lib/deployment/appmodel.dat"));

        QuarkusApplicationRemoteDevSession.DeliveryResult second = session.deliver(snapshot, packageRoot,
                directory.resolve("snapshot.tsv"), () -> client);

        assertThat(second.outcome()).isEqualTo("NO_CHANGES");
        assertThat(client.sentChanges).hasSize(1);
    }

    @Test
    void failedIncrementalDeliveryDoesNotAdvanceBaseline() throws Exception {
        Path packageRoot = Files.createDirectories(directory.resolve("package"));
        Files.createDirectories(packageRoot.resolve("lib"));
        Path application = packageRoot.resolve("lib/application.jar");
        Files.writeString(application, "one");
        RemoteDevPackageSnapshot first = RemoteDevPackageSnapshot.capture(packageRoot);
        FakeClient client = new FakeClient(Set.of());
        QuarkusApplicationRemoteDevSession session = new QuarkusApplicationRemoteDevSession();
        session.deliver(first, packageRoot, directory.resolve("snapshot.tsv"), () -> client);

        Files.writeString(application, "two");
        RemoteDevPackageSnapshot second = RemoteDevPackageSnapshot.capture(packageRoot);
        client.failSends = true;

        assertThatThrownBy(() -> session.deliver(second, packageRoot, directory.resolve("snapshot.tsv"), () -> client))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("boom");

        client.failSends = false;
        QuarkusApplicationRemoteDevSession.DeliveryResult result = session.deliver(second, packageRoot,
                directory.resolve("snapshot.tsv"), () -> client);

        assertThat(result.changed()).isEqualTo(1);
        assertThat(client.sentChanges).contains(List.of("lib/application.jar"));
    }

    private static final class FakeClient implements RemoteDevPackageClient {

        private final Set<String> requestedPaths;
        private final List<List<String>> sentChanges = new ArrayList<>();
        private Map<String, String> connectedHashes = Map.of();
        private boolean failSends;

        private FakeClient(Set<String> requestedPaths) {
            this.requestedPaths = requestedPaths;
        }

        @Override
        public RemoteDevPackageClientResult connect(Map<String, String> localHashes) {
            connectedHashes = Map.copyOf(localHashes);
            return RemoteDevPackageClientResult.connected(requestedPaths);
        }

        @Override
        public RemoteDevPackageClientResult send(RemoteDevPackageDiff diff) throws IOException {
            if (failSends) {
                throw new IOException("boom");
            }
            sentChanges.add(diff.changed().stream()
                    .map(RemoteDevPackageChange::relativePath)
                    .toList());
            return RemoteDevPackageClientResult.sent(diff.changed().size(), diff.deleted().size());
        }

        @Override
        public void startChangePolling() {
        }

        @Override
        public void close() {
        }
    }
}
