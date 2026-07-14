# Task Cacheability Follow-Up

This is a later workstream item, not part of the current P1-EP-04 fix. The
current compatibility work should first make task inputs, outputs, local state,
worker parameters, build-service state, and configuration-cache behavior
explicit. Only then should we revisit which tasks should be build-cacheable.

## Goal

Review all custom Gradle tasks under `devtools/gradle` and classify their build
cache stance after the configuration-cache and project-isolation cleanup has
made their state model explicit.

## Classification

Each task should end in one of these states:

- Already cacheable and correctly modeled.
- Should stay non-cacheable with `@DisableCachingByDefault`.
- Can become cacheable because it has real outputs and complete inputs.
- Could become cacheable only by adding a synthetic stamp/report output; this
  needs a task-specific justification and invalidation tests.

## Review Checklist

- Does the task produce a real reusable output, or is it only validation?
- Are all semantic inputs declared, including scalar options, files,
  environment-derived values, system properties, worker parameters, and build
  service state?
- Are file inputs normalized with the right path sensitivity and classpath
  normalization?
- Is task output reproducible across machines and checkouts?
- Are scratch files declared as local state instead of cacheable outputs?
- Does configuration-cache replay still work when the task is cacheable?
- Do tests prove that meaningful input changes invalidate the task?

## Completed Targeted Slices

- `a3e685d7172` (`Add Gradle extension deployment plugin`) carries the
  extension descriptor task input/output modeling and cacheability work.
- `f56491a335f` (`Rework Gradle application model task wiring`) carries the
  image extension check cacheability work and supersedes the earlier cacheable
  `QuarkusDeclaredDependenciesTask` experiment. Declared dependency enrichment
  now runs inside `QuarkusApplicationModelTask` execution, and that task remains
  deliberately non-build-cacheable.

These commits do not close the broader follow-up. Keep using this document for
the remaining custom-task cacheability review.

Declared dependency cacheability is no longer a standalone task-cacheability
item. M2 should be tracked under the broader build-tool-agnostic dependency
model work, not as a cacheable Gradle producer-task follow-up.

## `ValidateExtensionTask`

`P1-EP-04` makes `ValidateExtensionTask` configuration-cache compatible in the
covered TestKit scenario and removes its explicit
`notCompatibleWithConfigurationCache(...)` marker.

The task remains intentionally annotated with `@DisableCachingByDefault` for
now. It is a validation task with no real reusable output, and Gradle plugin
validation requires custom task types to declare a cacheability stance. Making
it `@CacheableTask` would require adding a synthetic stamp or report output and
proving that runtime/deployment dependency-shape changes invalidate the cached
result. That should be considered only as part of this broader cacheability
pass, not as a side effect of P1-EP-04.

## Application Model Generation Tasks

Do not make `GenerateApplicationModelTask` or `QuarkusApplicationModelTask`
cacheable just because P1-EP-02C removed the extension-plugin generator's
live-`Project` task action. The generated-model task now uses modeled,
serializable inputs, but cacheability still needs dedicated invalidation
coverage for classpath, project descriptor, platform/import metadata,
compile-only dependencies, declared dependency data, launch mode, and output
reproducibility.

The current decision is to keep these tasks non-cacheable by default. The
serialized application model contains resolved local file-system paths,
including dependency artifact paths and workspace/source/output paths, so the
output is not relocatable. Reconsider only if the serialized model contract
becomes relocatable, or if a later PR deliberately adds a complete
path-locality fingerprint and accepts same-path-only cache hits with matching
invalidation coverage.

P1-EP-02 now covers the known task-execution consumers for `NORMAL`,
`DEVELOPMENT`, and `TEST` generated model artifacts. Remaining
`ToolingUtils.create(...)` call sites are tooling/API compatibility paths unless
a future concrete task-execution consumer is identified. Track the modeling work
in the archived [P1-EP-02 application model generation plan](archive/legacy/history/p1-ep-02-application-model-generation-plan.md).
