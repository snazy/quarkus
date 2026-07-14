# Application Plugin Build Shapes

Status: current
Last reviewed: 2026-07-13

This directory tracks `P1-AP-02`, the Quarkus Gradle application-plugin work to
replace graph-selected package/native/image/deploy intent with explicit named
application outputs and stable task inputs. The named-output model now belongs
to the standalone `io.quarkus.application` plugin in
`devtools/gradle/gradle-app-plugin`; the legacy `io.quarkus` plugin keeps
compatibility behavior and legacy diagnostics.

## Current Docs

- `design.md`: source-of-truth design for the named-output model.
- `phase-b-task-topology.md`: task names, task types, dependencies, convenience
  tasks, Mermaid diagrams, and B0/B1/B2 implementation-slice boundaries for
  Phase B and later execution slices.
- `phase-b-augment-result-image-metadata.md`: investigation of image metadata
  available from `AugmentResult`, `ArtifactResult`, and container-image
  extensions.
- `phase-c-deployment-investigation.md`: investigation of legacy Gradle deploy
  behavior, Quarkus Kubernetes-family deployment machinery, image-source
  handling, and Phase C planning implications.
- `phase-d-aot-enhanced-image-investigation.md`: investigation of named
  AOT-enhanced image build/push execution, legacy metadata behavior, core AOT
  result handling, image receipts, and AOT producer boundaries.
- `effective-config-history.md`: history, tests, and reuse boundaries for
  `EffectiveConfig`, `EffectiveConfigProvider`, SmallRye Config source
  handling, and Gradle worker propagation.
- `package-output-naming-design.md`: proposed design for Gradle-style,
  legacy-compatible package/native output file naming in the new
  `io.quarkus.application` plugin while keeping named output directories
  isolated.

## Archived Docs

- `archive/phase-a/implementation-plan.md`: completed Phase A implementation
  plan.
- `archive/phase-a/investigation-results.md`: delegated Phase A investigation
  results.
- `archive/phase-a/a1-progress.md`, `a2-progress.md`, `a3-progress.md`:
  completed progress notes for Phase A slices.
- `archive/phase-b/implementation-plan.md`: completed Phase B implementation
  plan with ordered B0/B1/B2 steps, tests, and acceptance gates.
- `archive/phase-c/implementation-plan.md`: completed Phase C implementation
  plan for named deployment tasks.
- `archive/phase-d/implementation-plan.md`: completed Phase D implementation
  plan for named AOT-enhanced image build/push tasks and AOT deploy
  image-source enablement.
- `archive/phase-e/implementation-plan.md`: completed Phase E implementation
  plan for named fast-jar, mutable-jar, uber-jar, and legacy-jar package
  outputs.
- `archive/phase-f/implementation-plan.md`: completed Phase F implementation
  plan for named native executable and native-sources outputs.
- `archive/package-output-naming-implementation-plan.md`: completed
  implementation plan for Gradle-style, legacy-compatible package/native output
  file naming.

Phase A established the value objects, planners, initial named-output DSL,
skeleton task registration, and legacy diagnostics. Phase B added normal
named-output image build/push task wiring and deterministic image receipts.
Phase C added executable named deployment tasks for Kubernetes-family targets
using explicit image sources and deterministic deployment receipts.
Phase D added executable named AOT-enhanced image build/push tasks,
suffix-based enhanced image references using the existing core AOT request,
deterministic AOT image receipts, and AOT image-source deployment wiring.
Phase E added worker-backed named JVM package output execution, deterministic
package receipts, and full `JarResult`-based package result extraction.
Phase F added worker-backed named native executable and native-sources
execution, deterministic native receipts, and native result extraction from
`AugmentResult` facts.
Future phases start from the current design, topology, and metadata reference
docs, plus phase-specific investigation docs, not from archived implementation
plans.

## Current Code Inventory

The named-application package lives under
`devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/`.

Behaviorally meaningful pieces:

- descriptor/model types under `application/model`;
- task-name, output-layout, build-intent, image-reference, AOT, deployment, and
  package-layout inference planners under `application/planning`;
- `quarkusApplication.builds` DSL types, including typed build and deployment
  factories;
- named build/image/AOT/deploy/native-test task types and task registration;
- descriptor-driven effective-config planning and filtered config-input capture;
- normal named-output image build/push task execution through the worker-backed
  Quarkus build path;
- named AOT-enhanced image build/push task execution through the worker-backed
  Quarkus build path;
- normalized image receipt model, codec, and image-result extractors;
- named deployment image-source resolution, conflict validation, deploy
  operation requests, deployment receipts, and worker-backed deploy execution;
- production operations boundary around existing worker/bootstrap execution;
- named JVM package run-task execution;
- legacy task usage diagnostics and report generation.

Later-phase pieces:

- AOT training execution;
- named native-test/remote-dev/continuous-test behavior;
- gated real Docker/Podman/registry integration coverage.

Existing focused tests live under
`devtools/gradle/gradle-app-plugin/src/test/java/io/quarkus/gradle/application/`
and currently cover DSL storage, task registration, name validation, planner
behavior, image receipt/result handling, config-input planning, and worker
operation mapping. Future phases should keep the same preference order: pure
unit tests first, ProjectBuilder/TestKit for Gradle wiring, and heavy
integration tests only for real Quarkus/container behavior.
