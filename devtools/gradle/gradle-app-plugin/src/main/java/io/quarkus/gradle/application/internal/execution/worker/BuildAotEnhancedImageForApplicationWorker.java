package io.quarkus.gradle.application.internal.execution.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.gradle.api.GradleException;

import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.util.PropertyUtils;
import io.quarkus.deployment.cmd.BuildAotEnhancedCustomizerProducer;
import io.quarkus.deployment.cmd.BuildEnhancedAotContainerImageCommandHandler;
import io.quarkus.deployment.pkg.builditem.BuildAotOptimizedContainerImageResultBuildItem;

public abstract class BuildAotEnhancedImageForApplicationWorker
        extends QuarkusWorker<BuildAotEnhancedImageForApplicationWorkerParams> {

    @Override
    public void execute() {
        BuildAotEnhancedImageForApplicationWorkerParams params = getParameters();
        Path aotFile = params.getAotFile().getAsFile().get().toPath();

        try (CuratedApplication curatedApplication = createAppCreationContext()) {
            Map<String, Object> context = Map.of(
                    "original-container-image", params.getOriginalContainerImage().get(),
                    "container-working-directory", params.getContainerWorkingDirectory().get(),
                    "aot-file", aotFile);

            AugmentAction action = curatedApplication.createAugmentor(
                    BuildAotEnhancedCustomizerProducer.class.getName(),
                    context);
            AtomicReference<Map<String, String>> resultReference = new AtomicReference<>(Map.of());
            action.performCustomBuild(
                    BuildEnhancedAotContainerImageCommandHandler.class.getName(),
                    new Consumer<Map<String, String>>() {
                        @Override
                        public void accept(Map<String, String> result) {
                            resultReference.set(result);
                        }
                    },
                    BuildAotOptimizedContainerImageResultBuildItem.class.getName());
            writeResult(params.getAotImageResultFile().get().getAsFile().toPath(), resultReference.get());
        } catch (BootstrapException e) {
            throw new GradleException("Failed to build AOT enhanced container image: " + e.getMessage(), e);
        }
    }

    private static void writeResult(Path resultFile, Map<String, String> result) {
        try {
            if (resultFile.getParent() != null) {
                Files.createDirectories(resultFile.getParent());
            }
            PropertyUtils.store(result, resultFile);
        } catch (IOException e) {
            throw new GradleException("Failed to write AOT enhanced image result " + resultFile, e);
        }
    }
}
