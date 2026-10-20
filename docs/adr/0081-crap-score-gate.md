---
status: proposed
date: 2026-10-12
deciders: Lukáš Vlk
---

# ADR-0081: A CRAP score report for the backend and the console, gated as a warning first

## Context and Problem Statement

Coverage alone says nothing about where untested code is risky: a 100%-covered
getter and an untested twenty-branch handler weigh the same in a line
percentage. The CRAP score (Change Risk Anti-Patterns) combines a function's
cyclomatic complexity with its test coverage, so it points at the functions
that are both complex and poorly tested. We want it for the Kotlin backend
(`app/`) and the Next.js console (`console/`), visible in every merge request,
and eventually blocking — but the code already has violations, so it cannot
block on the day it is introduced.

## Decision Drivers

- One metric, defined once, for both stacks, so one CI gate reads both.
- Findings visible in the merge request, not only in a job log.
- Introduce it without blocking merges; flip it to required with a one-line
  change once the existing findings are fixed.
- No tool that drags the build onto a pre-release toolchain: the backend is on
  Kotlin 2.4, which only detekt 2.0 alphas support.
- Reuse the coverage the pipelines already produce (Kover on the backend, Vitest
  on the console) instead of a second instrumented test run.

## Considered Options

- **Own report per stack, source-level complexity + existing coverage** —
  Kotlin PSI for the backend, the TypeScript compiler API for the console.
- **detekt `CyclomaticComplexMethod` + Kover** — complexity from detekt, joined
  with coverage in a script.
- **Complexity from the coverage report** — read JaCoCo-style `COMPLEXITY`
  counters.
- **SaaS analysis (e.g. SonarQube)** — let an external platform compute it.

## Decision Outcome

Chosen option: **"Own report per stack"**, because it is the only one that
works on the current toolchain without a pre-release dependency, and it keeps
the definition in the repository where both stacks implement it identically.

**The metric.** A *unit* is one function body: a named function, lambda,
accessor with a body, `init` block or secondary constructor in Kotlin; a
function, arrow function, method, constructor or accessor in TypeScript. A
nested unit is scored on its own and is not part of its parent. Cyclomatic
complexity is 1 plus one for each `if`, ternary, non-default `case` /
non-`else` `when` entry, loop, `catch`, `&&`, `||`, `??` and elvis `?:`.
Coverage is the share of the unit's own lines (Kover) or statements (Vitest
V8) that ran. `CRAP = cc² × (1 − cov)³ + cc`; a unit scoring **above 30** is a
violation. A unit with nothing instrumented is unscored; a file missing from
the coverage report counts as 0% covered. A unit with complexity above 30
violates even at 100% coverage — that is intended: it has to be split.

**Tooling.**

- Backend: Gradle task `crapReport` in the `kotlin-jvm` convention plugin,
  reading `build/reports/kover/report.xml`. Kover's XML carries no complexity
  counters, so complexity comes from parsing the sources with the Kotlin
  compiler's PSI (`kotlin-compiler-embeddable`, no type resolution). The Kotlin
  Gradle plugin bundles relocated copies of some compiler classes, so the
  parser runs in a Gradle worker with an isolated classloader and the compiler
  jar is only `compileOnly` / `testImplementation` in `buildSrc`.
- Console: `npm run test:coverage` (Vitest with `@vitest/coverage-v8`) then
  `npm run crap` (`scripts/crap.mjs`, TypeScript compiler API).
- Each writes `crap-report.txt`, a Code Quality (Code Climate) JSON
  (`gl-code-quality-crap.json`) and `crap-summary.txt`. The report tools exit 0
  regardless of violations; only a real error (missing coverage, a parse
  failure) fails them. Code Quality fingerprints leave out line numbers, so a
  finding keeps its identity when the code around it moves.

**The gate.** *Superseded by [ADR-0082](0082-quality-reports-report-first.md): CRAP is the `crap` check of `quality-gate`, and the flip is its `mode` in `tools/quality/checks.json`.* CI job `crap-gate` sums `violations=` over every module's
`crap-summary.txt` and fails when the total is above zero, or when a summary is
missing. It also merges the per-module Code Quality files into the pipeline's
single Code Quality report (a job can upload only one). It starts with `allow_failure: true`: violations show as "passed with
warnings" and in the merge request's Code Quality widget, but do not block.
When the backlog is at zero, setting `allow_failure: false` makes the gate
required. *Amended:* the threshold lives in one place, `crap.threshold` in
`tools/quality/checks.json`, read by both reports; the gate rejects a report
written for another value. It starts at 30 and is lowered in steps
(30 → 25 → 20 → 15 → 10 → 9 → 8, the target), each step only once
nothing scores over the next value; it reached 10 on 2026-10-12, and 9 and then 8 on
2026-10-13. A local run can still override it (`-Pcrap.threshold=` /
`CRAP_THRESHOLD=`); CI never does.

### Consequences

- Good: the riskiest functions are listed by name in every merge request, and
  new ones show up as new Code Quality findings.
- Good: one definition, two implementations with matching output formats — a
  single gate, comparable numbers.
- Good: no second test run; the backend report reuses the Kover run that
  `koverVerify` already needs, the console run replaces `npm test` in CI.
- Bad: two small analyzers to maintain, each tied to a compiler API
  (`KotlinCoreEnvironment` is marked for rework and needs opt-ins).
- Bad: complexity is a source-level approximation (no `when` exhaustiveness,
  no bytecode branches), so numbers differ from tools that count bytecode.
- Bad: the Kotlin analyzer's compiler version follows the Kotlin plugin
  version, so a Kotlin upgrade can break it.

## Pros and Cons of the Options

### Option B — detekt `CyclomaticComplexMethod` + Kover

- Good: maintained complexity rule, widely known.
- Bad: only detekt 2.0 alphas support Kotlin 2.4; the 1.x line does not run on
  this toolchain.
- Bad: still needs our own join of complexity and coverage per function, and
  nothing for the console.

### Option C — complexity from the coverage report

- Good: no parser at all.
- Bad: Kover 0.9's XML has only `INSTRUCTION`, `BRANCH`, `LINE`, `METHOD` and
  `CLASS` counters — no `COMPLEXITY`; V8 coverage has none either.
- Bad: bytecode methods include synthetic lambdas and coroutine state machines
  that do not map to source functions.

### Option D — SaaS analysis

- Good: dashboards and trends out of the box.
- Bad: a new external service holding the source, and a new account and token
  to manage; outside the repository's control.

## More Information

- [detekt 2.0.0 changelog](https://detekt.dev/changelog-2.0.0/),
  [detekt compatibility](https://detekt.dev/docs/introduction/compatibility)
- [Code Climate engine output format](https://github.com/codeclimate/platform/blob/master/spec/analyzers/SPEC.md), which Code Quality reports follow
