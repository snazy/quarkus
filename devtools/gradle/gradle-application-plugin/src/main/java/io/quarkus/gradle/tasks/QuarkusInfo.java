package io.quarkus.gradle.tasks;

import java.io.IOException;

import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.devtools.commands.ProjectInfo;
import io.quarkus.devtools.project.QuarkusProject;
import io.quarkus.gradle.tooling.ToolingUtils;

@DisableCachingByDefault(because = "Not cacheable")
public abstract class QuarkusInfo extends QuarkusPlatformTask {

    private boolean perModule = false;

    @Input
    public boolean getPerModule() {
        return perModule;
    }

    @Option(description = "Log project's state per module.", option = "perModule")
    public void setPerModule(boolean perModule) {
        this.perModule = perModule;
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getApplicationModel();

    public QuarkusInfo() {
        super("Log Quarkus-specific project information, such as imported Quarkus platform BOMs, Quarkus extensions found among the project dependencies, etc.");
    }

    @TaskAction
    public void logInfo() throws IOException {
        getLogger().warn("{} is experimental, its options and output might change in future versions", getName());

        final QuarkusProject quarkusProject = getQuarkusProject(false);
        final ProjectInfo invoker = new ProjectInfo(quarkusProject);
        invoker.perModule(perModule);
        invoker.appModel(ToolingUtils.deserializeAppModel(getApplicationModel().get().getAsFile().toPath()));
        try {
            invoker.execute();
        } catch (Exception e) {
            throw new GradleException("Failed to collect Quarkus project information", e);
        }
    }
}
