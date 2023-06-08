/*
 * Copyright (C) 2023 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.quarkus.gradle.dependency;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaTestFixturesPlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testing.base.TestSuite;
import org.gradle.testing.base.TestingExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME;
import static org.gradle.api.tasks.SourceSet.TEST_SOURCE_SET_NAME;

public class GradleProjectSourceSets {

    private final Map<String, SourceSet> allSourceSets;

    public GradleProjectSourceSets(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
        TestingExtension testingExtension = project.getExtensions().findByType(TestingExtension.class);

        Map<String, SourceSet> allSourceSets = new LinkedHashMap<>();

        SourceSet mainSourceSet = sourceSets.findByName(MAIN_SOURCE_SET_NAME);

        if (mainSourceSet != null) {
            allSourceSets.put(mainSourceSet.getName(), mainSourceSet);
        }

        if (project.getPlugins().hasPlugin(JavaTestFixturesPlugin.class)) {
            SourceSet testFixtures = sourceSets.getByName("testFixtures");
            allSourceSets.put(testFixtures.getName(), testFixtures);
        }

        if (testingExtension != null) {
            for (TestSuite suite : testingExtension.getSuites()) {
                if (suite instanceof JvmTestSuite) {
                    JvmTestSuite jvmTestSuite = (JvmTestSuite) suite;
                    SourceSet jvmTestSources = jvmTestSuite.getSources();
                    allSourceSets.put(jvmTestSources.getName(), jvmTestSources);
                }
            }
        } else {
            SourceSet testSourceSet = sourceSets.findByName(TEST_SOURCE_SET_NAME);
            if (testSourceSet != null) {
                allSourceSets.put(testSourceSet.getName(), testSourceSet);
            }
        }

        this.allSourceSets = allSourceSets;
    }

    public SourceSet getMainSourceSet() {
        return getSourceSet(MAIN_SOURCE_SET_NAME);
    }

    public SourceSet getTestSourceSet() {
        return getSourceSet(TEST_SOURCE_SET_NAME);
    }

    public SourceSet getSourceSet(String name) {
        return allSourceSets.get(name);
    }

    public Iterable<SourceSet> getAllSourceSets() {
        return allSourceSets.values();
    }
}

