# Gradle Integration Test Full Run - 2026-07-04

Status: evidence
Current tracker: ../../tracker.md

## Run

- Command: `./mvnw -f integration-tests/gradle/pom.xml test`
- Started from: `/home/snazy/devel/quarkusio/quarkus/master`
- Finished: `2026-07-04T22:33:05+02:00`
- Result: failed
- Surefire summary: 164 tests, 1 failure, 0 errors, 7 skipped

## Rerun After Fix

- Command: `./mvnw -f integration-tests/gradle/pom.xml test`
- Started from: `/home/snazy/devel/quarkusio/quarkus/master`
- Finished: `2026-07-04T23:09:44+02:00`
- Result: passed
- Maven summary: `BUILD SUCCESS`
- Surefire failure scan: no `<failure>` or `<error>` entries in `integration-tests/gradle/target/surefire-reports/*.xml`

## Failure Inventory

### GIT-20260704-01 - Dry-run resolves Quarkus deployment configurations

- Failing test: `io.quarkus.gradle.JavaPlatformWithEagerResolutionTest.dryRunShouldNotResolveDeploymentConfigurations`
- Report: `integration-tests/gradle/target/surefire-reports/TEST-io.quarkus.gradle.JavaPlatformWithEagerResolutionTest.xml`
- Inner command: `test --dry-run -I log-resolution.init.gradle.kts`
- Observed resolved configurations before dry-run skipped the tasks:
  - `quarkusProdRuntimeClasspathConfigurationDeployment`
  - `quarkusTestRuntimeClasspathConfigurationDeployment`
- Expected behavior: `--dry-run` may select and skip Quarkus app-model/codegen tasks, but must not resolve the deployment configurations.

Diagnosis:

- The recent app-model/declared-dependencies task wiring stores Gradle resolution objects on selected Quarkus tasks.
- With `--dry-run`, those tasks do not execute, but configuration-cache storage still serializes the selected work graph.
- Trace evidence showed `ArtifactCollectionCodec.encode(...)` visiting an `ArtifactCollection` while writing the configuration-cache work graph.
- Serializing the app-model task's nested `QuarkusResolvedClasspath` state also resolves the compile-only configuration, which is configured with `shouldResolveConsistentlyWith(getDeploymentConfiguration())`; that asks the deployment configuration for consistent-resolution version locks and resolves it.
- This regresses the eager-resolution guard covered by `JavaPlatformWithEagerResolutionTest`.

Fix:

- For Gradle dry-run builds, do not wire the Quarkus app-model task's execution-only resolved classpath state. The task is selected only to be skipped, so its execution inputs are not needed.
- Also keep `QuarkusDeclaredDependenciesTask` free of deployment-backed state during dry-run.
- For non-dry-run builds, preserve the existing execution wiring.
- Switch the declared-dependencies POM input to Gradle's native `artifactType=pom` artifact view instead of deriving POM files from the deployment artifact collection through a provider.

Proof:

- `./mvnw -f devtools/gradle/pom.xml install -DskipTests`
- `./mvnw -f integration-tests/gradle/pom.xml -Dtest=JavaPlatformWithEagerResolutionTest test`
  - Passed: 2 tests, 0 failures.
  - The test covered both the default Gradle wrapper and the generated Gradle 8.14 wrapper.
- `./mvnw -f integration-tests/gradle/pom.xml -Dtest=DeclaredDependenciesMinimalTest test`
  - Passed: 4 tests, 0 failures for each wrapper run.
  - Configuration-cache reports for the relevant Gradle invocations reported `0 problems were found storing the configuration cache`.

## Configuration Cache Observations

- The integration-test base enables Gradle configuration cache by default.
- The full run used `--configuration-cache` for most wrapper invocations.
- Explicit config-cache disables still exist in:
  - `JandexMultiModuleTest.testBasicMultiModuleBuildKordamp`, documented as a Kordamp Jandex plugin limitation.
  - `ExtensionUnitTestTest.shouldRunTestWithSuccess`, documented as `QuarkusExtensionTest` application-model resolution not configuration-cache compatible yet.
  - `BasicJavaNativeBuildIT`, native-image integration tests.
- This can be revisited after fixing the functional failure; it should be evaluated with targeted runs rather than assumed from the full run.

### Jandex/Kordamp opt-out

- Trial change: removed `gradleConfigurationCache(false)` from `JandexMultiModuleTest.testBasicMultiModuleBuildKordamp`.
- Command: `./mvnw -f integration-tests/gradle/pom.xml -Dtest=JandexMultiModuleTest test`
- Result: failed.
- Evidence: the Kordamp `org.kordamp.gradle.plugin.jandex.tasks.JandexTask` still stores unsupported Gradle types in the configuration cache:
  - `DefaultProject`
  - `DefaultLegacyConfiguration`
  - `DefaultSourceSet`
  - `ProcessResources`
- Conclusion: keep this explicit opt-out. The failure is in the external Kordamp Jandex task, not in Quarkus Gradle task state.

### Extension unit-test opt-out

- Trial change: removed `gradleConfigurationCache(false)` from `ExtensionUnitTestTest.shouldRunTestWithSuccess`.
- Command: `./mvnw -f integration-tests/gradle/pom.xml -Dtest=ExtensionUnitTestTest test`
- Result: passed.
- Evidence:
  - Surefire summary: 1 test, 0 failures, 0 errors.
  - The generated Gradle 8.14 wrapper invocation ran with `--configuration-cache`.
  - The Gradle wrapper invocation reported `0 problems were found storing the configuration cache`.
- Conclusion: the `QuarkusExtensionTest` application-model compatibility comment is stale after the serialized test app-model task work. The explicit opt-out can be removed.

### Native build opt-out

- Trial change: removed all three `gradleConfigurationCache(false)` calls from `BasicJavaNativeBuildIT`.
- First attempted command: `./mvnw -f integration-tests/gradle/pom.xml -Dnative -Dit.test=BasicJavaNativeBuildIT verify`
  - Interrupted intentionally after confirming it was rerunning the full regular Surefire suite before Failsafe.
- Targeted command: `./mvnw -f integration-tests/gradle/pom.xml -Dnative -Dnative.surefire.skip=true -Dit.test=BasicJavaNativeBuildIT verify`
- Result: passed.
- Evidence:
  - Failsafe summary: 3 tests, 0 failures, 0 errors, 0 skipped.
  - Surefire executions were skipped by `native.surefire.skip=true`.
  - The inner Gradle `buildNative` invocations ran with `--configuration-cache`.
  - Gradle stored configuration-cache entries for the native builds.
- Conclusion: the native build tests no longer need to opt out of the Gradle configuration cache.

### Native profile final gate

- Command: `./mvnw -f integration-tests/gradle/pom.xml -Dnative -Dnative.surefire.skip=true verify`
- Result: passed.
- Evidence:
  - Failsafe summary: 7 tests, 0 failures, 0 errors, 0 skipped.
  - Covered:
    - `BasicJavaNativeBuildIT`
    - `CustomNativeTestSourceSetIT`
    - `NativeIntegrationTestIT`
  - Inner Gradle invocations ran with `--configuration-cache` and stored configuration-cache entries.

## Final Configuration Cache Opt-Out State

- Remaining explicit test opt-out:
  - `JandexMultiModuleTest.testBasicMultiModuleBuildKordamp`
- Reason: still required because the external Kordamp `JandexTask` is not configuration-cache compatible.
- Removed stale opt-outs:
  - `ExtensionUnitTestTest.shouldRunTestWithSuccess`
  - `BasicJavaNativeBuildIT`
