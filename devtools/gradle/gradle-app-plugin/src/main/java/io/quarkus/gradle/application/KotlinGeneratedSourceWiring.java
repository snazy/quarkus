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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

import io.quarkus.gradle.application.tasks.QuarkusApplicationGenerateCodeTask;

final class KotlinGeneratedSourceWiring {

    private KotlinGeneratedSourceWiring() {
    }

    static void wireKotlinCompileTasks(Project project,
            TaskProvider<QuarkusApplicationGenerateCodeTask> generateCode,
            TaskProvider<QuarkusApplicationGenerateCodeTask> generateTestCode) {
        wireCompileTask(project, "compileKotlin", generateCode);
        wireCompileTask(project, "compileTestKotlin", generateTestCode);
    }

    static void wireKaptStubTasks(Project project,
            TaskProvider<QuarkusApplicationGenerateCodeTask> generateCode,
            TaskProvider<QuarkusApplicationGenerateCodeTask> generateTestCode) {
        wireKaptStubTask(project, "kaptGenerateStubsKotlin", generateCode);
        wireKaptStubTask(project, "kaptGenerateStubsTestKotlin", generateTestCode);
    }

    private static void wireCompileTask(Project project, String taskName,
            TaskProvider<QuarkusApplicationGenerateCodeTask> generateTask) {
        project.getTasks().matching(task -> task.getName().equals(taskName))
                .configureEach(task -> addGeneratedSources(task, generateTask));
    }

    private static void wireKaptStubTask(Project project, String taskName,
            TaskProvider<QuarkusApplicationGenerateCodeTask> generateTask) {
        project.getTasks().matching(task -> task.getName().equals(taskName))
                .configureEach(task -> addGeneratedSources(task, generateTask));
    }

    private static void addGeneratedSources(Task task,
            TaskProvider<QuarkusApplicationGenerateCodeTask> generateTask) {
        task.dependsOn(generateTask);
        Object generatedSourceDirectory = generateTask
                .flatMap(QuarkusApplicationGenerateCodeTask::getGeneratedOutputDirectory);
        try {
            Method sourceMethod = task.getClass().getMethod("source", Object[].class);
            sourceMethod.invoke(task, (Object) new Object[] { generatedSourceDirectory });
        } catch (NoSuchMethodException e) {
            throw new GradleException("Kotlin task '" + task.getName() + "' does not expose source(Object...)", e);
        } catch (IllegalAccessException e) {
            throw new GradleException("Cannot configure generated sources for Kotlin task '" + task.getName() + "'", e);
        } catch (InvocationTargetException e) {
            throw new GradleException("Kotlin task '" + task.getName() + "' rejected Quarkus generated sources",
                    e.getCause());
        }
    }
}
