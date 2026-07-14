# Gradle-Native Application Run Task Implementation Plan

Status: implemented; archived completion record
Design: `../quarkus-run-task-design.md`
Last reviewed: 2026-07-13

## Objective

Implement named run tasks for the new `io.quarkus.application` Gradle plugin.
Run tasks should exist only for named JVM package builds, depend on the matching
package task, perform fresh `QuarkusBootstrap.Mode.RUN` augmentation on every
execution, and launch the selected run command against the already-built package
output.

## Scope

In scope:

- `quarkus<BuildName>Run` for build types where
  `QuarkusApplicationBuildType.isJar()` is true;
- no run task for `NATIVE_EXECUTABLE` or `NATIVE_SOURCES`;
- new-plugin-owned run request/operation abstractions;
- Quarkus run-command extraction through `Mode.RUN`;
- registry-backed Dev Services startup when a registry build item is present;
- foreground process launch with explicit output, stdin, cancellation, and
  cleanup behavior;
- ProjectBuilder/unit coverage and focused TestKit coverage.

Out of scope:

- generic `quarkusApplicationRun`;
- dev mode, remote dev, or continuous testing;
- direct native executable launch;
- caching/precomputing run commands;
- moving legacy plugin implementation code.

## Implementation Steps

### 1. Model Run Requests And Commands

Add internal run model types under
`io.quarkus.gradle.application.internal.execution`:

- `RunRequest`
  - `BuildRequest build`;
- package result file path;
  - `Optional<String> runTarget`;
  - `List<String> jvmArguments`;
  - `List<String> applicationArguments`;
  - working directory path.
- `RunCommand`
  - command name;
  - argument list;
  - optional working directory;
  - optional started expression/log metadata if preserved for extension
    commands.

Add unit tests for defensive copies and required fields, following the existing
`ExecutionRequestTest` style.

### 2. Add A Run Operation Boundary

Add `void run(RunRequest request)` to `BuildOperations`. This keeps
build/codegen/run tests consistent: task tests can inject a recording
`BuildOperations`.

Update production `WorkerBackedBuildOperations` with a `run(...)` method that:

- deserializes the app model through the existing helper path;
- builds a `QuarkusBootstrap` in `Mode.RUN`;
- sets the target directory and base name from the named package output;
- uses the request build-system properties;
- performs a custom build with a new run result handler;
- selects a command and launches it through a new-plugin-owned foreground
  process helper.

Keep the run operation non-cacheable and task-local. Do not write a reusable
command receipt.

### 3. Implement Run Result Handling

Add an internal result handler class loaded by Quarkus augmentation, analogous
in shape to the native/integration-test Dev Services handler but scoped to run.

Responsibilities:

- consume `RunCommandActionResultBuildItem`;
- consume `DevServicesLauncherConfigResultBuildItem`;
- consume `DevServicesRegistryBuildItem`, `DevServicesCustomizerBuildItem`, and
  `DevServicesAdditionalConfigBuildItem` when present;
- call `DevServicesRegistryBuildItem.startAll(...)` when a registry is present;
- merge launcher config and started-service config;
- inject merged Dev Services config into Java `-jar` commands before `-jar`;
- return only plain JDK collection data across classloader boundaries.

The custom build should request final outputs needed by the native-test pattern,
including:

- `RunCommandActionResultBuildItem`;
- `DevServicesLauncherConfigResultBuildItem`;
- `DevServicesRegistryBuildItem`;
- `DevServicesCustomizerBuildItem`;
- `DevServicesNetworkIdBuildItem`.

Add focused unit tests for command selection and config injection outside the
augmentation path. Cover the augmentation handler through TestKit or a targeted
integration-style test rather than trying to unit-test Quarkus augmentation
itself.

### 4. Implement Command Selection

Add a new-plugin-owned command selector that mirrors legacy behavior:

- if `runTarget` is set and exists, select that command;
- if `runTarget` is set and absent, fail with a clear `GradleException`;
- if one command exists, select it;
- if two commands exist and one is `java`, select the non-`java` command;
- if more than two commands exist, fail with a clear message listing command
  names and recommending `quarkus.run.target`.

Prefer failing over logging-and-returning. A selected run task that cannot
select a command should fail because no application was run.

After selecting the command:

- insert `jvmArguments` only for Java commands, after the executable and before
  `-jar`;
- append `applicationArguments` to the selected command after Quarkus and
  extension-provided arguments;
- apply the same append behavior for non-Java extension commands. If a command
  cannot accept trailing arguments, users should not configure
  `applicationArguments` for that run target.

### 5. Implement Foreground Process Launch

Add a small internal process helper, separate from dev-mode classes, that:

- launches the selected command in the selected or task working directory;
- forwards stdout and stderr to Gradle-visible output;
- attaches stdin with `ProcessBuilder.Redirect.INHERIT`;
- on cancellation or JVM shutdown, calls `destroy()`, waits a bounded grace
  period, then calls `destroyForcibly()` and waits again;
- joins output pump threads with bounded waits;
- returns or throws based on process exit code.

Use Java `ProcessBuilder`. Do not use `Project.exec()` inside the task action.

### 6. Convert The Run Task

Change `QuarkusApplicationRunTask` from a reserved `QuarkusApplicationLaunchTask`
stub to a concrete build-context task by extending `QuarkusApplicationBuildTask`.

Add properties:

- `ListProperty<String> getJvmArguments()` with `--jvm-args`;
- `ListProperty<String> getApplicationArguments()` with a command-line option
  `--quarkus-args`;
- `DirectoryProperty getWorkingDirectory()`;
- optional `Property<String> getRunTarget()` conventioned from
  `providers.systemProperty("quarkus.run.target")`;
- `RegularFileProperty getPackageResultFile()`.

The task action should:

- create a `BuildRequest` with no additional operation-forced properties;
- require/read the package result file before launching so a missing package
  receipt fails with a clear message;
- create a `RunRequest`;
- delegate to `buildOperations().run(request)`.

Keep continuous-test, dev, and remote-dev task stubs separate.

### 7. Update Task Registration

In `TaskRegistration`:

- register named run tasks only when `buildRegistration.type().isJar()`;
- do not register run tasks for native executable or native sources builds;
- make each run task depend on the matching named package task;
- wire run task `outputDirectory` to the build's package output directory;
- wire `packageResultFile` from the matching package task result path;
- set working directory convention to the project directory;
- update descriptions to say the task runs the named packaged application.

Review task name collision validation in `TaskNamePlanner` so native builds do
not reserve nonexistent run task names.

### 8. Tests

Unit tests:

- `RunRequest` validation/defensive copies;
- command selection edge cases;
- JVM argument insertion and application argument appending;
- Dev Services config injection around Java `-jar`;
- process helper behavior where it can be tested deterministically.

ProjectBuilder tests:

- JAR builds register run tasks;
- native builds do not register run tasks;
- run tasks depend on matching package tasks;
- run task properties are wired from package output/result providers;
- reserved continuous-test/remote-dev behavior remains unchanged.

TestKit tests:

- tiny fast-jar named run prints expected output;
- tiny uber-jar named run prints expected output;
- repeated run executes the run task again even when package task is up-to-date;
- configuration cache is reusable for configuration while run still executes;
- isolated-projects smoke test for a multi-project application;
- `--jvm-args` affect the launched process;
- application arguments are visible to the launched application;
- `quarkus.run.target` selection is honored or fails clearly when ambiguous.

Gated tests:

- Dev Services lifecycle around a container-backed app;
- extension-provided non-Java command, such as Azure Functions.

### 9. Documentation Cleanup

The holistic review ledger was updated and archived as
`archive/gradle-app-plugin-holistic-review.md`; run is no longer tracked there
as a reserved task.

Update `new-application-plugin-design.md` only if its non-goals or deferred
follow-ups still say all launch tasks are reserved.

## Risks And Mitigations

- **Dev Services startup mismatch:** the legacy handler only injects launcher
  config. Mitigation: use a new-plugin result handler that starts
  registry-backed owned services when a registry build item is present.
- **Cancellation leaks child process or Dev Services:** mitigation: process
  helper uses bounded graceful/forced termination and `try/finally` closes the
  `CuratedApplication`.
- **Configuration-cache regression:** mitigation: keep task action free of
  `Project`, task container, configuration, and source-set access; use modeled
  properties and providers.
- **Command selection drift:** mitigation: unit-test selector against the legacy
  decision table, but fail clearly instead of silently returning when no command
  can be selected.
- **Stdin behavior varies by Gradle daemon mode:** mitigation: use Java
  `ProcessBuilder.Redirect.INHERIT` and document any daemon-mode limitation
  found during testing.

## Suggested Order

1. Add run request/command/selector unit tests and implementation.
2. Add process helper with focused tests.
3. Add `BuildOperations.run(...)` and the worker implementation.
4. Convert `QuarkusApplicationRunTask`.
5. Update registration and ProjectBuilder tests.
6. Add focused TestKit fast-jar coverage.
7. Add uber-jar and configuration-cache coverage.
8. Update review/design docs after tests pass.
