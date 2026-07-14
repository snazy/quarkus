# Gradle-Native Remote Dev Task Design

Status: current design; initial implementation complete
Last reviewed: 2026-07-14

## Problem

The new `io.quarkus.application` plugin now has an initial Gradle-native
`quarkusApplicationRemoteDev` implementation. The legacy `io.quarkus` plugin
supports `quarkusRemoteDev`, but its implementation is based on the old
`QuarkusDev` path where Quarkus owns source watching, compilation,
mutable-jar creation, hashing, and remote synchronization.

The new plugin should preserve the useful user-facing remote-dev behavior
without reintroducing configuration-cache or isolated-project problems:

- Gradle must own compilation and resource processing.
- Gradle continuous build should drive new local outputs.
- The task must not scan other projects' mutable model.
- Remote-dev configuration must flow through explicit task configuration or
  invocation-local options. Secret-bearing invocation options must not become
  persisted Gradle task inputs.
- The remote protocol and server-side live reload behavior should be reused
  where possible.

## Legacy Behavior

The legacy task is intentionally thin:

- `QuarkusRemoteDev` extends `QuarkusDev`.
- It only overrides `modifyDevModeContext(DevModeCommandLineBuilder builder)`.
- That override calls `builder.remoteDev(true)`.
- `DevModeCommandLineBuilder.remoteDev(true)` switches the launch mode to
  `QuarkusBootstrap.Mode.REMOTE_DEV_CLIENT` and uses
  `IsolatedRemoteDevModeMain` as the alternate entry point.

`IsolatedRemoteDevModeMain` is the local developer-side remote-dev entry point.
It creates a production mutable application, verifies the result is mutable,
extracts dev-mode classes, creates file hashes, creates a `RemoteDevClient`,
connects to the remote side, and then sends changed or removed files as it
detects updates.

That path proves that Quarkus already has a working remote protocol and
server-side reload path. It also means the legacy Gradle task is not a good
implementation model for the new plugin, because the producer side is still
Quarkus-owned rather than Gradle-owned.

## Legacy Parameter Flow

The legacy `quarkusRemoteDev` task does not define task-specific command-line
options for the remote URL, password, or retry settings.

Those values are ordinary Quarkus configuration under `quarkus.live-reload.*`.
The Gradle docs show this shape:

- `quarkus.live-reload.password=changeit`
- `quarkus.live-reload.url=http://my.cluster.host.com:8080`
- `./gradlew quarkusRemoteDev -Dquarkus.live-reload.url=http://my-remote-host:8080`

The values reach remote dev through the normal dev-mode configuration path:

- `QuarkusDev` builds a `DevModeCommandLine`.
- `DevModeCommandLineBuilder.build()` copies the current Gradle JVM
  `System.getProperties()` into `DevModeContext.systemProperties`.
- It also copies Gradle build-system properties into
  `DevModeContext.buildSystemProperties`.
- `DevModeMain` propagates those properties into the forked dev-mode process.
- `LiveReloadConfig` reads `quarkus.live-reload.url`,
  `quarkus.live-reload.password`, `quarkus.live-reload.connect-timeout`,
  `quarkus.live-reload.retry-interval`, and
  `quarkus.live-reload.retry-max-attempts`.

So the missing `@Option` mappings are expected: URL and auth are not Gradle
task options in the legacy plugin.

## New Plugin Direction

The implemented model is a standalone local remote-dev client task:

- `quarkusApplicationRemoteDevBuild` builds an internal mutable-jar package for
  remote-dev delivery under `build/quarkus-remote-dev/build`.
- `quarkusApplicationRemoteDev` consumes that internal package result and
  package root, computes package-root diffs, and delivers them to the remote
  side.
- Remote-dev state lives under `build/quarkus-remote-dev`, including build
  output, build result, and snapshot state, so it cannot collide with a
  user-declared named build called `remote-dev`, `remoteDev`, or similar.
- The task is registered once per application project, not per
  `mutableJar(...)` build.
- The remote-dev package output directory is not itself a Gradle task input for
  the long-running client task. The producer task owns the package inputs and
  outputs; the client task reads the current package after Gradle reruns the
  producer in each continuous-build iteration. This avoids Gradle continuous
  build loops caused by the task observing its own rewritten package tree.
- The client materializes dev-mode application files before snapshotting by
  reading the package's deployment application model and extracting the
  mutable-jar `dev/app` contents.
- A plugin-local `RemoteDevPackageClient` and deployment-registry-backed handle
  preserve the remote session across continuous-build iterations and poll the
  remote `/dev` endpoint for server-side change requests.

The new plugin should not copy the legacy broad system-property capture. It
should keep using the explicit config-input filtering model already used by the
new application tasks.

For remote dev, that means the task needs an explicit way to model the relevant
`quarkus.live-reload.*` inputs.

The task should expose convenience command-line options only for the common
remote URL/authentication parameters, and translate those values into the same
Quarkus config keys before launching remote dev. That keeps the runtime-facing
configuration model canonical:

- `--live-reload-url` maps to `quarkus.live-reload.url`.
- `--live-reload-password` maps to `quarkus.live-reload.password`.

Do not add convenience options for `quarkus.live-reload.connect-timeout`,
`quarkus.live-reload.retry-interval`, or
`quarkus.live-reload.retry-max-attempts` in the first implementation. The
Quarkus defaults are expected to cover almost all use cases, and advanced users
can still use normal Quarkus configuration channels.

The task should merge these option-derived values with the normal effective
Quarkus config plan before building launch parameters. Task options should win
over file, Gradle property, environment, and system-property values because a
command-line option is the most explicit invocation-local choice.

The implementation must avoid logging sensitive option values. It should also
treat URL/authentication inputs deliberately: the live-reload password, and
possibly the remote URL and username if a username-bearing URL is used, must not
be persisted in the Gradle configuration cache. Prefer command-line option
fields that are not Gradle-managed `Property` inputs, for example private
transient task fields set by `@Option` methods, unless investigation proves a
managed Gradle property shape is not persisted and does not leak into task
state, receipts, diagnostics, or exception messages.

## Shared Continuous Launch Base

`QuarkusApplicationContinuousLaunchTask` is the common base for
`quarkusApplicationDev` and `quarkusApplicationRemoteDev`, but the base is
narrower than originally assumed.

Local dev and remote dev have different producers:

- `quarkusApplicationDev` consumes Gradle class/resource/runtime-jar output
  changes directly and maps them to external-build-output changes for a local
  dev-mode deployment.
- `quarkusApplicationRemoteDev` consumes a regenerated internal
  mutable-jar package root and sends package-root diffs to the remote side.
  Gradle output changes are only the reason to rerun the internal package
  build; they are not the remote-dev payload.

The common base should therefore own only shared launch/session concerns:

- require `--continuous`;
- expose shared launch options such as JVM arguments, application arguments,
  module options, compiler arguments, and test filters where applicable;
- model common config inputs and effective-config planning;
- carry shared build identity and launch-kind state;
- maintain common receipt/session lifecycle conventions where the fields are
  genuinely shared;
- perform shared validation and effective-config planning.

The base must not own local dev-session details. In particular, it should not
assume `DeploymentRegistry`, local `QuarkusApplicationDevDeploymentHandle`, or
the current local external-build-output transport.

The base also must not own the current local-dev `InputChanges` to
`BuildOutputChanges` mapping, because that mapping is not the remote-dev sync
contract. That logic should stay in `QuarkusApplicationDevTask` or in a
local-dev-specific helper. Remote dev may share a lower-level directory snapshot
and file-diff utility, but its snapshot root is the mutable package output, not
the Gradle class/resource output roots.

Subclasses decide how each continuous-build iteration is produced and
delivered:

- `QuarkusApplicationDevTask` sends the batch to the local dev-mode deployment.
- `QuarkusApplicationRemoteDevTask` reads the mutable package output produced
  earlier in the same Gradle invocation, computes the package-root diff, and
  sends that diff to the remote-dev client.

## Deployment Handle And Session State

Remote dev likely needs a deployment handle as well.

The current local `QuarkusApplicationDevTask` uses Gradle's internal
`DeploymentRegistry` to keep a `QuarkusApplicationDevDeploymentHandle` alive
across continuous-build iterations. That handle owns the long-lived session,
sequence numbers, readiness, close behavior, and delivery state.

Remote dev has the same category of stateful behavior:

- an initial connect/baseline must be established before incremental changes
  have meaning;
- the remote client session should stay open while Gradle continuous build is
  running;
- sequence numbers and last-delivered state need to survive across task
  iterations;
- reconnect and restart-required decisions should be made against the existing
  session state;
- stop/cancel should close the remote session and write an observable close
  receipt, like local dev.

The remote task should therefore have its own narrow handle/session pair, for
example `QuarkusApplicationRemoteDevDeploymentHandle` plus
`QuarkusApplicationRemoteDevDeployments`. It can share lower-level session
state abstractions with local dev if that removes duplication, but it should
not pretend that the local dev handle is generic enough until the remote
delivery semantics are known.

The shared `QuarkusApplicationContinuousLaunchTask` should not define one
producer snapshot model. Local dev owns Gradle output snapshots. Remote dev
owns package-output delivered-state snapshots. The remote deployment handle
should own remote-specific lifecycle and delivery state.

## Remote Dev Task Shape

`QuarkusApplicationRemoteDevTask` is a long-running, non-cacheable launch task,
similar to `QuarkusApplicationDevTask`.

The task is registered once as `quarkusApplicationRemoteDev`. It is not
registered per named `mutableJar(...)` build. Remote dev is a session mode, not
a deliverable package shape, so the client task does not depend on or share
output with a user-declared build.

The task depends on a standalone internal package producer,
`quarkusApplicationRemoteDevBuild`, which always builds a mutable-jar package
for remote-dev delivery. Under `--continuous`, Gradle reruns normal
upstream work and that internal package build whenever its inputs change, then
executes the remote-dev task action. The remote-dev task action does not
perform source compilation itself and does not manually invoke another Gradle
task. It consumes the package result and package output that the normal
task graph has already produced in the same build iteration.

The internal remote-dev package output should live under
`build/quarkus-remote-dev`, not under `build/quarkus-builds/<name>` or
`build/quarkus-build-results/<name>`. This keeps remote-dev state out of the
user-defined build-name namespace and avoids conflicts with builds named
`remote-dev`, `remoteDev`, or similar.

The remote side remains a mutable Quarkus application launched with
`QUARKUS_LAUNCH_DEVMODE=true`. The local Gradle task is the client that sends
package-root additions, modifications, and deletion information.

## Continuous Task Graph

The intended task graph is:

```text
classes/resources/dependency outputs
  -> quarkusApplicationRemoteDevBuild
  -> quarkusApplicationRemoteDev
```

The internal mutable-jar build task remains a normal finite package build task.
It does not become a long-running remote-dev task. The long-running state
belongs to the remote-dev deployment/session handle, while Gradle continuous
build reruns the finite producer tasks for each change batch.

Each continuous iteration should behave like this:

1. Gradle detects changed build inputs and reruns affected compile/resource
   tasks.
2. Gradle reruns `quarkusApplicationRemoteDevBuild` if its inputs are out of date.
3. The remote-dev task reads the internal package result and mutable package root.
4. The remote-dev task computes a package-root diff against the previous
   delivered state kept by the remote-dev session handle.
5. The remote-dev task sends the diff to the remote side and updates the
   delivered-state snapshot only after successful delivery.

This also means the remote-dev task does not need the same file collections as
local dev for application classes, application resources, dependency classes,
dependency resources, and runtime jars. Those inputs belong to the package
build task. The remote-dev task needs the internal package result file, the
mutable package output directory, remote connection configuration, and any
state files or receipts needed for observability.

## Remote Side Startup

The remote side is not started by the local `quarkusRemoteDev` Gradle task.
The user first builds and deploys a mutable Quarkus application, then starts
that application normally on the remote host with one additional environment
switch:

- build a mutable jar with `quarkus.package.jar.type=mutable-jar`;
- configure the shared live-reload password with
  `quarkus.live-reload.password`;
- start the remote application with `QUARKUS_LAUNCH_DEVMODE=true`;
- run the application using its normal packaged launch command, for example
  `java -jar ...`;
- for containers, pass `-e QUARKUS_LAUNCH_DEVMODE=true` and ensure the
  deployment directory is writable by the running process.

`LaunchMode.isRemoteDev()` treats the application as the server side of remote
dev when the launch mode is development and `QUARKUS_LAUNCH_DEVMODE=true` is
present in the environment. The mutable-jar startup path then uses
`DevModeTask` with `QuarkusBootstrap.Mode.REMOTE_DEV_SERVER`, extracts the
reloadable application/dependency classes under the mutable application root,
and starts the normal dev-mode runtime on the remote host.

The main server-side config requirement is the shared
`quarkus.live-reload.password`. The client must use the same value. The
`quarkus.live-reload.url` value is primarily local-client configuration: it is
the URL the local client uses to reach the remote application. The existing
Gradle docs explicitly note that the URL is only needed on the local side, so it
can be omitted from the remote `application.properties` and supplied on the
local Gradle invocation instead.

Remote dev also changes an HTTP default: when Quarkus detects remote dev server
mode, Vert.x defaults `quarkus.http.host` and `quarkus.management.host` to
`0.0.0.0` unless another default was already recorded. Users can still override
those normal Quarkus HTTP config values if their deployment needs a different
bind address.

## Implemented Remote Side Run Convenience

The new mutable-jar run task can start the remote server side directly:

```bash
./gradlew quarkusApplicationMutableJarRun --enable-remote-dev
```

This is a server-side launch convenience, not the remote
dev client task. The option should only make the packaged mutable application
start as the remote-dev server by passing `QUARKUS_LAUNCH_DEVMODE=true` to the
foreground process environment.

The implementation lives on `QuarkusApplicationRunTask` instead of a separate
`QuarkusApplicationMutableJarRunTask`. The run task knows its
`QuarkusApplicationBuildType`, which keeps one run-task type while still
allowing strict validation.

Important boundaries:

- `--enable-remote-dev` is accepted by `QuarkusApplicationRunTask` and fails
  clearly when used for any build type other than `MUTABLE_JAR`;
- it does not replace `QuarkusApplicationRemoteDevTask`, which is the local
  client that connects and sends changes;
- it should not require `--continuous`, because it is just running the packaged
  remote side;
- it should not set `quarkus.live-reload.url`, because the URL is client-side
  configuration;
- it accepts `--live-reload-password` as a server-side convenience and maps it
  to `quarkus.live-reload.password`;
- it still requires the client side to use the same live-reload password.

The run-command/request path now has an explicit environment overlay, and
`QuarkusApplicationRunTask` applies `QUARKUS_LAUNCH_DEVMODE=true` when
`--enable-remote-dev` is enabled.

## Remote Sync Contract

The existing remote-dev protocol is package-root based, not source-root based.
The local side computes hashes for files under the mutable application root,
using forward-slash relative paths. The hash walk skips the `quarkus/`
directory, but it includes package files such as `quarkus-run.jar`, `app/...`,
`lib/main/...`, `lib/boot/...`, and `lib/deployment/...`.

The first connect sends the local hash state to `POST /connect`. The remote
side compares it with its own mutable application root, asks for files it is
missing or that differ, and deletes remote-only files except for protected
metadata such as root-level files, `META-INF/MANIFEST.MF`, and Maven metadata.
Later sync iterations send changed files and removed-file notifications.

`lib/deployment/appmodel.dat` is a special package file. It is sent last during
the initial sync because changing it can trigger a remote restart, after which
the client waits for the remote probe endpoint and reconnects.

The producer contract that matters for a Gradle-native remote-dev task is
therefore:

- provide a mutable-jar application root that is runnable with
  `QUARKUS_LAUNCH_DEVMODE=true`;
- preserve the existing relative-path protocol under that root;
- send package-root files, not arbitrary Gradle source/output-root names;
- apply the existing delete exclusions;
- treat deployment metadata such as `appmodel.dat`,
  `deployment-class-path.dat`, and `build-system.properties` as part of the
  mutable package state.

## Mutable-Jar Rebuild And Diff Requirement

The mutable-jar output is not just copied Gradle classes and resources. Quarkus
creates a full package layout with copied dependency jars, generated bootstrap
metadata, generated bytecode/resources, transformed bytecode, serialized
classpath/index data, and mutable reaugmentation metadata.

Some files are straight copies in the common case, such as external dependency
jars under `lib/main` and `lib/boot`. Even those are not a universal
pass-through: directory dependencies are packaged as jars, dependency jars can
be rewritten when resources are removed or classes are tree-shaken, and the
application artifact under `app/` is generated from the application archive
with package-time filtering and manifest handling.

That means Gradle output changes alone are not enough to produce a correct
remote-dev file batch. They answer what changed in the build outputs, but not
which mutable-package files changed after augmentation. Source, resource,
dependency, and build-time config changes can affect:

- `app/<application>.jar`;
- `lib/main/...` and `lib/boot/...`;
- `lib/deployment/appmodel.dat`;
- `lib/deployment/deployment-class-path.dat`;
- generated or transformed package artifacts;
- serialized application metadata.

The existing legacy remote-dev client solves this by rerunning mutable-jar
production augmentation when changes are detected, hashing the newly generated
package output, comparing it with the previous package-output hash state, and
sending the package-root diff. The new Gradle-native task should assume the
same package-output diff requirement. The first implementation can satisfy
that with a plugin-local package client and package-root diff producer, shaped
so the client can later move to Quarkus core/deployment.

The existing package build task is the producer that maps changed Gradle state
to a regenerated mutable package root. The remote-dev task should not duplicate
the package task's inputs or augmentation logic. It should depend on that task,
then compute and deliver the package-root diff from the resulting output.

The existing new-plugin dev output snapshotting and `BuildOutputChanges`
machinery remains useful for local dev. It is not, by itself, the remote sync
payload. Remote dev may reuse lower-level snapshot/diff primitives, but the
snapshot scope is the package output root and the diff entries are remote-dev
relative package paths.

## Delivery Model Options

### Option A: Reuse `IsolatedRemoteDevModeMain` Directly

This is the smallest code path in Quarkus core, because
`DevModeCommandLineBuilder.remoteDev(true)` already selects it.

The problem is that this preserves the old producer model. The remote-dev main
creates the mutable application, hashes the application root, and uses
`RuntimeUpdatesProcessor` to scan and compile updates. That conflicts with the
new plugin goal where Gradle owns compilation and continuous output changes.

This option is useful as compatibility evidence, but it should not be the final
new-plugin design.

### Option B: Reuse Remote Protocol, Replace Producer

This is the preferred direction.

Extract or add a Quarkus core entry point that lets an external build tool
provide changed and removed package-root files while still reusing the existing
`RemoteDevClient` protocol and server-side reload handling.

In this model:

- Gradle continuous build compiles classes and processes resources.
- The standalone `quarkusApplicationRemoteDevBuild` task regenerates or
  refreshes the internal mutable package state.
- The remote-dev task computes a package-root diff against the previous
  delivered state.
- The task sends additions, modifications, and deletions through a
  remote-dev-client abstraction.
- Quarkus core still owns remote protocol details and remote-side application
  reload.

The first implementation adds this adapter in the new Gradle plugin, while
keeping it free of Gradle/plugin types so it can later move to Quarkus
core/deployment.

## Implementation Status

The first implementation is complete and the implementation plan is archived as
`archive/quarkus-remote-dev-task-implementation-plan.md`.

Implemented decisions:

- plugin-local `RemoteDevPackageClient` added without Gradle/plugin type
  coupling, leaving a later extraction path to Quarkus core/deployment;
- legacy remote-dev classes left untouched;
- delivered package-output snapshots stored as relative-path/hash/size state
  using the remote protocol hash algorithm;
- the internal package result/layout model shaped only as needed for generic
  package facts;
- URL and password command-line options modeled as private transient task
  fields rather than Gradle-managed task inputs;
- remote dev registered as one standalone application-project task, backed by
  an internal mutable-jar package build under `build/quarkus-remote-dev`;
- package-root changes sent through the remote-dev protocol, with client
  session reuse across continuous-build iterations.

## Implementation Decisions

### Remote Package Client

The new plugin codebase adds `RemoteDevPackageClient` first. It is designed as
a Quarkus-core candidate, analogous in spirit to
`BuildOutputChangesPolicy`: package-private or internal in its first home, but
free of Gradle APIs, task types, project services, providers, and plugin model
classes.

The client should operate on plain Java values:

- remote URL;
- password/authentication value;
- package-root-relative paths;
- changed file bytes or file paths;
- deleted package-root-relative paths;
- previous/current hash state as plain maps or simple records.

It should encapsulate the remote-dev protocol mechanics that are not Gradle
specific:

- initial connect;
- session headers and counters;
- changed-file upload;
- deleted-file notification;
- reconnect/probe behavior;
- `appmodel.dat` ordering;
- delete exclusions.

Do not refactor the existing legacy `IsolatedRemoteDevModeMain` or
`HttpRemoteDevClient` path as part of the first new-plugin implementation.
Duplicating the small adapter layer in the new plugin is acceptable if it keeps
the legacy path stable and leaves a clear later extraction path to
core/deployment.

### Package Snapshot Format

The remote-dev task needs a delivered-state snapshot of the mutable package
root. Use a simple line-oriented file, for example:

```text
relative/path<TAB>sha1<TAB>size
```

Rules:

- paths are mutable-package-root relative and use `/`;
- use SHA-1 to match the existing remote-dev protocol;
- record file size as a cheap diagnostic and corruption guard;
- write the snapshot only after successful remote delivery;
- apply remote-dev delete exclusions before producing removed-file events;
- keep the authoritative live state in the deployment/session handle while the
  snapshot file provides task observability and restart recovery where useful.

### Package Result Layout

`PackageResult` is internal to the new plugin, so it can be shaped toward the
right model. Any additions should remain generic package layout facts, not
remote-dev-specific requirements.

The implementation verified that only generic layout facts should be exposed:

- package output root;
- primary jar path;
- library directory;
- original artifact;
- mutable flag.

If later remote-dev work has to rely on additional implicit fast-jar layout
knowledge, extend `PackageResult` with a generic mutable-package or
application-root path. Such a field should be optional or meaningful only when
a package type has that layout, so other package usages do not inherit
remote-dev-only obligations.

### Transient Option Merge Path

The remote URL and password convenience options must both be treated as
leak-sensitive. The task should not expose them as Gradle-managed
`Property<String>` values and should not annotate them as task inputs.

Preferred task shape:

```java
private transient String liveReloadUrl;
private transient String liveReloadPassword;
```

`@Option` setter methods should assign those fields during invocation. At task
execution, the task should merge non-null option values into an invocation-only
config override map using the canonical Quarkus keys:

- `quarkus.live-reload.url`;
- `quarkus.live-reload.password`.

The values must not be written to receipts, package snapshots, task inputs,
exception messages, or logs. This applies to URL as well as password because a
URL can contain userinfo or deployment-sensitive host/route data.

## Implemented Shape

1. Extracted a narrow `QuarkusApplicationContinuousLaunchTask` from only the
   common launch/session/config pieces of `QuarkusApplicationDevTask`.
2. Kept local dev output collections, `InputChanges` mapping, local
   deployment/session, and local transport details in `QuarkusApplicationDevTask`.
3. Registered one standalone `quarkusApplicationRemoteDevBuild` package task
   that always produces an internal mutable-jar package under
   `build/quarkus-remote-dev/build`.
4. Registered one standalone `quarkusApplicationRemoteDev` client task and made
   it depend on `quarkusApplicationRemoteDevBuild`.
5. Wired the remote-dev task to consume the internal package result file and
   mutable package output directory.
6. Added remote-dev convenience options and merged them into the Quarkus config
   property map using the canonical `quarkus.live-reload.*` keys.
7. Added remote-dev-specific config input filtering for non-secret
   `quarkus.live-reload.*` values, while keeping URL/authentication
   convenience options out of managed task input state.
8. Added plugin-local `RemoteDevPackageClient` with no Gradle/plugin type
   dependencies, leaving it movable to Quarkus core/deployment later.
9. Added a remote-dev deployment handle/session pair for connection lifecycle,
   baseline state, sequence numbers, reconnect decisions, and stop cleanup.
10. Implemented SHA-1 package-root snapshot and diffing for the mutable package
   output.
11. Implemented `QuarkusApplicationRemoteDevTask` as a continuous task that reads
    the current package output, computes package output diffs, and sends them
    through the remote-dev API.
12. Extended `PackageResult` only as needed with generic package layout facts.
13. Added tests for task registration, config modeling, `--continuous`
    validation, changed-file delivery, deleted-file delivery, and retry/error
    reporting.

## Validation Notes

Useful coverage should include:

- TestKit coverage that remote-dev tasks require `--continuous`.
- TestKit coverage that `quarkusApplicationRemoteDev` exists without any named
  `mutableJar(...)` build and depends on `quarkusApplicationRemoteDevBuild`.
- TestKit coverage that named builds do not register `quarkus<Name>RemoteDev`
  tasks.
- TestKit coverage that remote-dev output is under `build/quarkus-remote-dev`
  and not under the named-build output/result namespaces.
- TestKit coverage that convenience options map to the expected
  `quarkus.live-reload.*` properties and override less explicit config
  sources.
- TestKit coverage that non-secret `quarkus.live-reload.*` config still flows
  through the normal config-input model.
- Secret-handling tests that ensure URL/authentication option values are not
  restored from configuration-cache state, logged, written to receipts, or
  included in exception messages.
- Handle/session tests for initial connect, repeated continuous-build
  iterations, changed-file sequence progression, stop cleanup, and
  configuration-fingerprint mismatch.
- Core-level tests for package-root changed and removed file delivery through a
  fake or local remote-dev client.
- Producer tests showing that source/resource, dependency, and build-time
  config changes rerun the mutable-jar package build and produce the expected
  mutable-package diff rather than only a Gradle output-root diff.
- At least one representative integration fixture for mutable-jar remote dev
  once the core seam exists.

## References

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusRemoteDev.java`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusDev.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/DevModeCommandLineBuilder.java`
- `core/deployment/src/main/java/io/quarkus/deployment/dev/IsolatedRemoteDevModeMain.java`
- `core/runtime/src/main/java/io/quarkus/runtime/LiveReloadConfig.java`
- `docs/src/main/asciidoc/gradle-tooling.adoc`
- `devtools/gradle/docs-wip/gradle-cc-work/quarkus-dev-continuous-build-design.md`
- `devtools/gradle/docs-wip/gradle-cc-work/quarkus-core-external-build-updates-design.md`
