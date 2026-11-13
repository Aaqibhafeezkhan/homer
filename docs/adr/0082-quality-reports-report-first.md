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
| `mock-calls` | `MockCallBudget` counts each mock method's calls per `:app` test; added 2026-11-13, see the amendment below | `CLAUDE.md` §6 |
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

Mutation testing of the backend (PIT, Nightly job `backend-mutation`) is a
scheduled, non-gating pilot; its scope is in the amendment of 2026-11-04 below. PIT mutates bytecode, so
Kotlin-generated code yields mutants no test can meaningfully kill: in a wider
trial run, 61% of the mutants were in kotlinx.serialization `$$serializer`
classes and 20% in property accessors, against a handful in null-check
intrinsics and coroutine state machines; the plugin that filters these for
Kotlin is commercial. Whether backend mutation testing joins the gate is
decided on the pilot's numbers.

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

*Amended (2026-11-04):* the backend PIT pilot widened from `homer.app.config` to the validators,
parsers and security helpers listed in `app/app/build.gradle.kts` (864 mutants, about 12 minutes on
a CI runner). It excludes `*$$serializer` classes. PIT runs, for each mutant, only the tests that
cover the mutated line; those are now chosen from every backend package (`targetTests =
homer.app.*`), so code tested from another package is no longer reported as uncovered. The report
(`tools/quality/pit.mjs`) ignores mutants that only remove a call the Kotlin compiler adds (null
checks, coroutine result checks, index-overflow guards): outside the score, no finding. PIT's own
`avoidCallsTo` was tried for this and dropped: it skips every line that calls a listed class, and
Kotlin compiles `==` on objects to `Intrinsics.areEqual`, so it hid 37 mutants that tests do kill.

*Amended (2026-11-04):* pull requests get the backend counterpart of the console's changed-lines
run: check `mutation-backend` (`warn`, optional, job `homer-app-mutation`, `tools/quality/pit-changed.mjs`).
PIT has no free filter by line, so it mutates each changed class whole, and only the mutants on
changed lines count. The covering tests are chosen from every backend package, not the diff mode's
related tests: a covering test that heuristic misses would turn a killed mutant into a false survivor.
Only `:app` is mutated. The nightly scope stays a list in `app/app/build.gradle.kts`, which
`tools/quality/pit.test.mjs` reads to check that each entry still names a source file; a manifest
both Gradle and the test read was not worth a second file format for some 25 entries.
Whether either backend run turns `required` is decided on their numbers.

*Amended (2026-11-05):* both mutation checks accept exceptions from a reviewed file per stack
(`tools/quality/mutation-exceptions/backend.json` and `console.json`, a JSON object of key → reason).
An accepted mutant leaves the score and the findings, is counted as `accepted` and listed with its
reason in `<check>-accepted.txt`. This is not a baseline: there is no list of known findings, every
unaccepted survivor stays visible, and an entry matching no mutant is a finding of its own in a full
run (a pull request's changed-lines run never judges that); an entry is stale when it matches no
survived or uncovered mutant. Keys carry no line number (backend: source path, mutated method,
description and the mutant's line text, `<inline>` when the line is past the file's end or the source is unreadable; console:
file, mutator, the original text with its replacement and the mutated line's text), so an edit above
a mutant does not invalidate its entry. Both keys include the line's text, since neither PIT's
description nor Stryker's original text and replacement tells two mutants of a method apart: an entry
covers only that exact line's mutants of that kind, and editing the line makes the entry stale, which
forces a re-review. An `<inline>` key has no line to tell them apart: it matches every mutant of its
description that the Kotlin compiler inlined into the method (or whose source is unreadable), so its entry is
`{ "reason": "…", "count": N }`, and a full run reports an entry matching any other number of mutants
as a finding (still accepting them, so the score stays stable); a new inlined survivor there is never
accepted silently. The exceptions live in one reviewed place for both languages instead of Stryker's
in-code disable comments, which PIT has no counterpart for.

*Amended (2026-11-05):* both full mutation runs are weekly (Nightly workflow, Sunday schedule, or
by hand through its inputs), and the narrow nightly backend scope is dropped: PIT mutates every
class of `:app`. A whole-backend run takes longer than GitHub's 6-hour job limit, so it is sharded
per package (`tools/quality/pit-full.mjs`): one job per directory that holds Kotlin sources, each
writing its PIT XML, and one job merging them into the single `mutation-backend` report. A shard
that fails or times out leaves the report `partial`, which names the missing shards and judges no
stale exception. A shard that outgrows the limit on its own is split further or, failing that, run
on a self-hosted runner instead of a GitHub job. The scope list in `app/app/build.gradle.kts` and the test
checking its entries are gone with it.

*Amended (2026-11-06):* the pull request's backend run has a time budget of its own,
`MUTATE_BUDGET_SEC` (default 14 minutes, inside the job's 20-minute bound). A change to classes that
most tests run through (the run starters on PR #124) did not finish within the job's bound: the coverage
pass alone took four minutes, and the mutants that keep a run from starting time out one by one, so
GitHub cancelled the job, left no report and held `quality-gate`, which needs it, for the whole 20
minutes. A run over the budget is now stopped (Gradle's daemon with it) and the check reports itself
skipped with the budget as the reason; the weekly full run covers those classes. It reports nothing
of what PIT judged before the budget, and no `partial` list as the weekly merge does: PIT writes
`mutations.xml` through a buffer and closes its root element only when the run ends, so a stopped run
leaves at most a cut-off file that cannot say which classes it finished. Raising the job's
bound would only hold `quality-gate` longer, and narrowing the tests to the ones the change touches
would report every mutant other tests kill as uncovered.

*Amended (2026-11-11):* the backend report also ignores `replaced return value with null` mutants on
a function or lambda that returns Unit, beside the compiler-added calls and the same way: outside the
score, no finding, counted under `ignored`. Nothing reads such a value, so no test can kill the
mutant, yet the weekly full run scored 860 of them (30% of its findings) as survivors, burying the
real ones and pushing agents towards meaningless tests or an accepted exception per mutant. The
decision comes from what the mutated function actually returns, never from its name: the JVM
descriptor PIT already emits decides a plain function or lambda (`)V`, or `)Lkotlin/Unit;` for a
boxed Unit), and a suspend function, whose state machine returns `Object` whatever it returns, is
resolved against its `fun` declaration in the source the report already reads for exception keys — a
block body or `: Unit` is Unit, while an inferred `=` body, another return type, no matching
declaration and an overload the name cannot tell apart are not. A lambda or suspend function that
returns a value therefore keeps its mutants. What the rule cannot decide is a suspend *lambda*: its
`invokeSuspend` takes the lambda instance, not a `Continuation`, and has no declaration to read, so
`CsrfProtection.kt#invokeSuspend` stays a reviewed exception rather than a silently ignored mutant.
The three backend exceptions the rule does cover are removed with it, since an entry matching no
mutant is a finding of its own.

*Amended (2026-11-13):* `mock-calls` joined as `warn`, with a hard limit beside it. `homer-app-tests`
ran out of its 2g heap on main (#198) without a failing assertion: fakes of a timed wait answered "not
yet" at once, so the executors' poll loops busy-spun for each test's timeout, and mockk recorded
about a million calls a test with their stack traces and kept them until the suite ended (#200). The
`MockCallBudget` extension, autodetected for the `:app` test task only (mockk-jvm's own
`MockKExtension` is registered for autodetection too and stays opt-in), counts the calls each mock
method took after every test and then clears the recorded calls, answers kept. A test that called
one method 20,000 times or more fails; 1,000 or more is a warn-mode finding. Measured over the whole
suite, the busiest ordinary test calls one method 454 times (99.9% stay at 204 or fewer), and a
one-second spin calls it about 140,000 times on a developer machine: the failure limit sits 44 times
above the first and still catches the second on a runner several times slower, so more calls from a
growing test never fail the build. Raising the test heap instead was rejected: it hides the next spin
until it outgrows the new limit.
