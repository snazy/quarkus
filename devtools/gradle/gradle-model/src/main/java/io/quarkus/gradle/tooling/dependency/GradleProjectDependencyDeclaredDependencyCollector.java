package io.quarkus.gradle.tooling.dependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.internal.composite.IncludedBuildInternal;

import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.GAV;

/**
 * Keeps Gradle project model inspection separate from external Maven POM processing.
 */
class GradleProjectDependencyDeclaredDependencyCollector {

    private static final String SCOPE_RUNTIME = "runtime";
    private static final String SCOPE_TEST = "test";

    private final Map<DeclaredDepsCacheKey, DeclaredDepsResult> declaredDependenciesCache = new ConcurrentHashMap<>();

    void collectDeclaredFromRootProject(Project project, boolean isTestConfig,
            Map<ArtifactKey, DeclaredDepsResult> resultMap) {
        collectDeclaredFromProject(project, isTestConfig, resultMap);
    }

    void collectDeclaredFromProjectGraph(Project project, boolean includeTestScopes,
            Map<ArtifactKey, DeclaredDepsResult> resultMap) {
        for (Project candidate : project.getRootProject().getAllprojects()) {
            collectDeclaredFromProject(candidate, candidate.equals(project) && includeTestScopes, resultMap);
        }
    }

    private void collectDeclaredFromProject(Project project, boolean collectTestScopes,
            Map<ArtifactKey, DeclaredDepsResult> resultMap) {
        String groupId = String.valueOf(project.getGroup());
        String artifactId = project.getName();
        ArtifactKey projectKey = ArtifactKey.of(groupId, artifactId,
                ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_JAR);
        DeclaredDepsCacheKey cacheKey = new DeclaredDepsCacheKey(projectKey, collectTestScopes);
        DeclaredDepsResult result = declaredDependenciesCache.computeIfAbsent(
                cacheKey,
                k -> DeclaredDepsResult.resolved(collectDeclaredDependenciesFromProject(project, collectTestScopes)));
        resultMap.put(projectKey, result);
    }

    void collectDeclaredFromProjectDependency(
            Project project,
            ResolvedArtifactResult artifact,
            ProjectComponentIdentifier projectId,
            Map<ArtifactKey, DeclaredDepsResult> resultMap) {
        final Project depProject = getProject(project, projectId);
        if (depProject == null) {
            throw new GradleException("Project dependency not found for path: " + projectId.getProjectPath());
        }
        String groupId = String.valueOf(depProject.getGroup());
        String artifactId = depProject.getName();
        String version = String.valueOf(depProject.getVersion());
        String type = DependencyDataCollector.resolveArtifactType(artifact);
        ArtifactKey projectKey = DependencyUtils.getKey(groupId, artifactId, version, artifact.getFile(), type);
        // from this code branch, depProject is never a root project, so we set collectTestScopes to false
        DeclaredDepsResult result = declaredDependenciesCache.computeIfAbsent(new DeclaredDepsCacheKey(projectKey, false),
                key -> DeclaredDepsResult.resolved(collectDeclaredDependenciesFromProject(depProject, false)));
        resultMap.put(projectKey, result);
    }

    private static Project getProject(Project project, ProjectComponentIdentifier projectId) {
        var includedBuild = ToolingUtils.includedBuild(project, projectId.getBuild().getBuildPath());
        final Project depProject;
        if (includedBuild != null) {
            if (includedBuild instanceof IncludedBuildInternal ib) {
                depProject = ToolingUtils.includedBuildProject(ib, projectId.getProjectPath());
            } else {
                depProject = null;
            }
        } else {
            depProject = project.getRootProject().findProject(projectId.getProjectPath());
        }
        return depProject;
    }

    private static List<DeclaredDependency> collectDeclaredDependenciesFromProject(Project project,
            boolean collectTestScopes) {
        // Configuration to scope mapping:
        // api/implementation -> compile
        // runtimeOnly -> runtime
        // compileOnly -> ignored altogether
        // test* -> test
        final Map<GAV, DeclaredDependency> declaredDeps = new LinkedHashMap<>();

        addDeclaredFromConfig(project, JavaPlugin.API_CONFIGURATION_NAME,
                io.quarkus.maven.dependency.Dependency.SCOPE_COMPILE, declaredDeps);
        addDeclaredFromConfig(project, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
                io.quarkus.maven.dependency.Dependency.SCOPE_COMPILE, declaredDeps);
        addDeclaredFromConfig(project, JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME, SCOPE_RUNTIME, declaredDeps);
        if (collectTestScopes) {
            addDeclaredFromConfig(project, JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, SCOPE_TEST, declaredDeps);
            addDeclaredFromConfig(project, JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME, SCOPE_TEST, declaredDeps);
            // addDeclaredFromConfig(project, JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME, SCOPE_TEST, declaredDeps);
        }

        return new ArrayList<>(declaredDeps.values());
    }

    private static void addDeclaredFromConfig(Project p, String cfgName, String scope,
            Map<GAV, DeclaredDependency> out) {
        final Configuration cfg = p.getConfigurations().findByName(cfgName);
        if (cfg == null) {
            return;
        }

        for (var d : cfg.getDependencies()) {
            var gav = new GAV(
                    String.valueOf(d.getGroup()),
                    d.getName(),
                    String.valueOf(d.getVersion()));
            if (d instanceof ProjectDependency pd) {
                Project dp = p.findProject(pd.getPath());
                if (dp == null) {
                    // should not happen
                    throw new GradleException("Failed to find project for dependency: " + pd.getPath());
                }
            }
            out.put(gav, new DeclaredDependency(
                    gav.getGroupId(),
                    gav.getArtifactId(),
                    gav.getVersion(),
                    null,
                    null,
                    scope,
                    false));

        }
    }

    private record DeclaredDepsCacheKey(ArtifactKey artifactKey, boolean includeTestScopes) {
    }
}
