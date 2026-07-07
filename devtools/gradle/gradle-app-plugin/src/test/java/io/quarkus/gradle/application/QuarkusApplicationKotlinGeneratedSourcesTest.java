/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.quarkus.gradle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusApplicationKotlinGeneratedSourcesTest {

    @TempDir
    Path testProjectDir;

    @Test
    void compilesKotlinMainAndTestAgainstGeneratedSourcesWhenKotlinIsAppliedBeforeApplicationPlugin() throws IOException {
        writeKotlinApplication(true, false);

        BuildResult result = runner("compileTestKotlin").build();

        assertGeneratedKotlinCompilation(result);
    }

    @Test
    void compilesKotlinMainAndTestAgainstGeneratedSourcesWhenKotlinIsAppliedAfterApplicationPlugin() throws IOException {
        writeKotlinApplication(false, false);

        BuildResult result = runner("compileTestKotlin").build();

        assertGeneratedKotlinCompilation(result);
    }

    @Test
    void compilesKaptMainAndTestStubsAgainstGeneratedSourcesWhenKaptIsAppliedBeforeApplicationPlugin()
            throws IOException {
        writeKotlinApplication(true, true);

        BuildResult result = runner("kaptGenerateStubsKotlin", "kaptGenerateStubsTestKotlin").build();

        assertGeneratedKaptStubs(result);
    }

    @Test
    void compilesKaptMainAndTestStubsAgainstGeneratedSourcesWhenKaptIsAppliedAfterApplicationPlugin()
            throws IOException {
        writeKotlinApplication(false, true);

        BuildResult result = runner("kaptGenerateStubsKotlin", "kaptGenerateStubsTestKotlin").build();

        assertGeneratedKaptStubs(result);
    }

    private void writeKotlinApplication(boolean kotlinBeforeApplicationPlugin, boolean kapt) throws IOException {
        String kotlinVersion = System.getProperty("kotlin_version", "2.4.0");
        writeString(testProjectDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        mavenCentral()
                        gradlePluginPortal()
                    }
                    plugins {
                        id 'org.jetbrains.kotlin.jvm' version '%1$s'
                        id 'org.jetbrains.kotlin.kapt' version '%1$s'
                    }
                }

                rootProject.name = 'kotlin-generated-sources'
                """.formatted(kotlinVersion));
        writeString(testProjectDir.resolve("build.gradle"), buildFile(kotlinBeforeApplicationPlugin, kapt, kotlinVersion));
        writeString(testProjectDir.resolve("src/main/kotlin/org/acme/KotlinMain.kt"), """
                package org.acme

                import org.acme.generated.GeneratedMain

                class KotlinMain {
                    fun value(): String = GeneratedMain.value()
                }
                """);
        writeString(testProjectDir.resolve("src/test/kotlin/org/acme/KotlinTestUsage.kt"), """
                package org.acme

                import org.acme.generated.GeneratedTest

                class KotlinTestUsage {
                    fun value(): String = KotlinMain().value() + GeneratedTest.value()
                }
                """);
        if (kapt) {
            writeString(testProjectDir.resolve("src/main/kotlin/org/acme/KaptMain.kt"), """
                    package org.acme

                    import org.acme.generated.GeneratedMain

                    @Deprecated(GeneratedMain.VALUE)
                    class KaptMain(val generated: GeneratedMain)
                    """);
            writeString(testProjectDir.resolve("src/test/kotlin/org/acme/KaptTestUsage.kt"), """
                    package org.acme

                    import org.acme.generated.GeneratedTest

                    @Deprecated(GeneratedTest.VALUE)
                    class KaptTestUsage(val generated: GeneratedTest)
                    """);
        }
    }

    private static String buildFile(boolean kotlinBeforeApplicationPlugin, boolean kapt, String kotlinVersion) {
        String kotlinPlugin = "    id 'org.jetbrains.kotlin.jvm'\n";
        String kaptPlugin = kapt ? "    id 'org.jetbrains.kotlin.kapt'\n" : "";
        String applicationPlugin = "    id 'io.quarkus.application'\n";
        String plugins = kotlinBeforeApplicationPlugin
                ? kotlinPlugin + kaptPlugin + applicationPlugin
                : applicationPlugin + kotlinPlugin + kaptPlugin;
        return """
                import java.nio.file.Files

                plugins {
                %1$s}

                version = '1.0'

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation 'org.jetbrains.kotlin:kotlin-stdlib:%2$s'
                }

                def generatedSourceRoot = 'generated/sources/quarkus-application'
                def java = extensions.getByType(org.gradle.api.plugins.JavaPluginExtension)
                if (java.sourceSets.named('main').get().java.srcDirs.any {
                    it.path.replace(File.separator, '/').contains(generatedSourceRoot)
                }) {
                    throw new GradleException('main source set must not contain Quarkus generated sources')
                }
                if (java.sourceSets.named('test').get().java.srcDirs.any {
                    it.path.replace(File.separator, '/').contains(generatedSourceRoot)
                }) {
                    throw new GradleException('test source set must not contain Quarkus generated sources')
                }
                def assertGeneratedSourceWiring = { task, String sourceSegment ->
                    def generatedSourcePath = "${generatedSourceRoot}/${sourceSegment}"
                    if (!task.inputs.files.files.any {
                        it.path.replace(File.separator, '/').contains(generatedSourcePath)
                    }) {
                        throw new GradleException("${task.path} must include ${generatedSourcePath}")
                    }
                }
                ['compileKotlin', 'kaptGenerateStubsKotlin'].each { taskName ->
                    tasks.matching { it.name == taskName }.configureEach {
                        doFirst {
                            assertGeneratedSourceWiring(it, 'main')
                        }
                    }
                }
                ['compileTestKotlin', 'kaptGenerateStubsTestKotlin'].each { taskName ->
                    tasks.matching { it.name == taskName }.configureEach {
                        doFirst {
                            assertGeneratedSourceWiring(it, 'test')
                        }
                    }
                }

                tasks.named('quarkusApplicationGenerateCode').configure {
                    doLast {
                        def sourcePackage = generatedOutputDirectory.get().asFile.toPath().resolve('org/acme/generated')
                        Files.createDirectories(sourcePackage)
                        Files.writeString(sourcePackage.resolve('GeneratedMain.java'), '''
                            package org.acme.generated;

                            public final class GeneratedMain {
                                public static final String VALUE = "main";

                                public static String value() {
                                    return VALUE;
                                }
                            }
                            '''.stripIndent())
                    }
                }
                tasks.named('quarkusApplicationGenerateTestCode').configure {
                    doLast {
                        def sourcePackage = generatedOutputDirectory.get().asFile.toPath().resolve('org/acme/generated')
                        Files.createDirectories(sourcePackage)
                        Files.writeString(sourcePackage.resolve('GeneratedTest.java'), '''
                            package org.acme.generated;

                            public final class GeneratedTest {
                                public static final String VALUE = "test";

                                public static String value() {
                                    return GeneratedMain.value() + "-" + VALUE;
                                }
                            }
                            '''.stripIndent())
                    }
                }
                """.formatted(plugins, kotlinVersion);
    }

    private GradleRunner runner(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments(tasks));
    }

    private static String[] arguments(String... tasks) {
        return Stream.concat(
                Arrays.stream(tasks),
                Stream.of(
                        "--configuration-cache",
                        "-Dorg.gradle.unsafe.isolated-projects=true",
                        "--stacktrace"))
                .toArray(String[]::new);
    }

    private static void assertGeneratedKotlinCompilation(BuildResult result) {
        assertThat(result.task(":quarkusApplicationCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationTestCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationGenerateCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationGenerateTestCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":compileKotlin").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":compileTestKotlin").getOutcome()).isEqualTo(SUCCESS);
    }

    private static void assertGeneratedKaptStubs(BuildResult result) {
        assertThat(result.task(":quarkusApplicationCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationTestCodegenModel").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationGenerateCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":quarkusApplicationGenerateTestCode").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":kaptGenerateStubsKotlin").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.task(":kaptGenerateStubsTestKotlin").getOutcome()).isEqualTo(SUCCESS);
    }

    private static void writeString(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
