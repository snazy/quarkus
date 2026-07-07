package io.quarkus.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

import io.quarkus.gradle.extension.QuarkusLegacyTaskUsageLevel;

public final class LegacyTaskUsageDiagnostics {

    public static final String REPORT_PATH = "reports/quarkus/legacy-task-usage.txt";

    private LegacyTaskUsageDiagnostics() {
    }

    public static void report(QuarkusLegacyTaskUsageLevel level, Collection<LegacyTaskUsage> usages, Logger logger,
            File reportFile) {
        if (level == QuarkusLegacyTaskUsageLevel.OFF || usages.isEmpty()) {
            return;
        }

        List<LegacyTaskUsage> usageSnapshot = List.copyOf(usages);
        writeReport(reportFile, usageSnapshot);

        String message = message(usageSnapshot, reportFile);
        if (level == QuarkusLegacyTaskUsageLevel.FAIL) {
            throw new GradleException(message);
        }
        logger.warn(message);
    }

    private static String message(List<LegacyTaskUsage> usages, File reportFile) {
        StringBuilder warning = new StringBuilder();
        warning.append("Legacy Quarkus Gradle application task usage detected.");
        for (LegacyTaskUsage usage : usages) {
            warning.append(System.lineSeparator()).append("  - ").append(usage.taskName());
            warning.append(": ").append(usage.replacement());
        }
        warning.append(System.lineSeparator()).append("Diagnostics report: ").append(reportFile);
        return warning.toString();
    }

    private static void writeReport(File reportFile, List<LegacyTaskUsage> usages) {
        try {
            Files.createDirectories(reportFile.toPath().getParent());
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(reportFile.toPath(), StandardCharsets.UTF_8))) {
                writer.println("Legacy Quarkus Gradle application task usage detected");
                writer.println();
                writer.println("The following legacy application tasks were part of the Gradle task graph:");
                for (LegacyTaskUsage usage : usages) {
                    writer.printf("- %s%n", usage.taskName());
                    writer.printf("  Replacement: %s%n", usage.replacement());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write legacy Quarkus Gradle task diagnostics to " + reportFile, e);
        }
    }

    public record LegacyTaskUsage(String taskName, String replacement) {
    }
}
