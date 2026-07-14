# Quarkus Core External Build Transport Implementation Plan

Status: implemented for TCP production-output slice
Last reviewed: 2026-07-09

## Objective

Add the first local transport for build-tool-produced dev-mode output batches.
This transport must deliver `BuildOutputChanges` into the existing
`RuntimeUpdatesProcessor.processBuildOutputChanges(...)` production-output seam
without adding Gradle concepts to core and without changing default dev-mode
behavior.

This is the next slice after the production-output core seam. It remains
production-output-only: test output routing, continuous testing, dependency
change handling, Gradle wiring, and non-TCP transports are deferred.

## Scope

In scope:

- deployment-internal transport types under `io.quarkus.deployment.dev`;
- serialized launch-context metadata in `DevModeContext` for an optional
  external build-output transport URI plus token;
- TCP loopback client as the only first implementation on the Quarkus side;
- TCP endpoints are local IPC only: both the generated server URI and accepted
  client targets are loopback addresses;
- length-prefixed UTF-8 JSON messages;
- random shared token authentication;
- bounded in-process queue between socket reader and processor;
- direct dispatch to `RuntimeUpdatesProcessor.processBuildOutputChanges(...)`;
- a public transport factory for build-tool listener creation plus internal
  opaque `AutoCloseable` Quarkus-side connections from
  `DevModeContext.ExternalBuildOutputTransport`;
- focused codec, framing, auth, queue, and lifecycle tests.

Out of scope:

- TLS;
- stdin/file/HTTP transports;
- remote-dev protocol changes;
- Gradle plugin wiring;
- test and continuous-test routing through `TestSupport`;
- changed dependency handling;
- Dev UI status or failed-build diagnostics display.

## Wire Contract

Use an initial line-based hello followed by length-prefixed JSON frames instead
of Java serialization.

Hello format:

```text
quarkus-build-output/1 <random-shared-token>\n
```

Semantics:

- the Quarkus-side TCP client sends the hello immediately after connecting;
- the build-tool-owned server reads the hello with a bounded line read;
- the server rejects unsupported versions, missing/blank tokens, and invalid
  tokens by closing the connection;
- the token authenticates the Quarkus client to the build-tool server once per
  connection and is not repeated in JSON frames.
- token comparison is constant-time over UTF-8 bytes;
- the first implementation intentionally does not support remote hosts. Remote
  scenarios need a separate protocol/security design, likely including TLS.

Frame format:

- 4-byte signed big-endian length;
- UTF-8 JSON payload of exactly that length;
- reject negative lengths and payloads above a fixed maximum.

Initial maximum payload size: 1 MiB. This is intentionally conservative for a
control-message transport that carries paths and metadata, not file contents.

JSON shape:

```json
{
  "sequence": 1,
  "status": "BUILD_SUCCEEDED",
  "mainClassChanges": [
    {
      "outputRoot": "/path/to/classes",
      "changedPath": "/path/to/classes/com/acme/Foo.class",
      "kind": "MODIFIED"
    }
  ],
  "mainResourceChanges": [],
  "testClassChanges": [],
  "testResourceChanges": [],
  "changedDependencyPaths": [],
  "failureSummary": null,
  "diagnosticsPath": null,
  "userInitiated": false,
  "forceRestart": false
}
```

Semantics:

- missing list fields default to empty lists;
- unknown JSON fields are ignored for forward compatibility;
- path values are represented as strings and converted with `Path.of(...)`;
- each changed path must normalize under its declared output root;
- status and kind values use the enum names;
- invalid JSON, missing required scalar fields, invalid enum values, invalid
  paths, changed paths outside their output root, and malformed frames reject
  the message.

## Blocking And Queueing

Socket reader threads must not execute reload work directly. Each decoded
message is decoded into a `BuildOutputChanges` instance and offered to a bounded
queue.

Initial queue policy:

- queue capacity: 16 batches;
- if the queue is full, drop the new batch and log a warning;
- do not block socket reader threads indefinitely;
- rely on `BuildOutputChanges.sequence` stale rejection for ordering safety;
- do not implement `coalesce(batch1, batch2)` in this slice.

This deliberately avoids a more complex merge/coalescing model. A later slice
may replace the drop-new policy with a latest-only or coalescing policy if real
continuous-build traffic shows the need.

## Implementation Steps

1. Add a transport codec.
   - Suggested type: package-private `BuildOutputChangesJsonCodec`.
   - Use `io.quarkus.bootstrap.json` for parsing and `Json` builders for test
     fixture generation if useful.
   - Add `quarkus-bootstrap-json` as a direct `core/deployment` dependency if
     production code imports it.
   - Tests:
     - decode full message;
     - missing optional lists become empty;
     - invalid enum rejected;
     - unknown fields ignored.

2. Add protocol hello and frame reader/writer helpers.
   - Suggested type for hello handling: package-private
     `BuildOutputChangesProtocol`.
   - The TCP client sends `quarkus-build-output/1 <token>\n` immediately after
     connecting.
   - The server side reads the hello with a bounded line read and closes the
     connection if the version or token is unsupported.
   - Suggested type: package-private `BuildOutputChangesFrameCodec`.
   - Use `DataInputStream` / `DataOutputStream` for big-endian length prefixes.
   - Tests:
     - hello round-trip;
     - unsupported hello version rejected;
     - blank hello token rejected;
     - oversized/truncated hello rejected;
     - round-trip JSON payload;
     - reject negative length;
     - reject oversized payload;
     - reject truncated payload.

3. Add a bounded dispatcher.
   - Suggested type: package-private `BuildOutputChangesDispatcher`.
   - Own a bounded `ArrayBlockingQueue<BuildOutputChanges>`.
   - One daemon worker thread drains the queue and calls a supplied
     `Consumer<BuildOutputChanges>`.
   - `close()` stops the worker and interrupts waits.
   - Tests:
     - accepted messages reach the consumer;
     - full queue rejects later offers without blocking;
     - close stops dispatch.

4. Add a TCP client.
   - Suggested type: package-private `BuildOutputChangesTcpClient`.
   - Connect only to a build-tool-owned endpoint.
   - The build tool owns the listening socket and starts it before launching
     Quarkus dev mode.
   - Use a bounded TCP connect timeout so a bad endpoint cannot hang startup
     indefinitely.
   - Send the bounded hello line before reading frames.
   - Read frames on one daemon reader thread.
   - Decode messages and offer them to the dispatcher.
   - Close the socket on `close()`.
   - Tests:
     - connect to a test server and verify one authenticated message dispatches;
     - wrong hello token causes the server to close and no message dispatches;
     - close releases the client connection.

5. Add a transport factory.
   - Suggested type: public `BuildOutputChangesTransports`.
   - Accept `DevModeContext.ExternalBuildOutputTransport` and a
     `Consumer<BuildOutputChanges>`.
   - Return only an opaque `AutoCloseable` for Quarkus-side connections.
   - Disabled transport returns a no-op closeable.
   - `tcp://host:port` creates `BuildOutputChangesTcpClient`.
   - TCP hosts must resolve to loopback addresses.
   - Missing URI, missing/blank token, missing host, missing port, and
     unsupported schemes fail fast with clear exceptions.
   - Do not put URI parsing or TCP construction on `RuntimeUpdatesProcessor`.
   - `RuntimeUpdatesProcessor` should consume only `BuildOutputChanges`, but it
     may own the opaque connection lifecycle so configured transport metadata is
     opened and closed with dev mode.
   - Provide public `createTcpServer()` for the build-tool side. It returns a
     public `BuildOutputChangesServer` whose `transport()` value can be passed
     into `DevModeContext` before launching Quarkus dev mode.
   - `BuildOutputChangesServer.transport()` returns independent metadata
     objects so caller mutation cannot alter server-owned endpoint/token state.
   - `BuildOutputChanges`, `BuildOutputPathChange`, `BuildOutputChangeKind`,
     and `BuildOutputChangeStatus` must be public so external build tools can
     construct and send batches through the server.

6. Add inert launch-context metadata.
   - Add a serializable `DevModeContext.ExternalBuildOutputTransport` nested
     type.
   - Transport metadata carries an optional endpoint URI and token.
   - A missing or `null` URI means disabled.
   - The first startup wiring slice should support `tcp://host:port` and reject
     unsupported schemes explicitly.
   - Do not start the TCP client from `IsolatedDevModeMain` in this slice.
     Startup wiring belongs to the later Gradle/dev-mode launch slice.

## Implementation Result

The TCP production-output transport slice is implemented:

- `BuildOutputChangesProtocol` writes and reads the bounded protocol hello;
- `BuildOutputChangesJsonCodec` encodes and decodes token-free JSON payloads;
- `BuildOutputChangesFrameCodec` reads and writes 4-byte length-prefixed UTF-8
  payloads with a 1 MiB maximum;
- `BuildOutputChangesDispatcher` provides a bounded queue with capacity 16 and
  drops new batches when full;
- `BuildOutputChangesTcpClient` connects to a build-tool-owned loopback server,
  uses a bounded connect timeout, sends the protocol hello with the shared
  token, reads framed messages, decodes batches, and dispatches them;
- `BuildOutputChangesServer` is the public build-tool-side listener contract.
  It owns transport metadata and can send `BuildOutputChanges` batches;
- `BuildOutputChangesTcpServer` binds a loopback ephemeral port, generates a
  random token, exposes independent matching `ExternalBuildOutputTransport`
  metadata, accepts and authenticates one Quarkus-side TCP client with a
  bounded hello timeout, keeps listening after rejected clients, closes
  in-progress authentication sockets during shutdown, and sends token-free
  framed batches;
- `BuildOutputChangesTransports` maps the optional launch-context URI/token to
  an opaque `AutoCloseable` connection, creates TCP servers for build-tool
  launchers, and keeps URI parsing/TCP construction out of
  `RuntimeUpdatesProcessor`;
- `RuntimeUpdatesProcessor` opens the opaque transport connection from
  `DevModeContext.ExternalBuildOutputTransport` and closes it with the rest of
  its lifecycle;
- `DevModeContext.ExternalBuildOutputTransport` carries an optional endpoint
  URI and token and defaults to disabled;
- the TCP server/client, codecs, protocol, and dispatcher remain internal
  implementation details.

The implementation intentionally does not coalesce batches. It processes
accepted batches as they arrive and relies on the monotonic sequence handling in
`RuntimeUpdatesProcessor.processBuildOutputChanges(...)` to reject stale
batches.

## External Build-Tool API

An external build tool has everything it needs in `core/deployment` for the
local TCP production-output dev workflow:

1. Create a listener with `BuildOutputChangesTransports.createTcpServer()`.
2. Put `server.transport()` into the `DevModeContext` used to launch Quarkus dev
   mode.
3. Start Quarkus dev mode. The Quarkus side connects back to the listener and
   authenticates with the generated token.
4. After successful external builds, call `server.send(new BuildOutputChanges(...))`.
5. Close the server when the dev-mode session ends.

The public build-tool-facing types are:

- `BuildOutputChangesTransports`;
- `BuildOutputChangesServer`;
- `BuildOutputChanges`;
- `BuildOutputPathChange`;
- `BuildOutputChangeKind`;
- `BuildOutputChangeStatus`;
- `DevModeContext.ExternalBuildOutputTransport`.

The TCP server/client implementations, JSON codec, frame codec, protocol helper,
and dispatcher remain internal.

## Verification

Run focused tests:

```bash
./mvnw -pl core/deployment -am -Dtest=io.quarkus.deployment.dev.BuildOutputChangesJsonCodecTest,io.quarkus.deployment.dev.BuildOutputChangesProtocolTest,io.quarkus.deployment.dev.BuildOutputChangesFrameCodecTest,io.quarkus.deployment.dev.BuildOutputChangesDispatcherTest,io.quarkus.deployment.dev.BuildOutputChangesTcpClientTest,io.quarkus.deployment.dev.BuildOutputChangesTransportsTest,io.quarkus.deployment.dev.DevModeContextTest,io.quarkus.deployment.dev.RuntimeUpdatesProcessorBuildOutputChangesTest -Dsurefire.failIfNoSpecifiedTests=false test
```

If class names change during implementation, update this command.

## Deferred Follow-Ups

- latest-only or coalescing queue policy;
- TLS or stronger authentication for remote scenarios;
- `stdin:` and `file:` endpoint scheme implementations;
- Gradle plugin wiring to create the server, pass `server.transport()` into the
  launch context, send batches, and close the server with the dev session;
- transport diagnostics in Dev UI;
- test and continuous-test routing;
- changed dependency handling.
