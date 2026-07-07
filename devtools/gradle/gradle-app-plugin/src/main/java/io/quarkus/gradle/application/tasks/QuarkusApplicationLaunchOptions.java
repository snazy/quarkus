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
package io.quarkus.gradle.application.tasks;

import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;

interface QuarkusApplicationLaunchOptions {

    @Input
    ListProperty<String> getJvmArguments();

    @Input
    ListProperty<String> getApplicationArguments();

    @Option(description = "Set JVM arguments", option = "jvm-args")
    default void setJvmArgs(List<String> jvmArguments) {
        getJvmArguments().set(jvmArguments);
    }

    @Option(description = "Set application arguments", option = "quarkus-args")
    default void setQuarkusArgs(List<String> applicationArguments) {
        getApplicationArguments().set(applicationArguments);
    }
}
