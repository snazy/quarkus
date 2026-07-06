package io.quarkus.extension.gradle;

import java.util.List;

import org.gradle.api.Action;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import io.quarkus.extension.gradle.dsl.Capabilities;
import io.quarkus.extension.gradle.dsl.Capability;
import io.quarkus.extension.gradle.dsl.RemovedResource;
import io.quarkus.extension.gradle.dsl.RemovedResources;

public abstract class QuarkusExtensionConfiguration {
    private final RemovedResources removedResources = new RemovedResources();
    private final Capabilities capabilities = new Capabilities();

    public QuarkusExtensionConfiguration() {
        getDisableValidation().convention(false);
        getDeploymentModule().convention("deployment");
    }

    public abstract Property<Boolean> getDisableValidation();

    public abstract Property<String> getDeploymentArtifact();

    public abstract Property<String> getDeploymentModule();

    public abstract ListProperty<String> getExcludedArtifacts();

    public abstract ListProperty<String> getParentFirstArtifacts();

    public abstract ListProperty<String> getRunnerParentFirstArtifacts();

    public abstract ListProperty<String> getLesserPriorityArtifacts();

    public abstract ListProperty<String> getConditionalDependencies();

    public abstract ListProperty<String> getConditionalDevDependencies();

    public abstract ListProperty<String> getDependencyConditions();

    public List<Capability> getProvidedCapabilities() {
        return capabilities.getProvidedCapabilities();
    }

    public List<Capability> getRequiredCapabilities() {
        return capabilities.getRequiredCapabilities();
    }

    @SuppressWarnings("unused")
    public void capabilities(Action<Capabilities> capabilitiesAction) {
        capabilitiesAction.execute(this.capabilities);
    }

    public List<RemovedResource> getRemoveResources() {
        return removedResources.getRemovedResources();
    }

    @SuppressWarnings("unused")
    public void removedResources(Action<RemovedResources> removedResourcesAction) {
        removedResourcesAction.execute(this.removedResources);
    }
}
