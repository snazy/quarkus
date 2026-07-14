# Effective Config History And Reuse Notes

Status: current
Last reviewed: 2026-07-07

This note records the behavior and history around `EffectiveConfig`,
`EffectiveConfigProvider`, SmallRye Config source handling, and Gradle worker
propagation. It exists so the named-output implementation can reuse the right
semantics without reusing the legacy task coupling.

In the standalone `io.quarkus.application` plugin, the corresponding DSL
surface is `quarkusApplication.buildProperties` plus per-build
`buildProperties`; those map to the legacy `quarkusBuildProperties` source
ordering described here.

## Current Behavior To Preserve

Current effective source ordering:

| Ordinal | Source |
| --- | --- |
| 600 | forced properties |
| 500 | task/manifest properties |
| 400 | JVM system properties |
| 300 | environment variables |
| 290 | extension `quarkusBuildProperties` |
| 280 | Gradle project properties |
| 265/260 | file-system `config/application.{yaml,yml,properties}` |
| 255/250 | classpath `application.{yaml,yml,properties}` |
| 110/100 | classpath `microprofile.{yaml,yml,properties}` |
| 0 | platform/default fallback properties |

Notable details:

- `quarkusBuildProperties` currently outrank Gradle project properties.
- Configuration-file values are part of the effective config and can influence
  build-time behavior.
- The full effective map must avoid expanding expressions when used as Gradle
  cache/task input state.
- Defaults from `PackageConfig` and `NativeConfig` are excluded from maps passed
  to workers unless explicitly set, so deprecated/config-origin behavior is not
  obscured by defaults.
- Worker propagation is source-aware. Quarkus workers receive only
  `quarkus.*` and `platform.quarkus.*` values from sources that the worker
  cannot otherwise see reliably: forced properties, task properties,
  `quarkusBuildProperties`, project properties, platform properties, system
  properties, and selected defaults.
- Environment variables and application config files are usually not propagated
  as worker system properties because Quarkus can read them directly.
- `quarkus.test.*` is an exception and is propagated from any source because
  launchers may read those values.
- Process-isolated Gradle workers reset stale `quarkus.*` and
  `platform.quarkus.*` system properties before bootstrapping Quarkus. This is
  required because Gradle can reuse worker JVMs across modules/tasks.
- In-process/classloader-isolated workers skip that reset because they share
  the Gradle daemon's system properties.
- Source directories are used as a classloader for config-file lookup, but
  `META-INF/services/*` from those source directories is hidden to avoid
  loading service implementations that may not be compiled yet.

Current source anchors:

- `../../../gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/EffectiveConfig.java`
  around line 52
  defines the effective source ordering and builds the SmallRye config.
- `../../../gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/EffectiveConfig.java`
  around line 122
  builds the full non-expanded config map.
- `../../../gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/EffectiveConfig.java`
  around line 156
  builds the source-aware Quarkus worker propagation map.
- `../../../gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/EffectiveConfigProvider.java`
  around line 55
  gathers legacy task inputs, manifest properties, defaults, source
  directories, profile, and additional forced properties.
- `../../../gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/QuarkusWorker.java`
  around line 23
  documents and implements forked-worker stale system-property reset.

## History

| Commit | Date | PR / refs | Behavior / rationale |
| --- | --- | --- | --- |
| [`f10a208b09a`](https://github.com/quarkusio/quarkus/commit/f10a208b09a) | 2023-02-18 | PR [`#31166`](https://github.com/quarkusio/quarkus/pull/31166), relates [`#30852`](https://github.com/quarkusio/quarkus/issues/30852) | Introduced `EffectiveConfig`, rewritten Gradle build config collection, YAML/YML support, worker isolation, `quarkusShowEffectiveConfig`, and the forced/task/system/env/Gradle/app-file ordering. The rationale was that Gradle-side config must override application config without mutating the Gradle daemon's system properties. |
| [`44b76fa9b90`](https://github.com/quarkusio/quarkus/commit/44b76fa9b90) | 2023-07-25 | PR [`#35003`](https://github.com/quarkusio/quarkus/pull/35003), fixes [`#34869`](https://github.com/quarkusio/quarkus/issues/34869) | Added cache-relevant property support for non-`quarkus.*` properties/env vars by regex. |
| [`a1f3057443f`](https://github.com/quarkusio/quarkus/commit/a1f3057443f) | 2023-10-31 | PR [`#36803`](https://github.com/quarkusio/quarkus/pull/36803), fixes [`#36767`](https://github.com/quarkusio/quarkus/issues/36767) | Fixed application config source locations by passing full file URIs to SmallRye Config, avoiding accidental lookup from the Gradle project directory. |
| [`6053da3880c`](https://github.com/quarkusio/quarkus/commit/6053da3880c) | 2023-12-16 | PR [`#37794`](https://github.com/quarkusio/quarkus/pull/37794) | Adapted to SmallRye Config 3.5.x and split YAML higher than properties. |
| [`a19f144957a`](https://github.com/quarkusio/quarkus/commit/a19f144957a) | 2024-02-22 | PR [`#38979`](https://github.com/quarkusio/quarkus/pull/38979) | Made `EffectiveConfig` accessible so `quarkus.test.profile` propagation could consume it. |
| [`11bd532b05d`](https://github.com/quarkusio/quarkus/commit/11bd532b05d) | 2024-02-23 | PR [`#38988`](https://github.com/quarkusio/quarkus/pull/38988) | Avoided expression expansion when producing config maps used by Gradle cache/task inputs. |
| [`6fd87d375b2`](https://github.com/quarkusio/quarkus/commit/6fd87d375b2) | 2024-06-20 | PR [`#41337`](https://github.com/quarkusio/quarkus/pull/41337) | Moved `quarkus.application.name`, `quarkus.application.version`, and ignored entries into SmallRye default values, lowering their precedence. |
| [`78045ea6741`](https://github.com/quarkusio/quarkus/commit/78045ea6741) | 2024-06-14 | PR [`#41897`](https://github.com/quarkusio/quarkus/pull/41897) | Adapted to SmallRye Config 3.9.0 with `ConfigUtils.emptyConfigBuilder().forClassLoader(...)`, system sources, YAML loaders, and properties sources. |
| [`778834e1dea`](https://github.com/quarkusio/quarkus/commit/778834e1dea) | 2024-08-20 | PR [`#42650`](https://github.com/quarkusio/quarkus/pull/42650) | Hid `META-INF/services/*` from source-dir config classloader to avoid loading uncompiled service implementations. |
| [`17d805a8248`](https://github.com/quarkusio/quarkus/commit/17d805a8248) | 2025-01-17 | PR [`#45681`](https://github.com/quarkusio/quarkus/pull/45681) | Added real platform properties into effective config instead of only a fallback builder-image placeholder. |
| [`f06d87135f0`](https://github.com/quarkusio/quarkus/commit/f06d87135f0) | 2025-07-03 | PR [`#48769`](https://github.com/quarkusio/quarkus/pull/48769) | Introduced `EffectiveConfigProvider` to gather effective-config inputs from `QuarkusPluginExtensionView`. |
| [`beecd2cdd32`](https://github.com/quarkusio/quarkus/commit/beecd2cdd32) | 2025-08-13 | PR [`#49503`](https://github.com/quarkusio/quarkus/pull/49503) | Stopped passing SmallRye default values to Quarkus workers/build. |
| [`50e4a237eea`](https://github.com/quarkusio/quarkus/commit/50e4a237eea) | 2025-09-01 | PR [`#49824`](https://github.com/quarkusio/quarkus/pull/49824) | Code generation switched back to the full config map, while excluding `PackageConfig` and `NativeConfig` entries. |
| [`6d934bf15b5`](https://github.com/quarkusio/quarkus/commit/6d934bf15b5) | 2025-10-15 | PR [`#50567`](https://github.com/quarkusio/quarkus/pull/50567) | Split full `values` from Quarkus-only `quarkusValues`; both exclude only `PackageConfig`/`NativeConfig` defaults, not all defaults. |
| [`9be7efdc7f6`](https://github.com/quarkusio/quarkus/commit/9be7efdc7f6) | 2025-10-28 | PR [`#50750`](https://github.com/quarkusio/quarkus/pull/50750) | Stopped collapsing `quarkus.*` project/build config into forced properties. This preserved source ordering by passing project properties as project properties. |
| [`410c1778cd9`](https://github.com/quarkusio/quarkus/commit/410c1778cd9) | 2025-12-19 | PR [`#51671`](https://github.com/quarkusio/quarkus/pull/51671) | Switched default detection to `ConfigValue.isDefault()` for SmallRye Config 3.15.0. |
| [`c940a0039dd`](https://github.com/quarkusio/quarkus/commit/c940a0039dd) | 2026-03-19 | PR [`#53180`](https://github.com/quarkusio/quarkus/pull/53180) | Made worker propagation source-aware: propagate Quarkus/platform properties from forced/task/build/project/platform/system/default sources, skip app config files/env. |
| [`9b40e452c81`](https://github.com/quarkusio/quarkus/commit/9b40e452c81) | 2026-04-14 | PR [`#53600`](https://github.com/quarkusio/quarkus/pull/53600) | Unified tasks such as deploy/run through `EffectiveConfigProvider`, removing a separate construction path. |
| [`ced8e96e4e1`](https://github.com/quarkusio/quarkus/commit/ced8e96e4e1) | 2026-04-14 | PR [`#53612`](https://github.com/quarkusio/quarkus/pull/53612) | Added Gradle `ValueSource` use for configuration-cache-friendly system-property access. |
| [`68676a829ea`](https://github.com/quarkusio/quarkus/commit/68676a829ea) | 2026-05-09 | PR [`#54055`](https://github.com/quarkusio/quarkus/pull/54055) | Always propagate `quarkus.test.*` values from any source to workers. |
| [`03f8ab33bd2`](https://github.com/quarkusio/quarkus/commit/03f8ab33bd2) | 2026-05-25 | PR [`#54447`](https://github.com/quarkusio/quarkus/pull/54447), issue [`#54095`](https://github.com/quarkusio/quarkus/issues/54095) | Reset stale `quarkus.*` / `platform.quarkus.*` system properties in reused Gradle worker JVMs to prevent cross-module config leaks. |
| [`b6ba5855b9b`](https://github.com/quarkusio/quarkus/commit/b6ba5855b9b) | 2026-06-20 | PR [`#54957`](https://github.com/quarkusio/quarkus/pull/54957) | Avoided `Project.getProperties()` under isolated projects; uses provider-backed Gradle property reads. |
| [`0d816ed0a04`](https://github.com/quarkusio/quarkus/commit/0d816ed0a04) | 2026-06-29 | PR [`#55184`](https://github.com/quarkusio/quarkus/pull/55184), issue [`#55131`](https://github.com/quarkusio/quarkus/issues/55131) | Skips worker system-property reset for in-process/classloader-isolated workers. |
| `f56491a335f` | 2026-07-06 | local branch, replace with upstream link when available | Local branch commit `Rework Gradle application model task wiring`; includes provider-backed env/system-property reads and `nativeBuild.getOrElse(false)` cleanup as part of broader Gradle application-model wiring changes. |

## Existing Test Coverage

Direct unit coverage:

- `EffectiveConfigTest.empty`: effective config includes JVM system properties
  and environment.
- `EffectiveConfigTest.fromProjectProperties`: project properties enter
  effective config.
- `EffectiveConfigTest.fromForcedProperties`: task properties enter effective
  config.
- `EffectiveConfigTest.appPropsOverload`: multiple resource directories are
  config sources and YAML beats properties.
- `EffectiveConfigTest.appPropsOverloadWrongProfile`: inactive profile values
  do not override the active/default profile.
- `EffectiveConfigTest.appPropsOverloadProdProfile`: prod-profile YAML wins
  over profile properties.
- `EffectiveConfigTest.crypto`: disabled; encrypted expression handling remains
  known-unfixed.

Integration and functional coverage:

- `BuildConfigurationTest`: `application.properties`, Gradle extension
  properties, and system properties affect package type/output behavior.
- `ConfigSystemOverrideProjectTest`: JVM system property beats Gradle project
  property for package jar type.
- `ConfigPropagationTest`: Gradle `quarkusBuildProperties` and Gradle
  properties become build-time fixed config, while `application.properties`
  remains file-backed instead of being propagated as a worker system property.
- `MultiModuleConfigIsolationTest`: reused worker JVMs must not leak one
  module's `quarkus.package.output-name` or datasource config into another.
- `SystemPropsAsBuildTimeConfigSourceTest`: build-time system properties affect
  build behavior without being written into generated `build-system.properties`
  as application build-system config.
- `NoProcessWorkerProfileConfigDevModeTest`: in-process dev-mode worker keeps
  the dev profile so `application-dev.properties` is loaded.
- `TestPrefixedProfilePropertiesTest`: `%test` properties work for tests and
  `%prod` values are not incorrectly propagated.
- `GrpcDescriptorSetAlternateOutputDirBuildTest`: `application.properties`
  affects code generation.
- `CustomManifestArgumentsTest`: Gradle manifest attributes and sections reach
  the packaged jar.
- `BuildForkOptionsAreIncludedInQuarkusBuildTaskTest`: fork options affect
  generate-code/build execution.

## Named-Output Gaps

Current named-output tests cover DSL storage, planner forced-property intent,
image property merging, and task registration. They do not yet prove that the
effective-config behavior above works through executable named-output tasks.

Before replacing legacy execution paths, add focused coverage for:

- named-output common and output-specific `quarkusBuildProperties`;
- manifest attributes/sections through named jar outputs;
- operation-specific forced properties for package, native, image build/push,
  and deploy;
- application config files and profile-specific files influencing named-output
  builds;
- source-aware worker propagation, including `quarkus.test.*`;
- per-output/per-module worker system-property isolation when multiple named
  outputs run in one Gradle invocation;
- direct unit tests for the extracted planner equivalent of
  `generateFullConfigMap()` and `generateQuarkusConfigMap()`.

Suggested Phase B test mapping:

| Slice | Coverage |
| --- | --- |
| B0 | Pure unit tests for source ordering, profile selection, full map generation, Quarkus worker map generation, default exclusion, descriptor-owned shape forcing/validation, and worker-reset input calculation. |
| B1 | ProjectBuilder/TestKit tests proving named-image task provider wiring, property conventions, image-scoped property merge order, config-file influence on ordinary config, descriptor-shape resistance to config files, and deterministic stub receipts. |
| B2 | Worker-oriented tests proving production requests are mapped to existing worker/bootstrap parameters, plus narrowly gated image integration tests for real metadata extraction and receipt writing. |

## Design Guidance

Do not treat the current `EffectiveConfigProvider` as a generic named-output
API. It is still shaped around `QuarkusPluginExtensionView`, global
`nativeBuild`, and call-time `additionalForcedProperties`.

The named-output model should extract or mirror the behavior as a pure planner
fed by explicit immutable inputs:

- platform properties and application coordinates from the app model;
- common extension build properties;
- registered-output build properties;
- operation-specific forced properties from package/native/image/deploy
  planners;
- manifest properties when supported by the output type;
- source directories for application config loading;
- provider-backed, `configInputs`-filtered system, environment, and Gradle
  project properties;
- explicit profile inputs.

Concrete planner shape for Phase B:

```java
record EffectiveConfigRequest(
        Map<String, String> platformProperties,
        String applicationName,
        String applicationVersion,
        Set<File> sourceDirectories,
        Map<String, String> commonBuildProperties,
        Map<String, String> outputBuildProperties,
        Map<String, String> operationForcedProperties,
        Map<String, ?> taskProperties,
        Map<String, ?> projectProperties,
        Map<String, String> environment,
        Map<String, String> systemProperties,
        Map<String, String> defaultProperties,
        String profile) {}

record EffectiveConfigPlan(
        Map<String, String> fullValues,
        Map<String, String> quarkusWorkerValues,
        Map<String, String> buildSystemProperties,
        Map<String, String> descriptorShapeValues) {}
```

The implementation can refine names and types, but it should keep these
responsibilities separate. `fullValues` mirrors `generateFullConfigMap()`.
`quarkusWorkerValues` mirrors `generateQuarkusConfigMap()`. Build-system
properties are the values intended for `QuarkusBootstrap#setBuildSystemProperties`
and generated `build-system.properties`. `descriptorShapeValues` are the
operation-owned values that must be forced and validated.

Named-output tasks should not capture every Gradle project property, JVM system
property, or environment variable by default. The normal path is driven by the
extension-level `configInputs` DSL:

- Gradle project property prefixes/names;
- JVM system property prefixes/names;
- environment-variable prefixes/names;
- `legacyAmbientConfigCapture`, conventioned from
  `-PquarkusBuildLegacyAmbientConfigCapture=true`.

Default prefixes are `quarkus.`, `platform.quarkus.`, and
`smallrye.config.` for Gradle project and JVM system properties, and
`QUARKUS_`, `PLATFORM_QUARKUS_`, and `SMALLRYE_CONFIG_` for environment
variables. Exact names are supported for all three source types. The configured
sets are task inputs. In normal mode, `buildSystemProperties` starts with
`quarkusWorkerValues` and merges explicitly modeled build, task, Gradle project,
and JVM system property values. In legacy ambient mode, `buildSystemProperties`
uses `fullValues`, and the task warns, disables build caching, opts out of
configuration-cache reuse, and is never up-to-date.

The output of that planner should separately expose:

- full effective config values for code generation/build operations that need
  the complete config view;
- Quarkus worker propagation values for system properties;
- build-system properties intended for `QuarkusBootstrap#setBuildSystemProperties`
  and `build-system.properties`.

Keep these maps distinct. Past regressions came from collapsing properties into
forced properties, propagating values that Quarkus could already read from
files/env, propagating defaults, and allowing stale worker JVM system
properties to survive between task submissions.

For named outputs, preserve one additional invariant: config sources may
contribute build-time configuration, but they must not define the registered
output shape. Package type, native enablement, jar enablement, output
directory/name, image build/push intent, modeled image builder, and AOT target
properties must come from the descriptor/operation layer at higher precedence
and be validated after effective config resolution. This prevents
`application.properties`, environment variables, or project properties from
silently turning a registered fast-jar output into an uber-jar, a jar output
into a native build, or an image-build task into a different image operation.

Validation should run after the effective config is created and before the
worker request is submitted. A failure should name the registered output, the
selected operation, the expected descriptor-owned value, and the resolved value,
for example:

```text
Named Quarkus output 'app' is registered as FAST_JAR but resolved
quarkus.package.jar.type=uber-jar while executing quarkusAppImageBuild.
Descriptor-owned output shape must not be changed by application config.
```
