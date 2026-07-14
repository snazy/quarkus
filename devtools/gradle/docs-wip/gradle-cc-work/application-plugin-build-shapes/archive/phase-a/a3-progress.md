# P1-AP-02A3 Progress

Status: complete
Last reviewed: 2026-07-06

## Scope

Implement opt-in legacy application-task diagnostics for `P1-AP-02A3`.

## Checklist

- [x] Add `quarkus.diagnostics` extension model
- [x] Add `legacyTaskUsage = OFF | WARN | FAIL`
- [x] Default from `-Pquarkus.diagnostics.legacy-task-usage`
- [x] Keep default `OFF`
- [x] Include `quarkusBuild` in legacy usage diagnostics
- [x] Include `imageBuild`, `imagePush`, `buildNative`, `testNative`, `deploy`, and `buildAotEnhancedImage`
- [x] Treat direct and transitive task-graph participation as usage
- [x] Write `build/reports/quarkus/legacy-task-usage.txt`
- [x] `WARN` logs and writes report
- [x] `FAIL` writes report and fails
- [x] Targeted diagnostics and extension tests
- [x] Full `:gradle-application-plugin:test`

## Guardrails

- `quarkusBuild` is diagnosed as legacy model usage, not deprecated by name.
- Diagnostics remain opt-in for Quarkus 4.0.
- Existing legacy task behavior is unchanged when diagnostics are `OFF`.
