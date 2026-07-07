package io.quarkus.gradle.tooling.tasks;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.tooling.GradlePomResolver;
import io.quarkus.gradle.tooling.dependency.DependencyDataCollector;
import io.quarkus.gradle.tooling.dependency.ExternalModuleDeclaredDependencyInput;
import io.quarkus.gradle.tooling.dependency.PomClosureResult;
import io.quarkus.gradle.tooling.dependency.PomClosureResultCodec;
import io.quarkus.maven.dependency.GAV;

@DisableCachingByDefault(because = "The resolved parent/imported-BOM POM closure is discovered dynamically during task execution")
public abstract class GeneratePomClosureTask extends DefaultTask {

    @Inject
    protected abstract DependencyHandler getDependencyHandler();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    @Nested
    public abstract ListProperty<ExternalModuleDeclaredDependencyInput> getExternalModuleInputs();

    @Input
    public abstract MapProperty<String, String> getSelectedPomFilesByGav();

    @Classpath
    public abstract ConfigurableFileCollection getSelectedPomFiles();

    @Input
    public abstract ListProperty<String> getMavenLocalRepositoryRoots();

    @OutputFile
    public abstract RegularFileProperty getPomClosureFile();

    @TaskAction
    public void execute() throws IOException {
        var pomResolver = new GradlePomResolver(selectedPomFilesByGav(), getDependencyHandler(),
                mavenLocalRepositoryRoots());
        var collector = new DependencyDataCollector(pomResolver,
                getProviderFactory().systemPropertiesPrefixedBy("")::get);
        collector.collectExternalDeclaredDependencies(getLogger(), getExternalModuleInputs().get());
        PomClosureResultCodec.write(PomClosureResult.from(pomResolver.getPomResults()),
                getPomClosureFile().get().getAsFile().toPath());
    }

    private Map<GAV, File> selectedPomFilesByGav() {
        Map<GAV, File> result = new TreeMap<>((left, right) -> left.toString().compareTo(right.toString()));
        getSelectedPomFilesByGav().get().forEach((gav, file) -> result.put(parseGav(gav), new File(file)));
        return result;
    }

    private List<File> mavenLocalRepositoryRoots() {
        return getMavenLocalRepositoryRoots().get().stream()
                .filter(root -> !root.isBlank())
                .map(File::new)
                .toList();
    }

    private static GAV parseGav(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("POM closure GAV must have format groupId:artifactId:version: " + value);
        }
        return new GAV(parts[0], parts[1], parts[2]);
    }
}
