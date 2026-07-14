# P1-AP-02B AugmentResult Image Metadata Investigation

Date: 2026-07-07

## Summary

`AugmentResult` does not expose a dedicated container-image result API. It
exposes:

- `getResults()`: a list of `ArtifactResult`;
- `getJar()`: the `JarResult`, when present;
- `getNativeResult()`: the native executable path, when present;
- `getGraalVMInfo()`: native-image metadata, when present.

Container-image information reaches build tools, when it reaches them at all,
through `ArtifactResult` entries derived from `ArtifactResultBuildItem`. The
metadata map is untyped and extension-owned.

Current image metadata is enough to identify the image reference for several
builders, but it is not a full image manifest and does not consistently include
an image digest/SHA.

## Source Path

`AugmentActionImpl.createProductionApplication()` consumes
`ArtifactResultBuildItem`, `JarBuildItem`, `NativeImageBuildItem`, and
`SbomBuildItem`, then creates an `AugmentResult` from:

- each `ArtifactResultBuildItem` path/type/metadata;
- `JarBuildItem.toJarResult(...)`;
- `NativeImageBuildItem.getPath()`;
- `NativeImageBuildItem.getGraalVMInfo()`.

Relevant source:

- [AugmentResult.java](../../../../../independent-projects/bootstrap/core/src/main/java/io/quarkus/bootstrap/app/AugmentResult.java)
- [ArtifactResult.java](../../../../../independent-projects/bootstrap/core/src/main/java/io/quarkus/bootstrap/app/ArtifactResult.java)
- [ArtifactResultBuildItem.java](../../../../../core/deployment/src/main/java/io/quarkus/deployment/pkg/builditem/ArtifactResultBuildItem.java)
- [AugmentActionImpl.java](../../../../../core/deployment/src/main/java/io/quarkus/runner/bootstrap/AugmentActionImpl.java)

## Normal Container-Image Results

### Docker / Podman Common Processor

Docker and Podman share the common processor for normal jar/native image
artifact results.

Jar image result:

- type: `jar-container`
- path: `null`
- metadata:
  - `container-image`: built image reference
  - `pull-required`: `false`
  - `working-directory`: optional, from image inspection
  - `output-directory`: output target directory

Native image result:

- type: `native-container`
- path: `null`
- metadata:
  - `container-image`: built image reference
  - `pull-required`: `false`

The common processor inspects the built image to infer working directory and
base-image-related details, but it does not propagate image ID or digest into
the artifact metadata.

Relevant source:

- [CommonProcessor.java](../../../../../extensions/container-image/container-image-docker-common/deployment/src/main/java/io/quarkus/container/image/docker/common/deployment/CommonProcessor.java)

### Jib

Jib jar image result:

- type: `jar-container`
- path: `null`
- metadata:
  - `container-image`: target image reference
  - `pull-required`: whether push was requested
  - `working-directory`: Jib working directory
  - `output-directory`: output target directory

Jib native image result:

- type: `native-container`
- path: `null`
- metadata:
  - `container-image`: target image reference
  - `pull-required`: whether push was requested

Jib does obtain `JibContainer.getDigest()` and `JibContainer.getImageId()`. It
logs the digest and writes both values to configured files:

- `quarkus.jib.image-digest-file`, default `jib-image.digest`;
- `quarkus.jib.image-id-file`, default `jib-image.id`.

Those values are not currently copied into `ArtifactResult` metadata.

Relevant source:

- [JibProcessor.java](../../../../../extensions/container-image/container-image-jib/deployment/src/main/java/io/quarkus/container/image/jib/deployment/JibProcessor.java)
- [ContainerImageJibConfig.java](../../../../../extensions/container-image/container-image-jib/deployment/src/main/java/io/quarkus/container/image/jib/deployment/ContainerImageJibConfig.java)

### Buildpack

Buildpack jar image result:

- type: `jar-container`
- path: `null`
- metadata:
  - `container-image`: target image name

Buildpack native image result:

- type: `native-container`
- path: `null`
- metadata:
  - `container-image`: target image name

No digest or image ID is exposed through `ArtifactResult` metadata.

Relevant source:

- [BuildpackProcessor.java](../../../../../extensions/container-image/container-image-buildpack/deployment/src/main/java/io/quarkus/container/image/buildpack/deployment/BuildpackProcessor.java)

### OpenShift

OpenShift jar/native image results:

- type: `jar-container` or `native-container`
- path: `null`
- metadata: empty map

The result confirms that an OpenShift image build artifact result was produced,
but it does not expose the effective image reference, digest, or image ID.

Relevant source:

- [OpenshiftProcessor.java](../../../../../extensions/container-image/container-image-openshift/deployment/src/main/java/io/quarkus/container/image/openshift/deployment/OpenshiftProcessor.java)

## AOT-Enhanced Container-Image Results

AOT-enhanced image builds do not currently flow through
`AugmentResult.createProductionApplication()`. The legacy Gradle
`buildAotEnhancedImage` task runs a custom build via
`BuildAotEnhancedImageWorker`.

The Docker, Podman, and Jib AOT-enhanced paths produce
`BuildAotOptimizedContainerImageResultBuildItem` with only:

- `containerImage`: enhanced image reference

The Gradle worker invokes the custom build with
`BuildEnhancedAotContainerImageCommandHandler`, but that command handler is
currently empty and the worker does not read a structured result. The legacy
Gradle task therefore has no structured image result beyond successful
completion/logging.

Relevant source:

- [BuildAotEnhancedImage.java](../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/BuildAotEnhancedImage.java)
- [BuildAotEnhancedImageWorker.java](../../../../../devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/BuildAotEnhancedImageWorker.java)
- [BuildAotOptimizedContainerImageResultBuildItem.java](../../../../../core/deployment/src/main/java/io/quarkus/deployment/pkg/builditem/BuildAotOptimizedContainerImageResultBuildItem.java)
- [BuildEnhancedAotContainerImageCommandHandler.java](../../../../../core/deployment/src/main/java/io/quarkus/deployment/cmd/BuildEnhancedAotContainerImageCommandHandler.java)
- [DockerProcessor.java](../../../../../extensions/container-image/container-image-docker/deployment/src/main/java/io/quarkus/container/image/docker/deployment/DockerProcessor.java)
- [PodmanProcessor.java](../../../../../extensions/container-image/container-image-podman/deployment/src/main/java/io/quarkus/container/image/podman/deployment/PodmanProcessor.java)
- [JibProcessor.java](../../../../../extensions/container-image/container-image-jib/deployment/src/main/java/io/quarkus/container/image/jib/deployment/JibProcessor.java)

## Implications For P1-AP-02B

- Do not model container images themselves as Gradle file outputs.
- Image build/push tasks should write a small Gradle-owned result/receipt file
  as their declared output.
- Use `@Nested` Gradle beans for intended image target inputs only. Produced
  image data should be serialized into the receipt file, exposed with
  `@OutputFile`, and consumed by dependent tasks with `@InputFile`.
- Add a small support model around that boundary:
  - `ContainerImageTarget` for declared task inputs;
  - `BuiltContainerImage` for the normalized Java result;
  - `BuiltContainerImageResultCodec` for receipt serialization and
    deserialization;
  - builder-specific extractors/generators for Jib, Docker/Podman, Buildpack,
    OpenShift, and AOT-enhanced image results.
- The receipt should always include the requested/effective image reference and
  builder when known.
- Digest/SHA must be optional. Today Jib is the only inspected builder that
  clearly obtains a digest and image ID for Gradle to enrich from side files; it
  writes those values to configured output files rather than exposing them in
  `ArtifactResult` metadata. The absence of equivalent digest metadata for
  Docker/Podman, Buildpack, and OpenShift is based on source inspection of the
  current processors, not on an explicit Quarkus API guarantee.
- A production operations layer can enrich the receipt from:
  - `AugmentResult.getResults()` image artifact metadata;
  - known Jib digest/image-id files when configured and present;
  - AOT custom-build result data if/when the command handler is made to expose
    it.
- OpenShift currently cannot provide a result image reference from
  `ArtifactResult` metadata alone.
- A future Quarkus core/API improvement could add typed image result metadata
  instead of requiring Gradle to parse extension-specific metadata maps and
  side files.

## Receipt Schema

Use a conservative UTF-8 Java-properties-style receipt. The codec writer should
use `io.quarkus.bootstrap.util.PropertyUtils.store(...)`, not
`java.util.Properties.store(...)`, so generated receipts have deterministic
escaping, stable lexical key order, and no timestamp/date comment. Unknown
optional fields must be omitted rather than populated with guessed values.
Readers must ignore unknown fields so later Quarkus versions can add metadata
without breaking older consumers. Malformed receipts should fail with a message
naming the file and field.

Required fields:

- `schema.version`: initially `1`.
- `result.type`: one of `jar-container`, `native-container`,
  `aot-enhanced-container`, or a future explicit value.
- `image.builder`: one of the modeled builder enum values lower-cased for the
  Quarkus builder name, such as `jib`, `docker`, `podman`, `openshift`, or
  `buildpack`.
- `image.pushed`: `true` or `false`, reflecting selected Gradle operation
  intent.

Conditionally required fields:

- `image.reference`: required when the descriptor or Quarkus result provides an
  effective image reference. For OpenShift, this may be absent when current
  `ArtifactResult` metadata does not expose the reference and the descriptor did
  not provide a complete reference.

Optional fields:

- `image.digest`
- `image.id`
- `image.pull-required`
- `image.working-directory`
- `image.output-directory`

Minimum valid normal image build receipt:

```properties
schema.version=1
result.type=jar-container
image.builder=jib
image.pushed=false
image.reference=quay.io/acme/app:1.0
```

Minimum valid normal image push receipt:

```properties
schema.version=1
result.type=jar-container
image.builder=jib
image.pushed=true
image.reference=quay.io/acme/app:1.0
```

The receipt file is useful for Gradle task wiring, diagnostics, and downstream
consumers. It is not proof that Gradle owns the external image artifact.

Codec requirements:

- round-trip known fields without changing values;
- preserve absence as absence for optional fields;
- write with `PropertyUtils.store(...)` and never with
  `Properties.store(...)`;
- reject invalid booleans and unknown required enum values;
- tolerate additional unknown keys;
- never infer or fabricate `image.digest`.
