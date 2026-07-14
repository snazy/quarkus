# Gradle Application Plugin Parity Investigation

Date: 2026-07-13
Scope: compare hidden functional contracts in the legacy `io.quarkus` Gradle application plugin and `integration-tests/gradle` against the new `io.quarkus.application` plugin work.

This is a tracked working note for iteration. It intentionally excludes expected DSL, configuration-cache, and isolated-project design differences unless they expose a hidden behavior contract.

## Current Candidate Findings

### Kotlin, KAPT, KSP, And Generated Sources

Legacy behavior:

- `compileJava` and `compileTestJava` receive Quarkus-generated source directories.
- `compileKotlin` and `compileTestKotlin` receive Quarkus-generated source directories.
- `kaptGenerateStubsKotlin` and `kaptGenerateStubsTestKotlin` also receive those generated source directories because KAPT stub generation does not inherit the sources injected into `compileKotlin`.
- Legacy code has explicit comments pointing at issues `#29698` and `#50486`.
- Legacy code also has task-ordering workarounds for generated-source compile tasks and mentions issue `#45057` for `org.gradle.parallel=true` / IntelliJ ordering.

Evidence:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/KaptGrpcMapStructTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/KspPluginWithSourcesJarTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/KotlinGRPCProjectBuildTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/GrpcDescriptorSetBuildTest.java`

Initial assessment:

- Likely real gap for the new plugin: `TaskRegistration.wireGeneratedSourcesIntoJavaCompilation()` currently wires Java compile tasks only.
- Needs history investigation for `#29698`, `#50486`, and `#45057`.

### Test Task Preparation And Test Models

Legacy behavior:

- Configures every Gradle `Test` task with Quarkus-specific system properties, `java.util.logging.manager`, module opens/exports, `useJUnitPlatform()`, serialized test application model input, compose-file inputs, and a `BeforeTestAction`.
- Creates `integrationTest` and `native-test` source sets/configurations and registers `quarkusIntTest` and deprecated `testNative`.
- Supports non-standard test source sets and Java test fixtures in Quarkus test models.

Evidence:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/actions/BeforeTestAction.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/AdditionalSourceSetsTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/TestFixtureMultiModuleTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/TestFixturesClientExceptionMapperTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/nativeimage/CustomNativeTestSourceSetIT.java`

Initial assessment:

- Likely real gap or deliberate deferral. The new plugin has named native-test task shape, but not the legacy source-set/test-task setup.
- Needs a design decision before implementation because the new plugin may not want to own all legacy test task conventions.

### Dev Mode And Continuous Testing

Legacy behavior:

- `quarkusDev` is a live reload contract: start, HTTP readiness, source mutation, reload, and process cleanup.
- `quarkusDev --tests` forwards test filters into continuous testing.
- `quarkusDev` exposes command-line `@Option` inputs that users can pass from
  Gradle invocations, including `--jvm-args`, `--quarkus-args`, `--modules`,
  `--open-lang-package`, `--compiler-args`, and `--tests`.
- `quarkusTest` runs continuous testing without a persistent HTTP endpoint; tests parse command output for status.
- Compile-only dependencies such as Lombok must be available during live reload in `quarkusTest`.
- Coverage spans Java, Kotlin, generated sources, gRPC, Avro, Jandex, composite/included builds, environment variables, working directory, and dotenv behavior.

Evidence:

- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/QuarkusDevGradleTestBase.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/TestSelectionTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/CompileOnlyContinuousTestingModeTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/continuoustesting/ContinuousTestingLogClient.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/AddEnvironmentVariablesDevModeTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/CustomWorkingDirDevModeTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/DotEnvQuarkusDevModeConfigurationTest.java`

Initial assessment:

- New dev mode is intentionally Gradle-continuous-build driven, so direct parity is not expected.
- Hidden contracts still matter for future dev/continuous-test work: test filters, compile-only dependencies, env/system-property/working-directory propagation, generated-source reload, and process cleanup.
- `--jvm-args` and `--quarkus-args` overlap with the new
  `QuarkusApplicationRunTask` command-line surface and should probably be
  shared with the new dev task through a small package-private task-options
  contract if Gradle task option discovery accepts the inherited/interface
  methods cleanly. Dev-only options such as `--tests`, `--compiler-args`,
  `--modules`, and `--open-lang-package` should stay separate unless the new
  dev design has an equivalent runtime contract.

### Application Model Dependency Semantics

Legacy behavior:

- NORMAL, TEST, and DEVELOPMENT application models differ materially.
- Compile-only dependency flags matter.
- Normal model must not include root test dependencies.
- Test model must include test dependencies at the right level.
- Normal/test models generated in the same invocation must not leak dependencies into each other.
- Conditional dependencies require runtime/deployment split, exclusions, explicit platform imports, and dev-only dependency handling.

Evidence:

- `integration-tests/gradle/src/test/java/io/quarkus/gradle/CompileOnlyDependencyFlagsTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/CompileOnlyDependencyFlagsBuildTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/DeclaredDependenciesMinimalTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/ConditionalDependenciesTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/ConditionalDependenciesKotlinTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/EnforcingPlatformForConditionalDepsTest.java`

Initial assessment:

- New model generation already addresses a lot of this, but this deserves a direct model-diff pass against legacy for NORMAL/TEST/DEVELOPMENT.

### Worker JVM And System Property Isolation

Legacy behavior:

- Forked workers scrub Quarkus system properties so modules do not leak configuration into each other.
- In-process workers must not scrub daemon properties.
- Multi-module builds with conflicting package output names must be isolated in both task orders.

Evidence:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/QuarkusWorker.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/execution/worker/QuarkusWorker.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/MultiModuleConfigIsolationTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/NoProcessWorkerProfileConfigDevModeTest.java`

Initial assessment:

- New worker has analogous logic. Needs history investigation for issues `#54095` and `#55131`, plus targeted app-plugin regression coverage.

### Run Task Behavior

Legacy behavior:

- `quarkusRun` must use `QuarkusBootstrap.Mode.RUN`, not `Mode.TEST`.
- Rest Data Panache + Liquibase catches incorrect launch mode.
- Additional source-set dependency catches missing classpath/source-set handling.
- Process handling is fragile: output forwarding, shutdown hook behavior, Ctrl-C, and Gradle daemon behavior were explicitly called out in comments.

Evidence:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusRun.java`
- `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/execution/run/ForegroundProcessRunner.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/run/QuarkusRunWithRestDataPanacheAndLiquibaseTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/run/AdditionalSourceSetAsDependencyTest.java`

Initial assessment:

- New run task now uses `Mode.RUN` and has a custom `ForegroundProcessRunner`,
  but still needs parity regression tests.
- The archived run-task implementation plan records the completed first slice;
  active follow-up should focus on parity coverage and any intentional
  differences from the legacy `quarkusRun` task.
- Needs history investigation for `#54001`, `#48950`, commit `46ce666581d`, and `#48768`.

### Package Layout, Native, Image, And AOT

Legacy behavior:

- Fast jar, mutable jar, legacy jar, uber jar, and multi-module uber jar are validated by launching artifacts, not only by checking file existence.
- Timestamps are expected to be preserved while copying app parts.
- Legacy injects `quarkus.package.output-timestamp=1970-01-02T00:00:00Z` when Gradle `jar` does not preserve timestamps.
- Native tests validate native-image log markers, binary naming, custom output names, suffix behavior, executable bit, and HTTP boot.
- AOT/Jib test validates a Docker image exists for the AOT-enhanced image.
- Image tasks have legacy configuration-cache and isolated-project integration coverage.

Evidence:

- `integration-tests/gradle/src/test/java/io/quarkus/gradle/FastJarFormatWorksTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/MutableJarFormatBootsInDevModeTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/LegacyJarFormatWorksTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/UberJarFormatWorksTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/MultiModuleUberJarTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/TimestampsComparisonTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/nativeimage/BasicJavaNativeBuildIT.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/nativeimage/NativeIntegrationTestIT.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/JibAotTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/ImageTasksWithConfigurationCacheTest.java`

Initial assessment:

- Needs explicit support/defer matrix for new plugin package/native/image/AOT behavior.
- The timestamp convention looks like a likely real gap unless new packaging avoids the same issue another way.

### Composite Builds, Included Builds, Jandex, And Local Extensions

Legacy behavior:

- Composite library and extension outputs must be built and copied into `quarkus-app/lib/main`.
- Included Quarkus builds must not fail during `jar`.
- Legacy adds dependencies/orderings for local extension deployment modules, `processResources`, `jar`, `jandex`, and `processJandexIndex`.

Evidence:

- `integration-tests/gradle/src/test/java/io/quarkus/gradle/BasicCompositeBuildQuarkusBuildTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/BasicCompositeBuildExtensionQuarkusBuildTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/MultiCompositeBuildExtensionsQuarkusBuildTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/IncludedQuarkusBuildTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/JandexMultiModuleTest.java`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/devmode/JandexIncludedBuildTest.java`

Initial assessment:

- New plugin uses variant-aware dependency/model generation, which may cover some of this differently.
- Needs deeper investigation around local extension and Jandex ordering before declaring this intentional.

## Deep Investigation Results

### Kotlin, KAPT, KSP, And Generated Sources

History:

- `#29698`: KSP builds failed with a cycle after Quarkus 2.13:
  `kspKotlin -> quarkusGenerateCode -> processResources -> kspKotlin`.
  Public discussion points at `#27764` as the likely original regression.
- PR `#49811`, commit `03c8011bf84`: changed Gradle code generation wiring from
  adding generated source directories to the main/test `SourceSet` to adding
  them directly to compile tasks. This fixed the KSP and `sourcesJar` cycle.
- `#45057`: after `#49811`, IntelliJ-selected task graphs with
  `org.gradle.parallel=true` could trigger Gradle implicit dependency
  validation because `compileKotlin` consumed generated-source compile output
  without task ordering. Fixed by PR `#49864`, commit `01dfacb046e`, by adding
  `mustRunAfter` from Java/Kotlin compile tasks to generated-source compile
  tasks.
- `#50486`: `#49811` regressed Gradle/IDE generated-source visibility because
  generated directories were no longer source roots, and exposed KAPT/MapStruct
  failures because KAPT stubs did not inherit `compileKotlin` source injection.
  Fixed by PR `#53737`, upstream commit `5390769c04a`, local cherry-pick
  `d3b3c1ca02f`, by explicitly wiring `kaptGenerateStubsKotlin` and
  `kaptGenerateStubsTestKotlin`.

Legacy evidence:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java:526`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java:537`
- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/QuarkusPlugin.java:591`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/KspPluginWithSourcesJarTest.java:7`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/KaptGrpcMapStructTest.java:7`

Current legacy contract:

- Generated output is wired into `compileJava` / `compileTestJava`.
- Generated output is wired into `compileKotlin` / `compileTestKotlin`.
- Generated output is wired into `kaptGenerateStubsKotlin` /
  `kaptGenerateStubsTestKotlin` when KAPT is applied.
- Generated-source compile task ordering is preserved when those intermediate
  compile tasks are part of the graph.

New plugin implications:

- `io.quarkus.application` wires generated output into Java, Kotlin, and KAPT
  tasks from
  `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/TaskRegistration.java`
  and `KotlinGeneratedSourceWiring.java`.
- Generated output directories are
  `build/generated/sources/quarkus-application/{main,test}` from
  `TaskRegistration.java:124`.
- Kotlin and KAPT generated-source wiring is implemented in the new plugin.
  Remaining follow-up is KSP/source-set cycle regression coverage once a stable
  KSP version source is available for `gradle-app-plugin`.
- Do not add generated directories back to shared `SourceSet`s; that was the
  source of the KSP plus `sourcesJar` cycle.
- The new plugin already tests that generated directories are not added to
  shared source sets:
  `devtools/gradle/gradle-app-plugin/src/test/java/io/quarkus/gradle/application/QuarkusApplicationPluginTest.java:718`.
- Preserve the generated-source compile ordering workaround only if the new
  plugin introduces generated-source compile tasks analogous to legacy
  `compileQuarkusGeneratedSourcesJava`; otherwise direct task source inputs are
  the preferred model.
- Existing WIP notes already point in this direction:
  `devtools/gradle/docs-wip/gradle-cc-work/archive/p1-ap-01-codegen-project-walk-plan.md:264`
  and
  `devtools/gradle/docs-wip/gradle-cc-work/new-application-plugin-design.md:943`.

### Run Task Mode, Dev Services, And Process Handling

History:

- `#40270`, implemented by `#40273`: `quarkus:run` / `quarkusRun` switched to
  TEST-mode bootstrap to get Dev Services.
- `#48950`: Maven Rest Data Panache plus Liquibase exposed the bug in that
  approach. TEST mode activated test-only Arc build steps and tried to index
  `io.quarkus.test.ActivateSessionContext` without `quarkus-test-common`.
- PR `#48968`, commit `46ce666581d`: Maven uses real `RUN` launch mode and
  adds `LaunchMode.RUN` with Dev Services support.
- `#54001`: Gradle later had the same TEST-mode bug.
- `#54320`: legacy `QuarkusRun` changed from
  `.setMode(QuarkusBootstrap.Mode.TEST)` to
  `.setMode(QuarkusBootstrap.Mode.RUN)`.
- `#48768`: separate source-set dependency bug. `quarkusRun` failed because a
  generated/source-set directory dependency was copied under a different
  synthetic JAR name than the runner expected.
- `#48800`, commit `32c1d557f9e`: fixed `quarkusDependenciesBuild` to use
  `JarResultBuildStep.getJarFileName(...)`.

Legacy evidence:

- `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/QuarkusRun.java:76`
- `integration-tests/gradle/src/test/java/io/quarkus/gradle/run/QuarkusRunWithRestDataPanacheAndLiquibaseTest.java:7`
- `integration-tests/gradle/src/main/resources/additional-source-set-as-dependency/build.gradle:17`

New plugin implications:

- `QuarkusApplicationRunTask` uses `QuarkusBootstrap.Mode.RUN` in
  `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/execution/worker/WorkerBackedBuildOperations.java:166`.
  Do not regress to TEST mode for Dev Services.
- It depends on package output and runs from the package result file:
  `TaskRegistration.java:581`, with runner-jar replacement in
  `WorkerBackedBuildOperations.java:218`.
- Run target selection matches the legacy intent with better failure semantics:
  `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/execution/run/RunCommandSelector.java:14`.
- Dev Services handling is stronger than legacy:
  `RunCommandResultHandler.java:38` merges launcher config and starts/closes
  registry services, while `WorkerBackedBuildOperations.java:201` closes them in
  `finally`.
- `ForegroundProcessRunner` uses inherited stdin:
  `ForegroundProcessRunner.java:22`.
- Remaining process risk: line-forwarded output can delay partial prompts that
  do not end with a newline. Legacy also line-consumed output, but sent both
  streams to `System.out`.
- Ctrl-C and daemon shutdown remain explicit test requirements. Legacy comments
  warned that `getProject().exec()` mishandles Ctrl-C and daemon mode may not
  trigger the shutdown hook (`QuarkusRun.java:128`). New runner has a shutdown
  hook and a five-second graceful destroy path in `ForegroundProcessRunner.java:32`.

### Worker JVM And Configuration Isolation

History:

- `#54095`: multi-module Gradle build leak. Module A's `quarkusAppPartsBuild`
  config leaked into Module B via reused Gradle worker JVM system properties,
  causing Module B to resolve Module A's datasource driver.
- `#54447`, merged 2026-05-27: reset `quarkus.*` and
  `platform.quarkus.*` JVM system properties before Quarkus bootstrap in forked
  workers to the current submission map. Discussion confirmed that silent
  default-value leaks were also possible.
- `#55131`: regression from that fix with
  `gradle.quarkus.gradle-worker.no-process=true`; dev-mode profile config such
  as `application-dev.properties` stopped loading.
- `#55184`, merged 2026-07-01: contract changed to scrub only
  forked/process-isolated workers. In-process/classloader-isolated workers must
  not scrub because that mutates Gradle daemon `System` properties.

Local evidence:

- Legacy and new implement the same scrub gate:
  `devtools/gradle/gradle-application-plugin/src/main/java/io/quarkus/gradle/tasks/worker/QuarkusWorker.java:55`
  and
  `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/internal/execution/worker/QuarkusWorker.java:55`.
- `createAppCreationContext()` calls reset only when `getProcessIsolated()` is
  true.
- New worker selection disables process isolation for `org.gradle.debug` or
  `gradle.quarkus.gradle-worker.no-process`:
  `WorkerBackedBuildOperations.java:401`, then uses classloader isolation in
  no-process mode and process isolation otherwise at
  `WorkerBackedBuildOperations.java:407`.
- Codegen mirrors the same worker selection in
  `WorkerBackedCodegenOperations.java:82`.
- Forked worker system properties are limited to `quarkus.`,
  `platform.quarkus.`, and `gradle.quarkus.`:
  `WorkerBackedBuildOperations.java:74`,
  `WorkerBackedBuildOperations.java:437`, and
  `WorkerBackedCodegenOperations.java:26`.
- Effective config uses declared maps unless legacy ambient capture is enabled:
  `EffectiveConfig.java:74` and `QuarkusApplicationBaseTask.java:111`.

Coverage implications:

- Legacy has targeted coverage in
  `MultiModuleConfigIsolationTest.java:12`,
  `no-process-worker-profile-config/gradle.properties:4`, and
  `NoProcessWorkerProfileConfigDevModeTest.java:5`.
- New plugin currently has unit mapping coverage in
  `WorkerBackedBuildOperationsTest.java:35` and
  `WorkerBackedCodegenOperationsTest.java:29`.
  These assert separation of bootstrap vs forked system properties, but do not
  exercise reused worker JVMs or the scrub/no-scrub behavior.
- New plugin TestKit hard-gates configuration cache and isolated projects in
  `QuarkusApplicationPluginTest.java:1688`.

Recommended new-plugin tests:

- Add a TestKit multi-module equivalent for `#54095`: two modules built in one
  invocation, one setting a Gradle-side propagated `quarkus.*` value and the
  other omitting it. Assert no crash and no silent output-name/config leak.
- Add a no-process/classloader regression equivalent for `#55131`:
  `systemProp.gradle.quarkus.gradle-worker.no-process=true`, with a required
  value only in `application-dev.properties`, proving dev/profile config
  survives.
- Add a focused unit test around `QuarkusWorker.resetQuarkusSystemProperties()`
  if feasible: stale `quarkus.*` and `platform.quarkus.*` are cleared in
  process-isolated mode, and no reset happens when `processIsolated=false`.
- Keep the scrub scope narrow.

### Test Tasks, Native, Image, AOT, Jandex, And Composite Builds

Legacy contracts:

- Gradle `Test` task preparation is broad. `QuarkusPlugin.java:492` wires
  `BeforeTestAction.java:58`, which sets Quarkus system properties, serialized
  test app model paths, output-source mappings, `TEST_TO_MAIN_MAPPINGS`, and
  `native.image.path`.
- Source-set contract: legacy creates `integrationTest` and `native-test`,
  extends configurations from test configurations, wires `quarkusIntTest`, and
  makes `testNative` run both `native-test` and `integrationTest` classes.
  Relevant code is in `QuarkusPlugin.java:140`, `QuarkusPlugin.java:443`, and
  `QuarkusPlugin.java:709`. History includes PR `#24064` and PR `#24459`.
- Native ITs validate artifact names and launch behavior, including
  `quarkus.package.output-name` and
  `quarkus.package.jar.add-runner-suffix=false`:
  `integration-tests/gradle/src/test/java/io/quarkus/gradle/nativeimage/NativeIntegrationTestIT.java:20`.
- Custom native test source sets are supported. The fixture maps
  `sourceSets.integrationTest` into `quarkus.sourceSets.extraNativeTest`:
  `integration-tests/gradle/src/main/resources/custom-java-native-sourceset-module/build.gradle:36`.
- Timestamp preservation was fixed by `#51767` for `#50726`. The regression
  test compares `build/quarkus-app` timestamps against generated/app/dependency
  copies in
  `integration-tests/gradle/src/test/java/io/quarkus/gradle/TimestampsComparisonTest.java:15`.
  Legacy build code uses `copyPreservingTimestamps` in
  `QuarkusBuildTask.java:319`.
- Jib AOT is Docker-backed, Java 25/non-Windows, runs
  `build quarkusIntTest buildAotEnhancedImage`, and checks that the AOT image
  exists:
  `integration-tests/gradle/src/test/java/io/quarkus/gradle/JibAotTest.java:16`.
  History includes PR `#52595`.
- Image tasks have configuration-cache and isolated-project coverage in
  `integration-tests/gradle/src/test/java/io/quarkus/gradle/ImageTasksWithConfigurationCacheTest.java:14`.
- Jandex ordering history includes `#43952` / `#48363` and `#54729` /
  `#54730`. Legacy orders all main-resource-consuming Quarkus tasks after
  `jandex` / `processJandexIndex` in `QuarkusPlugin.java:842`.
- Composite/included builds assert that included library and extension outputs
  are built and packaged into `quarkus-app/lib/main`:
  `BasicCompositeBuildQuarkusBuildTest.java:31`,
  `BasicCompositeBuildExtensionQuarkusBuildTest.java:34`, and
  `MultiCompositeBuildExtensionsQuarkusBuildTest.java:36`.

New plugin implications:

- Do not treat `quarkus<name>NativeTest` as parity yet. It is currently
  reserved/fails in
  `devtools/gradle/gradle-app-plugin/src/main/java/io/quarkus/gradle/application/tasks/QuarkusApplicationNativeTestTask.java:6`.
- New plugin has good unit/TestKit coverage for named package tasks,
  image/AOT/deploy receipts, package-element variants, and primary JAR
  inference:
  `TaskRegistration.java:472` and
  `QuarkusApplicationPluginTest.java:331`.
- Remaining high-risk parity needs executable integration coverage for:
  Gradle `Test` task preparation, `integrationTest` and custom native-test
  source-set semantics, native test launch/name validation, timestamp
  preservation in named outputs, real Jib AOT image flow, image
  configuration-cache and isolated-project behavior, Jandex ordering for named
  build/image/deploy/AOT tasks, and composite/included-build packaging.
