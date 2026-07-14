# Declared Dependencies Gradle-Native Investigation

Date: 2026-07-05

Status: evidence
Current design:
[../../declared-dependencies-gradle-native-design.md](../../../declared-dependencies-gradle-native-design.md)

Owner / audience: Gradle configuration-cache workstream

## Reframed Problem

The problem is not primarily `--dry-run`.

The Gradle-native target is:

- Gradle plugin configuration should stay declarative and cheap;
- dependency graph resolution, artifact inspection, POM lookup, and Maven
  effective-model building should not run during the configuration phase;
- `--dry-run` is one useful regression gate because it exposes accidental
  configuration-time resolution, but it should not drive production
  `StartParameter.isDryRun()` branching.

## Investigation Questions

1. Why does Quarkus need declared dependency metadata in the application model?
2. Which user-visible features consume the metadata?
3. Why was Maven effective-model data introduced for Gradle builds?
4. Which parts of the metadata could come from Gradle's selected graph, and
   which parts require Maven model semantics?
5. What task/provider boundary lets Gradle do this work natively without
   configuration-phase resolution?

## Delegated Work

### History / Intent

Read-only history and GitHub archaeology around the original declared
dependency collector work.

Anchors:

- [PR #52226](https://github.com/quarkusio/quarkus/pull/52226) / commit
  [`28a082273f1ff60da9af7eb7576b3ff6910dff06`](https://github.com/quarkusio/quarkus/commit/28a082273f1ff60da9af7eb7576b3ff6910dff06)
- local precursor commit
  [`dc8343051c659d01d4465b8298e3a0fddef12de7`](https://github.com/quarkusio/quarkus/commit/dc8343051c659d01d4465b8298e3a0fddef12de7)
- SBOM/direct-dependency commits such as
  [`065d8cfde0c6`](https://github.com/quarkusio/quarkus/commit/065d8cfde0c6e3583dfe9a8bd072052736757f6e),
  [`5b9d4ce3359`](https://github.com/quarkusio/quarkus/commit/5b9d4ce3359f826162f1104917cc528653d677ba), and
  [`94f0c3f0740`](https://github.com/quarkusio/quarkus/commit/94f0c3f07405e6d01e43f58b21c41e50cd145103)
- modular packaging commit
  [`c194c85b5b4`](https://github.com/quarkusio/quarkus/commit/c194c85b5b47b7ed6ce1c9ea06f1c39211badd63)

Expected output:

- chronological source-backed summary;
- original use-cases;
- explicit statements about why Gradle graph data was insufficient, if present;
- uncertainty separated from evidence.

### Consumer Mapping

Read-only mapping of current consumers of:

- `ResolvedDependency.getDependencies()`
- `ResolvedDependency.getDirectDependencies()`
- `DependencyFlags.MISSING_FROM_APPLICATION`
- Maven scope and optional metadata attached by
  `DependencyDataCollector.setDirectDeps(...)`

Expected output:

- consumer list with file references;
- behavior at risk if only Gradle selected edges are available;
- mandatory semantics versus SBOM/diagnostic semantics;
- which consumers truly need Maven effective-model semantics.

### Gradle-Native Boundary Review

Read-only review of Gradle-native alternatives.

Expected output:

- current configuration-phase resolution risks;
- viable boundary options;
- semantic-preserving versus semantic-changing options;
- phased plan for small reviewable PRs.

## Primary Source Anchors

- [PR #52226](https://github.com/quarkusio/quarkus/pull/52226) says it provides
  a way to collect declared and resolved dependencies for Gradle builds and
  wire them into the application model.
- Commit
  [`28a082273f1ff60da9af7eb7576b3ff6910dff06`](https://github.com/quarkusio/quarkus/commit/28a082273f1ff60da9af7eb7576b3ff6910dff06)
  adds the Gradle collector, Maven model resolver integration, and
  `DeclaredDependenciesMinimalTest`.
- Commit
  [`065d8cfde0c6`](https://github.com/quarkusio/quarkus/commit/065d8cfde0c6e3583dfe9a8bd072052736757f6e)
  made the main SBOM component record direct dependencies.
- Commit
  [`c194c85b5b4`](https://github.com/quarkusio/quarkus/commit/c194c85b5b47b7ed6ce1c9ea06f1c39211badd63)
  introduced modular packaging and added direct-dependency APIs/usage in the
  bootstrap app model.
- [Quarkus Working Group Activity, Jan 2026](https://github.com/quarkusio/quarkus/discussions/52231)
  and the matching [quarkus-dev mail](https://groups.google.com/g/quarkus-dev/c/F1_IgQCZ2r4).
- [Quarkus Working Group Activity, Feb 2026](https://github.com/quarkusio/quarkus/discussions/52713)
  and the matching [quarkus-dev mail](https://groups.google.com/g/quarkus-dev/c/TFNpPVl1oG0).
- [Quarkus working groups overview](https://quarkus.io/working-groups/).
- [Quarkus 4 roadmap discussion](https://github.com/quarkusio/quarkus/discussions/52020).
- [Modularity / JPMS / JLink working group](https://github.com/quarkusio/quarkus/discussions/53223).
- [Modularization tracking epic #51583](https://github.com/quarkusio/quarkus/issues/51583).
- [AOT Support working group](https://github.com/quarkusio/quarkus/discussions/52017).
- Quarkus 4 public tracking / summaries:
  [March 2026](https://github.com/quarkusio/quarkus/discussions/53138),
  [April 2026](https://github.com/quarkusio/quarkus/discussions/53830), and
  [June 2026](https://github.com/quarkusio/quarkus/discussions/55059).
- [Quarkus Development mailing list public index](https://groups.google.com/g/quarkus-dev)
  showing the Quarkus 4 branch / main-branch transition threads in late June
  2026.
- [Working Group - Quarkus Config and IDEs](https://github.com/quarkusio/quarkus/discussions/42671).
- [Gradle configuration-cache/effective-config discussion #52506](https://github.com/quarkusio/quarkus/discussions/52506).
- [First-class Bazel support discussion #54762](https://github.com/quarkusio/quarkus/discussions/54762).
- [Gradle ApplicationModel modernization issue #49335](https://github.com/quarkusio/quarkus/issues/49335).
- [Gradle configuration-cache test behavior issue #49813](https://github.com/quarkusio/quarkus/issues/49813).
- [Gradle configuration-cache NPE issue #46682](https://github.com/quarkusio/quarkus/issues/46682).
- [Gradle build-cache correctness issue #39218](https://github.com/quarkusio/quarkus/issues/39218).
- [Gradle dev-mode/test-fixtures issue #43576](https://github.com/quarkusio/quarkus/issues/43576).
- [SBOM/CycloneDX backport tracking #43044](https://github.com/quarkusio/quarkus/issues/43044).

## Consolidated Findings

- The declared-dependency collector exists to make Gradle application models
  expose the same direct-dependency semantics as Maven application models.
- `ResolvedDependency.getDependencies()` represents selected direct dependency
  coordinates present in the application model.
- `ResolvedDependency.getDirectDependencies()` represents configured declared
  direct dependencies, excluding test dependencies of transitive dependencies,
  and retaining optional/provided/excluded/missing entries with
  `MISSING_FROM_APPLICATION` where appropriate.
- SBOM, Dev UI, and dependency logging primarily consume selected direct edges
  and may be satisfiable from Gradle `ResolutionResult`.
- Modular packaging consumes configured declared edges, optional/provided
  metadata, and missing markers. This is the hard consumer that prevents a
  simple selected-graph-only replacement.
- Maven effective-model parsing is therefore semantic-preserving for external
  Maven modules. Replacing it with Gradle `ResolutionResult` only would be a
  deliberate behavior change.
- The current hybrid is also risky if misinterpreted: Maven model data must
  enrich the Gradle-selected graph, not replace it. Gradle remains authoritative
  for selected versions, variants, artifact files, and selected graph edges.
- Public Quarkus 4 signals make this more important, not less: JPMS/JLink,
  modular packaging, AOT/Leyden packaging, Gradle ApplicationModel
  modernization, and IDE/tooling APIs all depend on precise app-model and
  build-tool metadata boundaries.
- I found no single public Quarkus 4 design that already solves the
  Gradle-native dependency/application-model boundary. The relevant public
  signals are distributed across working-group reports, GitHub discussions, and
  Gradle-specific issues.
- The next design target should be Gradle-native phase boundaries: no graph
  resolution, POM lookup, artifact inspection, or Maven model building during
  configuration. Dry-run is one regression gate for this, not the design
  center.
- The design now uses a two-milestone strategy: first contain the current
  eager-resolution problem by keeping Maven-declared enrichment out of task
  input snapshotting, then remove `QuarkusDeclaredDependenciesTask` once
  Quarkus consumers can use Gradle-native dependency semantics.

## Expected Output

This investigation superseded `dry-run-resolution-design-proposals.md` with
[declared-dependencies-gradle-native-design.md](../../../declared-dependencies-gradle-native-design.md),
a design centered on configuration-phase avoidance and Gradle-native
task/provider boundaries.

[dry-run-resolution-inventory.md](dry-run-resolution-inventory.md) should
remain as a regression inventory, not the main design document.
