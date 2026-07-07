package io.quarkus.gradle.application.internal.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quarkus.gradle.application.model.QuarkusApplicationAotEnhancedImageDescriptor;
import io.quarkus.gradle.application.model.QuarkusApplicationImageBuilder;
import io.quarkus.gradle.application.model.QuarkusApplicationImageDescriptor;

class AotEnhancedImagePlannerTest {

    private final AotEnhancedImagePlanner planner = new AotEnhancedImagePlanner();

    @Test
    void defaultsEnhancedImageReferenceToBaseTagWithAotSuffix() {
        var image = new QuarkusApplicationImageDescriptor("quay.io/acme/app", "1.0",
                QuarkusApplicationImageBuilder.JIB);
        var aot = QuarkusApplicationAotEnhancedImageDescriptor.producedBy("build/aot/app.aot", "myIntTest");

        var plan = planner.plan(image, aot);

        assertThat(plan.aotFile()).isEqualTo("build/aot/app.aot");
        assertThat(plan.baseReference()).isEqualTo("quay.io/acme/app:1.0");
        assertThat(plan.enhancedReference()).isEqualTo("quay.io/acme/app:1.0-aot");
        assertThat(plan.hasProducer()).isTrue();
    }

    @Test
    void supportsFullImageReferenceOverride() {
        var image = new QuarkusApplicationImageDescriptor("quay.io/acme/app", "base",
                QuarkusApplicationImageBuilder.JIB);
        var aot = new QuarkusApplicationAotEnhancedImageDescriptor("build/aot/app.aot", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("quay.io/acme/app:enhanced"), "-aot");

        assertThat(planner.plan(image, aot).enhancedReference()).isEqualTo("quay.io/acme/app:enhanced");
    }

    @Test
    void supportsStructuredRepositoryAndTagOverrides() {
        var image = new QuarkusApplicationImageDescriptor("quay.io/acme/app", "base",
                QuarkusApplicationImageBuilder.JIB);
        var aot = new QuarkusApplicationAotEnhancedImageDescriptor("build/aot/app.aot", Optional.empty(),
                Optional.of("quay.io/acme/aot-app"), Optional.of("trained"), Optional.empty(), "-aot");

        assertThat(planner.plan(image, aot).enhancedReference()).isEqualTo("quay.io/acme/aot-app:trained");
    }

    @Test
    void rejectsMissingAotFile() {
        assertThatThrownBy(() -> QuarkusApplicationAotEnhancedImageDescriptor.producedBy("", "myIntTest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AOT file");
    }

    @Test
    void modelsAotFileFromProducerHelper() {
        var descriptor = QuarkusApplicationAotEnhancedImageDescriptor.aotFileFrom("myIntTest", "build/aot/app.aot");

        assertThat(descriptor.aotFile()).isEqualTo("build/aot/app.aot");
        assertThat(descriptor.producer()).contains("myIntTest");
    }

    @Test
    void rejectsContradictoryFullAndStructuredReferenceOverrides() {
        assertThatThrownBy(() -> new QuarkusApplicationAotEnhancedImageDescriptor("build/aot/app.aot", Optional.empty(),
                Optional.of("quay.io/acme/app"), Optional.empty(), Optional.of("quay.io/acme/app:aot"), "-aot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be combined");
    }
}
