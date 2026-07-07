package io.quarkus.gradle.application.internal.remotedev;

import java.io.IOException;
import java.util.Map;

public interface RemoteDevPackageClient extends AutoCloseable {

    RemoteDevPackageClientResult connect(Map<String, String> localHashes) throws IOException;

    RemoteDevPackageClientResult send(RemoteDevPackageDiff diff) throws IOException;

    void startChangePolling() throws IOException;

    @Override
    void close() throws IOException;
}
