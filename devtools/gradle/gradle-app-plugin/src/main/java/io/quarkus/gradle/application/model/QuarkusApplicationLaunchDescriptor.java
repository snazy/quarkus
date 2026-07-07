package io.quarkus.gradle.application.model;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

public record QuarkusApplicationLaunchDescriptor(Optional<String> name,
        QuarkusApplicationLaunchKind kind) {

    public QuarkusApplicationLaunchDescriptor {
        requireNonNull(name, "name");
        if (kind == null) {
            throw new IllegalArgumentException("Quarkus application launch descriptor requires a kind");
        }
    }

    public static QuarkusApplicationLaunchDescriptor continuousTest() {
        return new QuarkusApplicationLaunchDescriptor(Optional.empty(), QuarkusApplicationLaunchKind.CONTINUOUS_TEST);
    }

    public static QuarkusApplicationLaunchDescriptor continuousTest(String name) {
        return new QuarkusApplicationLaunchDescriptor(Optional.of(name), QuarkusApplicationLaunchKind.CONTINUOUS_TEST);
    }
}
