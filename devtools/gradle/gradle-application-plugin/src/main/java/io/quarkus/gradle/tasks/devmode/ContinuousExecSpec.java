package io.quarkus.gradle.tasks.devmode;

public class ContinuousExecSpec {

    private final DevProjectState devProjectState;

    public ContinuousExecSpec(DevProjectState devProjectState) {
        this.devProjectState = devProjectState;
    }

    public DevProjectState getDevProjectState() {
        return devProjectState;
    }
}
