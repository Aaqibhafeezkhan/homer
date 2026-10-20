---
status: accepted
date: 2026-09-14
deciders: Lukáš Vlk
---
# ADR-0062: Cost by tag — aggregated at query time from live rows

## Context and Problem Statement

Homer prices every Claude attempt onto `processes.cost_usd` (ADR-0061) and
runs carry `key:value` subject tags in `process_tags` (ADR-0060), but the two
are never joined: there is no way to ask what everything carrying one tag
value, such as one ticket, cost. Operators want cost broken down by the values of
one or more tag keys, with the tracked keys declared in server config rather
than open to any key a caller names.

## Decision Drivers

- **No new persistence.** `cost_entries` (ADR-0035/ADR-0007) is an
  append-only ledger with no `process_id` column, backing `CostMeter`'s
  throttle windows; adding one to correlate spend with tags is a migration
  and a second source of truth for a value `processes.cost_usd` already
  holds.
- **Runtime tags must count.** A tag declared mid-run via
  `POST /api/v1/callback/tags` (ADR-0060) must be reflected even though the
  Claude attempts that produced the run's cost were recorded before the tag
  existed.
- **§12 rule 7 / CWE-203 (ADR-0060's existing screen).** A tag whose value is
  currently secret must never surface as a breakdown key — the same
  constraint `getAll`'s `?tag=` filter and its response already enforce.
- **§12 rule 4.** The candidate row set is bounded by the same
  `MAX_TAG_SCREENED_ROWS` cap the tag-filtered branch of `getAll` uses, not a
  new, separately-tuned bound.
- **TagValidation.KEY_PATTERN allows `_`.** A SQL single-character wildcard,
  so a prefix match built as `LIKE 'key:%'` from a config-declared key would
  also match an unrelated look-alike key (`ticket_id:` vs `ticketXid:`).
- **Trust boundary: no new one.** `/costs`, `/costs/monthly`, `/costs/totals`
  are already `authorizer.isAdmin` 403 gates; the new endpoint answers the
  same question (aggregate spend) and should sit behind the same gate.

## Considered Options

- **Option A — aggregate at query time.** Join `process_tags` to `processes`
  by tag-key prefix, keep only tags whose key is configured, screen for
  secrets, group by tag value and sum `processes.cost_usd`. No schema change.
- **Option B — snapshot the tag value onto each Claude spend entry** at
  `accountAttempt` time, and sum `cost_entries` grouped by that column.
- **Option C — reuse Langfuse trace tags** (ADR-0050) and pull cost from
  Langfuse's own aggregation.

## Decision Outcome

Chosen option: **"Option A — aggregate at query time"**. Option B needs a
`cost_entries` migration and would systematically undercount: a tag declared
after some of a run's Claude attempts already posted their entries would
never be attached to those entries, so the run's earlier spend would be
missing from any tag-scoped total. Query-time aggregation always reads a
run's *current* tag set against its *current* running total, so it is
correct regardless of when a tag was declared relative to the spend that
produced it. Option C fails for the same reason ADR-0060 rejected it for
subject-tag search: a telemetry-`OFF` run has no trace, and Homer's spend
figures (session-endpoint-priced, ADR-0061) are not necessarily what Langfuse
reports.

Specifics:

- **`ProcessRepository.costByTag(key, screenTags): Map<String, Double>`**
  (since the 2026-09-29 amendment: returns `CostByTagResult`, which adds
  `truncated`) — candidate rows are `Processes` filtered by a correlated `EXISTS` against
  `ProcessTags` (`carriesTagPrefix`, mirroring `carriesAllTags`'s shape from
  ADR-0060), not a `LIKE`. The prefix test is a **range bound**: every
  character `TagValidation.KEY_PATTERN` permits in a key or a value sorts
  strictly between `:` (0x3A) and `;` (0x3B), so `tag >= "key:" AND tag <
  "key;"` matches exactly the tags whose key portion equals `key` — no `_`/`%`
  wildcard ambiguity, and no `ESCAPE` clause to get right. The existing
  `(tag, process_id)` index on `ProcessTags` supports the range scan.
  (2026-09-29 amendment: the correlated `EXISTS` planned as a scan of
  `processes`; the candidate query is now `id IN (…)` driven by that index,
  `process_tags_tag_process_id`.)
- **Bounded the same way `getAll`'s tag-filtered branch is.** The candidate
  query is capped with `MAX_TAG_SCREENED_ROWS` (10,000) — the same constant,
  not a second bound to keep in sync. (2026-09-29 amendment: the constant is
  unchanged, but the query orders by `id DESC` and fetches one row past it to
  report `truncated`, so the shape differs from `getAll`'s tag branch.)
- **Secret screening happens after the SQL, never in it**, for the same
  reason ADR-0060's `?tag=` filter does: a value that is currently secret
  (an input flipped to `secret: true` since the tag was written, or a
  `fromInputs` tag derived from a `secret` input) must not become a breakdown
  key. Candidate rows are hydrated into `ProcessInfo` (batched
  `tagsByProcessTx`, chunked by `SQLITE_PARAM_CHUNK`) and every tag is passed
  through the caller-supplied `screenTags` before it can contribute — the
  same callback signature and the same `screenTags(tags, inputParams, agent,
  secretInputKeys)` function the `/status/all` route already builds. A
  route calling this method without a real screen cannot compile past the
  required, non-nullable parameter — the same fail-closed shape ADR-0060
  established for `getAll`.
- **Grouping happens in the application, not `GROUP BY`**, because the
  screen must run per row before a value is allowed to become (or contribute
  to) a group key; a SQL `GROUP BY` would group on the raw, unscreened value
  first.
- **Route: `GET /api/v1/costs/by-tag/{key}`**, `authorizer.isAdmin`-gated
  (403, matching its siblings) — spend data is treated as an admin-only
  surface regardless of the axis it's sliced on, the same posture `/costs`,
  `/costs/monthly`, and `/costs/totals` already take, so this endpoint
  introduces no new principal boundary. `key` must be a member of
  `costTrackedTagKeys` or the route answers 400 — the config list is
  what's queryable, not any key a caller can name, which is also what keeps
  the response's cardinality bounded at deploy time rather than at request
  time.
- **`AppConfig.costTrackedTagKeys: List<String>`** is validated at config
  load against `TagValidation.KEY_PATTERN` (same charset as a tag key) and
  capped at `TagValidation.MAX_TAG_QUERY_PARAMS` entries — reusing
  `TagValidation`'s existing constants rather than declaring new ones.
- **Accepted tradeoff: a run can be counted once per value under a shared
  key.** `process_tags`' primary key is `(process_id, tag)`, so nothing
  stops a run from carrying two different values under the same configured
  key (e.g. a workflow that re-tags itself). Such a run's `cost_usd`
  contributes to both values' totals. This is accepted rather than
  engineered around: the endpoint is informational (§2 — no billing or
  enforcement reads it), and a run legitimately carrying two subject values
  for the same key arguably did incur cost attributable to both.

Trust boundary touched: **none new** — `/costs/by-tag/{key}` reuses the
existing admin-only gate the sibling cost endpoints already enforce.
Secrets are excluded via the same post-SQL `screenTags` application
ADR-0060's `?tag=` filter uses, with the same fail-closed (non-nullable
callback) shape. Availability is bounded by the pre-existing
`MAX_TAG_SCREENED_ROWS` cap and by restricting queryable keys to
`costTrackedTagKeys`.

### Consequences

- Good: no new table, no new column, no migration — `cost_entries` and
  `CostMeter` are untouched.
- Good: a tag declared at any point in a run's life is reflected correctly,
  because aggregation reads the run's current tag set and current
  `cost_usd`, not a value frozen at spend-record time.
- Good: reuses `MAX_TAG_SCREENED_ROWS`, `carriesAllTags`'s `EXISTS` pattern
  (since the 2026-09-29 amendment, its index-driven `id IN (…)` form instead),
  and the `screenTags` callback verbatim — no parallel security surface to
  keep in sync with ADR-0060's.
- Neutral: the queryable key space is deploy-time, not request-time — an
  operator who wants to slice by a new tag key must add it to
  `costTrackedTagKeys` and restart/reload rather than querying it ad hoc.
- Bad: a run carrying two values under one configured key has its cost
  counted once per value (accepted above).
- Bad: like `getAll`'s screened branch, cost scales with the rows the
  candidate query admits (up to `MAX_TAG_SCREENED_ROWS`), not with the
  number of distinct values returned.

## More Information

- Write path (unchanged, from ADR-0060): `process_tags` inserts at
  `obtainProcessId` and via `POST /api/v1/callback/tags`.
- Read path: `ProcessRepository.costByTag` (range-bound candidate query —
  since the 2026-09-29 amendment an index-driven `id IN (SELECT process_id …)`,
  `hasTagPrefix`, instead of the original `EXISTS` — + `screenTags` +
  application-side grouping), `ProcessManager.costByTag` (delegates),
  `SecuredRoutes.kt` `GET /api/v1/costs/by-tag/{key}`, `CostByTagResponse`
  (with `truncated`).
- Config: `AppConfig.costTrackedTagKeys`, validated against
  `TagValidation.KEY_PATTERN` / `MAX_TAG_QUERY_PARAMS`.
- Related: [ADR-0060](0060-run-tags.md) (tag storage, screening, the
  `EXISTS`/range-bound pattern this ADR reuses);
  [ADR-0061](0061-price-claude-runs-from-session-spend-endpoint.md)
  (`cost_usd`, `cost_entries`); [ADR-0035](0035-sqlite-backed-cost-ledger.md)
  (the ledger this ADR deliberately does not extend);
  [ADR-0047](0047-security-by-design-and-threat-model.md) (§12).

## Amendments

- 2026-09-29: the `MAX_TAG_SCREENED_ROWS` bound stays — the
  secret screen must decode every candidate row (`ProcessInfo` +
  `screenTags`), so raising or removing it is unbounded work per §12
  rule 4, not a size the endpoint can absorb. Aggregating the sum in SQL
  (`GROUP BY` over `cost_usd`, no row cap) was considered and rejected
  for the same reason ADR-0060 rejected it for the `?tag=` filter: the
  screen needs each run's `inputParams`, `secretInputKeys` and current
  agent definition, none of which a `GROUP BY` can evaluate — grouping
  has to happen after every row is hydrated and screened, in the
  application.

  Instead, `costByTag` now reports when the bound cut the candidate set
  short: it fetches `MAX_TAG_SCREENED_ROWS + 1` rows ordered by `id
  DESC` and drops the extra one, so `CostByTagResponse.truncated` is
  `true` exactly when more runs carried the key than fit the bound.
  The `LIMIT` bounds the rows returned, not the query's work: that is
  bounded by the `key:` range of the tag index, at most `maxProcesses` ×
  the per-run tag cap entries.
  Kept end: the **newest** `MAX_TAG_SCREENED_ROWS` runs by process id —
  the same end `getAll`'s tag-filtered branch already keeps for its
  default descending order, so a truncated total silently drops the
  oldest matches, never the most recent activity. The endpoint is
  admin-only, so exposing the flag discloses nothing across principals.

  Adding the `ORDER BY` also fixed the candidate query's shape: the
  previous correlated `EXISTS` (`Processes` filtered row-by-row against
  `process_tags`) plans as a full `SCAN processes`, probing the
  `(process_id, tag)` primary key once per row instead of driving from
  an index. It is replaced with the same `id IN (SELECT process_id FROM
  process_tags WHERE …)` form `carriesAllTags`'s index-driven branch
  already uses (ADR-0060) — planned on `process_tags_tag_process_id`,
  with `processes` fetched by primary key and no `TEMP B-TREE` for the
  sort, checked at 150k rows with and without `sqlite_stat1`
  (`DatabaseMigrationTest`).
