package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

import io.quarkus.gradle.application.internal.planning.PackageOutputName;

public class QuarkusApplicationBuilds {

    private final ExtensiblePolymorphicDomainObjectContainer<QuarkusApplicationBuild> container;
    private final ProviderFactory providers;
    private final String projectName;
    private final Provider<String> projectVersion;

    @Inject
    public QuarkusApplicationBuilds(ObjectFactory objects, ProviderFactory providers, ProjectLayout layout,
            String projectName, Provider<String> projectVersion) {
        this.providers = providers;
        this.projectName = projectName;
        this.projectVersion = projectVersion;
        this.container = objects.polymorphicDomainObjectContainer(QuarkusApplicationBuild.class);
        registerFactories(objects, layout);
    }

    private void registerFactories(ObjectFactory objects, ProjectLayout layout) {
        container.registerFactory(QuarkusFastJarOutput.class,
                name -> newBuild(objects, layout, QuarkusFastJarOutput.class, name));
        container.registerFactory(QuarkusLegacyJarOutput.class,
                name -> newBuild(objects, layout, QuarkusLegacyJarOutput.class, name));
        container.registerFactory(QuarkusMutableJarOutput.class,
                name -> newBuild(objects, layout, QuarkusMutableJarOutput.class, name));
        container.registerFactory(QuarkusUberJarOutput.class,
                name -> newBuild(objects, layout, QuarkusUberJarOutput.class, name));
        container.registerFactory(QuarkusNativeOutput.class,
                name -> newBuild(objects, layout, QuarkusNativeOutput.class, name));
        container.registerFactory(QuarkusNativeSourcesOutput.class,
                name -> newBuild(objects, layout, QuarkusNativeSourcesOutput.class, name));
    }

    private <T extends QuarkusApplicationBuild> T newBuild(ObjectFactory objects, ProjectLayout layout, Class<T> type,
            String name) {
        T build = objects.newInstance(type, name, layout, objects);
        build.getArchiveBaseName().convention(projectName);
        build.getArchiveBaseNameSuffix().convention("");
        build.getArchiveVersion().convention(projectVersion);
        build.getOutputName().convention(providers.provider(() -> PackageOutputName.assemble(
                build.getArchiveBaseName().get(),
                build.getArchiveBaseNameSuffix().get(),
                build.getArchiveVersion().get())));
        if (build instanceof QuarkusApplicationRunnerOutput runnerOutput) {
            runnerOutput.getArchiveRunnerSuffix().convention("-runner");
            runnerOutput.getArchiveAddRunnerSuffix().convention(true);
        }
        return build;
    }

    public NamedDomainObjectProvider<QuarkusFastJarOutput> fastJar(String name) {
        return register(name, QuarkusFastJarOutput.class);
    }

    public NamedDomainObjectProvider<QuarkusFastJarOutput> fastJar(String name,
            Action<? super QuarkusFastJarOutput> action) {
        return register(name, QuarkusFastJarOutput.class, action);
    }

    public NamedDomainObjectProvider<QuarkusLegacyJarOutput> legacyJar(String name) {
        return register(name, QuarkusLegacyJarOutput.class);
    }

    public NamedDomainObjectProvider<QuarkusLegacyJarOutput> legacyJar(String name,
            Action<? super QuarkusLegacyJarOutput> action) {
        return register(name, QuarkusLegacyJarOutput.class, action);
    }

    public NamedDomainObjectProvider<QuarkusMutableJarOutput> mutableJar(String name) {
        return register(name, QuarkusMutableJarOutput.class);
    }

    public NamedDomainObjectProvider<QuarkusMutableJarOutput> mutableJar(String name,
            Action<? super QuarkusMutableJarOutput> action) {
        return register(name, QuarkusMutableJarOutput.class, action);
    }

    public NamedDomainObjectProvider<QuarkusUberJarOutput> uberJar(String name) {
        return register(name, QuarkusUberJarOutput.class);
    }

    public NamedDomainObjectProvider<QuarkusUberJarOutput> uberJar(String name,
            Action<? super QuarkusUberJarOutput> action) {
        return register(name, QuarkusUberJarOutput.class, action);
    }

    public NamedDomainObjectProvider<QuarkusNativeOutput> nativeExecutable(String name) {
        return register(name, QuarkusNativeOutput.class);
    }

    public NamedDomainObjectProvider<QuarkusNativeOutput> nativeExecutable(String name,
            Action<? super QuarkusNativeOutput> action) {
        return register(name, QuarkusNativeOutput.class, action);
    }

    public NamedDomainObjectProvider<QuarkusNativeSourcesOutput> nativeSources(String name) {
        return register(name, QuarkusNativeSourcesOutput.class);
    }

    public NamedDomainObjectProvider<QuarkusNativeSourcesOutput> nativeSources(String name,
            Action<? super QuarkusNativeSourcesOutput> action) {
        return register(name, QuarkusNativeSourcesOutput.class, action);
    }

    public <T extends QuarkusApplicationBuild> NamedDomainObjectProvider<T> register(String name, Class<T> type) {
        return container.register(name, type);
    }

    public <T extends QuarkusApplicationBuild> NamedDomainObjectProvider<T> register(String name, Class<T> type,
            Action<? super T> action) {
        return container.register(name, type, action);
    }

    public void all(Action<? super QuarkusApplicationBuild> action) {
        container.all(action);
    }

    public void configure(Action<? super QuarkusApplicationBuilds> action) {
        action.execute(this);
    }
}
