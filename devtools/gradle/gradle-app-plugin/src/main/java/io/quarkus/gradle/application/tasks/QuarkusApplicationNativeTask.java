package io.quarkus.gradle.application.tasks;

import java.nio.file.Path;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.gradle.application.internal.nativeimage.NativeResultCodec;

@DisableCachingByDefault(because = "Native image builds are not build-cacheable yet")
public abstract class QuarkusApplicationNativeTask extends QuarkusApplicationBuildTask {

    @Input
    public abstract MapProperty<String, String> getNativeArguments();

    @OutputFile
    public abstract RegularFileProperty getNativeResultFile();

    @TaskAction
    public void buildNativeImage() {
        Path nativeResultFile = getNativeResultFile().get().getAsFile().toPath();
        Path augmentResultFile = nativeResultFile.resolveSibling("native-augmentation-result.properties");
        var result = buildOperations().buildNative(buildRequest(getNativeArguments().get()), augmentResultFile);
        new NativeResultCodec().write(nativeResultFile, result);
    }
}
