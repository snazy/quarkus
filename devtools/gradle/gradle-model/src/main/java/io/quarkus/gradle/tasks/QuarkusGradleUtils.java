package io.quarkus.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import io.quarkus.bootstrap.util.IoUtils;

public class QuarkusGradleUtils {

    private static final String ERROR_COLLECTING_PROJECT_CLASSES = "Failed to collect project's classes in a temporary dir";

    public static SourceSetContainer getSourceSets(Project project) {
        return project.getExtensions().getByType(SourceSetContainer.class);
    }

    public static SourceSet getSourceSet(Project project, String sourceSetName) {
        return getSourceSets(project).getByName(sourceSetName);
    }

    public static SourceSet findSourceSet(Project project, String sourceSetName) {
        return getSourceSets(project).findByName(sourceSetName);
    }

    public static Path mergeClassesDirs(Collection<Path> classesDirs, File tmpDir, boolean populated, boolean test) {
        List<Path> existingClassesDirs = classesDirs.stream().filter(Files::exists).toList();

        if (existingClassesDirs.isEmpty()) {
            return null;
        }

        if (existingClassesDirs.size() == 1) {
            return existingClassesDirs.get(0);
        }

        try {
            Path mergedClassesDir = tmpDir.toPath().resolve("quarkus-app-classes" + (test ? "-test" : ""));

            if (!populated) {
                return mergedClassesDir;
            }

            if (Files.exists(mergedClassesDir)) {
                IoUtils.recursiveDelete(mergedClassesDir);
            }

            for (Path classesDir : existingClassesDirs) {
                IoUtils.copy(classesDir, mergedClassesDir);
            }

            return mergedClassesDir;
        } catch (IOException e) {
            throw new UncheckedIOException(ERROR_COLLECTING_PROJECT_CLASSES, e);
        }
    }

}
