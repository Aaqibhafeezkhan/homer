# Quality checks

What Homer tests automatically to keep its code clean, testable and tested,
where each check runs, and how to run it yourself. Homer is developed mostly by
coding agents. That makes every rule a machine can check a CI check, not a
review comment. The design and the rollout rules are in
[ADR-0081](adr/0081-crap-score-gate.md) (CRAP) and
[ADR-0082](adr/0082-quality-reports-report-first.md) (the gate). The current
mode of every check lives in
[`tools/quality/checks.json`](../tools/quality/checks.json).

## How the gate works

Every check writes the same three files into its own report directory:

- `<check>-summary.txt`, whose first line is the count, `findings=<n>`. The `crap` check writes `violations=<n>` instead, and the gate reads either;
- `<check>-report.txt`, which lists one `path:line message` per finding;
- `gl-code-quality-<check>.json`, a Code Climate-style list of the findings. The format is a GitLab-era leftover: GitHub renders no such file, so it only reaches the `quality-gate` artifact (below).

A check exits non-zero only on a real error, such as missing input or a tool
crash. Findings alone never fail it.

The CI job **`quality-gate`** reads every check listed in `checks.json`. It
prints a table with the top findings in the job's log, merges the Code Quality files into one
`code-quality.json`, and exits with one of three codes:

| Exit | Meaning | CI run |
| --- | --- | --- |
| 0 | no findings | passes |
| 3 | findings only in `warn` checks | passes; the job adds a warning annotation to the run |
| 1 | findings in a `required` check, or a report missing, broken or written for another threshold (a missing report of an `optional` check does not fail it; it shows as `not run`) | fails |

The job uploads the `quality-gate` artifact (7 days): the report directories under the root
`reports/quality/` and the merged `code-quality.json`. The console checks write under
`console/reports/quality/` and `crap` under `app/*/build/reports/crap` and `console/coverage/crap`;
those findings reach the artifact only merged into `code-quality.json`. GitHub shows no per-line coverage
annotations in a pull request's diff; the coverage reports only feed `diff-coverage` and `crap`.

A new check starts in `warn` mode, its findings get fixed, and then it is set
to `required`, which is one-way (ADR-0082). It can start as `required` straight away when the change that
adds it also fixes everything it finds. A required check never returns to
`warn`. Report-only Konsist rules live in their own lane (the
`architecture-backend-pending` check) until they reach zero, and then move into
the required suite. The lane is empty since the package-cycle rule became
required (ADR-0083), so the check is not listed until a rule enters it again.

## Diff mode: the checks of a change, for the edit loop

```bash
export JAVA_HOME=<path to a JDK 25>   # the backend checks run Gradle
node tools/quality/changed.mjs                       # every required check the change touches
node tools/quality/changed.mjs --only crap,detekt    # some checks; --only mutation-console runs Stryker
node tools/quality/changed.mjs --base <rev>          # another base than the merge base with origin/main
```

The diff mode runs the checks against the change you are making: the working tree
against the merge base with `origin/main`, uncommitted and untracked files included
(ADR-0082). It runs a check only when the change touches what the check reads, and it
runs only the tests related to the change. It takes about ten seconds for a console
change and under a minute for a backend change. A full run takes as long as the whole
backend and console suites. It prints one table like the gate's, then each finding as
`path:line message`, and exits 0, 3 or 1 like `quality-gate`. Logs and tool output go to
`reports/quality/changed/`.

- **Tests and coverage.** On the backend, the tests for a changed Kotlin file are its
  own `<Name>*Test` classes in the mirrored test package. When it has none, they are the
  test classes that name one of its declarations. A changed test class runs as well, and
  a module with more than 40 related classes runs all its tests. The console runs
  `vitest related` on the changed files. A failing test or a compile error is a finding
  in the `build` row.
- **`crap` and `diff-coverage`** judge only the changed functions and lines, because the
  coverage comes from the related tests alone. A Kotlin file no test relates to is listed
  as *not measured*, and the pull request's full CI run decides it. A console file no test
  imports is a `diff-coverage` finding.
- **Every other check** reads the whole project and reports everything it finds. Main has
  no findings in a required check, so each finding comes from the change, even one in a
  file the change does not touch, such as an export left unused or a query that lost its
  index. Those are marked `(outside the change)`.
- **Not covered:** `app/buildSrc` is a build of its own (`app/gradlew -p app/buildSrc
  check`). The e2e and architecture modules' own code is not measured. Build-script-only
  changes run no backend check. `mutation-console` runs only when `--only` names it.

The diff mode is an approximation for the loop. The pull request's CI run still runs every
check in full.

## Checks in the gate

| Check | Mode | What it catches | Tool / source of the rule | Run locally |
| --- | --- | --- | --- | --- |
| `crap` | required | functions that are complex *and* under-tested: CRAP = cc² × (1 − cov)³ + cc over `crap.threshold` (8, the target) | Kotlin PSI + Kover; TypeScript compiler API + V8 coverage (ADR-0081) | `app/gradlew -p app crapReport`; `cd console && npm run test:coverage && npm run crap` |
| `kotlin-warnings` | required | any Kotlin compiler warning; compilation also fails on one (`allWarningsAsErrors`) | Kotlin compiler, CI Gradle log | any Gradle build |
| `architecture-backend` | required | documented backend conventions: tests mirror packages, every service and executor has a test, executor naming, no inline 400 responses, `Route` builders in `routes/`, layering imports, no package cycles, DTO placement, `TelemetryService` interface, MockK only, **every secured `/api/v1` handler calls the `Authorizer`**, file ≤ 600 / class ≤ 400 / function ≤ 60 lines, full layer order (HTTP → Services → Execution → Executors → Infra, ADR-0083) | Konsist module `app/architecture` | `app/gradlew -p app :architecture:test` |
| `eslint-warnings` | required | ESLint warnings, including console architecture rules: backend calls only through `homerApi`, no native `EventSource`, `CONSOLE_*` env read only in layout/middleware, `queryKeys` registry, SSE never writes cache, no body font, lucide icons only, `dark:` only with a stated reason | ESLint, rules from `console/CLAUDE.md` | `cd console && npm run lint` |
| `architecture-console` | required | layering (`lib` ↛ `components`/`app`, `components` ↛ `app`, `components/ui` is a leaf, `types/homer.ts` imports nothing), no circular imports | dependency-cruiser | `cd console && npm run quality:report` |
| `unused-console` | required | unused files, exports and dependencies | knip | `cd console && npm run quality:report` |
| `size-console` | required | console files over 600 lines and functions over 60 lines | ESLint `max-lines*`, a separate run | `cd console && npm run quality:report` |
| `dto-contract` | required | drift between backend DTOs and their hand-written mirrors in `console/types/homer.ts`: missing or extra fields, nullability, coarse types, enum values, sealed variants | `DtoContractTest` (kotlinx.serialization descriptors) + TypeScript compiler API | `app/gradlew -p app :app:test`, `cd console && npm run quality:dto-types`, `node tools/quality/dto-contract.mjs` |
| `diff-coverage` | required | a pull request's added or changed lines are less than 80% covered in a file | Kover XML + V8 coverage vs `git diff` | `node tools/quality/diff-coverage.mjs origin/main` (after coverage runs) |
| `duplication` | required | copy-pasted blocks in production code of both stacks; import and package lines are not counted. Repetition a contract fixes (a `@Serializable` variant restating its base's fields, an interface override's signature) may be wrapped in `// jscpd:ignore-start` … `// jscpd:ignore-end` with a one-line reason above it | jscpd | `node tools/quality/duplication.mjs` |
| `detekt` | required | defects in code bodies that Konsist reads only as text: coroutine misuse (`GlobalScope`, a swallowed `CancellationException`, a suspending cleanup in `finally` outside `NonCancellable`, `Thread.sleep` in `suspend`), exception handling (a swallowed cause, a too generic throw), likely bugs (`!!`, `exitProcess` outside `main`). Only the coroutines, exceptions and potential-bugs rule sets; complexity, size, naming and style are other checks' job | detekt 2.0 (alpha, K2 Analysis API) with type resolution, `app/config/detekt/detekt.yml`. detekt does not run the kotlinx.serialization compiler plugin, so it logs the generated `serializer()` calls as unresolved; a rule reading those expressions can miss a finding there | `app/gradlew -p app detektMain -Dorg.gradle.jvmargs="-XX:MaxMetaspaceSize=1g"`, then `node tools/quality/detekt.mjs` |
| `query-plans` | required | a full scan (`SCAN` without an index) of a table that grows with use (the list in SQLite rule 1, `app/CLAUDE.md`) in any SQL statement production code runs during `:app:test`. An unfiltered primary-key page (`ORDER BY id LIMIT n`) is no finding; a statement the planner cannot prepare is one, and an empty capture fails the report. A query no test runs is not checked | `QueryPlanCapture` (a test-only Exposed `GlobalStatementInterceptor`) records each statement and its source line; `QueryPlanReport` plans them with `EXPLAIN QUERY PLAN` on a freshly migrated schema without `sqlite_stat1` | `app/gradlew -p app :app:queryPlanReport`, then `node tools/quality/query-plans.mjs` |
| `query-repeats` | required | a statement production code runs three or more times in one transaction during `:app:test`: a query or write per row in a loop (SQLite rule 2). A read is major, a write minor; a JDBC batch counts once. Deliberate repeats are listed with their reason in `tools/quality/query-repeats.mjs` | the same `QueryPlanCapture`, counting statements per transaction | `app/gradlew -p app :app:queryPlanReport`, then `node tools/quality/query-repeats.mjs` |
| `mutation-console` | warn, optional (its job runs only on pull requests; a missing report does not fail the gate) | surviving mutants on the console lines a pull request changes, i.e. tests that run code without asserting it | Stryker (Vitest runner) | `cd console && MUTATE_BASE=origin/main npm run mutation:changed` |

Fixing a `crap` finding in the console: V8 counts JSX inside `cond && <X/>` as
covered even when it never renders. After moving such a block into its own
component, re-measure before you trust the score; it may need real tests.

Run any `tools/quality` script from the repository root after
`cd tools/quality && npm ci` once. `node tools/quality/gate.mjs` prints the same
table CI does, once the reports exist.

## Other automated checks

| What | Where | Run locally |
| --- | --- | --- |
| Backend unit and integration tests (JUnit, MockK, Ktor test host); Kover coverage | `homer-app-tests` | `app/gradlew -p app :app:test` |
| ktlint formatting and lint | `homer-app-tests` (`check`) | `app/gradlew -p app ktlintFormat` |
| Contract backstops: `SchemaValidationTest` (schemas vs. example config and agents), `AgentMdExamplesTest` (every `AGENT.md` example parses and validates), `AuthoringPluginBundleTest` (bundled plugin copies are byte-identical) | `homer-app-tests` | `app/gradlew -p app :app:test` |
| Third-party notices and license policy (backend and console) | `homer-app-tests`, `homer-console-tests` | `app/gradlew -p app :app:checkThirdPartyNotices`; `cd console && npm run licenses:check` |
| buildSrc's own tests (`CrapScoreTest`, `ThirdPartyNoticesTest`, `QualityConfigTest`) | `homer-app-tests` | `app/gradlew -p app/buildSrc check` |
| Console tests (Vitest, React Testing Library, jsdom), with V8 coverage | `homer-console-tests` | `cd console && npm test` / `npm run test:coverage` |
| Console type check and production build | `homer-console-tests` | `cd console && npm run build` |
| High+ advisories in shipped console dependencies | `homer-console-tests` | `cd console && npm audit --omit=dev --audit-level=high` |
| `tools/quality` script tests | (run with the scripts) | `cd tools/quality && npm test` |
| Release-page notes script tests (`tools/release/notes.sh`: changelog section, verified image digests) | `release-notes-tests` | `tools/release/notes-test.sh` |
| Documentation site: generator tests, and a build that fails on a link to a file the repository does not hold or a dead link between pages (ADR-0087) | `docs-site` | `cd site && npm ci && npm test && npm run docs:build` |
| LangGraph Python runtime tests | `homer-python-tests` | `cd clients/python && .venv/bin/python -m pytest` |
| Black-box E2E suite against the production backend image (also proves `app/Dockerfile` builds) | `homer-e2e` | see [`app/e2e/README.md`](../app/e2e/README.md) |
| Known CVEs in backend dependencies (OWASP dependency-check, NVD data from the dependency-check project's daily mirror) | `dependency-check` (Nightly workflow): scheduled once the repository is public, or run by hand with `workflow_dispatch` | `app/gradlew -p app :app:dependencyCheckAnalyze :homer-runner:dependencyCheckAnalyze` |
| Console image must not ship LGPL-3.0 `libvips` | `images` job of the Release workflow (tags, and `workflow_dispatch` builds of a protected branch) | — |
| Backend mutation-testing pilot (PIT, `homer.app.config`) | `backend-mutation` (Nightly workflow): scheduled once the repository is public, or run by hand with `workflow_dispatch` | `app/gradlew -p app :app:pitest` |
| Full console mutation run (~7,500 mutants) | `console-mutation-full` (Nightly workflow): manual, `workflow_dispatch` with `full-console-mutation` | `cd console && npm run mutation` |

## Conventions the checks back up

- **Tests (root `CLAUDE.md` §6).** Every new public function, route, service
  or executor has a test, and so does every console component, hook and
  utility. User-visible backend changes also get E2E coverage. Parsers,
  validators, formatters and security-relevant input handling also get a
  property-based test (`kotest-property`, `fast-check`).
- **New code is written to the target.** CRAP ≤ 8
  per function, no new warning, no new finding in any `warn` check in code you
  touch.
- **Security by design (root `CLAUDE.md` §12).** Denial-path tests for every
  gate. Every secured handler's explicit `Authorizer` call is enforced by the
  required route rule.
- **AI review of GitHub pull requests ([ADR-0091](adr/0091-ai-review-of-github-pull-requests.md)).**
  The `main` ruleset requires the `homer-ai-review` status, which is `success`
  when no blocker or major finding is open.
  - A pull request from an organization member is reviewed 30 minutes after
    its last push.
  - One from anyone else is reviewed once a maintainer has approved its head
    commit, and only after a pre-check. When such a pull request changes a
    build, test or CI file, or `CLAUDE.md`, the pre-check stops the AI review
    and a maintainer reviews the pull request by hand.
  - After fixing the findings, an organization member asks for the next round
    with an `@homer review` comment. The review answers in its threads and
    resolves the ones a later round finds fixed.

## Open backlog and next steps

The counts below are a snapshot, last refreshed when `detekt` became required (2026-10-14). For the current numbers, look at the `quality-gate` job's table and its report artifacts.

| Item | State | Next step |
| --- | --- | --- |
| `crap.threshold` | 8 (the target), required | keep it: new and changed functions stay at CRAP ≤ 8 (in practice cc ≤ 7) |
| `mutation-console` | per pull request | raise test strength; full runs need sharding before they can be scheduled |
| detekt 2.0 stable | `detekt` runs on 2.0.0-alpha.6 | move to 2.0 stable once it ships |
| Backend mutation testing | pilot | exclude `*$$serializer` classes, widen the scope, decide on the numbers |
