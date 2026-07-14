# Configuration Cache Defaults Plan

Status: historical
Superseded by: ../../tracker.md

This tracks the local branch work to make Gradle configuration cache the normal
execution mode for Quarkus Gradle plugin builds and tests.

## Phase 1: Gradle Plugin Code Build

Status: fixed locally by `b5874e17416` (`Enable configuration cache for Gradle
plugin build`).

- Enable `--configuration-cache` for the Gradle build launched from
  `devtools/gradle/pom.xml`.
- Keep CI explicitly on `--no-configuration-cache` because configuration-cache
  state can persist values observed during configuration, including accidental
  secret-bearing provider reads.
- Document the CI gate next to the Maven profile that disables the cache.

## Phase 2: Devtools Gradle Plugin Tests

Status: fixed locally by `463ee24e5d6` (`Run Gradle plugin TestKit builds with
configuration cache`).

- Make TestKit-based tests under `devtools/gradle` run with configuration cache
  by default through local helpers.
- Keep explicit `--no-configuration-cache` where a test intentionally verifies
  non-cache behavior or a non-compatible task path.
- Keep explicit reuse tests where they assert second-run cache reuse or project
  isolation behavior.

## Phase 3: Integration Tests

Status: fixed locally by `42717cd8eac` (`Use configuration cache defaults in
Gradle integration tests`).

- Audit `integration-tests/gradle` opt-outs from configuration cache.
- Remove redundant explicit opt-in coverage when normal wrapper execution
  already uses configuration cache.
- Keep opt-outs that document known unsupported behavior or test a
  non-configuration-cache path intentionally.

Current local audit:

- `QuarkusGradleWrapperTestBase` already enables configuration cache by default.
- `JandexMultiModuleTest.testBasicMultiModuleBuildJandex` no longer needs an
  explicit `gradleConfigurationCache(true)` call.
- `JavaPlatformWithEagerResolutionTest.dryRunShouldNotResolveDeploymentConfigurations`
  no longer needs an explicit `--no-configuration-cache` argument.
- `SystemPropsAsBuildTimeConfigSourceTest` no longer needs an explicit
  `gradleConfigurationCache(false)` call.
- `ExtensionUnitTestTest` keeps an explicit opt-out because
  `QuarkusExtensionTest` application-model resolution still fails under
  configuration cache even after the fixture declares its deployment artifact.
- Native-image Gradle ITs still opt out pending a separate native-specific
  verification pass.
- `HtmlDependencyReportTest` intentionally runs both modes because the covered
  regression was reported in both modes.

Verification recorded for the local commits:

- `./mvnw -f devtools/gradle/pom.xml help:evaluate -Dexpression=gradle.configuration.cache.argument -q -DforceStdout`
  returned `--configuration-cache`.
- `CI=true ./mvnw -f devtools/gradle/pom.xml help:evaluate -Dexpression=gradle.configuration.cache.argument -q -DforceStdout`
  returned `--no-configuration-cache`.
- `./mvnw -f devtools/gradle/pom.xml -pl gradle-model -DskipTests package`
  passed and stored a Gradle configuration-cache entry.
- `./mvnw -f devtools/gradle/pom.xml process-sources -DskipTests` passed.
- `cd devtools/gradle && ./gradlew :gradle-application-plugin:compileTestJava :gradle-extension-plugin:compileTestJava`
  passed.
- `cd devtools/gradle && ./gradlew :gradle-application-plugin:test :gradle-extension-plugin:test --rerun-tasks`
  passed.
- `./mvnw -f integration-tests/gradle process-sources -DskipTests` passed.
- `./mvnw -f integration-tests/gradle test-compile` passed.
- `./mvnw -f integration-tests/gradle test -Dtest=ExtensionUnitTestTest -Dstart-containers -Dtest-containers`
  is blocked locally by stale `mavenLocal()` `999-SNAPSHOT` Gradle plugin
  artifacts: the generated test JVM still receives
  `quarkus-internal-test.serialized-app-model.path` as a Gradle provider
  display string instead of the absolute path mapping present in the current
  working-tree source.
