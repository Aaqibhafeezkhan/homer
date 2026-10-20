---
status: accepted
date: 2026-08-13
deciders: Lukáš Vlk
---
# ADR-0040: Reject ReDoS-prone `inputSchema` patterns with a load-time lint

## Context and Problem Statement

An agent's optional `inputSchema` is a JSON Schema (Draft 2020-12) that Homer
validates the request body against. `InputSchemaValidator` delegates that
validation to the networknt json-schema-validator, which compiles each schema
`pattern` to `java.util.regex` and matches it against caller-supplied body
values. A pattern that backtracks catastrophically (e.g. `^(a+)+$`) combined
with a crafted small value pins a request thread's CPU indefinitely — a ReDoS
denial-of-service reachable by any caller allowed to POST a body.

`ParamValidator` already bounds the identical "author pattern + caller value"
vector for `inputs` params via `BoundedRegex`, which runs the match on a worker
thread reading an `InterruptibleCharSequence` and interrupts it on timeout.
`InputSchemaValidator` has no such bound.

## Decision Drivers

- The vulnerable regex is author-supplied and evaluated against
  attacker-influenceable input, so the guard must be structural or fail-closed.
- The fix must be contained and backward-compatible — it must not change how
  legitimate schemas validate bodies.
- Homer does not own the match: networknt compiles the pattern internally and
  feeds it a plain `String`, so the `BoundedRegex` interruption trick cannot be
  injected here.

## Considered Options

- **Load-time lint** — reject schemas whose `pattern` exhibits classic
  evil-regex shapes when the agent is loaded.
- **Runtime wall-clock timeout** around networknt's `validate` call.
- **Swap the regex engine** for a linear-time one (RE2J).

## Decision Outcome

Chosen option: **load-time lint**. `InputSchemaValidator.precompile` (already
invoked at agent load by `AgentsService`, and once per distinct schema) walks
the schema for every `pattern` string and `patternProperties` key and rejects
any that `RedosPatternDetector` flags — nested unbounded quantifiers (`(X+)+`,
`(X*)*`, `(X+)*`, `(X*)+`, `((X+))+`) and quantified alternations with
overlapping branches (`(a|a)*`, `(a|ab)*`, `(a|)*`), including alternations that
are non-capturing (`(?:a|ab)*`) or wrapped in nested groups (`((a|ab))*`) — the
detector normalises `(?…)` group prefixes and carries an overlap up through
nesting. A flagged pattern fails agent load with a message naming the pattern,
exactly like a malformed schema.

The detector is a documented heuristic: conservative, neither sound nor complete.
It can over-reject an unusual-but-safe pattern and can miss an exotic vulnerable
one; an author who trips it restructures the pattern.

### Consequences

- Good: the vulnerable pattern is rejected before any match runs, so no crafted
  body ever reaches an unbounded matcher. No per-request cost.
- Good: no new dependency, no engine swap, no behavioral change for schemas that
  already validate bodies today.
- Bad: heuristic — false positives (a safe pattern rejected) and false negatives
  (an exotic evil regex slipping through) are both possible.
- Bad: covers `inputSchema` patterns only; `patternProperties` keys are covered,
  but `format`/`$ref`-driven remote regexes are out of scope.

## Pros and Cons of the Options

### Runtime wall-clock timeout

- Good: catches any vulnerable pattern regardless of shape.
- Bad: a naive `Future.get(timeout)` does **not** stop the CPU burn — a
  `java.util.regex.Matcher` in a backtracking loop ignores interruption unless it
  re-reads an interruptible `CharSequence` (how `BoundedRegex` works). networknt
  owns the match and feeds it a plain `String`, so we cannot inject that; the
  worker thread keeps burning after the timeout returns.
- Bad: per-request cost and a leaked runaway thread.

### Swap the regex engine (RE2J)

- Good: linear-time matching eliminates the vector entirely.
- Bad: networknt is coupled to `java.util.regex`; swapping means replacing or
  forking the validator. RE2J also rejects some `java.util.regex` constructs
  (backreferences, lookaround), silently changing validation semantics for
  existing schemas — not backward-compatible.

## More Information

Related: `BoundedRegex`, `ParamValidator` (the `inputs` equivalent, ADR-0031).
