package io.quarkus.gradle.tasks;

import java.io.IOException;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.tooling.ToolingUtils;

@DisableCachingByDefault(because = "Not cacheable")
public abstract class QuarkusGoOffline extends QuarkusTask {

    @Inject
    public QuarkusGoOffline() {
        super("Resolve all dependencies for offline usage", true);
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getApplicationModel();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getDevApplicationModel();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getTestApplicationModel();

    @TaskAction
    public void resolveAllModels() throws IOException {
        ToolingUtils.deserializeAppModel(getApplicationModel().get().getAsFile().toPath());
        ToolingUtils.deserializeAppModel(getDevApplicationModel().get().getAsFile().toPath());
        ToolingUtils.deserializeAppModel(getTestApplicationModel().get().getAsFile().toPath());
    }

}
