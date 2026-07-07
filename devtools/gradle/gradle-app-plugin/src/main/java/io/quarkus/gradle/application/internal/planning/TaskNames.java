package io.quarkus.gradle.application.internal.planning;

public record TaskNames(String build, String run, String imageBuild, String imagePush,
        String aotTraining, String aotEnhancedImageBuild, String aotEnhancedImagePush, String nativeTest) {
}
