---
status: proposed
date: 2026-10-12
deciders: Lukáš Vlk
consulted: ADR-0081
---

# ADR-0082: Quality checks are reported first and made required one by one

## Context and Problem Statement

Homer is developed mostly by coding agents. The repository's quality rules —
layering, "every service has a test", no inline 400 responses, console data
flow — live in prose (`CLAUDE.md`, `app/CLAUDE.md`, `console/CLAUDE.md`), and
the root guide says outright that apart from schemas and `AGENT.md` they rely
on reviewer discipline. When the reviewer is also an agent it shares the
author's blind spots, so prose rules drift. ADR-0081 added one executable
check (CRAP). This ADR adds the rest of the set and the shared mechanism that
brings each one from "reported" to "required" without blocking merges on the
existing backlog.

## Decision Drivers

- Rules that a machine can check should be checked by a machine, in every
  merge request.
- The existing code already violates most of them; introducing a check must
  not block unrelated work.
- Each check is made required on its own, when its own backlog is at zero.
- One place to read the results, one place to flip a check.
- Tools must run on the current toolchain (Kotlin 2.4, JDK 25, Gradle 9,
  JUnit 6, Vitest 4) without pre-release dependencies.

## Considered Options

- **One gate over per-check summaries, mode per check** — every check writes
  the same three files; `quality-gate` reads them and decides by a mode
  listed in `tools/quality/checks.json`.
- **One CI job per check, each with its own `allow_failure`** — flip a check by
  editing its job.
- **Make every tool fail the build now and suppress existing findings with
  baselines** — detekt/ESLint-style baseline files.

## Decision Outcome

Chosen option: **"One gate over per-check summaries"**, because it gives one
table per pipeline and one line to change per check, and it keeps the tools
themselves free of gating logic.

**The contract.** Each check writes `<check>-summary.txt` (`findings=<n>` plus
optional `key=value` lines), `<check>-report.txt` and
`gl-code-quality-<check>.json` into its own directory, and exits non-zero only
on a real error (missing input, a tool crash), never because of findings.
Code Quality fingerprints hash the check name, the path and a line-free key,
so a finding keeps its identity when code moves.

**The gate.** `tools/quality/gate.mjs` (CI job `quality-gate`) reads every
check in `checks.json`, prints a table and the top findings, and merges all
Code Quality files into the one report a job may upload. It exits 0 with no
findings, 3 when only `warn` checks have findings, and 1 when a `required`
check has findings or a report is missing or unreadable. The job allows exit
code 3 only: report-only findings show as "passed with warnings", while a
missing report fails closed, except for a check flagged `optional`, whose
job does not run in every pipeline. A check is made required by setting its
`mode` to `required` once its backlog is fixed. That is one-way: a required
check that misfires gets fixed, in a merge request of its own, rather than
demoted to `warn`. `crap-gate` from ADR-0081 is folded
into this gate as the `crap` check.

**The checks** (`warn` at introduction, unless the introducing change already brings the backlog to zero — `dto-contract` landed `required` because its merge request fixed the drift it found):

| Check | Tool | Source of the rule |
| --- | --- | --- |
| `crap` | ADR-0081 | complexity × missing coverage |
| `kotlin-warnings` | Kotlin compiler warnings, parsed from the CI Gradle log | compiler |
| `architecture-backend` | Konsist tests in the test-only `:architecture` module | `CLAUDE.md` §6, `app/CLAUDE.md` |
| `architecture-backend-pending` | the same module's rules tagged `report-only`, run by `architectureReportOnly`; removed 2026-10-13 while no rule is tagged, see the amendment below | `CLAUDE.md` §2, §12, `app/CLAUDE.md` |
| `eslint-warnings` | ESLint warnings, including architecture rules at `warn` | `console/CLAUDE.md` |
| `architecture-console` | dependency-cruiser | `console/CLAUDE.md` layout and data flow |
| `unused-console` | knip | unused files, exports, dependencies |
| `duplication` | jscpd over both stacks' production code | DRY |
| `diff-coverage` | changed lines vs. Kover and V8 coverage, threshold 80% per file | new code is tested |
| `detekt` | detekt 2.0 `detektMain` (type-resolved) SARIF, coroutines / exceptions / potential-bugs rule sets; added 2026-10-13, see the amendment below | likely defects in code bodies |
| `query-plans` | `EXPLAIN QUERY PLAN` of every distinct SQL statement production code ran in `:app:test`, on a fresh schema; added 2026-10-14, see the amendment below | `app/CLAUDE.md` § SQLite rules, rule 1 |
| `query-repeats` | statements production code ran three or more times in one transaction in `:app:test`, from the same capture; added 2026-10-14, see the amendment below | `app/CLAUDE.md` § SQLite rules, rule 2 |
| `mutation-console` | Stryker on the console lines a merge request changes; a full run is manual | tests assert behaviour |
| `size-console` | ESLint `max-lines` (600) and `max-lines-per-function` (60), blank lines and comments not counted, in its own run over console production code | the file and function size limits the backend uses too |
| `dto-contract` | backend DTO serial descriptors (`DtoContractTest`) vs the TS mirrors in `console/types/homer.ts` (TypeScript compiler API) | `console/CLAUDE.md` § Types & backend coupling |

*Amended (2026-10-13):* `duplication` became `required` once its clones were
merged. jscpd does not count import and package lines, which two files that use the
same APIs share without copying logic. Repetition that a contract fixes, such as a
`@Serializable` variant restating its base's fields or an interface override's
signature, may be wrapped in `// jscpd:ignore-start` … `// jscpd:ignore-end`, with
a one-line reason above it. That is not the rejected baseline: a baseline is a
generated file that freezes the whole backlog in place. A marker sits on one block,
in the code, next to its reason, so each exception gets reviewed where it is
added and remains visible afterwards. `size-console` became `required` on the same day, once its
34 over-long functions were split. The backend size rules (file ≤ 600, class ≤ 400, function ≤ 60
lines) left the pending lane for `architecture-backend` the same way, once their 57
findings were split; the layering rule followed with ADR-0083's ports, and the cycle rule with its one
package per layer. That emptied the lane, so the `architecture-backend-pending` check and its
`quality-gate` line were removed; a rule tagged `report-only` again brings them back.

Mutation testing of the backend (PIT, job `homer-app-mutation`) is a
scheduled, non-gating pilot on `homer.app.config`. PIT mutates bytecode, so
Kotlin-generated code yields mutants no test can meaningfully kill: in a wider
trial run, 61% of the mutants were in kotlinx.serialization `$$serializer`
classes and 20% in property accessors, against a handful in null-check
intrinsics and coroutine state machines; the plugin that filters these for
Kotlin is commercial. Widening the scope starts with excluding `*$$serializer`;
whether backend mutation testing joins the gate is decided on the pilot's
numbers.

A full console mutation run is about 7,500 mutants and takes hours on a cold
incremental cache, so it is a manual job until the scope is sharded; merge
requests mutate only the lines they change, within a 20-minute job bound.

Property-based testing (kotest-property, fast-check) is a convention for
parsers, validators and security-relevant input handling, not a check.

**The compiler flag.** For `kotlin-warnings`, `required` in the gate is the
first step; turning on `allWarningsAsErrors` in the convention plugin is the
second, once the count is zero, so a warning then fails at compile time
locally too.

### Consequences

- Good: every documented rule that can be checked is checked in every merge
  request, with findings in the Code Quality widget and coverage annotations in
  the diff.
- Good: a check moves from report-only to required with one line, on its own
  schedule.
- Good: a missing or broken report fails the pipeline instead of reading as
  clean.
- Bad: a warning-only gate is easy to ignore; the rollout depends on actually
  fixing backlogs and flipping modes.
- Bad: more CI time — the console suite now also runs dependency-cruiser and
  knip, mutation runs on changed console lines, and the gate installs its own
  tools.
- Bad: `kotlin-warnings` reads warnings from the Gradle log, and a compile
  task restored from the remote build cache prints none. Such modules are left
  out of the count and named in the report; when nothing compiled, the check
  shows as `skipped`. Changed code always recompiles, so no new warning is
  missed, but the total backlog is only visible in a build that compiles
  everything.

## Pros and Cons of the Options

### One CI job per check

- Good: native CI semantics per job.
- Bad: nine more jobs, nine places to flip, no single view of the state.

### Fail now with baselines

- Good: new findings block immediately.
- Bad: baselines need per-tool support (jscpd, knip and the parsers here have
  none in a common form) and hide the backlog instead of listing it.

## More Information

- ADR-0081 (CRAP score), whose gate this replaces.
- [Code Climate engine output format](https://github.com/codeclimate/platform/blob/master/spec/analyzers/SPEC.md) (Code Quality reports),
  [Cobertura coverage](https://cobertura.github.io/cobertura/).

*Amended (2026-10-13):* `detekt` joined as a `warn` pilot. Konsist checks declarations and reads code
bodies only as text. detekt analyses bodies with type resolution, so it finds what no other check
does: coroutine misuse, swallowed exceptions and likely bugs. It runs only those three rule sets,
because complexity, size, formatting and naming already have their own checks. It is on detekt 2.0,
still an alpha, because 1.23 is built on the K1 compiler and cannot resolve types on Kotlin 2.4. The pilot ends in one of
two ways. Either the rules worth keeping are fixed and the check becomes `required` (detekt then moves to
2.0 stable), or the few rules that matter are rewritten as Konsist tests and detekt is removed.
It is not part of `check` and runs in its own CI job, `homer-app-detekt`, on a larger runner: its
Analysis API loads into the Gradle daemon, and inside the `homer-app-tests` build that job ran out of memory.
`MissingUseCall` is off: all 39 of its findings were ownership transfers (a session or stream returned,
wrapped, stored, or closed in a `finally`), not leaks; the rule cannot follow ownership, so keeping it
would only have produced suppressions.

*Amended (2026-10-14):* the `detekt` pilot ended the first way: each finding was fixed, or its rule
was turned off with the reason recorded (in `app/config/detekt/detekt.yml`, or above for `MissingUseCall`),
and the check is `required`. It stays on the 2.0 alpha until 2.0 stable ships.

*Amended (2026-10-14):* `query-plans` joined as `warn`. SQLite rule 1 in `app/CLAUDE.md` (no full
scan of a growing table) was checked by hand and by plan tests pinned per query, so a new query that
nobody pinned went unchecked. The check needs no list of queries: a test-only Exposed interceptor
records every distinct statement production code runs during `:app:test` with the source line that
ran it, and `:app:queryPlanReport` plans each one on a freshly migrated database. The plans are the
planner's choice without `sqlite_stat1`, as on a first boot, which makes them deterministic; a query
`:app:test` never runs is not checked. Not findings: a scan through an index, and an unfiltered page
in primary-key order (`ORDER BY id LIMIT n`), which reads only the rows it returns. It fails
closed: a statement the planner cannot prepare is a finding, and an empty capture fails the report.
It becomes `required` once its findings are fixed.

*Amended (2026-10-14):* `query-repeats` joined as `warn`, read from the same capture: a statement
production code runs three or more times in one transaction is a query per row in a loop (SQLite
rule 2). Twice is not counted, as two separate reads of one shape are common and harmless; a JDBC
batch counts once, and a run test code issues is not counted.

*Amended (2026-10-14):* `query-plans` and `query-repeats` are `required`. Paging `GET /api/v1/questions`,
with its visibility moved into SQL, removed the last full scan.

*Amended (2026-10-14):* the checks have a diff mode, `node tools/quality/changed.mjs`, for coding
agents to run in their edit loop. A full gate run needs the whole backend and console suites, far
too slow to repeat after every edit. The diff mode reads the working tree against the merge base
with `origin/main`, uncommitted and untracked files included, and runs only the checks the change
can affect. Tests run only where they relate to the change: a changed Kotlin file's own
`<Name>*Test` classes, else the test classes that name its declarations, and `vitest related` in the
console. That coverage is partial, so `crap` and `diff-coverage` judge only the functions and lines
the change touches, and a Kotlin file no test relates to is listed as not measured rather than
reported as uncovered. Every other check still reads the whole project and reports everything it
finds, marking findings outside the change. Main has no findings in a required check, so anything
these checks find comes from the change, and a change can surface a finding in a file it never
touched: an export left unused, a query that loses its index. Filtering those out would only move
the failure to the merge request. CI keeps the full checks; the diff mode is a faster approximation
for the edit loop and does not replace the gate.
