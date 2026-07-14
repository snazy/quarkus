# P1-AP-02A2 Progress

Status: complete
Last reviewed: 2026-07-06

## Scope

Implement named-output DSL and skeleton task registration for `P1-AP-02A2`.

## Checklist

- [x] A2.1 extension model with `quarkus.builds`
- [x] A2.1 typed output factory methods
- [x] A2.1 explicit class-based registration overloads
- [x] A2.1 descriptor conventions for output root and output name
- [x] A2.1 image, AOT, deployment, manifest, native, and build-property descriptor surfaces
- [x] A2.2 `QuarkusApplication*` task skeleton hierarchy
- [x] A2.2 typed task inputs and output root properties
- [x] A2.2 clear failure for skeleton task execution
- [x] A2.3 separate named-output registration path in `QuarkusPlugin`
- [x] A2.3 derived build, image, AOT image, deploy, and native-test task names
- [x] A2.3 legacy task registration preserved
- [x] A2.4 JVM test-suite integration deferred
- [x] A2.5 targeted verification

## Guardrails

- Legacy tasks are still registered and keep their existing task types.
- New task actions intentionally fail until later execution slices implement behavior.
- New native-test tasks are registered only for native executable outputs.
- New image and AOT image tasks are registered when their descriptor blocks are configured.
- No `quarkus<name>Deploy` sugar is registered; deployments use `quarkus<name>DeployTo<deployment>`.
