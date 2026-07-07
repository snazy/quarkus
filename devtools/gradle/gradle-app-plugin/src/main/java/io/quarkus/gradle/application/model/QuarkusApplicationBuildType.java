package io.quarkus.gradle.application.model;

import java.util.Optional;

public enum QuarkusApplicationBuildType {
    FAST_JAR(false, false, true, Optional.of("fast-jar")),
    LEGACY_JAR(false, false, true, Optional.of("legacy-jar")),
    MUTABLE_JAR(false, false, false, Optional.of("mutable-jar")),
    UBER_JAR(false, false, false, Optional.of("uber-jar")),
    NATIVE_EXECUTABLE(true, false, true, Optional.empty()),
    NATIVE_SOURCES(true, true, false, Optional.empty());

    private final boolean nativeOutput;
    private final boolean nativeSources;
    private final boolean reusableDependencyFragmentCandidate;
    private final Optional<String> jarType;

    QuarkusApplicationBuildType(boolean nativeOutput, boolean nativeSources, boolean reusableDependencyFragmentCandidate,
            Optional<String> jarType) {
        this.nativeOutput = nativeOutput;
        this.nativeSources = nativeSources;
        this.reusableDependencyFragmentCandidate = reusableDependencyFragmentCandidate;
        this.jarType = jarType;
    }

    public boolean isJar() {
        return jarType.isPresent();
    }

    public boolean isNativeOutput() {
        return nativeOutput;
    }

    public boolean isNativeSources() {
        return nativeSources;
    }

    public boolean canReuseDependencyFragment() {
        return reusableDependencyFragmentCandidate;
    }

    public Optional<String> jarType() {
        return jarType;
    }
}
