package io.quarkus.gradle.application.dsl;

import java.util.List;

import org.gradle.api.provider.ListProperty;

public abstract class QuarkusApplicationCodegen {

    static final List<String> DEFAULT_PROVIDERS = List.of("grpc", "avdl", "avpr", "avsc");
    static final List<String> DEFAULT_INPUT_NAMES = List.of("proto", "avro");

    public QuarkusApplicationCodegen() {
        getProviders().convention(DEFAULT_PROVIDERS);
        getInputNames().convention(DEFAULT_INPUT_NAMES);
    }

    public abstract ListProperty<String> getProviders();

    public abstract ListProperty<String> getInputNames();
}
