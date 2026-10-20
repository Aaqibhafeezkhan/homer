---
status: accepted
date: 2026-09-08
deciders: Lukáš Vlk
---
# ADR-0060: Run tags — subject tags stored per run and filterable through the API

## Context and Problem Statement

An operator watching Homer wants to find every run *about a subject*: "show me
the runs for MR 15", "which runs touched project 4711". Until now the only thing
that could answer this was the Langfuse trace tags (`telemetry.traceTags`, ADR-0050),
and only inside Langfuse — the traces API filters on `tags`, but Homer's own
status API and console had nothing to filter on. A run's subject was implicit in
its inputs, which are per-agent and not searchable.

We want a run to carry `0..n` `key:value` **subject tags**, returned on the status
API, filterable (`GET /api/v1/status/all?tag=mr:15`), and shown in the console — so
the same `paramName:value` strings that already tag the trace also make the run
findable in Homer itself. A subject is sometimes known only mid-run (a decision the
run reached), so tags must also be declarable at run time, by the run, without a way
for one run to tag another or to leak a value a `secret` input asked to hide.

## Decision Drivers

- **Find runs by subject in Homer, not only in Langfuse.** The status API and
  console must filter on tags directly.
- **No duplicate source of truth with trace tags.** `traceTags` already derives
  `paramName:value` from inputs; run tags must reuse that derivation, not fork it.
- **Some tags are only known at run time.** A run must be able to add a tag to
  *itself* while running.
- **§12: principals are a boundary; availability is in scope.** A run may tag only
  itself (the callback token is the identity — no run id in the body); filtering must
  not reveal a run the caller cannot see (absent, never `403`); every caller-multipliable
  resource (tags per run, tag filters per request) is bounded locally with a denial test.
- **§12 rule 7: secrets travel by reference.** A value that was ever `secret` for the
  run must never be stored as a tag — on the `fromInputs` path (dropped by the
  ADR-0052-union derivation) and, symmetrically, on the `runtime` path (a value
  containing any secret the run holds is refused).
- **Records outlive workspaces (ADR-0048); tags evolve within a run (ADR-0051).** Tag
  rows must be written and deleted co-transactionally with the run row.

## Considered Options

- **Option A — a child `process_tags(process_id, tag)` table**, one row per tag,
  `fromInputs` rows written co-transactionally with the process INSERT and `runtime`
  rows appended by the callback route; joined for `?tag=` with `GROUP BY … HAVING
  COUNT(*) = n`; deleted with the process row.
- **Option B — a JSON-text `tags` column on the process row.** One column, no join.
- **Option C — reuse Langfuse.** Store nothing; answer "find runs by subject" by
  querying the traces API and mapping traces back to runs.

## Decision Outcome

Chosen option: **"Option A — a `process_tags` child table"**. A separate table is the
only option that lets `?tag=` filter *in the query* (a JSON column would scan and
`LIKE`-match in application code, and could not AND cheaply), keeps a tag's identity a
first-class row for the `HAVING COUNT` AND, and lets tag rows share the process row's
transaction and deletion. Langfuse (Option C) fails outright: a run with telemetry
`OFF` has no trace, the mapping is external, and it cannot honour Homer's own
visibility union.

Specifics:

- **Two origins, one top-level `tags` block.** `tags.fromInputs` lists `paramName`s
  resolved to `paramName:value` at start; `tags.runtime` lists tag *keys* the run may
  declare mid-run. Both live in the agent schema; the block replaces
  `telemetry.traceTags` (see deprecation below).
- **`fromInputs` reuses the trace-tag derivation.** `langfuseTraceTags(agent,
  queryParams, secretInputKeys)` is the single function that turns inputs into
  `paramName:value`, applied to the ADR-0052 secret **union** (`secretInputKeys` =
  the keys the agent declared secret at run time), so a historically-secret value is
  never stored. The resulting list is stored as `fromInputs` tag rows **and** emitted
  as trace tags — one derivation, two sinks. Values must match
  `^[A-Za-z0-9._-]{1,128}$` (decision 4 — `/`-free, so a path-shaped subject like
  `group/repo` is dropped and must be tagged by numeric id, and no value needs
  URL-encoding in a `?tag=` query); a blank/absent value adds no tag and an
  over-length or off-charset value is dropped with a WARN, never truncated (a
  truncated tag matches no exact filter).
- **`fromInputs` rows are written co-transactionally with the INSERT (ADR-0051).**
  `obtainProcessId` writes the process row and its `fromInputs` tag rows in one
  transaction, so a run's subject tags are visible the instant its row is — the same
  atomicity ADR-0051 established for `created_at` and the cooldown gate.
- **`runtime` tags are declared by the run, via the callback credential domain
  (ADR-0019).** `POST /api/v1/callback/tags` resolves the token to a process id
  (constant-time; no run id in the body, so one run can never tag another), refuses a
  terminal/unknown run with `409`, requires the tag's key to be listed in
  `tags.runtime` (else `400`), validates the value charset, refuses a value containing
  any of the run's secret values (`400`, next bullet), and appends the row. The
  `homer-tag` workspace helper wraps this. A run may hold at most **20** runtime tags
  (`400` past the cap); re-declaring the same `key:value` is idempotent (the row set
  collapses on the unique `(process_id, tag)`).
- **Runtime tag values are screened with the run's own `SecretRedactor`, not a
  bespoke list.** The redactor `Process` builds for telemetry is attached to the
  callback registration and the route refuses any value the redactor would alter
  (`redact(value) != value`), the same test `ArtifactNameResolver` applies to an
  attacker-influenced artifact name. That covers every secret Homer knows for the
  run — `secret` inputs under the ADR-0052 union, `globalEnv` / `agent.env` values
  sourced via `@SYSENV:`, and the run's `HMR_CALLBACK_TOKEN` — as substrings, not
  only verbatim. The token case is the one that matters most: it is plain hex and
  fits the tag charset, and a tag carrying it would hand every read-grant holder the
  ability to ask, dispatch, and post results *as that run* — the "making Homer act"
  escalation of §12, across a principal boundary. A checked equality against declared
  secret inputs only, the first cut, caught none of the three. The needle floor is
  the artifact screen's **8 characters**: a shorter secret would match as a substring
  of nearly any value (§12: availability is in scope) and offers no protection to
  match anyway. A refused call logs the run id, agent and key — a run trying to
  publish a secret is a compromise indicator — never the value. The redactor is
  dropped when the registration closes, since a closed run answers `409` first.
  Defense in depth on the read side: `ProcessInfo.toResponse` omits any stored tag
  containing a value that is secret under the ADR-0052 union *at read time*, so an
  input flipped to `secret: true` after a tag was written is covered the same way
  ADR-0052 covers input params (fail closed to "every input value" when the agent is
  gone). **Accepted residual risk:** an encoded (base64, hex) or split secret passes
  both screens. No content filter can close this: twenty 128-character tags over a
  66-symbol alphabet are ~15 kbit of run-controlled output per run, and the run
  already has stdout, artifacts and the network as higher-capacity channels, every
  one of which Homer screens with this same substring redactor. The guard matches the
  strongest posture the codebase has rather than pretending to a stronger one.
- **`runtime` keys are disjoint from `fromInputs` keys.** A key in both is an
  agent-load error — otherwise a runtime declaration could shadow or duplicate an
  input-derived tag with confusing precedence.
- **Every tag key matches `^[A-Za-z0-9._-]{1,64}$`, `fromInputs` paramNames included.**
  A `fromInputs` name becomes the stored key verbatim, so it is held to the same
  charset and bound as a `runtime` key at agent load (`TagValidation.KEY_PATTERN`).
  Without it an off-charset or over-long paramName would mint, on every run, a
  `key:value` longer than the 193-character `?tag=` bound or outside its URL-safe
  charset — a tag that no query can ever match and that only grows the table
  (denial_of_service / CWE-400).
- **`?tag=` ANDs with the visibility clause (ADR-0058 pattern).** `getAll` collects
  repeated `tag` params (trimmed, de-duped), caps them at **10** locally in the
  handler (`400` past the cap), and ANDs a correlated `EXISTS (… GROUP BY process_id
  HAVING COUNT(*) = n)` predicate onto the same `WHERE` that carries the owner/grant
  visibility condition. A run the caller cannot see, or that lacks a tag, is simply
  absent — never `403`, never a distinct not-found.
- **Child runs do not inherit parent tags.** A dispatched/continued run is tagged only
  by its own `fromInputs`/`runtime`. Whole-flow discovery is
  `?parentProcessId=` (ADR-0058), which already exists; copying tags down would
  double-count and leak a parent's subject onto a sub that a different principal may own.
- **Deleted co-transactionally with the row (ADR-0048).** `remove` deletes a run's
  `process_tags` rows in the same transaction as the process row, on both record purge
  and capacity eviction.
- **`telemetry.traceTags` is deprecated, kept one release.** It becomes an alias for
  `tags.fromInputs` (same meaning), logs a WARN at agent load, and an agent may set one
  or the other. Setting both is not rejected — it is accepted and their union is
  used: `effectiveFromInputTags = (tags.fromInputs + telemetry.traceTags).distinct()`
  is the single value every start path and the load-time validation read, so the
  alias cannot drift from the new field, and an agent that sets both simply gets the
  de-duplicated union alongside the deprecation WARN.
  *(Amended 2026-10-09: deprecated in 1.22.0, the alias is still accepted; it stays
  until a release removes it.)*

Trust boundary touched: **principals** (the callback route — a run tags only itself)
and **availability** (two local bounds: 20 runtime tags per run, 10 tag filters per
request, each with a denial-path test per §12 rule 8). Secrets are excluded on the
`fromInputs` path by construction via the ADR-0052 union, and on the `runtime` path
by refusing a value the run's `SecretRedactor` would alter (rule 7) plus a read-time
screen, each with a denial-path test (substring embed, `@SYSENV:` value, own callback
token, post-run `secret` flip).

### Consequences

- Good: "find runs by subject" is answered by Homer's own API and console, for every
  run including telemetry-`OFF` ones, with no external dependency.
- Good: one derivation feeds both trace tags and run tags; the deprecated alias funnels
  through `effectiveFromInputTags`, so the two cannot disagree.
- Good: a run can only tag itself, and only with keys its author allowed; the cap and
  charset bound what it can write.
- Neutral: tags are `key:value` strings — a numeric `mr:4211` is the string `"4211"`,
  matched exactly, with no range queries.
- Bad: tag *cardinality* is unbounded by design (any allowed value), so an author who
  tags with free text or a per-run UUID mints a distinct filter value per run. Documented
  as a "bounded value set only" rule, the same guidance `traceTags` already carried.
- Neutral: a visible run exposes its own tags to any viewer who can see it; tags are
  subject metadata the author chose to attach, not secrets.
- Bad: the runtime-tag secret screen is substring matching with an 8-character floor.
  An encoded or split secret, or one shorter than 8 characters, is not caught —
  accepted, for the channel-capacity reasons above.

## Pros and Cons of the Options

### Option A — `process_tags` child table

- Good: `?tag=` filters and ANDs in the query; tags are first-class rows; shares the
  process row's transaction and deletion.
- Bad: a join per filtered list read and a second table in the eviction/purge paths.

### Option B — JSON-text `tags` column

- Good: no new table; one column written with the row.
- Bad: `?tag=` becomes an application-side scan-and-match; ANDing several tags has no
  cheap SQL form; a tag is not an addressable row, so the callback append is a
  read-modify-write of the whole column under the write lock.

### Option C — reuse Langfuse traces

- Good: no storage change.
- Bad: a telemetry-`OFF` run has no trace; the run↔trace mapping is external; it cannot
  honour Homer's visibility union; and the console would depend on a Langfuse round-trip
  to render a run's own tags.

## Addenda

- **Runtime-tag writes fail closed on a missing run row (CWE-459).** `process_tags`
  carries no foreign key (decision above — rows share the process row's transaction
  and deletion), and the record purge (`remove`) and the registration close that
  gates the callback route are not one transaction. A callback that arrives after a
  run's row is purged would therefore `INSERT` a tag row for a process that no longer
  exists — a tag outliving the run it describes, an orphan that no delete path will
  ever reclaim. `insertRuntimeTag` now checks row existence **inside its own
  transaction** and returns "not written" when the row is gone, so the route answers
  the same terminal/unknown `409` it already gives. Denial-path test per §12 rule 8.
- **`?tag=` id resolution is visibility-scoped in the query, not after it (CWE-400).**
  `tagMatchingIdsTx` originally resolved the `GROUP BY … HAVING COUNT` id set across
  **all** tenants' `process_tags` rows and left the owner/grant union to the outer
  `getAll` query. That made the intermediate id set — and the work to build it —
  scale to the whole table regardless of what the caller can see, an availability
  side-channel a reader could drive with tags on runs they have no grant on. The
  visibility union is now pushed into the resolution query as a `JOIN processes` with
  the same owner/grant predicate `getAll` applies (null `visibleAgents` = admin = no
  filter), so the materialized set never spans invisible runs and the cost scales to
  the caller's own rows. Denial-path test per §12 rule 8.
- **`?tag=` no longer materializes an id set at all (CWE-400, follow-up).** Even
  visibility-scoped, the id list was read into the application and fed back as
  `processes.id IN (?, ?, …)` before `LIMIT` applied — proportional to every visible
  matching run rather than to the page, and bounded only by `server.maxProcesses`
  living in another component (§12 rule 4). Past SQLite's bound-parameter cap it would
  also fail outright. The predicate is now a correlated `EXISTS` in the main `WHERE`
  (Exposed DSL, no raw SQL): per candidate row, `process_tags` is probed by its
  `(process_id, tag)` primary key and the `HAVING COUNT(*) = n` group proves every
  requested tag is present. One query, nothing held in the application, cost scales
  with the rows the outer visibility clause admits. A statement-recorder test asserts
  the shape (§12 rule 8).
- **`?tag=` honours the same secret screen the status response applies (CWE-203,
  Observable Discrepancy).** The correlated `EXISTS … HAVING COUNT` above matches
  stored `process_tags` values *before* display redaction runs. `screenTags` drops
  a `fromInputs` tag whose value came from a `secret: true` input (the ADR-0052
  redaction union) from a run's status, but the filter matched that same value, so
  `total`/results changed for a probe that guessed it — a read-only oracle
  confirming a secret-derived value to any caller with ordinary read access. The
  filtered path (`tags` non-empty ∧ a `screenTags` is supplied) now re-materializes
  its **visibility-scoped** candidate set and re-checks each matched row through the
  same `screenTags` the display path uses, so a value the display would hide is not
  matchable, and the returned `total` is the post-screen count and cannot leak a
  hidden-match tally. This supersedes the "no id set materialization" addendum **for
  the tag-filtered branch only** — the no-tag path is unchanged. Residual bound: the
  screened branch reads the caller's matching rows into the application before
  paging, so its cost scales with the rows the visibility clause already admits (the
  same set the prior branch's `EXISTS` scanned), not the whole table — and the fetch
  is capped in SQL at **10 000 rows** (`MAX_TAG_SCREENED_ROWS`), because the screen
  cannot run inside the SQL predicate and a caller's `limit=1` must not materialize
  the whole visible-matching set first; `total` reflects only matches within that
  prefix, in id order. Tag rows for the surviving candidates arrive in the same
  one-batched-`IN`-list read the no-tag paging path uses, not a per-row query. That
  read chunks the ids by **512** (`SQLITE_PARAM_CHUNK`) and runs one query per chunk:
  every id is a bound parameter and SQLite's `SQLITE_LIMIT_VARIABLE_NUMBER` default
  (999) makes an `IN`-list wider than that a runtime error, so a candidate set the
  visibility clause admits past 999 matches must degrade to repeated reads, not a
  500. The screen is a **required, no-null-escape** parameter of `ProcessRepository.getAll`
  whenever a tag filter is present (failing closed otherwise) — ADR-0052 again: a
  defaulted parameter is how a future caller silently re-opens the oracle. The
  screen is per-row identity-based — it needs the row's agent/snapshot — so it must
  stay row-scoped: a refactor hoisting the agent/current-definition part out of the
  callback would over-hide sibling runs and break the "sibling with the same key
  still matches" guarantee the pinned tests assert. Denial-path test per §12 rule 8.

  **Accepted residual channel: response timing** ([CWE-208](0047-security-by-design-and-threat-model.md)).
  The screened branch's cost scales with the rows the visibility clause admits, so
  a probed tag the caller can *match* (rows materialised, tags fetched, screen run)
  takes measurably longer than one that never appears in `process_tags` (the
  correlated `EXISTS` exits on an empty index scan). That is enough to distinguish
  "value never stored" from "value stored but screened" — a residual oracle even
  though the content and `total` oracles (CWE-203) are closed. Fixing it exactly is
  not available: option (a), always materialising `MAX_TAG_SCREENED_ROWS` rows
  regardless of match count, normalises nothing (the SQL `EXISTS` scan cost differs
  before any application work, and an unconditioned 10 000-row read on every
  no-match probe converts the noise channel into the DoS vector the
  `MAX_TAG_SCREENED_ROWS` cap exists to close); option (b), a calibrated artificial
  delay, requires padding all responses to the 10 000-row worst case per call and
  is the same trade with worse latency. Accepted risk per ADR-0047's "fixed when
  cheap" standard instead, with the reasoning: the oracle's guessing space is
  already trichotomised away by the visibility clause and the screen itself — a
  probe only yields a timing signal when the value exists on a run the caller can
  already *see is present* (the row survives the visibility clause), so the extra
  bit it leaks ("that run's tag is screened") reveals a secret state of data whose
  existence the caller already knows about, not the secret itself. Any future
  redesign of this branch (e.g. a screen-capable SQL predicate) should re-examine
  both this and the CWE-203 bound together.

## Amendment (2026-09-29): the admin tag filter is an uncorrelated subquery

At a full `maxProcesses` table the correlated `EXISTS` probed `process_tags` once per
process row the outer clause admits: 96–114 ms per `?tag=` read at 150 000 rows, and
the unused `(tag, process_id)` index never drove it. For a caller with no visibility
filter (an admin) the predicate is now `processes.id IN (SELECT process_id FROM
process_tags WHERE tag IN (…) GROUP BY process_id HAVING COUNT = n)`, still one SQL
statement with nothing held in the application. The index drives it and processes are
fetched by id: 34 ms for a rare tag and 47 ms for one on 12 500 runs, against the
34 ms connection baseline.

A scoped caller keeps the correlated `EXISTS`. The subquery's cost follows every run
carrying the tag, other principals' runs included, so its timing would give a scoped
caller a coarse count of runs it cannot see: a new cross-principal channel, where the
accepted timing residual above is limited to runs the caller can already see. The
correlated form's cost follows only the rows its visibility clause admits. An admin
sees every run, so no boundary is crossed on the fast path. `ProcessRepositoryTest`
pins both forms, including the denial case that a scoped caller never gets the
subquery. The choice keys off `visibleAgents == null`, the same argument that already
means "no visibility filter": a caller that wrongly passed null would already see every
row, a larger exposure than the timing. An explicit flag threaded from the route was
rejected, because it could disagree with the visibility arguments it duplicates.

## More Information

- Write path: `Process.fromInputTags` (= `langfuseTraceTags(agent, queryParams,
  secretInputKeys)`), `ProcessRepository.obtainProcessId(tags)`,
  `CallbackRoutes` `POST /api/v1/callback/tags` → `ProcessManager.addRuntimeTag`.
- Read path: `ProcessRepository.getAll(tags)` (`id IN (SELECT … HAVING COUNT)` for an admin, correlated
  `EXISTS … HAVING COUNT` for a scoped caller, ∧ visibility; see the 2026-09-29 amendment),
  `ProcessInfoResponse.tags`; query cap in `SecuredRoutes.parseStatusAllParams`.
- Validation: `TagValidation` (value/key charset, per-run and per-request caps) is the
  single source for every enforcement surface.
- Deprecation: `Agent.effectiveFromInputTags`, WARN at agent load; `homer-tag` helper.
- Related: [ADR-0050](0050-per-agent-telemetry-block.md) (traceTags, now aliased);
  [ADR-0051](0051-cooldown-is-enforced-in-the-insert-transaction.md) (co-transactional
  INSERT); [ADR-0052](0052-redaction-metadata-is-captured-with-the-run.md) (secret union);
  [ADR-0048](0048-split-record-retention-from-workspace-purge.md) (co-transactional delete);
  [ADR-0058](0058-parent-child-run-linkage.md) (visibility-ANDed filter, child
  non-inheritance); [ADR-0019](0019-human-in-the-loop-questions.md) (callback token
  credential domain); [ADR-0047](0047-security-by-design-and-threat-model.md) (§12).
