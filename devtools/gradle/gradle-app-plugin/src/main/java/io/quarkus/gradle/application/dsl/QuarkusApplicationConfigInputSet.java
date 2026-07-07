package io.quarkus.gradle.application.dsl;

import org.gradle.api.provider.SetProperty;

public abstract class QuarkusApplicationConfigInputSet {

    public abstract SetProperty<String> getPrefixes();

    public abstract SetProperty<String> getNames();
}
