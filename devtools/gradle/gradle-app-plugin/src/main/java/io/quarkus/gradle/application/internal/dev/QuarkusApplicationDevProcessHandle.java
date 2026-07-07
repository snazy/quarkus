package io.quarkus.gradle.application.internal.dev;

interface QuarkusApplicationDevProcessHandle extends AutoCloseable {

    @Override
    void close() throws Exception;
}
