package io.quarkus.gradle.application.internal.remotedev;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusApplicationRemoteDevDeploymentHandleTest {

    @TempDir
    Path directory;

    @Test
    void replacingClientStartsNewPollerAndClosesPreviousClient() {
        QuarkusApplicationRemoteDevDeploymentHandle handle = new QuarkusApplicationRemoteDevDeploymentHandle(
                directory.resolve("closed.txt"), "test-client");
        FakeClient first = new FakeClient();
        FakeClient second = new FakeClient();

        QuarkusApplicationRemoteDevClients.replace("test-client", first);
        QuarkusApplicationRemoteDevClients.replace("test-client", second);

        assertThat(first.started).isTrue();
        assertThat(first.closed).isTrue();
        assertThat(second.started).isTrue();
        assertThat(second.closed).isFalse();

        handle.stop();

        assertThat(second.closed).isTrue();
    }

    private static final class FakeClient implements RemoteDevPackageClient {
        private boolean started;
        private boolean closed;

        @Override
        public RemoteDevPackageClientResult connect(Map<String, String> localHashes) {
            return RemoteDevPackageClientResult.connected(java.util.Set.of());
        }

        @Override
        public RemoteDevPackageClientResult send(RemoteDevPackageDiff diff) {
            return RemoteDevPackageClientResult.sent(diff.changed().size(), diff.deleted().size());
        }

        @Override
        public void startChangePolling() {
            started = true;
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }
}
