# Application Plugin Coexistence Design Seed

Status: WIP design notes, not an implementation plan
Last reviewed: 2026-07-09

## Objective

Define the intended coexistence behavior when both Gradle application plugins
are applied to the same project:

- legacy `io.quarkus`;
- new standalone `io.quarkus.application`.

Applying both plugins is a deliberate migration mode. The goal is not to make
that mode perfect, but to make it predictable, non-racy, and easy to diagnose.

## Current Context

The new plugin exists because making the legacy application plugin fully
configuration-cache and isolated-project compatible is too risky. The new
plugin owns new task names, new DSL, and Gradle-native variants.

During migration, users may still apply the legacy plugin because it owns
behavior that the new plugin does not yet implement fully, especially:

- legacy Quarkus test instrumentation;
- legacy `quarkusTest`;
- legacy `quarkusIntTest`;
- legacy dev mode;
- some established build-script integrations around legacy task names.

The new plugin currently owns:

- `quarkusApplication` extension;
- `quarkusApplicationGenerateCode`;
- `quarkusApplicationGenerateTestCode`;
- `quarkusApplicationModel` and related model tasks;
- named package/build/image/AOT/deploy/native-test tasks;
- package elements variants such as `quarkusFastJarPackageElements`.

The legacy plugin currently owns:

- `quarkus` extension;
- `quarkusGenerateCode`;
- `quarkusGenerateCodeTests`;
- legacy `quarkusBuild`, `quarkusDev`, `quarkusTest`, `quarkusIntTest`,
  `testNative`, and related aliases;
- `tasks.withType(Test).configureEach(...)` instrumentation;
- `BeforeTestAction` for `Test` tasks.

## Design Principles

- Do not use plugin application order as the main behavior contract.
- Do not silently let both plugins mutate the same task in incompatible ways.
- Do allow duplicate work where it is isolated by task names and output
  directories.
- Prefer explicit ownership for behavior that changes runtime semantics.
- Warn clearly when both plugins are applied.
- Keep migration mode useful for real builds that need legacy testing/dev
  behavior but want new package/build variants.

## First-Applied-Wins Considered

A first-applied-wins rule is tempting, but it is too implicit for important
behavior.

Problems:

- plugin order can be hidden by convention plugins, aliases, precompiled
  script plugins, and included-build plugins;
- reordering plugin declarations would change test/tooling semantics;
- IDE import and tooling behavior would become harder to explain;
- the order does not express the user's actual migration intent.

Application order may still be useful in diagnostics, but it should not be the
primary ownership rule.

## Current Coexistence Risk Areas

### Application Model Generation

Duplicate application-model generation is mostly harmless.

The legacy and new plugins write different model files. Extra model generation
costs time, but it should not race on the same output or change task runtime
semantics.

### Code Generation

Duplicate code generation is mostly tolerable because the output directories
are intentionally distinct:

- legacy: `build/generated/sources/quarkus/...`;
- new plugin: `build/generated/sources/quarkus-application/...`.

However, this is not guaranteed harmless. Some code generators may emit the
same fully qualified class names into both output roots. If both roots are
wired into compilation, javac/Kotlin compilation may fail with duplicate-class
errors.

Therefore duplicate codegen should be allowed in migration mode, but users
must receive a warning explaining the duplicate-class risk and the available
ownership controls once they exist.

### Test Tasks

Test task ownership is not harmless.

The legacy plugin currently configures every Gradle `Test` task with Quarkus
test runtime behavior, including system properties, model inputs, and
`BeforeTestAction`. The new plugin does not yet implement equivalent
Gradle-native `Test` task ownership.

For now, when both plugins are applied:

- legacy should own `Test` task instrumentation;
- the new plugin should not try to instrument existing `Test` tasks;
- new plugin continuous-test/remote-dev tasks may remain reserved until their
  Gradle-native design is implemented; run and project-level dev are handled by
  the new plugin's own task implementations.

### Integration Tests

Legacy `quarkusIntTest` remains legacy-owned.

Custom integration-test tasks can consume the new plugin's package variants,
for example:

```kotlin
dependencies {
    nessieQuarkusServer(project(":nessie-quarkus", "quarkusFastJarPackageElements"))
}
```

That package consumption is compatible with legacy test instrumentation, but
the `Test` task runtime behavior still comes from the legacy plugin unless an
explicit new-plugin test ownership mode is introduced.

### Build Tooling Model

The build-tooling/application-model path is a separate design problem tracked
in `build-tooling-model-design.md`.

Coexistence should not make plugin application order decide tooling-model
semantics. If the new plugin gets a Gradle-native tooling-model path, ownership
should be explicit or keyed to a clearly documented model type/version.

## Proposed Near-Term Behavior

When both plugins are applied:

- legacy owns legacy task names;
- new plugin owns new task names and variants;
- application model generation may run in both plugins;
- code generation may run in both plugins, with a warning about duplicate
  generated classes;
- legacy owns existing Gradle `Test` task instrumentation;
- new plugin does not mutate existing `Test` tasks;
- new plugin warns that the project is in migration mode and that legacy test
  behavior remains active.

This is conservative and matches the implementation reality today.

## Possible Future Explicit Ownership DSL

If users need finer control, add explicit coexistence settings to the new
plugin extension. Names are placeholders:

```kotlin
quarkusApplication {
    coexistence {
        codegenOwner = CoexistenceOwner.LEGACY
        testOwner = CoexistenceOwner.LEGACY
        toolingModelOwner = CoexistenceOwner.LEGACY
    }
}
```

or:

```kotlin
quarkusApplication {
    coexistence {
        claimCodegen = false
        claimTests = false
        claimToolingModel = false
    }
}
```

Default values while both plugins are applied should be conservative:

- `claimCodegen = true` may be acceptable only if duplicate output roots remain
  non-overlapping and warnings are clear;
- `claimTests = false` until the new plugin has real Gradle-native test
  integration;
- `claimToolingModel = false` until the tooling-model design is settled.

Open question: should codegen be explicitly claimable, or is duplicate codegen
acceptable enough to avoid adding configuration now?

## Warning Strategy

The warning should be specific, not generic.

Recommended warning content:

- both `io.quarkus.application` and legacy `io.quarkus` are applied;
- this is supported as migration mode;
- new plugin owns new task names and package variants;
- legacy plugin still owns Gradle `Test` task instrumentation;
- application model and code generation may run twice;
- duplicate generated classes are possible if both plugins generate the same
  FQCNs;
- use explicit coexistence settings once available to change ownership.

The warning should not imply the build is unsupported.

## Investigation Needed

Before implementing ownership controls, answer:

1. Which source sets and compile tasks are currently wired by both plugins when
   both are applied?
2. Which Quarkus code generators can emit identical FQCNs when run by both
   plugins?
3. Can duplicate codegen be detected cheaply, or should the warning be enough?
4. Do any existing Quarkus Gradle integration tests apply both plugins and run
   `test` or `quarkusIntTest`?
5. Should new-plugin codegen auto-wiring be disabled when legacy is present, or
   should duplicate codegen remain the default migration behavior?
6. What future new-plugin test API should replace legacy `Test` task
   instrumentation?
7. Should custom test suites use Gradle `jvm-test-suite` integration rather
   than Quarkus-specific `intTest` task creation?
8. How should build-tooling model ownership interact with this coexistence
   story?

## Early Test Targets

- Apply both plugins and run `tasks`; assert a precise migration warning.
- Apply both plugins and run `compileJava`; assert generated output roots do
  not overlap.
- Apply both plugins and run `compileTestJava`; assert generated test output
  roots do not overlap.
- Apply both plugins and run `test`; assert legacy `BeforeTestAction` remains
  the only `Test` task instrumentation.
- Apply both plugins and consume `quarkusFastJarPackageElements` from another
  project while running a legacy-instrumented `Test` task.
- Add a duplicate-codegen fixture if a concrete generator can reproduce the
  risk cheaply.

## Related Docs

- `new-application-plugin-design.md`
- `build-tooling-model-design.md`
- `archive/p1-ap-01-codegen-project-walk-plan.md`
- `application-model-and-codegen.md`
