package io.quarkus.gradle.application.model;

import org.gradle.api.attributes.Attribute;

public final class QuarkusApplicationVariantAttributes {

    public static final String PACKAGE_CATEGORY = "quarkus-application-package";
    public static final Attribute<String> BUILD_NAME_ATTRIBUTE = Attribute.of("io.quarkus.application.build-name",
            String.class);
    public static final Attribute<String> BUILD_TYPE_ATTRIBUTE = Attribute.of("io.quarkus.application.build-type",
            String.class);

    private QuarkusApplicationVariantAttributes() {
    }
}
