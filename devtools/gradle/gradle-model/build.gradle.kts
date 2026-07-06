plugins {
    id("io.quarkus.devtools.java-library")
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
    implementation("org.apache.maven:maven-core")
    gradleApi()
}

group = "io.quarkus"

java {
    withSourcesJar()
    withJavadocJar()
}

// to generate reproducible jars
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    // Required by Gradle's ProjectBuilder on strongly encapsulated JDKs.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = "quarkus-gradle-model"
        from(components["java"])
    }
}
