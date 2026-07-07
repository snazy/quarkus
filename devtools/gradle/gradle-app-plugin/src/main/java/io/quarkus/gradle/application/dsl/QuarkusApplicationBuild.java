package io.quarkus.gradle.application.dsl;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.jspecify.annotations.NonNull;

import io.quarkus.gradle.application.model.QuarkusApplicationBuildType;

public abstract class QuarkusApplicationBuild implements Named {

    private final String name;
    private final QuarkusApplicationBuildType buildType;
    private final QuarkusApplicationImage image;
    private final QuarkusApplicationAotEnhancedImage aotEnhancedImage;
    private final QuarkusApplicationDeployments deployments;
    private final List<Action<? super QuarkusApplicationBuild>> aotEnhancedImageConfiguredActions = new ArrayList<>();
    private boolean aotEnhancedImageConfigured;

    protected QuarkusApplicationBuild(String name, QuarkusApplicationBuildType buildType, ObjectFactory objects,
            ProjectLayout layout) {
        this.name = requireNonNull(name, "name");
        this.buildType = requireNonNull(buildType, "buildType");
        this.image = objects.newInstance(QuarkusApplicationImage.class);
        this.aotEnhancedImage = objects.newInstance(QuarkusApplicationAotEnhancedImage.class);
        this.deployments = objects.newInstance(QuarkusApplicationDeployments.class, objects);

        getOutputDirectory().convention(layout.getBuildDirectory().dir("quarkus-builds/" + name + "/package"));
        getQuarkusBuildProperties().convention(Map.of());
        getManifestAttributes().convention(Map.of());
        getNativeArguments().convention(Map.of());
        image.getQuarkusBuildProperties().convention(Map.of());
        aotEnhancedImage.getAotFile().convention(
                layout.getBuildDirectory().file("quarkus-builds/" + name + "/aot/app.aot"));
        aotEnhancedImage.getImageSuffix().convention("-aot");
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    public QuarkusApplicationBuildType getBuildType() {
        return buildType;
    }

    public abstract DirectoryProperty getOutputDirectory();

    public abstract Property<String> getOutputName();

    public abstract Property<String> getArchiveBaseName();

    public abstract Property<String> getArchiveBaseNameSuffix();

    public abstract Property<String> getArchiveVersion();

    public abstract MapProperty<String, String> getQuarkusBuildProperties();

    public abstract MapProperty<String, String> getManifestAttributes();

    public abstract MapProperty<String, String> getNativeArguments();

    public QuarkusApplicationImage getImage() {
        return image;
    }

    public void image(Action<? super QuarkusApplicationImage> action) {
        action.execute(image);
    }

    public QuarkusApplicationAotEnhancedImage getAotEnhancedImage() {
        return aotEnhancedImage;
    }

    public void aotEnhancedImage(Action<? super QuarkusApplicationAotEnhancedImage> action) {
        action.execute(aotEnhancedImage);
        if (!aotEnhancedImageConfigured) {
            aotEnhancedImageConfigured = true;
            aotEnhancedImageConfiguredActions.forEach(callback -> callback.execute(this));
        }
    }

    public QuarkusApplicationDeployments getDeployments() {
        return deployments;
    }

    public void deployments(Action<? super QuarkusApplicationDeployments> action) {
        action.execute(deployments);
    }

    void whenAotEnhancedImageConfigured(Action<? super QuarkusApplicationBuild> action) {
        aotEnhancedImageConfiguredActions.add(action);
        if (aotEnhancedImageConfigured) {
            action.execute(this);
        }
    }
}
