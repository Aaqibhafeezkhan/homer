# CLAUDE.md

Behavioral defaults (§1–§4) apply to every task. Homer project rules
(§5–§10) encode this repo's specific conventions that repeatedly bite
LLM agents. **§12 is a cross-cutting constraint: it binds every other
section and overrides them on conflict** (there is no §11; §12 keeps its
number because code, tests and ADRs cite it).

**Tradeoff:** These guidelines bias toward caution over speed. For
trivial tasks, use judgment — except under §12, which does not scale
down with task size.

**Everything this repository produces is written in English.** Code,
comments, commit messages, CHANGELOG entries, docs, ADRs, pull-request
titles and descriptions, code-review comments, and issue-tracker comments
about repository work — all of it, without exception.

This holds **regardless of the language the task was asked in, and
regardless of the language of the ticket or PR being answered**. Match the
repository, not the thread: replying in Czech to a Czech pull request is
the easy mistake, and it is still wrong. If the audience needs another
language, that is a separate translation next to the English, never
instead of it.

The reason is reach. Homer ships an open-source-shaped surface
(`LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`) and
its history is meant to be readable by people outside this team. A Czech
commit message or ADR is a door closed to every one of them, and unlike
code it is never revisited and fixed later.

When working inside a package, the nearer CLAUDE.md auto-loads and
augments this file:

- [`app/CLAUDE.md`](app/CLAUDE.md) — backend architecture (Kotlin/Ktor).
- [`console/CLAUDE.md`](console/CLAUDE.md) — frontend architecture
  (Next.js).
- [`clients/python/homer_langgraph/CLAUDE.md`](clients/python/homer_langgraph/CLAUDE.md)
  — the Python LangGraph runtime.
- [`app/app/src/main/kotlin/homer/app/runners/CLAUDE.md`](app/app/src/main/kotlin/homer/app/runners/CLAUDE.md)
  — the runner contract, claim queue and external pull runners.
- [`app/app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md`](app/app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md)
  — the persistence model and the schema-version history.
- [`app/app/src/main/kotlin/homer/app/executors/llm/CLAUDE.md`](app/app/src/main/kotlin/homer/app/executors/llm/CLAUDE.md)
  — the LLM harness recipe and its cost accounting.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

## 5. Commit hygiene and build verification

- **One commit per logical change.** No bundled multi-issue commits.
  Imperative subject; body explains *why*, not *what*.
- **Before every commit**, run the relevant verify step and make sure
  it's green:

  | Area touched | Verify |
  | --- | --- |
  | `app/**` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app ktlintFormat :app:test :app:checkThirdPartyNotices` |
  | `app/buildSrc/**` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app/buildSrc check` (a separate build — `-p app check` does not cover it) |
  | `console/**` | `cd console && npm run lint && npm test && npm run build && npm run licenses:check` |
  | `clients/python/**` | `cd clients/python && .venv/bin/python -m pytest` (venv setup: [`clients/python/homer_langgraph/CLAUDE.md`](clients/python/homer_langgraph/CLAUDE.md)) |
  | `tools/quality/**` | `cd tools/quality && npm ci && npm test` |
  | `site/**`, or a file the site publishes (listed in `site/scripts/sources.mjs`, every ADR) | `cd site && npm ci && npm test && npm run docs:build` (fails on a link to a missing file) |
  | `app/schemas/**` or `Agent.kt` / `AppConfig.kt` | backend verify (runs `SchemaValidationTest`, `AgentMdExamplesTest`) |
  | `.github/ISSUE_TEMPLATE/**` or `.github/pull_request_template.md` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app :app:test --tests 'homer.app.CommunityTemplatesTest'` (the community-template guard; `node tools/quality/changed.mjs` selects no check for a template-only change) |

- **Before every commit, also re-check §9 doc map.** For each file you
  changed, ask: *"what surface does this affect, and which canonical
  doc(s) describe it?"* Update those docs in the **same** commit. This
  is the cheapest way to stop doc drift: a doc update one PR late is
  almost never written. Backstop tests cover only schemas and
  `AGENT.md`; everything else relies on this check.
- JAVA_HOME pinning matters — the backend does not build on system Java.
- **Quality reports** ([`docs/quality-checks.md`](docs/quality-checks.md)
  lists every check). CI job `quality-gate` lists every check's findings
  (CRAP, compiler and lint warnings, architecture rules, unused and
  duplicated code, diff coverage, mutation results) in its output; a
  warn-mode finding adds a warning annotation to the run, and the full
  report is uploaded as the job's `quality-gate` artifact. Checks in `warn` mode
  ([`tools/quality/checks.json`](tools/quality/checks.json)) do not block;
  still, add no new finding in code you touch. `required` checks fail the
  CI run — among them `diff-coverage`: the lines a pull request adds or
  changes must be at least 80% covered in every file. Write new and changed
  functions for a CRAP score of at most 8, the `crap` threshold
  (ADR-0081): in practice cc ≤ 7, since a cc-8 function fails on any
  uncovered line. A check becomes `required`
  once its backlog is fixed —
  [ADR-0082](docs/adr/0082-quality-reports-report-first.md).
- **While you work, run the checks on your change:** `node tools/quality/changed.mjs`
  runs the quality checks against the working tree's change since `origin/main`, with
  only the related tests, in seconds to a minute. Fix what it reports before you commit.
  It is an approximation: the verify step above and the pull request's full CI run still
  decide ([`docs/quality-checks.md`](docs/quality-checks.md) § Diff mode).
- Don't bypass hooks (`--no-verify`) or skip tests to "unblock" a commit.

This is the operational checklist; §4 is the task-level framing.

## 6. Tests

- **Backend: every new public function, route, service, or executor has
  ≥1 test.** Location: `app/app/src/test/kotlin/homer/app/...`
  mirroring the production package. MockK for mocks, Ktor test-host
  for HTTP.
- **Console: every new component, hook, or utility has ≥1 Vitest +
  React Testing Library test.** `npm test` must pass. See
  [`console/TESTING.md`](console/TESTING.md) for the stack and layout.
- **Backend: user-visible behavior changes (API endpoints, agent-format
  semantics, validation rules) also get black-box E2E coverage** in
  [`app/e2e/`](app/e2e/README.md) — extend the relevant fixture agent
  and test class. Exclusions (Claude/LangGraph commands, cron) per that
  README. The suite is dockerized and runs in CI, not in the §5
  pre-commit verify step.

- **Parsers, validators, formatters and security-relevant input
  handling also get a property-based test** (`kotest-property` on the
  backend, `fast-check` in the console): example tests mirror the
  implementation an agent just wrote, properties find the inputs it did
  not think of. A property failing on existing code is a bug report, not
  a test to weaken.

TDD framing for bug fixes and refactors is in §4.

## 7. Architecture decisions → ADRs

Write an ADR under `docs/adr/` when **any** of these is true:

- Another reasonable option existed and the choice has lasting
  structural impact (error-handling model, concurrency strategy, auth
  scheme, storage layout, etc.).
- The decision would otherwise only live in a PR description that
  nobody will find six months later.
- The choice constrains future work (e.g. "agents are filesystem-only",
  "errors flow through one HTTP handler").

Routine fixes, refactors, and implementation details **do not** need an
ADR. Template and authoring rules: [`docs/adr/README.md`](docs/adr/README.md).

## 8. Changelog

User-visible changes get an entry under `[Unreleased]` in the single
project changelog **in the same commit**:
[`/CHANGELOG.md`](CHANGELOG.md). Group entries under the appropriate
subsection per release: `### Homer` (backend / API / agent schema /
CLI / config) or `### Console` (console UI / routes). A change to a
shipped Docker image that an operator notices goes under the stack the
image ships. Older releases carry a `### Build / CI` subsection; don't
add new ones.

**Entry triggers:** new/changed API endpoints, new config keys, new
agent-schema fields, new CLI flags, UI-surface changes, fixed
user-visible bugs, breaking changes (prefix with `**BREAKING**`).

**No entry:** internal refactors, test-only commits, doc-only commits,
dependency bumps with no user-visible impact, **CI pipelines, build
tooling and quality checks** (they never reach a user; the commit body
and, when structural, an ADR record them), and **fixes to a feature
that is still under `[Unreleased]`** — the bug never reached users, so
the feature's own `Added` / `BREAKING` entry already describes the net
shipped behavior. (If the fix changes that net behavior, fold it into
the existing entry instead of adding a `Fixed` line.)

**Merging.** `CHANGELOG.md` is `merge=union` in `.gitattributes`: when two
branches add bullets at the same spot, `git merge` keeps both instead of
stopping on a conflict. After such a merge, glance at the `[Unreleased]`
section for a bullet that landed under the wrong subsection.

**Format — keep entries short.** One line, one short sentence: *what
changed*, nothing more. The CHANGELOG is a scan-friendly index, not a
design document.

- Format: `- **<Category>: <short title>** — <one sentence describing what changed>.`
- Add `See [ADR-XXXX](docs/adr/XXXX-...md).` at the end only when an
  ADR exists for the change. No other references.
- Do **not** include: implementation details (class names, internal
  fields, file paths), rationale paragraphs, design tradeoffs,
  migration recipes, config snippets, audit / issue-tracker numbers.
  Rationale belongs in the ADR; how-to belongs in the canonical doc
  (§9); deep `Why:` lives in the commit body.
- Breaking changes use `**BREAKING: <title>**` instead of the normal
  category prefix.

Examples:

```markdown
- **Added: `server.requireClaudeCodeVersion` config** — Pins Homer to a specific Claude CLI version; non-matching runs fail without retry.
- **Added: HMAC body-signature authentication** — New `AuthConfig.Hmac` and `AuthConfig.Combined` auth modes for webhook signature verification. See [ADR-0010](docs/adr/0010-auth-schemes.md).
- **BREAKING: agent directory and `agent.name` reject spaces** — Allowed character set is `^[a-zA-Z0-9_-]+$`.
- **Fixed: `STD_ERR` result mapping** — Was reading from stdout.
```

Follow Keep-a-Changelog categories: `Added`, `Changed`, `Deprecated`,
`Removed`, `Fixed`, `Security`.

## 9. Documentation — canonical doc map

**Core rule: code change → same-commit doc update.** Every canonical
doc in the table below describes a surface — user-facing or internal —
that other contributors rely on. If your change alters what a canonical
doc describes, update that doc **in the same commit**.

This applies equally to:

- **User-facing surfaces** — HTTP API, agent format, server config,
  console flows, Docker topology, CHANGELOG-worthy deltas.
- **Internal architecture surfaces** — service layering, directory
  layout, status precedence, gotchas, workflows. The
  `app/CLAUDE.md` and `console/CLAUDE.md` dev guides are full
  canonical docs, not optional notes. A refactor that moves
  `ProcessExecutor`'s status-precedence rules without updating
  `app/CLAUDE.md`'s "Status precedence rules" section is a
  half-finished commit.

Two related rules:

- **Don't update every `.md` that mentions the topic** — only the
  canonical one for that surface. Touching peripheral mentions is how
  drift starts (one file gets updated, the others lag).
- **Don't update a doc you didn't break.** A refactor inside one
  service rarely touches user-visible surfaces. Don't open
  `AGENT.md` "just to be safe" — the diff should reflect what
  actually changed.

| Surface | Canonical doc |
| --- | --- |
| HTTP API endpoints | [`app/README.md`](app/README.md) (reference) + [`/CHANGELOG.md`](CHANGELOG.md) (delta) |
| Agent definition format | [`app/AGENT.md`](app/AGENT.md) |
| Server configuration | [`app/SERVER.md`](app/SERVER.md) |
| JSON schemas (editor autocomplete) | [`app/schemas/homer-config.schema.json`](app/schemas/homer-config.schema.json), [`app/schemas/homer-agent.schema.json`](app/schemas/homer-agent.schema.json) |
| Architecture decisions | [`docs/adr/`](docs/adr/) |
| Quality checks: what is tested, where, and how to run it | [`docs/quality-checks.md`](docs/quality-checks.md); modes in [`tools/quality/checks.json`](tools/quality/checks.json) |
| Console user flows / features | [`console/README.md`](console/README.md) + [`/CHANGELOG.md`](CHANGELOG.md) (`### Console`) |
| Console deployment | [`console/DEPLOYMENT.md`](console/DEPLOYMENT.md) |
| Console testing | [`console/TESTING.md`](console/TESTING.md) |
| Contributor verify commands (mirror of §5) | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| Docker topology | [`/DOCKER.md`](DOCKER.md) |
| Feature atlas (single-page overview of every user-visible feature; published on the documentation site) | [`docs/homer-feature-atlas.html`](docs/homer-feature-atlas.html) |
| Documentation site (which docs it publishes, versions, landing page) | [`site/`](site/), [ADR-0087](docs/adr/0087-versioned-documentation-site.md) |
| Backend development guide | [`app/CLAUDE.md`](app/CLAUDE.md) |
| Runner contract, claim queue, external pull runners (backend subsystem guide) | [`app/app/src/main/kotlin/homer/app/runners/CLAUDE.md`](app/app/src/main/kotlin/homer/app/runners/CLAUDE.md) |
| Persistence model, read/write handles, schema-version history (backend subsystem guide) | [`app/app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md`](app/app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md) |
| LLM harness recipe and cost accounting (backend subsystem guide) | [`app/app/src/main/kotlin/homer/app/executors/llm/CLAUDE.md`](app/app/src/main/kotlin/homer/app/executors/llm/CLAUDE.md) |
| Console development guide | [`console/CLAUDE.md`](console/CLAUDE.md) |
| LangGraph Python runtime (segment model, env contract, `inspect` / `state` output) | [`clients/python/homer_langgraph/README.md`](clients/python/homer_langgraph/README.md) |
| LangGraph runtime development guide | [`clients/python/homer_langgraph/CLAUDE.md`](clients/python/homer_langgraph/CLAUDE.md) |
| Authoring plugin (bundled skill + schema) | [`plugins/homer-authoring/`](plugins/homer-authoring/) — bundled copies of `AGENT.md` + schemas; refresh via `:app:syncAuthoringPlugin` (see §10) |

Everything else under `docs/` or `console/` is archival — do not touch
unless the task is explicitly "update this doc."

### Comments vs docs — keep code comments sparse

Rationale, alternatives considered, and multi-paragraph background do
**not** belong in inline code comments. They belong in an ADR (§7) or
the relevant canonical doc above. Verbose comment blocks go stale, bloat
the diff, and duplicate what the ADR already records.

Keep an inline comment only when it is **terse and load-bearing** — a
non-obvious correctness constraint a future editor would otherwise break
(e.g. "must nest INSIDE `authenticate`: `requestKey` reads the principal
auth populates first"). State the constraint in one or two lines and
point to the ADR (`See ADR-XXXX`) for the why. If a comment is
explaining *why a decision was made*, that's a sign it should be in the
ADR with a pointer, not in the code.

## 10. Schema ↔ model ↔ doc sync (the contract-surface instance of §9)

This is §9 applied to the strongest contract surface in the repo:
the serializable Kotlin models, their JSON schemas, and the reference
docs that describe them to agent authors. Because these three forms
of the same contract drift apart easily, the rule is spelled out
explicitly and backed by automated tests.

Editing `homer.app.models.Agent`, any `homer.app.models.Command`
subclass, `homer.app.models.AppConfig`, or related serializable
models requires a **same-commit** update to **both**:

1. The matching file under `app/schemas/` — `homer-agent.schema.json` for
   `Agent`/`Command` changes; `homer-config.schema.json` for
   `AppConfig` changes.
2. The canonical reference doc per §9 —
   [`app/AGENT.md`](app/AGENT.md) for agent/command changes;
   [`app/SERVER.md`](app/SERVER.md) for config changes.

For `AGENT.md`, specifically update **every** affected surface in the
same commit:

- The Agent Fields / Cmd / Script / Claude / RunAgent / Workspace /
  LockStrategy / etc. **field table** (column for column: type,
  required, default, description).
- At least one **YAML example** showing the new/changed field.
- The matching **JSON example** inside the `<details>` block (kaml-tag
  YAML and the `type`-discriminated JSON form are both reference
  surfaces).
- The default-value comment in any "Default workspace" / "Default
  lock" / etc. example block that mentions the field.

The distributed authoring plugin under `plugins/homer-authoring/` is a
**fourth, derived form** of this contract: it bundles its own copies of
`app/AGENT.md` and the `app/schemas/*.json` files so it can ship to external
users without the repo layout. Those copies are **generated artifacts** — any
edit to `app/AGENT.md` or `app/schemas/*.json` requires re-running
`app/gradlew -p app :app:syncAuthoringPlugin` in the **same commit** so the bundled
copies stay byte-identical.

Three automated backstops fail loudly when this drifts:

- `SchemaValidationTest` — schema validates the on-disk
  `example/config/*` and `example/agents/*` fixtures.
- `AgentMdExamplesTest` — every complete-agent YAML/JSON block in
  `app/AGENT.md` is deserialized via `Agent.serializer()` and
  validated against `homer-agent.schema.json`. Catches structural
  drift (e.g. `agents: [...]` list form when the model expects a map,
  or `resultEnvParam: {…}` object form when it expects an array).
- `AuthoringPluginBundleTest` — asserts each bundled copy under
  `plugins/homer-authoring/` is byte-identical to its `app/...` source;
  fails with "run `app/gradlew -p app :app:syncAuthoringPlugin`" on drift.

Neither backstop catches prose drift — wrong default values in field
tables, missing rows, misdescribed validation semantics. Those rely
on the reviewer reading the diff. If you change a field's *default*
or *required* status without touching its row in the AGENT.md table,
the test won't notice.

For all other canonical docs in the §9 table (README, SERVER.md,
CLAUDE.md dev guides, DOCKER.md, ADR set, console docs) there is no
automated backstop — keeping them in sync is reviewer discipline
under §5 + §9.

## 12. Security by design (binds every other section)

**Homer runs untrusted-by-construction code, holding credentials, for
callers with distinct grants. Security is a design input, not a review
finding.** On conflict this section overrides the others, §2 included:
a bound, a gate, or a denial test is never speculative work.

Boundaries — say which one a change touches when it isn't obvious:

- **Principals are a boundary.** Crossing one is a defect whatever its
  severity score.
- **Same-UID access is not.** Fix when cheap, else record accepted risk
  with reasoning. But making *Homer act* is still an escalation.
- **Availability is in scope**, including for authenticated readers.

Design rules:

1. Name the trust boundary the feature crosses — ADR (§7) if
   structural, commit body otherwise.
2. Treat everything an agent can write as attacker-controlled
   (artifacts, workspace files, agent env, checkpoint stores, output
   parsed into env). Validate at the point of use, not only entry.
3. Authorize in the handler, fail closed, hide existence — denials
   return the same `404` as not-found.
4. Bound every resource a caller can multiply. A bound living in
   another component is not a bound; make it local and explicit.
5. Every bound creates a queue: bound the wait too, and shed rather
   than accumulate. Never cache a shed response.
6. Never write derived data where another principal can write. Prefer
   removing the need for the file.
7. Secrets travel by reference, never by manifest — not in pod specs,
   argv, logs, spans, or error text.
8. Prove each constraint with a test that asserts the **denial** path
   (§6).

Fixing a finding: the mechanism a fix introduces is new surface, so
re-examine it, and cover the symmetric cases and any sibling service
sharing the pattern. Prefer deleting a repeatedly-flagged component over
defending it again — measure its benefit before assuming it has one.

Threat model, and the defect behind each rule:
[ADR-0047](docs/adr/0047-security-by-design-and-threat-model.md).

---

**These guidelines are working if:** fewer unnecessary changes in
diffs, fewer rewrites due to overcomplication, clarifying questions
come before implementation rather than after mistakes, ADRs capture
decisions that would otherwise be lost, changelogs reflect every
user-visible delta, the schemas never lag behind the models, and
security findings arrive as design questions answered up front rather
than as scanner rows answered later.
