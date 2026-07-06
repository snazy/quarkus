package io.quarkus.gradle.extension;

import java.io.File;
import java.util.List;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.process.JavaForkOptions;

import io.quarkus.gradle.dsl.Manifest;
import io.quarkus.gradle.tasks.AbstractQuarkusExtension;
import io.quarkus.gradle.tasks.QuarkusGradleUtils;

public abstract class QuarkusPluginExtension extends AbstractQuarkusExtension {
    // TODO dynamically load generation provider, or make them write code directly in quarkus-generated-sources
    public static final String[] CODE_GENERATION_PROVIDER = new String[] { "grpc", "avdl", "avpr", "avsc" };
    public static final String[] CODE_GENERATION_INPUT = new String[] { "proto", "avro" };
    private final SourceSetExtension sourceSetExtension;

    public QuarkusPluginExtension(Project project) {
        super(project);

        getCleanupBuildOutput().convention(true);
        getCacheLargeArtifacts().convention(project.getProviders().environmentVariable("CI")
                .map(ignored -> false).orElse(true));
        getCodeGenerationProviders().convention(List.of(CODE_GENERATION_PROVIDER));
        getCodeGenerationInputs().convention(List.of(CODE_GENERATION_INPUT));

        this.sourceSetExtension = new SourceSetExtension();
    }

    public Manifest getManifest() {
        return manifest();
    }

    @SuppressWarnings("unused") // publicly documented DSL
    public void manifest(Action<Manifest> action) {
        action.execute(this.getManifest());
    }

    @Override
    public abstract Property<String> getFinalName();

    /**
     * Whether the build output, build/*-runner[.jar] and build/quarkus-app, for other package types than the
     * currently configured one are removed, default is 'true'.
     */
    @Internal
    public abstract Property<Boolean> getCleanupBuildOutput();

    /**
     * Whether large build artifacts, like uber-jar and native runners, are cached. Defaults to 'false' if the 'CI' environment
     * variable is set, otherwise defaults to 'true'.
     */
    @Internal
    public abstract Property<Boolean> getCacheLargeArtifacts();

    /**
     * The directories of code generation inputs, only needed if using a customer extension that provides its own code
     * generator.
     */
    @Internal
    public abstract ListProperty<String> getCodeGenerationInputs();

    /**
     * The identifiers of the code generation providers, only needed if using a customer extension that provides its own code
     * generator.
     */
    @Internal
    public abstract ListProperty<String> getCodeGenerationProviders();

    @SuppressWarnings("unused") // publicly documented DSL
    public void sourceSets(Action<? super SourceSetExtension> action) {
        action.execute(this.sourceSetExtension);
    }

    public SourceSetExtension sourceSetExtension() {
        return sourceSetExtension;
    }

    public static FileCollection combinedOutputSourceDirs(Project project) {
        ConfigurableFileCollection classesDirs = project.files();
        SourceSetContainer sourceSets = QuarkusGradleUtils.getSourceSets(project);
        classesDirs.from(sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getOutput().getClassesDirs());
        classesDirs.from(sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).getOutput().getClassesDirs());
        return classesDirs;
    }

    /**
     * Adds an action to configure the {@code JavaForkOptions} to build a Quarkus application.
     *
     * @param action configuration action
     */
    @SuppressWarnings("unused")
    public void buildForkOptions(Action<? super JavaForkOptions> action) {
        buildForkOptions.add(action);
    }

    /**
     * Adds an action to configure the {@code JavaForkOptions} to generate Quarkus code.
     *
     * @param action configuration action
     */
    @SuppressWarnings("unused")
    public void codeGenForkOptions(Action<? super JavaForkOptions> action) {
        codeGenForkOptions.add(action);
    }

    /**
     * Returns the last file from the specified {@link FileCollection}.
     *
     * @param fileCollection the collection of files present in the directory
     * @return result returns the last file
     */
    public static File getLastFile(FileCollection fileCollection) {
        File result = null;
        for (File f : fileCollection) {
            if (result == null || f.exists()) {
                result = f;
            }
        }
        return result;
    }

    @SuppressWarnings("unused")
    @Override
    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    /**
     * Native-image build arguments configured through the Gradle extension.
     */
    @Override
    public abstract MapProperty<String, String> getNativeArguments();

    @Override
    public abstract ListProperty<String> getCachingRelevantProperties();

    public void set(String name, String value) {
        getQuarkusBuildProperties().put(addQuarkusBuildPropertyPrefix(name), value);
    }

    public void set(String name, Provider<String> value) {
        getQuarkusBuildProperties().put(addQuarkusBuildPropertyPrefix(name), value);
    }

    private String addQuarkusBuildPropertyPrefix(String name) {
        return String.format("quarkus.%s", name);
    }
}
