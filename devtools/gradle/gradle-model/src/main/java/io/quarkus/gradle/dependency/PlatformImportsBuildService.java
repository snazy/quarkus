package io.quarkus.gradle.dependency;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import io.quarkus.bootstrap.model.MappableCollectionFactory;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.model.PlatformReleaseInfo;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.maven.dependency.ArtifactCoords;

/**
 * Build-scoped storage for platform imports discovered while resolving the Quarkus platform configuration.
 * Gradle can use build services concurrently, so the returned imports synchronize all access and expose snapshots.
 */
public abstract class PlatformImportsBuildService implements BuildService<BuildServiceParameters.None> {

    static final String NAME = "quarkusPlatformImports";

    private final ConcurrentMap<String, SynchronizedPlatformImports> platformImports = new ConcurrentHashMap<>();

    static String key(String projectPath, String platformConfigurationName) {
        return projectPath + ":" + platformConfigurationName;
    }

    PlatformImports getPlatformImports(String key) {
        return getOrCreatePlatformImports(key);
    }

    void addPlatformDescriptor(String key, String groupId, String artifactId, String classifier, String type, String version) {
        getOrCreatePlatformImports(key).addPlatformDescriptor(groupId, artifactId, classifier, type, version);
    }

    void addPlatformProperties(String key, String groupId, String artifactId, String classifier, String type, String version,
            Path propsPath) throws AppModelResolverException {
        getOrCreatePlatformImports(key).addPlatformProperties(groupId, artifactId, classifier, type, version, propsPath);
    }

    private SynchronizedPlatformImports getOrCreatePlatformImports(String key) {
        return platformImports.computeIfAbsent(key, ignored -> new SynchronizedPlatformImports());
    }

    private static final class SynchronizedPlatformImports implements PlatformImports, Serializable {

        private static final long serialVersionUID = 1L;

        private final PlatformImportsImpl delegate = new PlatformImportsImpl();

        synchronized void addPlatformDescriptor(String groupId, String artifactId, String classifier, String type,
                String version) {
            delegate.addPlatformDescriptor(groupId, artifactId, classifier, type, version);
        }

        synchronized void addPlatformProperties(String groupId, String artifactId, String classifier, String type,
                String version, Path propsPath) throws AppModelResolverException {
            delegate.addPlatformProperties(groupId, artifactId, classifier, type, version, propsPath);
        }

        @Override
        public synchronized Map<String, String> getPlatformProperties() {
            return Map.copyOf(delegate.getPlatformProperties());
        }

        @Override
        public synchronized Collection<PlatformReleaseInfo> getPlatformReleaseInfo() {
            return List.copyOf(delegate.getPlatformReleaseInfo());
        }

        @Override
        public synchronized Collection<ArtifactCoords> getImportedPlatformBoms() {
            return List.copyOf(delegate.getImportedPlatformBoms());
        }

        @Override
        public synchronized String getMisalignmentReport() {
            return delegate.getMisalignmentReport();
        }

        @Override
        public synchronized boolean isAligned() {
            return delegate.isAligned();
        }

        @Override
        public synchronized Map<String, Object> asMap(MappableCollectionFactory factory) {
            return delegate.asMap(factory);
        }

        private synchronized Object writeReplace() {
            return PlatformImports.fromMap(delegate.asMap(MappableCollectionFactory.defaultInstance()));
        }
    }
}
