package io.quarkus.gradle.tooling;

import java.io.Serializable;
import java.util.Map;

import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

public class DefaultProjectDescriptor implements Serializable, ProjectDescriptor {

    private static final long serialVersionUID = 1L;

    private WorkspaceModule.Mutable module;
    private Map<WorkspaceModuleId, WorkspaceModule.Mutable> modules;

    public DefaultProjectDescriptor(WorkspaceModule.Mutable module, Map<WorkspaceModuleId, WorkspaceModule.Mutable> modules) {
        this.module = module;
        this.modules = modules;
    }

    @Override
    public WorkspaceModule.Mutable getWorkspaceModule() {
        return module;
    }

    public void setWorkspaceModule(WorkspaceModule.Mutable module) {
        this.module = module;
    }

    @Override
    public WorkspaceModule.Mutable getWorkspaceModuleOrNull(WorkspaceModuleId moduleId) {
        return modules.get(moduleId);
    }

    @Override
    public String toString() {
        return "DefaultProjectDescriptor{" +
                "\nmodule=" + module +
                "\nmodules=" + modules +
                "\n}";
    }
}
