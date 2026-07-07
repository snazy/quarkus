package io.quarkus.gradle.application.internal.remotedev;

import java.io.IOException;

public interface RemoteDevPackageClientFactory {

    RemoteDevPackageClient create(RemoteDevPackageClientConfig config) throws IOException;
}
