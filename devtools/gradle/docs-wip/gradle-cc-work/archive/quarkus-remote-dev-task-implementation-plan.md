# Standalone Gradle-Native Remote Dev Task Implementation Plan

Status: implemented plan; archived
Last reviewed: 2026-07-14

Archive note: implemented in `devtools/gradle/gradle-app-plugin` as the
standalone `quarkusApplicationRemoteDevBuild` producer plus
`quarkusApplicationRemoteDev` client task. The implemented shape uses the
`remoteDev {}` DSL, stores internal build/result/snapshot state under
`build/quarkus-remote-dev`, keeps URL/password command-line options transient,
materializes dev-mode application files before snapshotting, reuses a
deployment-registry-backed remote client across continuous-build iterations,
and leaves real backend integration coverage as a deferred follow-up.

## Goal

Refactor the new `io.quarkus.application` plugin's Gradle-native remote-dev
task so the local remote-dev client is independent of any user-declared
`mutableJar` build.

The target model is one standalone `quarkusApplicationRemoteDev` task,
configured by a new `remoteDev {}` extension block. That task should build its
own internal mutable-jar package in a remote-dev-specific output directory,
snapshot that package root, and send package-root changes to the remote side.

This replaces the current named-build model where
`quarkusApplicationMutableJarRemoteDev` depends on a user-declared mutable-jar
package build.

## Rationale

Remote dev is a session mode, not a deliverable package shape. Coupling the
client producer to a normal named mutable-jar build creates avoidable overlap
with user package/run tasks and makes it too easy to run two long-lived sessions
against the same mutable package output.

The standalone task should own its package producer and state:

- no dependency on `QuarkusApplicationBuild` or `QuarkusMutableJarOutput` as
  public DSL concepts;
- no dependency on a named `mutableJar(...)` build being present;
- no sharing of package output or package snapshot files with normal package
  builds;
- no use of `build/quarkus-builds/<name>` or
  `build/quarkus-build-results/<name>` for remote-dev internals, because those
  paths share the user-defined build-name namespace;
- no named-build remote-dev task names.

The remote-dev server side remains a mutable jar launched with
`QUARKUS_LAUNCH_DEVMODE=true`. The existing
`quarkusApplicationMutableJarRun --enable-remote-dev` convenience remains
server-side only.

## Keep From The Current Implementation

The current remote-dev implementation already has pieces that should mostly
survive this refactor:

- `RemoteDevPackageClient` and related protocol value types;
- `HttpRemoteDevPackageClient`;
- `RemoteDevPackageSnapshot`, `RemoteDevPackageDiff`, and delete policy;
- `QuarkusApplicationRemoteDevDeploymentHandle`;
- `QuarkusApplicationRemoteDevDeployments`;
- package-root diff delivery and non-secret receipt writing;
- transient `--live-reload-url` and `--live-reload-password` handling on the
  remote-dev task.

Do not touch the legacy `io.quarkus` plugin remote-dev path.

## Non-Negotiable Constraints

- The standalone remote-dev task must always use an internal
  `QuarkusApplicationBuildType.MUTABLE_JAR` package request.
- Do not require, register, or consume a user-declared `mutableJar(...)` build
  for remote-dev client operation.
- Do not register `quarkus<Name>RemoteDev` tasks for named builds.
- Do not expose remote-dev package output as a consumable package artifact.
- Do not make remote-dev package output part of `builds {}`.
- Store all standalone remote-dev artifacts under `build/quarkus-remote-dev`.
  Do not store them under `build/quarkus-builds/remote-dev` or
  `build/quarkus-build-results/remote-dev`.
- Do not manually invoke Gradle tasks from inside a task action.
- Keep URL and password command-line options in private transient fields, not
  Gradle-managed `Property<String>` task inputs.
- Do not write URL/password values to receipts, snapshots, logs, exception
  messages, or configuration-cache-backed task state.
- Keep the long-running remote-dev task non-cacheable and not
  up-to-date-skippable.
- Preserve configuration-cache and isolated-project compatibility for bounded
  task graph wiring and non-network tests.

## Target User Model

The user-facing shape should be:

```kotlin
quarkusApplication {
    remoteDev {
        quarkusBuildProperties.put("key", "value")
        forkOptions {
            jvmArgs("-Dexample=true")
        }
    }
}
```

The task should be:

```text
quarkusApplicationRemoteDev
```

The local client invocation should be:

```bash
./gradlew quarkusApplicationRemoteDev \
  --continuous \
  --live-reload-url=http://localhost:8080 \
  --live-reload-password=changeit
```

The server-side local convenience remains:

```bash
./gradlew quarkusApplicationMutableJarRun \
  --enable-remote-dev \
  --live-reload-password=changeit
```

## Target Task Graph

The standalone remote-dev task should have an internal package producer task.
Use separate output and result directories so it cannot collide with normal
named builds:

```text
classes/resources/dependency outputs
  -> quarkusApplicationRemoteDevBuild
  -> quarkusApplicationRemoteDev
```

Required paths:

```text
build/quarkus-remote-dev/build/
build/quarkus-remote-dev/build-result/package-result.properties
build/quarkus-remote-dev/build-result/remote-dev-result.properties
build/quarkus-remote-dev/snapshot/package-snapshot.tsv
build/quarkus-remote-dev/snapshot/session-closed.txt
```

This dedicated top-level namespace avoids collisions with a user-defined build
named `remote-dev`, `remoteDev`, or similar.

The build name used for the internal descriptor should be a fixed internal
name, for example `remoteDev`. It must be used only for descriptor/output
planning and receipt identity, not exposed as a user-declared build.

## Phase 1: Add `remoteDev {}` DSL

Files:

- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/dsl/QuarkusApplicationExtension.java`
- new `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/dsl/QuarkusApplicationRemoteDev.java`

Implementation steps:

1. Add a `QuarkusApplicationRemoteDev` DSL type similar to
   `QuarkusApplicationDev`.
2. Give it:
   - `MapProperty<String, String> getQuarkusBuildProperties()`;
   - `QuarkusApplicationDevForkOptions getForkOptions()`.
   A later cleanup may rename or share the dev fork-options type, but this
   refactor should reuse it directly to keep the implementation narrow.
3. Add `getRemoteDev()` and `remoteDev(Action<? super QuarkusApplicationRemoteDev>)`
   to `QuarkusApplicationExtension`.
4. Set `getQuarkusBuildProperties().convention(Map.of())`.
5. Do not add package output DSL, build name, build type, manifest attributes,
   image options, deployment options, native options, or package publication
   options.

Acceptance tests:

- ProjectBuilder verifies the extension exposes `remoteDev`.
- `remoteDev { quarkusBuildProperties.put(...) }` can be configured lazily.
- The new DSL type does not depend on `QuarkusApplicationBuild` or
  `QuarkusMutableJarOutput`.

## Phase 2: Register Standalone Remote-Dev Build Task

Files:

- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/TaskRegistration.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/tasks/QuarkusApplicationPackageTask.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/tasks/QuarkusApplicationBuildTask.java`

Implementation steps:

1. Add a private fixed internal descriptor/helper for remote-dev package
   production. Do not create a `QuarkusApplicationBuild` instance and do not
   route this through `BuildRegistration`; that record currently wraps a
   public DSL build and is the coupling this refactor removes.
   The fixed facts are:
   - internal build name: `remoteDev`;
   - build type: `QuarkusApplicationBuildType.MUTABLE_JAR`;
   - package build task name: `quarkusApplicationRemoteDevBuild`;
   - client task name: `quarkusApplicationRemoteDev`.
2. Register `quarkusApplicationRemoteDevBuild` as a
   `QuarkusApplicationPackageTask`.
3. Configure it with the same main application model, runtime classpath, source
   directories, config inputs, fork options, and package build machinery used
   by normal package builds. Extract shared private helpers from
   `configureNamedBuildTask(...)` where useful, but keep the standalone
   remote-dev configuration explicit enough that it does not depend on
   `QuarkusApplicationBuild`.
4. Merge Quarkus build properties in this order:
   - extension-level `getQuarkusBuildProperties()`;
   - `extension.getRemoteDev().getQuarkusBuildProperties()`.
5. Force descriptor shape properties for a mutable jar:
   - `quarkus.package.jar.enabled=true`;
   - `quarkus.package.jar.type=mutable-jar`;
   - remote-dev-specific `quarkus.package.output-directory`;
   - remote-dev-specific `quarkus.package.output-name`.
   Set the task `outputName` to the Gradle project name by convention. Do not
   leave it unset, because `QuarkusApplicationBuildTask.descriptorShapeProperties()`
   otherwise falls back to the internal build name `remoteDev`.
6. Set the package result file to
   `build/quarkus-remote-dev/build-result/package-result.properties`.
7. Set the output directory to
   `build/quarkus-remote-dev/build`.
8. Do not register a package elements configuration for the remote-dev package.
9. Do not register run, image, deploy, AOT, native-test, or continuous-test
   tasks for this internal package build.

Acceptance tests:

- `quarkusApplicationRemoteDevBuild` exists without any `builds { mutableJar }`
  DSL.
- The task is a `QuarkusApplicationPackageTask` configured as mutable jar.
- The package result and output paths are remote-dev-specific.
- No consumable package-elements configuration is registered for `remoteDev`.
- Remote-dev build properties override extension-level build properties.

## Phase 3: Register Standalone `quarkusApplicationRemoteDev`

Files:

- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/TaskRegistration.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/tasks/QuarkusApplicationRemoteDevTask.java`

Implementation steps:

1. Register `quarkusApplicationRemoteDev` exactly once during plugin
   application, next to `quarkusApplicationDev`.
2. Make it depend on `quarkusApplicationRemoteDevBuild`.
3. Configure:
   - launch kind: `REMOTE_DEV`;
   - build name: fixed internal name `remoteDev`;
   - build type: `MUTABLE_JAR`;
   - project directory;
   - continuous-build flag;
   - package result file from the standalone package build;
   - package output directory from the standalone package build;
   - receipt file at
     `build/quarkus-remote-dev/build-result/remote-dev-result.properties`;
   - snapshot file at
     `build/quarkus-remote-dev/snapshot/package-snapshot.tsv`;
   - close receipt file at
     `build/quarkus-remote-dev/snapshot/session-closed.txt`.
4. Configure normal config inputs using the extension's config-input filters.
5. Merge remote-dev DSL build properties into task config the same way as the
   standalone build task.
6. Set `outputName` to the same project-name convention used by the standalone
   package build.
7. Preserve transient `--live-reload-url` and `--live-reload-password` option
   handling.
8. Keep the task action package-result-driven. It should read the standalone
   package result and verify the package result is mutable.

Acceptance tests:

- `quarkusApplicationRemoteDev` exists without any named mutable-jar build.
- It depends on `quarkusApplicationRemoteDevBuild`.
- It reads package result/output paths from the standalone build.
- It still fails without `--continuous`.
- It still requires URL from `quarkus.live-reload.url` or
  `--live-reload-url`.
- The password option is not exposed as a task property/input.

## Phase 4: Remove Named-Build Remote-Dev Registration

Files:

- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/TaskRegistration.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/planning/TaskNames.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/planning/TaskNamePlanner.java`
- related tests

Implementation steps:

1. Remove `registerNamedRemoteDevTask(...)`.
2. Remove the call that registers remote-dev tasks for
   `QuarkusApplicationBuildType.MUTABLE_JAR`.
3. Remove `remoteDev` from `TaskNames` if it is no longer used for any other
   task-name planning path.
4. Remove remote-dev task-name collision checks for named builds.
5. Update task-name planner tests so named builds no longer produce or reserve
   `quarkus<Name>RemoteDev`.
6. Update plugin registration tests:
   - remove assertions for `quarkusMutableRemoteDev`;
   - add assertions for the standalone `quarkusApplicationRemoteDev`.

Acceptance tests:

- A user-defined mutable-jar build does not create
  `quarkus<Name>RemoteDev`.
- A project with no named builds still has `quarkusApplicationRemoteDev` and
  `quarkusApplicationRemoteDevBuild`.
- Existing named build tasks for build, run, image, deploy, AOT, native-test,
  and continuous-test keep their prior behavior.

## Phase 5: Continuous-Build And Configuration-Cache Handling

Files:

- `QuarkusApplicationRemoteDevTask`
- `QuarkusApplicationDevTask`
- shared helper code if a common fix is introduced
- TestKit tests

Implementation steps:

1. Re-check how `continuousBuild` is modeled. The current pattern captures
   `project.getGradle().getStartParameter().isContinuous()` into a task
   property during configuration.
2. Add a regression test for a non-continuous configuration-cache entry
   followed by a `--continuous` remote-dev invocation, if Gradle/TestKit can
   model that scenario reliably.
3. If the stale configuration-cache value reproduces, fix the continuous-mode
   guard for both dev and remote-dev together. Do not paper over only
   remote-dev.
4. If Gradle cannot safely combine continuous build and configuration-cache
   reuse for this task shape, document and enforce the limitation explicitly
   rather than relying on stale task inputs.

Acceptance tests:

- Remote-dev fails early and clearly when not run with `--continuous`.
- The failure does not happen spuriously when invoked with `--continuous`.
- Any fix or limitation matches `quarkusApplicationDev` behavior; the two
  continuous tasks do not diverge accidentally.

## Phase 6: Keep Run-Task Server Convenience Separate

Files:

- `QuarkusApplicationRunTask`
- run-task tests

Implementation steps:

1. Keep `--enable-remote-dev` only on jar run tasks.
2. Keep `--live-reload-password` support on run tasks only as server-side
   convenience.
3. Do not add `--live-reload-url` to run tasks. The URL is client-side
   knowledge.
4. Keep the mutable-jar guard for `--enable-remote-dev`.
5. Keep run-task remote-dev server properties separate from the standalone
   remote-dev client task's configuration.

Acceptance tests:

- `quarkusApplicationMutableJarRun --enable-remote-dev --live-reload-password=...`
  maps the password to `quarkus.live-reload.password`.
- Non-mutable run tasks still reject `--enable-remote-dev`.
- `--live-reload-password` without `--enable-remote-dev` still fails.

## Phase 7: Documentation Updates

Files:

- `devtools/gradle/docs-wip/gradle-cc-work/quarkus-remote-dev-task-design.md`
- `devtools/gradle/docs-wip/gradle-cc-work/new-application-plugin-design.md`
- `devtools/gradle/docs-wip/gradle-cc-work/README.md`
- `devtools/gradle/docs-wip/gradle-cc-work/tracker.md`

Implementation steps:

1. Update the remote-dev design doc to describe the standalone task model and
   remove named mutable-jar remote-dev task language.
2. Keep a short note that this supersedes the earlier named mutable-jar
   remote-dev direction.
3. Add or keep deferred follow-ups for:
   - real remote backend integration coverage;
   - eventual extraction of `RemoteDevPackageClient` and
     `BuildOutputChangesPolicy` into Quarkus core/deployment;
   - stdin/continuous-testing support for dev and remote-dev;
   - color support propagation for run/dev tasks.
4. Update the docs index/tracker entries so the implementation plan no longer
   claims the named mutable-jar model is current.

Acceptance tests:

- Docs consistently refer to `quarkusApplicationRemoteDev` as the client task.
- Docs consistently refer to `quarkusApplicationMutableJarRun --enable-remote-dev`
  as the server-side convenience.
- No doc in `gradle-cc-work` still recommends
  `quarkusApplicationMutableJarRemoteDev` as the desired final shape.

## Suggested Implementation Order

1. Add `remoteDev {}` DSL and tests.
2. Add standalone remote-dev package build task and tests.
3. Rewire `quarkusApplicationRemoteDev` to consume the standalone package
   result.
4. Remove named mutable-jar remote-dev task registration and task-name planning.
5. Update focused remote-dev task tests.
6. Add bounded TestKit coverage for task graph/configuration-cache wiring.
7. Review and fix continuous-build/configuration-cache behavior if reproduced.
8. Update docs.
9. Run targeted tests.

## Suggested Verification

From `devtools/gradle`:

```bash
./gradlew :gradle-app-plugin:test --tests '*QuarkusApplication*RemoteDev*'
./gradlew :gradle-app-plugin:test --tests '*TaskNamePlannerTest'
./gradlew :gradle-app-plugin:test --tests '*QuarkusApplicationPluginTest'
```

If the touched surface is broad, also run:

```bash
./gradlew :gradle-app-plugin:test
```

Optional Nessie smoke after unit/TestKit coverage:

```bash
./gradlew :nessie-quarkus:quarkusApplicationRemoteDev --continuous \
  --live-reload-url=http://localhost:8080 \
  --live-reload-password=changeit
```

Use a bounded or manually supervised invocation for any continuous task. Do not
leave long-running Gradle processes behind.

## Plan Review

Correctness:

- The plan removes the public named-build coupling and gives remote-dev its
  own internal mutable-jar package producer.
- The remote-dev client continues to use package-root diffs, matching the
  existing remote-dev protocol.
- The run task remains server-side only; it does not become part of the client
  producer path.
- Secret-bearing invocation options remain transient and are not modeled as
  Gradle inputs.

Completeness:

- The plan covers DSL, task registration, task naming, package output layout,
  remote-dev task wiring, named-build cleanup, tests, docs, and verification.
- It explicitly calls out the continuous-build/configuration-cache risk that
  affects both dev and remote-dev.
- It preserves already implemented protocol/session internals instead of
  rewriting working code.

Blind-agent readiness:

- A competent agent can follow this plan without needing a separate design
  decision for the main topology.
- The fixed task names, output paths, DSL shape, removed named-task behavior,
  and acceptance tests are specified.
- The only conditional area is continuous-build/configuration-cache handling.
  That is intentionally framed as an investigation-and-fix phase because it
  affects `quarkusApplicationDev` too and should not be solved narrowly inside
  this refactor.
