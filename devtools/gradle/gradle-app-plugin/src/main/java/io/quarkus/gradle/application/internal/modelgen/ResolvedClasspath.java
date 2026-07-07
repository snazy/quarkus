package io.quarkus.gradle.application.internal.modelgen;

import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Internal;

/**
 * Lazy resolution-result input for application model generation.
 */
public abstract class ResolvedClasspath {

    @Internal
    public abstract Property<ResolvedComponentResult> getRoot();

    @Internal
    public abstract SetProperty<ResolvedArtifactResult> getResolvedArtifacts();

    @Internal
    public abstract ConfigurableFileCollection getResolvedArtifactFiles();

    @Internal
    FileCollection getAllResolvedFiles() {
        return getResolvedArtifactFiles();
    }

    Map<ComponentIdentifier, List<ResolvedArtifact>> resolvedArtifactsByComponentIdentifier() {
        return getQuarkusResolvedArtifacts().stream()
                .collect(Collectors.groupingBy(artifact -> artifact.id.getComponentIdentifier()));
    }

    private List<ResolvedArtifact> getQuarkusResolvedArtifacts() {
        return getResolvedArtifacts().get().stream()
                .map(this::toResolvedArtifact)
                .collect(toList());
    }

    private ResolvedArtifact toResolvedArtifact(ResolvedArtifactResult result) {
        String type = result.getVariant().getAttributes().getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE);
        return new ResolvedArtifact(result.getId(), result.getFile(), type);
    }

    public void configureFrom(Configuration configuration) {
        ResolvableDependencies resolvableDependencies = configuration.getIncoming();
        getRoot().set(resolvableDependencies.getResolutionResult().getRootComponent());
        var artifacts = resolvableDependencies.getArtifacts();
        getResolvedArtifacts().set(artifacts.getResolvedArtifacts());
        getResolvedArtifactFiles().from(artifacts.getArtifactFiles());
    }
}
