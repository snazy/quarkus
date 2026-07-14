# Gradle-Native Application Run Task Design

Status: draft design
Last reviewed: 2026-07-13

## Decision Summary

Add useful run tasks to the new `io.quarkus.application` Gradle plugin by
modeling run as a launch operation over a named runnable JAR package output.

Current direction:

- register `quarkus<BuildName>Run` only for named build types that emit runnable
  JVM JAR outputs;
- do not register run tasks for native executable or native sources builds;
- make each run task depend on its corresponding named package task;
- use `QuarkusBootstrap.Mode.RUN` to derive the launch command rather than
  manually constructing `java -jar ...`;
- execute the `Mode.RUN` augmentation on every run invocation, because Dev
  Services and extension-provided run commands are live state and must not be
  precomputed or cached;
- launch the selected command as a foreground process with explicit stdout,
  stderr, stdin, cancellation, and shutdown behavior.

This keeps the new plugin compatible with the legacy `quarkusRun` semantics
where they matter, without introducing run tasks for package shapes that the
run command model does not naturally support.

The legacy plugin is a behavioral reference only. The implementation should
reuse the new application's plugin task, request, worker, config, and process
infrastructure wherever possible instead of moving code from the legacy
`gradle-application-plugin`.

## Background

The legacy `io.quarkus` plugin registers `quarkusRun` as a task that depends on
`quarkusBuild`. Its task action opens a `CuratedApplication` with
`QuarkusBootstrap.Mode.RUN`, performs a custom build using
`StartDevServicesAndRunCommandHandler`, selects a run command, and launches that
command.

`Mode.RUN` is not an artifact-producing package build. It runs Quarkus
augmentation in `LaunchMode.RUN` and returns run-command data:

- command name, such as `java`;
- argument list, such as `java -D... -jar <package-output>/quarkus-app/quarkus-run.jar`;
- optional working directory;
- optional startup/log metadata for extension-provided run targets;
- Dev Services launcher config injected into the Java command by the handler.

The built-in Java command computes the expected runnable JAR path from
`PackageConfig` and `OutputTargetBuildItem`. It assumes the runnable package
output already exists. It does not itself produce a package result, JAR receipt,
or native executable.

## Goals

- Provide useful run tasks for named JVM package builds.
- Preserve extension-aware run command selection from Quarkus core.
- Preserve Dev Services startup and runtime-config injection behavior.
- Reuse the new plugin's build-request, effective-config, worker-operation, and
  process-lifecycle patterns.
- Keep the task model explicit and compatible with Gradle configuration cache
  and isolated projects.
- Keep run task behavior separate from dev mode, remote dev, continuous testing,
  image builds, and deployment.

## Non-Goals

- Do not implement run tasks for native executable or native sources outputs.
- Do not implement a generic `quarkusApplicationRun` that guesses a package
  shape or depends on all named builds.
- Do not cache or precompute run command receipts across invocations.
- Do not make run tasks cacheable or up-to-date-skippable.
- Do not implement Gradle-native dev mode, remote dev, or continuous testing as
  part of this work.
- Do not move or directly reuse implementation classes from the legacy
  `gradle-application-plugin`.
- Do not manually duplicate Quarkus' run-command selection rules in Gradle.

## Internal Reuse

The run implementation should be shaped like other new-plugin operations:

- reuse `QuarkusApplicationBuildTask.buildRequest(...)` and
  `effectiveConfig(...)` by making `QuarkusApplicationRunTask` a build-context
  task;
- reuse `BuildRequest` as the core request payload for run augmentation;
- add a run-specific operation boundary, either as `BuildOperations.run(...)`
  or as a sibling `RunOperations`, so unit tests can inject a cheap fake;
- reuse `WorkerBackedBuildOperations` bootstrap helpers where they are already
  internal to the new plugin, such as app-model deserialization, build-system
  property handling, base-name selection, target-directory selection, and
  worker process isolation;
- reuse the package task's provider-backed output directory and package result
  file for Gradle task wiring;
- reuse the new plugin's dev-process lifecycle ideas only after extracting a
  generic helper that is not coupled to dev-mode output prefixes or disabled
  console input.

Legacy `QuarkusRun` remains useful to confirm behavior, but it should not become
the implementation source. In particular, legacy run's SmallRye process API and
legacy dev's `ExecOperations` path are not automatically preferred over a
new-plugin process helper.

## Task Model

For every named build whose `QuarkusApplicationBuildType` emits a runnable JAR,
register a run task:

- `fastJar("fastJar")` registers `quarkusFastJarRun`;
- `uberJar("uberJar")` registers `quarkusUberJarRun`;
- `legacyJar(...)` and `mutableJar(...)` builds also register run tasks;
- native executable and native sources builds do not register run tasks.

Each run task depends on the corresponding package task, for example:

```text
quarkusFastJarRun -> quarkusFastJarBuild
```

This dependency is intentional. The run command points at the package output
layout and expects the runnable JAR to exist.

The run task should use the package task's output directory as the Quarkus
`OutputTargetBuildItem` target. It should not use a separate
`build/quarkus-builds/<build>/run` output directory unless a future design makes
the run task perform its own package build.

## Execution Flow

The run task action should:

1. Build an explicit request from task inputs and the selected named build.
2. Open a `CuratedApplication` with the named build's application model.
3. Configure `QuarkusBootstrap.Mode.RUN`.
4. Set the target directory and base name to match the named package output.
5. Pass the same effective build-system properties needed to describe the named
   build shape.
6. Perform the custom build with a new-plugin-owned run result handler that
   extracts run commands and handles Dev Services config/startup.
7. Select a command:
   - use `quarkus.run.target` when configured;
   - otherwise follow the legacy selection rule for one command, or two commands
     where `java` is the default and the other is preferred.
8. Inject task-provided JVM arguments into Java commands after the executable
   and before `-jar`.
9. Append task-provided application arguments to the selected command.
10. Launch the command as a foreground process.
11. Wait for the process to exit.
12. Close the `CuratedApplication` so Quarkus shutdown tasks run.

The selected command should be derived from Quarkus augmentation every time the
task runs. This is necessary because the command may include fresh Dev Services
config such as ports, credentials, or container-specific connection details.

## Dev Services Lifecycle

Dev Services config is computed during `Mode.RUN` augmentation, before the
command is returned. The legacy `StartDevServicesAndRunCommandHandler` consumes
`DevServicesLauncherConfigResultBuildItem` and injects those properties into
the Java command, but it does not itself start newer registry-backed owned Dev
Services.

The new plugin should therefore use a small new-plugin-owned result handler
instead of relying directly on the legacy handler. That handler should combine
run-command extraction with the native/integration-test pattern for
`DevServicesRegistryBuildItem.startAll(...)`, when a registry is present, and
then inject the combined Dev Services config into the selected command.

Owned Dev Services are registered through Quarkus close tasks attached via
`CuratedApplicationShutdownBuildItem`. When the run task closes the
`CuratedApplication`, the Quarkus classloader close tasks run and owned Dev
Services are stopped.

Discovered or shared Dev Services are not necessarily owned by the current run
task and must not be assumed to stop when the task exits.

The Gradle implementation must make `CuratedApplication.close()` run in normal
completion, task failure, and cancellation paths. A hard Gradle/JVM kill may
still bypass normal cleanup, which matches the general limitation of process
and container shutdown hooks.

## Process I/O And Cancellation

The run process should behave like a foreground application launched from
Gradle:

- forward child stdout to the Gradle console;
- forward child stderr to the Gradle console;
- attach Gradle-owned stdin with `ProcessBuilder.Redirect.INHERIT`;
- handle non-interactive stdin without blocking indefinitely;
- on task cancellation or Gradle shutdown, request graceful process
  termination, wait for a bounded grace period, then terminate forcibly if
  needed;
- after the process exits or is terminated, close the `CuratedApplication` to
  stop owned Dev Services.

The legacy run task uses SmallRye `ProcessBuilder`, does not bridge stdin, and
uses a JVM shutdown hook to stop the child. Legacy dev mode uses Gradle
`ExecOperations` with `System.in`, while the new plugin's Gradle-native dev
launcher uses Java `ProcessBuilder`, output pumps, bounded shutdown, and
explicitly disables Quarkus console input.

The new run task should choose the smallest new-plugin-owned foreground process
helper that satisfies the run requirements. Stdin passthrough is a deliberate
new-plugin behavior choice, not strict legacy `quarkusRun` parity.

## Inputs And Outputs

The run task should be non-cacheable and should not declare reusable outputs.

Inputs should include at least:

- named build descriptor and build type;
- package output directory and output name;
- application model file;
- runtime classpath where required by the bootstrap path;
- source/resource directories used for effective config resolution;
- declared Gradle/system/environment config inputs from the shared config-input
  model;
- run target selection, if exposed as a Gradle property;
- JVM arguments passed to the launched Java command;
- application arguments appended to the selected command;
- working directory.

The task may write local diagnostic state in the future, but that state must not
be treated as a reusable command cache.

## API Shape

`QuarkusApplicationRunTask` should become the concrete implementation for named
JAR run tasks.

Candidate task properties:

- `jvmArguments`: `ListProperty<String>`, plus a `--jvm-args` option matching
  legacy behavior where feasible;
- `applicationArguments`: `ListProperty<String>`, appended to the selected run
  command after Quarkus and extension-provided arguments, exposed as
  `--quarkus-args` for consistency with the legacy dev task;
- `workingDirectory`: `DirectoryProperty`;
- `runTarget`: optional `Property<String>` with a convention from
  `providers.systemProperty("quarkus.run.target")`;
- package result or package output providers needed to connect the run task to
  its named package task.

Because `runTarget` participates in task behavior, prefer a modeled task
property whose convention reads `providers.systemProperty("quarkus.run.target")`
over reading the system property directly during execution.

`QuarkusApplicationContinuousTestTask`, `QuarkusApplicationDevTask`, and
`QuarkusApplicationRemoteDevTask` should remain separate concerns.

## Test Plan

Unit and ProjectBuilder coverage:

- run tasks are registered for JAR-emitting build types only;
- run tasks are not registered for native executable or native sources builds;
- each run task depends on the matching named package task;
- task properties are provider-backed and do not expose live Gradle model state;
- command-selection edge cases match legacy behavior.

TestKit coverage:

- a tiny fast-jar application can be built and run through the named run task;
- an uber-jar named build can be built and run through the named run task;
- configuration cache works for the run task configuration phase, while the run
  task still executes on each invocation;
- isolated-projects smoke coverage for a multi-project application;
- `--jvm-args` are inserted into the Java command;
- application arguments are appended to the selected command;
- `quarkus.run.target` selection is honored;
- stdout/stderr forwarding works;
- task cancellation terminates the child process and closes the curated
  application.

Gated or optional coverage:

- Dev Services start and stop around a run task that requires a container;
- extension-provided non-Java run command selection, such as Azure Functions.

## Deferred Follow-Ups

- A richer public run-task DSL, if users need more than JVM arguments,
  application arguments, working directory, and run target.
- Command receipt diagnostics for debugging only. These receipts must not be
  used to skip `Mode.RUN` augmentation because Dev Services state is live.
- Revisit stdin handling if `ProcessBuilder.Redirect.INHERIT` proves
  insufficient in daemon or non-daemon builds.
