---
status: proposed
date: 2026-10-15
deciders: Lukáš Vlk
consulted: ADR-0056, ADR-0084
---

# ADR-0088: Check runtime dependencies against the dependency-check project's NVD mirror

## Context and Problem Statement

OWASP dependency-check fails the scheduled pipeline on a CVE at CVSS 7.0 or above
(`app/app/build.gradle.kts`). It read its data from the NVD REST API with an API
key. On GitHub-hosted runners a cold download ran for more than an hour and then
failed on an NVD `503`. The upstream pipeline had no schedule, so the check never
gated anything there either. The first complete run found 44 advisories, most of them in
Netty under the backend's HTTP server, and some in the compiler-side tools of
the build. The problem is where the check gets its data, and which dependencies
it judges.

## Decision Drivers

- The check must finish on a cold runner within the job's time limit, every
  night.
- It must judge what ships to operators, not the tools that build it.
- Fewer secrets to keep, and no rate limit to share.

## Considered Options

- **A: the dependency-check project's NVD mirror**
  (`https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/`),
  rebuilt daily from the NVD API, scanning `runtimeClasspath`.
- **B: the NVD API with an API key**, kept warm by a cache.
- **C: NIST's own 2.0 JSON data feeds.**
- **D: a self-hosted mirror** built with the vulnz CLI.

## Decision Outcome

Chosen: **A**. `nvd.datafeedUrl` points at the mirror, so the plugin never
calls the NVD API and needs no key. A cold run takes about five minutes.
`scanConfigurations` is `runtimeClasspath`, the dependency set
ADR-0056 already gates for licenses, matching the console's
`npm audit --omit=dev`. A false positive is suppressed in
`app/app/dependency-check-suppression.xml` with a note saying why.
The upstream pipeline runs it from a nightly schedule on `main` (02:00
local time), set in the project's CI/CD settings rather than in the
repository. GitHub's nightly cron skips it while the repository is private and
runs it once the repository is public; a manual run works at any time.

**Why not the others:**
- **B** is what failed: the API rate-limits, returns `503`s, and makes every
  cold run take over an hour.
- **C** has been stale for one to two weeks at a time, and is aggressively
  rate-limited under load, according to dependency-check's documentation.
- **D** gives the most control but means running and securing another service
  for one nightly job.

### Consequences

- Good: the check finishes in minutes, so each scheduled run gates: nightly
  upstream now, and on GitHub once the repository is public.
- Good: no NVD API key to store, rotate or leak.
- Bad: the mirror becomes part of the trust boundary. Its feeds are unsigned and
  come over TLS from GitHub Pages, so a stale or tampered mirror would narrow
  what the gate sees without failing it. Accepted: the check is a backstop next
  to Dependabot on GitHub, not the only source of advisories, and a stale mirror
  delays a finding rather than hiding it for good. dependency-check documents the
  mirror as best effort, updated every 24 hours.
- Bad: advisories in build-only tools (the Kotlin compiler embedded by detekt or
  ktlint, for example) no longer fail the check. They never reach an operator.
  Homer's own compiler is kept current in the version catalog.
- Neutral: `:homer-runner` is scanned with the same settings, since its image
  adds a few runtime dependencies of its own (`clikt`, `slf4j-simple`) to
  `:app`'s. Its `dependencyCheck` block copies `:app`'s, as the license report
  blocks do; a shared `buildSrc` setting was rejected because the convention
  plugin carries no plugin-specific configuration. A change to the mirror URL
  or the threshold has to be made in both files.
- Neutral: switching the data source rebuilds the NVD database once, so both
  pipelines' cache keys moved to `v2`.

## Amendment 2026-10-17: the accepted stack-exhaustion risk in the console's lint toolchain

`SECURITY.md`'s accepted-risks table carries one row whose decision record is
this ADR; this amendment records the row's acceptance argument here, where the
table's header says it lives — the ADR is the authority, the table the index.

The advisory: a deeply nested brace pattern exhausts the stack while `braces`
compiles it, so whoever shapes a glob pattern can stall the process (CWE-674,
CVSS 8.7). The exposed copy is `braces` 3.0.3 under `micromatch` 4.0.8 under
`fast-glob` 3.3.1 under `@next/eslint-plugin-next` 16.4.0 — the console's
dev-only ESLint toolchain. No patched `braces`, `micromatch`, `fast-glob` or
`@next/eslint-plugin-next` release exists yet (checked against the registry on
2026-10-17), so no override or parent bump can lift it.

Accepted, because no principal is crossed (CLAUDE.md §12):

- It ships in no image: the console image carries only the `.next/standalone`
  trace, and this ADR's checks judge what ships, not the tools that build it.
- The only patterns it compiles are the repository's own ESLint globs —
  repository-controlled input, never untrusted data — so the one actor who
  could reach the failure mode already holds the repository.
- The residual impact is a developer stalling their own lint run: no
  confidentiality or integrity impact, and no caller-multipliable resource.

Revisit when a fixed `braces`, `micromatch`, `fast-glob` or
`@next/eslint-plugin-next` release ships: lift the resolution, drop the row.
