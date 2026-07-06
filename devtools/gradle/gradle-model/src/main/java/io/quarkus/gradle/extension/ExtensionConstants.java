package io.quarkus.gradle.extension;

import org.gradle.api.attributes.Attribute;

public interface ExtensionConstants {
    String EXTENSION_CONFIGURATION_NAME = "quarkusExtension";
    String EXTENSION_DEPLOYMENT_PLUGIN_ID = "io.quarkus.extension.deployment";
    String EXTENSION_DEPLOYMENT_MARKER_ELEMENTS_CONFIGURATION_NAME = "quarkusExtensionDeploymentMarkerElements";
    String EXTENSION_DEPLOYMENT_MARKER_TASK_NAME = "quarkusExtensionDeploymentMarker";
    String EXTENSION_DEPLOYMENT_MARKER_CATEGORY = "quarkus-extension-deployment-marker";
    Attribute<Boolean> EXTENSION_DEPLOYMENT_ATTRIBUTE = Attribute.of(EXTENSION_DEPLOYMENT_PLUGIN_ID, Boolean.class);
    String QUARKUS_ANNOTATION_PROCESSOR = "io.quarkus:quarkus-extension-processor";
}
