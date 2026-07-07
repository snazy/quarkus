package io.quarkus.gradle.application.dsl;

import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

public abstract class QuarkusApplicationExtension {

    private final QuarkusApplicationBuilds builds;
    private final QuarkusApplicationConfigInputs configInputs;
    private final QuarkusApplicationCodegen codegen;
    private final QuarkusApplicationDev dev;
    private final QuarkusApplicationRemoteDev remoteDev;
    private final QuarkusApplicationForkOptions buildForkOptions;
    private final QuarkusApplicationForkOptions codeGenForkOptions;

    @Inject
    public QuarkusApplicationExtension(ObjectFactory objects, ProviderFactory providers, ProjectLayout layout,
            String projectName, Provider<String> projectVersion) {
        this.builds = objects.newInstance(QuarkusApplicationBuilds.class, objects, providers, layout, projectName,
                projectVersion);
        this.configInputs = objects.newInstance(QuarkusApplicationConfigInputs.class, objects, providers);
        this.codegen = objects.newInstance(QuarkusApplicationCodegen.class);
        this.dev = objects.newInstance(QuarkusApplicationDev.class);
        this.remoteDev = objects.newInstance(QuarkusApplicationRemoteDev.class);
        this.buildForkOptions = objects.newInstance(QuarkusApplicationForkOptions.class);
        this.codeGenForkOptions = objects.newInstance(QuarkusApplicationForkOptions.class);
        getQuarkusBuildProperties().convention(Map.of());
    }

    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    public QuarkusApplicationForkOptions getBuildForkOptions() {
        return buildForkOptions;
    }

    public QuarkusApplicationForkOptions getCodeGenForkOptions() {
        return codeGenForkOptions;
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void buildForkOptions(Action<? super QuarkusApplicationForkOptions> action) {
        action.execute(buildForkOptions);
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void codeGenForkOptions(Action<? super QuarkusApplicationForkOptions> action) {
        action.execute(codeGenForkOptions);
    }

    public QuarkusApplicationBuilds getBuilds() {
        return builds;
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void builds(Action<? super QuarkusApplicationBuilds> action) {
        action.execute(builds);
    }

    public QuarkusApplicationConfigInputs getConfigInputs() {
        return configInputs;
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void configInputs(Action<? super QuarkusApplicationConfigInputs> action) {
        action.execute(configInputs);
    }

    public QuarkusApplicationCodegen getCodegen() {
        return codegen;
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void codegen(Action<? super QuarkusApplicationCodegen> action) {
        action.execute(codegen);
    }

    public QuarkusApplicationDev getDev() {
        return dev;
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void dev(Action<? super QuarkusApplicationDev> action) {
        action.execute(dev);
    }

    public QuarkusApplicationRemoteDev getRemoteDev() {
        return remoteDev;
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void remoteDev(Action<? super QuarkusApplicationRemoteDev> action) {
        action.execute(remoteDev);
    }
}
