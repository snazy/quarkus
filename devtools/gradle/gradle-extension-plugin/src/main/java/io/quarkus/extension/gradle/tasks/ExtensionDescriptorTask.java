package io.quarkus.extension.gradle.tasks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.devtools.project.extensions.ScmInfoProvider;
import io.quarkus.extension.gradle.QuarkusExtensionConfiguration;
import io.quarkus.extension.gradle.dsl.Capability;
import io.quarkus.extension.gradle.dsl.RemovedResource;
import io.quarkus.fs.util.ZipUtils;
import io.quarkus.gradle.tasks.QuarkusBaseTask;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.GACT;

/**
 * Task that generates extension descriptor files.
 */
@CacheableTask
public abstract class ExtensionDescriptorTask extends QuarkusBaseTask {

    private static final String GROUP_ID = "group-id";
    private static final String ARTIFACT_ID = "artifact-id";
    private static final String METADATA = "metadata";

    @Inject
    public ExtensionDescriptorTask(QuarkusExtensionConfiguration quarkusExtensionConfiguration, SourceSet mainSourceSet,
            Configuration runtimeClasspath) {

        setDescription("Generate extension descriptor file");
        setGroup("quarkus");

        getClasspath().from(runtimeClasspath);
        getInputResourcesDirs().from(mainSourceSet.getResources().getSourceDirectories());

        File outputResourcesDir = mainSourceSet.getOutput().getResourcesDir();
        getExtensionPropertiesFile().fileValue(outputResourcesDir.toPath()
                .resolve(BootstrapConstants.META_INF)
                .resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME)
                .toFile());
        getExtensionDescriptorFile().fileValue(outputResourcesDir.toPath()
                .resolve(BootstrapConstants.META_INF)
                .resolve(BootstrapConstants.QUARKUS_EXTENSION_FILE_NAME)
                .toFile());

        Map<String, String> projectInfo = new HashMap<>();
        projectInfo.put("name", getProject().getName());
        if (getProject().getDescription() != null) {
            projectInfo.put("description", getProject().getDescription());
        }
        projectInfo.put("group", getProject().getGroup().toString());
        projectInfo.put("version", getProject().getVersion().toString());
        getProjectInfo().putAll(projectInfo);

        getDeploymentArtifact().convention(quarkusExtensionConfiguration.getDeploymentArtifact()
                .orElse(getDefaultDeploymentArtifactName(getProject())));
        getConditionalDependencies().convention(quarkusExtensionConfiguration.getConditionalDependencies());
        getConditionalDevDependencies().convention(quarkusExtensionConfiguration.getConditionalDevDependencies());
        getDependencyConditions().convention(quarkusExtensionConfiguration.getDependencyConditions());
        getParentFirstArtifacts().convention(quarkusExtensionConfiguration.getParentFirstArtifacts());
        getRunnerParentFirstArtifacts().convention(quarkusExtensionConfiguration.getRunnerParentFirstArtifacts());
        getExcludedArtifacts().convention(quarkusExtensionConfiguration.getExcludedArtifacts());
        getLesserPriorityArtifacts().convention(quarkusExtensionConfiguration.getLesserPriorityArtifacts());
        getProvidedCapabilities().convention(getProviderFactory().provider(
                () -> capabilityInputs(quarkusExtensionConfiguration.getProvidedCapabilities())));
        getRequiredCapabilities().convention(getProviderFactory().provider(
                () -> capabilityInputs(quarkusExtensionConfiguration.getRequiredCapabilities())));
        getRemovedResources().convention(getProviderFactory().provider(
                () -> removedResourcesInputs(quarkusExtensionConfiguration.getRemoveResources())));
        getQuarkusCoreVersion().convention(getProviderFactory().provider(() -> getQuarkusCoreVersionOrNull(runtimeClasspath)));
        getExtensionDependencyArtifactKeys()
                .convention(getProviderFactory().provider(() -> extensionDependencyArtifactKeys(runtimeClasspath)));
        getRuntimeArtifactKeysByFilePath()
                .convention(getProviderFactory().provider(() -> runtimeArtifactKeysByFilePath(runtimeClasspath)));
    }

    public static Provider<String> getDefaultDeploymentArtifactName(Project project) {
        var projectName = project.getName();
        var projectGroup = project.getGroup().toString();
        var projectVersion = project.getVersion().toString();
        var projectPath = project.getPath();
        // Keep the object reference to `project` out of the lambda expression, so it's not captured.
        return project.getProviders().provider(() -> {
            var name = projectName;
            if (name.equals("runtime")) {
                var projectPathParts = projectPath.split(":");
                if (projectPathParts.length > 2) {
                    name = projectPathParts[projectPathParts.length - 2];
                } else if (projectPathParts.length == 2) {
                    throw new GradleException("The project '" + projectPath
                            + "' must not be named 'runtime' and be a direct child project of the root project. " +
                            "Set 'deploymentArtifact' on the 'QuarkusExtensionConfiguration'.");
                }
            }
            return String.format("%s:%s-deployment:%s", projectGroup, name, projectVersion);
        });
    }

    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputResourcesDirs();

    @OutputFile
    public abstract RegularFileProperty getExtensionPropertiesFile();

    @OutputFile
    public abstract RegularFileProperty getExtensionDescriptorFile();

    @Input
    public abstract MapProperty<String, String> getProjectInfo();

    @Input
    public abstract Property<String> getDeploymentArtifact();

    @Input
    public abstract ListProperty<String> getConditionalDependencies();

    @Input
    public abstract ListProperty<String> getConditionalDevDependencies();

    @Input
    public abstract ListProperty<String> getDependencyConditions();

    @Input
    public abstract ListProperty<String> getParentFirstArtifacts();

    @Input
    public abstract ListProperty<String> getRunnerParentFirstArtifacts();

    @Input
    public abstract ListProperty<String> getExcludedArtifacts();

    @Input
    public abstract ListProperty<String> getLesserPriorityArtifacts();

    @Input
    public abstract ListProperty<String> getProvidedCapabilities();

    @Input
    public abstract ListProperty<String> getRequiredCapabilities();

    @Input
    public abstract ListProperty<String> getRemovedResources();

    @Input
    @Optional
    public abstract Property<String> getQuarkusCoreVersion();

    @Input
    public abstract ListProperty<String> getExtensionDependencyArtifactKeys();

    @Internal
    public abstract MapProperty<String, String> getRuntimeArtifactKeysByFilePath();

    @TaskAction
    public void generateExtensionDescriptor() throws IOException {
        Path outputMetaInfDir = getExtensionPropertiesFile().get().getAsFile().toPath().getParent();

        generateQuarkusExtensionProperties(outputMetaInfDir);
        generateQuarkusExtensionDescriptor(outputMetaInfDir);
    }

    private void generateQuarkusExtensionProperties(Path metaInfDir) {
        final Properties props = new Properties();
        String deploymentArtifact = getDeploymentArtifact().get();

        props.setProperty(BootstrapConstants.PROP_DEPLOYMENT_ARTIFACT, deploymentArtifact);

        setConditionalDepsProperty(BootstrapConstants.CONDITIONAL_DEPENDENCIES,
                getConditionalDependencies().get(), props);
        setConditionalDepsProperty(BootstrapConstants.CONDITIONAL_DEV_DEPENDENCIES,
                getConditionalDevDependencies().get(), props);

        List<String> dependencyConditions = getDependencyConditions().get();
        if (!dependencyConditions.isEmpty()) {
            final StringBuilder buf = new StringBuilder();
            int i = 0;
            buf.append(GACT.fromString(dependencyConditions.get(i++)).toGacString());
            while (i < dependencyConditions.size()) {
                buf.append(' ').append(GACT.fromString(dependencyConditions.get(i++)).toGacString());
            }
            props.setProperty(BootstrapConstants.DEPENDENCY_CONDITION, buf.toString());
        }

        List<String> parentFirstArtifacts = getParentFirstArtifacts().get();
        if (!parentFirstArtifacts.isEmpty()) {
            String val = String.join(",", parentFirstArtifacts);
            props.put(ApplicationModelBuilder.PARENT_FIRST_ARTIFACTS, val);
        }

        List<String> runnerParentFirstArtifacts = getRunnerParentFirstArtifacts().get();
        if (!runnerParentFirstArtifacts.isEmpty()) {
            String val = String.join(",", runnerParentFirstArtifacts);
            props.put(ApplicationModelBuilder.RUNNER_PARENT_FIRST_ARTIFACTS, val);
        }

        List<String> excludedArtifacts = getExcludedArtifacts().get();
        if (!excludedArtifacts.isEmpty()) {
            String val = String.join(",", excludedArtifacts);
            props.put(ApplicationModelBuilder.EXCLUDED_ARTIFACTS, val);
        }

        List<String> lesserPriorityArtifacts = getLesserPriorityArtifacts().get();
        if (!lesserPriorityArtifacts.isEmpty()) {
            String val = String.join(",", lesserPriorityArtifacts);
            props.put(ApplicationModelBuilder.LESSER_PRIORITY_ARTIFACTS, val);
        }

        List<String> providedCapabilities = getProvidedCapabilities().get();
        if (!providedCapabilities.isEmpty()) {
            props.setProperty(BootstrapConstants.PROP_PROVIDES_CAPABILITIES, String.join(",", providedCapabilities));
        }

        List<String> requiredCapabilities = getRequiredCapabilities().get();
        if (!requiredCapabilities.isEmpty()) {
            props.setProperty(BootstrapConstants.PROP_REQUIRES_CAPABILITIES, String.join(",", requiredCapabilities));
        }

        List<String> removedResources = getRemovedResources().get();
        if (!removedResources.isEmpty()) {
            for (String removedResource : removedResources) {
                String[] parts = removedResource.split("=", 2);
                if (parts.length != 2 || parts[1].isEmpty()) {
                    continue;
                }
                final ArtifactKey key;
                try {
                    key = ArtifactKey.fromString(parts[0]);
                } catch (IllegalArgumentException e) {
                    throw new GradleException(
                            "Failed to parse removed resource '" + parts[0], e);
                }
                props.setProperty(ApplicationModelBuilder.REMOVED_RESOURCES_DOT + key, parts[1]);
            }
        }

        try {
            Files.createDirectories(metaInfDir);
            try (BufferedWriter writer = Files
                    .newBufferedWriter(metaInfDir.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME))) {
                props.store(writer, "Generated by extension-descriptor");
            }
        } catch (IOException e) {
            throw new GradleException(
                    "Failed to persist extension descriptor " + metaInfDir.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME),
                    e);
        }
    }

    private static void setConditionalDepsProperty(String propName, List<String> conditionalDependencies, Properties props) {
        if (conditionalDependencies != null && !conditionalDependencies.isEmpty()) {
            final StringBuilder buf = new StringBuilder();
            int i = 0;
            buf.append(ArtifactCoords.fromString(conditionalDependencies.get(i++)));
            while (i < conditionalDependencies.size()) {
                buf.append(' ').append(ArtifactCoords.fromString(conditionalDependencies.get(i++)));
            }
            props.setProperty(propName, buf.toString());
        }
    }

    private void generateQuarkusExtensionDescriptor(Path outputMetaInfDirectory)
            throws IOException {
        File extensionFile = getInputExtensionDescriptorFile();

        ObjectMapper mapper = getMapper();
        ObjectNode extObject;
        if (extensionFile != null && extensionFile.exists()) {
            extObject = readExtensionFile(extensionFile.toPath(), mapper);
        } else {
            extObject = mapper.createObjectNode();
        }

        computeArtifactCoords(extObject);
        computeProjectName(extObject);
        computeSourceLocation(extObject);
        computeQuarkusCoreVersion(extObject);
        computeQuarkusExtensions(extObject);

        Map<String, String> projectInfo = getProjectInfo().get();
        if (!extObject.has("description") && projectInfo.containsKey("description")) {
            extObject.put("description", projectInfo.get("description"));
        }

        final DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        try (BufferedWriter bw = Files
                .newBufferedWriter(outputMetaInfDirectory.resolve(BootstrapConstants.QUARKUS_EXTENSION_FILE_NAME))) {
            bw.write(getMapper().writer(prettyPrinter).writeValueAsString(extObject));
        } catch (IOException e) {
            throw new GradleException(
                    "Failed to persist " + outputMetaInfDirectory.resolve(BootstrapConstants.QUARKUS_EXTENSION_FILE_NAME), e);
        }
    }

    private void computeProjectName(ObjectNode extObject) {
        Map<String, String> projectInfo = getProjectInfo().get();
        if (!extObject.has("name")) {
            if (projectInfo.containsKey("name")) {
                extObject.put("name", projectInfo.get("name"));
            } else {
                JsonNode node = extObject.get(ARTIFACT_ID);
                String defaultName = node.asText();
                int i = 0;
                if (defaultName.startsWith("quarkus-")) {
                    i = "quarkus-".length();
                }
                final StringBuilder buf = new StringBuilder();
                boolean startWord = true;
                while (i < defaultName.length()) {
                    final char c = defaultName.charAt(i++);
                    if (c == '-') {
                        if (!startWord) {
                            buf.append(' ');
                            startWord = true;
                        }
                    } else if (startWord) {
                        buf.append(Character.toUpperCase(c));
                        startWord = false;
                    } else {
                        buf.append(c);
                    }
                }
                defaultName = buf.toString();
                getLogger().warn("Extension name has not been provided for {}:{}! Using '{}' as the default one.",
                        extObject.get(GROUP_ID).asText(""),
                        extObject.get(ARTIFACT_ID).asText(""),
                        defaultName);
                extObject.put("name", defaultName);
            }
        }
    }

    private void computeArtifactCoords(ObjectNode extObject) {
        Map<String, String> projectInfo = getProjectInfo().get();
        String groupId = null;
        String artifactId = null;
        String version = null;
        final JsonNode artifactNode = extObject.get("artifact");

        if (artifactNode == null) {
            groupId = extObject.has("groupId") ? extObject.get("groupId").asText() : null;
            artifactId = extObject.has("artifactId") ? extObject.get("artifactId").asText() : null;
            version = extObject.has("version") ? extObject.get("version").asText() : null;
        } else {
            final String[] coordsArr = artifactNode.asText().split(":");
            if (coordsArr.length > 0) {
                groupId = coordsArr[0];
                if (coordsArr.length > 1) {
                    artifactId = coordsArr[1];
                    if (coordsArr.length > 2) {
                        version = coordsArr[2];
                    }
                }
            }
        }
        if (artifactNode == null || groupId == null || artifactId == null || version == null) {
            final ArtifactCoords coords = ArtifactCoords.jar(
                    groupId == null ? projectInfo.get("group") : groupId,
                    artifactId == null ? projectInfo.get("name") : artifactId,
                    version == null ? projectInfo.get("version") : version);
            extObject.put("artifact", coords.toString());
        }
    }

    private void computeSourceLocation(ObjectNode extObject) {
        Map<String, String> repo = new ScmInfoProvider(null).getSourceRepo();
        if (repo != null) {
            ObjectNode metadata = getMetadataNode(extObject);

            for (Map.Entry<String, String> e : repo.entrySet()) {
                metadata.put("scm-" + e.getKey(), e.getValue());

            }
        }
    }

    private void computeQuarkusCoreVersion(ObjectNode extObject) {
        String coreVersion = getQuarkusCoreVersion().getOrNull();
        if (coreVersion != null) {
            ObjectNode metadata = getMetadataNode(extObject);
            metadata.put("built-with-quarkus-core", coreVersion);
        }
    }

    private static void appendCapability(Capability capability, StringBuilder buf) {
        buf.append(capability.getName());
        if (!capability.getOnlyIf().isEmpty()) {
            for (String onlyIf : capability.getOnlyIf()) {
                buf.append('?').append(onlyIf);
            }
        }
        if (!capability.getOnlyIfNot().isEmpty()) {
            for (String onlyIfNot : capability.getOnlyIfNot()) {
                buf.append("?!").append(onlyIfNot);
            }
        }
    }

    private static List<String> capabilityInputs(List<Capability> capabilities) {
        List<String> inputs = new ArrayList<>(capabilities.size());
        for (Capability capability : capabilities) {
            StringBuilder input = new StringBuilder();
            appendCapability(capability, input);
            inputs.add(input.toString());
        }
        return inputs;
    }

    private static List<String> removedResourcesInputs(List<RemovedResource> removedResources) {
        List<String> inputs = new ArrayList<>(removedResources.size());
        for (RemovedResource removedResource : removedResources) {
            inputs.add(removedResource.getArtifactName() + "="
                    + String.join(",", removedResource.getRemovedResources()));
        }
        return inputs;
    }

    private File getInputExtensionDescriptorFile() {
        for (File inputResourcesDir : getInputResourcesDirs().getFiles()) {
            File extensionDescriptor = inputResourcesDir.toPath()
                    .resolve(BootstrapConstants.META_INF)
                    .resolve(BootstrapConstants.QUARKUS_EXTENSION_FILE_NAME)
                    .toFile();
            if (extensionDescriptor.exists()) {
                return extensionDescriptor;
            }
        }
        return null;
    }

    private void computeQuarkusExtensions(ObjectNode extObject) {
        ObjectNode metadataNode = getMetadataNode(extObject);
        ArrayNode extensionArray = metadataNode.putArray("extension-dependencies");
        for (String extension : extensionDependencies(getRuntimeArtifactKeysByFilePath().get())) {
            extensionArray.add(extension);
        }
    }

    private static List<String> extensionDependencies(Map<String, String> runtimeArtifactKeysByFilePath) {
        Set<String> extensions = new HashSet<>();
        for (Map.Entry<String, String> artifact : runtimeArtifactKeysByFilePath.entrySet()) {
            Path p = Path.of(artifact.getKey());
            if (Files.isDirectory(p) && isExtension(p)) {
                extensions.add(artifact.getValue());
            } else {
                try (FileSystem fs = ZipUtils.newFileSystem(p)) {
                    if (isExtension(fs.getPath(""))) {
                        extensions.add(artifact.getValue());
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + p, e);
                }
            }
        }
        return extensions.stream().sorted().toList();
    }

    private static List<String> extensionDependencyArtifactKeys(Configuration classpath) {
        return classpath.getResolvedConfiguration().getResolvedArtifacts().stream()
                .filter(resolvedArtifact -> resolvedArtifact.getExtension().equals("jar"))
                .map(ExtensionDescriptorTask::toExtensionDependency)
                .sorted()
                .toList();
    }

    private static Map<String, String> runtimeArtifactKeysByFilePath(Configuration classpath) {
        Map<String, String> runtimeArtifactKeysByFilePath = new TreeMap<>();
        for (ResolvedArtifact resolvedArtifact : classpath.getResolvedConfiguration().getResolvedArtifacts()) {
            if (resolvedArtifact.getExtension().equals("jar")) {
                runtimeArtifactKeysByFilePath.put(resolvedArtifact.getFile().getAbsolutePath(),
                        toExtensionDependency(resolvedArtifact));
            }
        }
        return runtimeArtifactKeysByFilePath;
    }

    private static String toExtensionDependency(ResolvedArtifact extension) {
        ModuleVersionIdentifier id = extension.getModuleVersion().getId();
        return ArtifactKey.of(id.getGroup(), id.getName(), extension.getClassifier(), extension.getExtension())
                .toGacString();
    }

    private static String getQuarkusCoreVersionOrNull(Configuration classpath) {
        for (ResolvedArtifact resolvedArtifact : classpath.getResolvedConfiguration().getResolvedArtifacts()) {
            ModuleVersionIdentifier artifactId = resolvedArtifact.getModuleVersion().getId();
            if (artifactId.getGroup().equals("io.quarkus") && artifactId.getName().equals("quarkus-core")) {
                return artifactId.getVersion();
            }
        }
        return null;
    }

    private static boolean isExtension(Path extensionFile) {
        final Path p = extensionFile.resolve(BootstrapConstants.DESCRIPTOR_PATH);
        return Files.exists(p);
    }

    private ObjectMapper getMapper() {
        YAMLFactory yf = new YAMLFactory();
        return new ObjectMapper(yf)
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
    }

    private ObjectNode getMetadataNode(ObjectNode extObject) {
        JsonNode mvalue = extObject.get(METADATA);
        if (mvalue != null && mvalue.isObject()) {
            return (ObjectNode) mvalue;
        } else {
            return extObject.putObject(METADATA);
        }
    }

    private ObjectNode readExtensionFile(Path extensionFile, ObjectMapper mapper) throws IOException {
        try (InputStream is = Files.newInputStream(extensionFile)) {
            return mapper.readValue(is, ObjectNode.class);
        } catch (IOException io) {
            throw new IOException("Failed to parse " + extensionFile, io);
        }
    }
}
