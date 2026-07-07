package io.quarkus.gradle.application.internal.dev;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.List;

import io.quarkus.deployment.dev.BuildOutputChangeStatus;

public record GradleDevBuildResult(
        long sequence,
        BuildOutputChangeStatus status,
        List<GradleDevFileChange> changes,
        String failureSummary,
        Path diagnosticsPath,
        boolean userInitiated,
        boolean forceRestart) {

    public GradleDevBuildResult {
        requireNonNull(status, "status");
        changes = changes == null ? List.of() : List.copyOf(changes);
        diagnosticsPath = diagnosticsPath == null ? null : diagnosticsPath.normalize();
    }
}
