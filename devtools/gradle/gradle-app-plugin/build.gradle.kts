plugins {
    id("io.quarkus.devtools.gradle-plugin")
}

dependencies {
    implementation(project(":gradle-model"))
    implementation("io.quarkus:quarkus-analytics-common")
    implementation(libs.smallrye.config.yaml)

    testImplementation(testFixtures(project(":gradle-model")))
}

group = "io.quarkus.application"

gradlePlugin {
    plugins.create("quarkusApplicationPlugin") {
        id = "io.quarkus.application"
        implementationClass = "io.quarkus.gradle.application.QuarkusApplicationPlugin"
        displayName = "Quarkus Application Plugin"
        description = "Builds explicit named Quarkus application outputs with a Gradle-native task model"
        tags.addAll("quarkus", "quarkusio", "graalvm")
    }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(project(":gradle-extension-plugin").sourceSets.main.get().runtimeClasspath)
    pluginClasspath.from(project(":gradle-extension-deployment-plugin").sourceSets.main.get().runtimeClasspath)
}
