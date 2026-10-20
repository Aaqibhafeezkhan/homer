---
status: accepted
date: 2026-05-07
deciders: Lukáš Vlk
---
# ADR-0009: Conditional command execution via bash predicates

## Context and Problem Statement

Agent definitions executed commands in a fixed linear sequence. The
only flow control was `continueIfFail` (don't abort on non-zero exit)
and `finishExitCode` (early-stop on a specific exit code). There was
no way to express "run this command **only if** some condition over
the current env is true" — agents that wanted branching ("deploy only
if `$REGION` starts with `eu-`", "skip cleanup if `$DEPLOY_RESULT` is
empty") had to inline shell `if`/`exit 0` inside `script` commands,
which loses execution-list visibility (the command always shows as
ran), doesn't compose with `resultEnvParam`, and forces every author
to reinvent the same idiom.

The decision was *how* to express the predicate: a custom DSL, a
boolean expression mini-language, or shell-out to bash.

## Decision Drivers

- **Native operators users already know.** `isEmpty`, `equals`, `not`,
  `regex` — the operators the use cases actually need — are already
  bash test-builtins (`-n`, `-z`, `=`, `!`, `[[ … =~ … ]]`). Reaching
  for bash means zero learning curve and zero implementation work for
  the operator set itself.
- **No new evaluator to maintain.** A custom DSL means a parser, an
  AST, a type system, error messages, edge-case fuzzing — months of
  drift versus the bash semantics every Homer agent author already
  works against.
- **Predicate eval errors must be loud.** A typo in a predicate
  (`[ -m "$VAR" ]` instead of `-n`) should fail the agent, not
  silently skip a command. Skip-on-error masks predicate bugs in
  exactly the way that's hardest to detect (the command "didn't run
  because the condition was false," but actually it didn't run because
  the predicate is broken).
- **Scales to AND-of-many and OR-of-many cleanly.** A single bash
  string with `&&`/`||` is fine for one or two terms but becomes
  unreadable past that, and a per-expression error report (which
  expression failed) requires running them separately anyway.
- **Composes with everything else uniformly.** Predicates need to see
  the same env the command would have seen, including vars produced
  by earlier commands' `resultEnvParam`. Wiring the evaluator into
  `AgentExecutor` after `appendToEnv` makes that automatic.

## Considered Options

- **Option A — Bash predicates with `all` / `any` and a string
  shorthand.** Each expression runs as `bash -c <expr>` with a 5 s
  timeout; exit `0` → true, `1` → false, `≥ 2` → eval error fails
  the agent. Schema accepts `when: <string>`, `when: { all: [...] }`,
  or `when: { any: [...] }`. Skipped commands record an
  `ExecutionInfo` with `skipped: true` and continue.
- **Option B — Custom expression DSL** (e.g.
  `when: { isEmpty: $VAR }`, `when: { equals: ["$VAR", "x"] }`,
  with `not`/`and`/`or` combinators). Strong typing on values, no
  shell dependency.
- **Option C — Single bash string with `&&`/`||` only.** No `all`/`any`
  wrapper; users compose their own conjunctions in the shell.
- **Option D — Full nested expression tree** (
  `{any: [{all: […]}, {not: …}]}`). Maximally expressive, no
  arbitrary depth limit.

## Decision Outcome

Chosen option: **"Option A — Bash predicates with `all` / `any` and a
string shorthand"**.

Bash supplies the operator set users actually want (`-n`, `-z`, `=`,
`!`, `[[ … =~ … ]]`) at zero implementation cost; the `all`/`any`
list-of-expressions wrapper buys readable AND-of-many / OR-of-many
without a nested-tree grammar; the string shorthand keeps the
single-expression case ergonomic; and the eval-error-fails-agent rule
makes predicate bugs visible.

The 5 s per-expression timeout is hard-coded in v1: predicates should
be fast (`test` is microseconds; one shell startup is <100 ms on every
runner Homer supports), and a configurable timeout is configuration
that nobody will tune. If a real use case for longer predicates
appears later, adding a per-command `whenTimeout` is non-breaking.

`agentdebug --dry-run` prints the predicate but does not evaluate it.
Dry-run env may not match production env (no transformers ran, no
upstream `resultEnvParam` produced its outputs), so evaluating would
report a false negative against the real run. Printing-without-running
is what the rest of `--dry-run` already does for command bodies.

`commandIndices` (debug runs, single-command launches) still gates on
the predicate. The predicate is part of command semantics, not a
list-time filter — running a single command "as it would run in
production" should include the gate.

### Consequences

- Good: zero new mini-language; agent authors use idioms they already
  know from any shell they've ever written.
- Good: the eval-error-fails-agent rule makes typos visible
  immediately rather than silently skipping commands the author
  expected to run.
- Good: short-circuit evaluation means a 100-element `all` list pays
  for one expression in the common case.
- Good: per-expression error reporting names the offending expression
  and stderr summary in the failure message, which a single
  `bash -c "x && y && z"` could not (failure of `y` looks identical
  to failure of `z`).
- Good: scales to AND-of-many and OR-of-many cleanly without a nested
  tree; the flat-list shape is what 90 %+ of the requested use cases
  need.
- Good: composes with `resultEnvParam` automatically — predicates run
  after `appendToEnv`, so they see the env produced by upstream
  commands.
- Bad: depends on `bash` being on PATH. Same dependency as `Cmd` /
  `Script` already have, so any runner Homer supports already meets
  this requirement. Documented in `AGENT.md`.
- Bad: bash quoting is sharp. `[ -n $VAR ]` (unquoted) behaves
  differently from `[ -n "$VAR" ]` when `$VAR` is empty. This is a
  property of bash, not of the predicate system, but new users will
  hit it. Mitigated by the `Common bash-test idioms` table in
  `AGENT.md`.
- Bad: a future expressive use case (nested boolean tree) would
  require either a non-breaking schema extension (e.g.
  `when: { not: { all: […] } }`) or a backwards-compat shim. The
  flat list-of-strings shape is intentionally a non-goal for nested
  composition; if that demand appears, the `When` model can grow
  without breaking existing fixtures (the `WhenSerializer` already
  handles a polymorphic decode).

## Pros and Cons of the Options

### Option A — Bash predicates with `all` / `any` + string shorthand

- Good: native bash operators cover every requested operator
  (`isEmpty`, `equals`, `not`, `regex`) with zero implementation.
- Good: per-expression error reports name the offending expression.
- Good: schema is small (one new `$defs.When` entry, three accepted
  shapes) and the model is one `data class` plus a custom serializer
  that mirrors the existing `CmdArgsSerializer` pattern.
- Neutral: relies on bash. Same dependency the rest of the executor
  set has.
- Bad: bash quoting gotchas exist; mitigated by docs.

### Option B — Custom expression DSL

- Good: strong typing on values; no shell dependency.
- Bad: implementation cost is months: parser, AST, type system,
  evaluator, error messages, fuzzing, docs. All for an operator set
  bash already provides.
- Bad: every operator the DSL doesn't yet support becomes a feature
  request. Bash's `test` accepts dozens of operators; the DSL would
  be perpetually behind.

### Option C — Single bash string with `&&`/`||` only

- Good: simplest possible schema.
- Bad: per-expression error reporting impossible — failure of `y` in
  `x && y && z` looks identical to failure of `z`.
- Bad: AND-of-many and OR-of-many become unreadable past two or three
  terms.
- Bad: short-circuit semantics still happen, but the user can't
  observe which term short-circuited.

### Option D — Full nested expression tree

- Good: maximally expressive.
- Bad: no concrete use case requested it. The flat-list shape covers
  the requested AND-of-many and OR-of-many cleanly; introducing
  nesting adds schema surface and ambiguity (decode rules for `not`,
  precedence, etc.) that the requested use cases don't pay for.
- Neutral: deferred — `WhenSerializer` already polymorphically decodes
  three shapes, and a future `not` / nested form can be added without
  breaking existing fixtures.

## More Information

- Model: `When` in
  [`Agent.kt`](../../app/app/src/main/kotlin/homer/app/models/Agent.kt).
- Evaluator: [`WhenEvaluator.kt`](../../app/app/src/main/kotlin/homer/app/infra/WhenEvaluator.kt).
- Wire-in: `AgentExecutor.execute` in
  [`AgentExecutor.kt`](../../app/app/src/main/kotlin/homer/app/executors/AgentExecutor.kt).
- Skip recording: `ExecutionInfo.skipped` in
  [`ProcessManager.kt`](../../app/app/src/main/kotlin/homer/app/services/ProcessManager.kt).
- Schema: `$defs.When` in
  [`homer-agent.schema.json`](../../app/schemas/homer-agent.schema.json).
- agentdebug printers: `DryRunPrinter`, `ShowCommandsCommand`,
  `DebugAgentRunner` under
  [`agentdebug/`](../../app/agentdebug/src/main/kotlin/homer/agentdebug/).
- User-facing docs: `Conditional execution (when)` section in
  [`AGENT.md`](../../app/AGENT.md).
- Related ADRs:
  [ADR-0002](0002-unified-error-model-at-http-boundary.md) for why
  eval errors propagate via the existing `CommandExecutionException`
  path.
