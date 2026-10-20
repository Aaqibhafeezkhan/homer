---
status: accepted
date: 2026-08-28
deciders: Lukáš Vlk
---
# ADR-0056: Dependency license policy and gating

> **Amendment (2026-10-09):** `:homer-runner` (the external pull runner,
> [ADR-0066](0066-external-pull-runners-and-unified-claim-queue.md)) is a third gated
> module: it carries the same `licenseReport { }` gate, and its report feeds the merged
> notices alongside `:app` and `:agentdebug`.

## Context and Problem Statement

Homer is Apache-2.0 and distributed as shadow JARs and Docker images —
both bundle every runtime dependency's compiled code directly into the
artifact that ships. That is binary redistribution of the full runtime
dependency graph, which carries its own attribution and (for
weak-copyleft licenses) source-availability obligations, independent
of Homer's own license. The npm/console side of the build already has
a license gate (`build-homer-console` fails on LGPL-3.0 `libvips`
binaries before push). The Gradle side — `:app` and `:agentdebug`,
250+ runtime dependencies between them — had no equivalent check:
`dependencyCheck` gates CVEs on the same classpath but says nothing
about license terms, so a new transitive dependency could introduce
GPL/LGPL terms unnoticed. A prior commit on this branch wired the
`com.github.jk1.dependency-license-report` plugin into both modules;
this ADR records the policy it enforces and why.

## Decision Drivers

- Every runtime dependency ships inside the fat JAR/Docker image, so
  license compatibility has to be reviewed once and then re-checked on
  every dependency change — not audited occasionally by hand.
- The check must fail the build, not just report — a silent report
  nobody reads doesn't stop a bad license from shipping.
- Attribution for the permissive/weak-copyleft licenses already in use
  should be satisfied by a generated notices file, not hand-maintained
  legal text that drifts from the actual classpath.
- Real dependencies here are dual-licensed (logback EPL-1.0|LGPL 2.1,
  jna Apache-2.0|LGPL 2.1, javax/jakarta.annotation CDDL-1.0|GPL-2.0
  with classpath exception, jetty alpn-api Apache-2.0|EPL-1.0) — the
  policy has to have a defined answer for those instead of requiring a
  per-dependency carve-out.

## Considered Options

- **Allowlist, enforced in CI, plus bundled notices** (chosen) —
  `checkLicense` (jk1 plugin) gates `tasks.check` against an explicit
  allowlist of accepted licenses; `generateLicenseReport` emits a
  `THIRD-PARTY-NOTICES.txt` bundled into each shadow JAR.
- **Blocklist known-bad licenses, allow everything else by default** —
  rejected: default-allow is backwards for a project that redistributes
  every dependency in binary form. An unrecognized or new copyleft
  license would pass silently until someone happened to notice it.
- **Manual periodic audit, no CI enforcement** — rejected: same failure
  mode as the blocklist — nothing stops a bad license from merging
  between audits, and the audit itself has no forcing function.

## Decision Outcome

Chosen option: **allowlist enforced in `check`, shared between `:app`
and `:agentdebug`, plus bundled notices.**

Both modules configure `licenseReport { }` identically against
`configurations = ["runtimeClasspath"]`, apply
`LicenseBundleNormalizer` (so license-name variants for the same
license collapse to one entry), and point `allowedLicensesFile` at the
single shared
[`app/config/allowed-licenses.json`](../../app/config/allowed-licenses.json).
`tasks.check.dependsOn("checkLicense")` makes the gate part of the
normal build, not an opt-in extra step. `checkLicense` fails the build
if any dependency's *every* declared license is missing from the
allowlist.

The allowlist currently holds exactly the normalized license names
that appear on the actual runtime classpath of both modules today:
Apache-2.0, MIT, MIT-0, BSD-3-Clause, EPL-1.0, EPL-2.0, and CDDL-1.0
(the SPDX shorthand used here for readability — the actual
`allowed-licenses.json` holds the normalizer's display names, e.g.
"Apache License, Version 2.0", "The 3-Clause BSD License", so a grep
of the file for these SPDX ids won't match).
It was built empirically from the classpath, not copied from a generic
template — nothing is allowlisted "just in case."

**Dual-licensed dependencies pass via an any-match rule.** `checkLicense`
treats a dependency as compliant if *any one* of its declared licenses
is on the allowlist — this is the jk1 plugin's own matching behavior,
not a per-dependency override this repo added. So logback (EPL-1.0 |
LGPL 2.1), jna (Apache-2.0 | LGPL 2.1), javax/jakarta.annotation
(CDDL-1.0 | GPL-2.0 with classpath exception), and jetty alpn-api
(Apache-2.0 | EPL-1.0) all pass through their permissive/weak-copyleft
half, without the LGPL/GPL half ever being added to the allowlist.

**LGPL, GPL, and unrecognized licenses are deliberately never
allowlisted.** A dependency change that lands under one of those — or
under a license the plugin can't identify — fails `checkLicense`, and
the only ways forward are: drop the dependency, replace it with one
under an acceptable license, or make a conscious, reviewed decision to
add the new license to the allowlist. There is no silent-pass path.

`generateLicenseReport`'s `TextReportRenderer` produces
`THIRD-PARTY-NOTICES.txt`; `shadowJar` depends on that task and copies
the file into `META-INF/` of the fat JAR, so the notices ship inside
the exact artifact they describe and are regenerated from the real
classpath on every build — no hand-maintained notices file to fall out
of sync.

The plugin has no Gradle configuration-cache support: Gradle discards
the cache entry for `checkLicense`/`generateLicenseReport` and runs
them without it. This is a build-performance footnote, not a
correctness concern — both tasks still run and still gate `check`.

### Consequences

- Good: a new dependency under a non-allowlisted license fails CI
  immediately, forcing a conscious decision instead of an unreviewed
  merge.
- Good: the attribution obligation for every permissive/weak-copyleft
  license in use is satisfied automatically by the bundled notices,
  regenerated from the live classpath rather than maintained by hand.
- Good: the any-match rule keeps the allowlist itself honest and small
  — it only ever needs to list licenses actually reviewed and
  accepted, never a copyleft license added solely to unblock one
  dual-licensed dependency.
- Bad (accepted): the EPL-1.0/2.0 and CDDL-1.0 dependencies passing
  this gate must stay unmodified as vendored or patched sources —
  those licenses' weak-copyleft terms mean modifying and redistributing
  their source triggers a source-disclosure obligation the license gate
  itself does not check for (it classifies the declared license, not
  whether the module's source was changed).
- Neutral: no configuration-cache support for `checkLicense` /
  `generateLicenseReport` — since `app/gradle.properties` enables the
  configuration cache build-wide, Gradle discards it for the whole
  invocation whenever either task is scheduled, not just for those two
  tasks, so every `check` and `shadowJar` run pays this cost. A minor,
  accepted build-time cost.

## Addendum (2026-09-04): committed notices

The generated `THIRD-PARTY-NOTICES.txt` inside each shadow JAR stays as it is,
but the attributions are now **also committed at the repository root**:
[`NOTICE`](../../NOTICE) (Homer's copyright plus every dependency NOTICE text we
are required to reproduce) and
[`THIRD-PARTY-LICENSES.md`](../../THIRD-PARTY-LICENSES.md) (the inventory,
grouped by license, with the full text of each license type).

A file inside a build artifact was not enough. Legal review needs the
attributions readable in the repository, at a stable path, without building
anything or unpacking a JAR — and the same file has to be citable in a review
thread and diffable when a dependency changes.

Both files are generated and drift-gated, so they cannot rot:

- **Backend** — `:app:generateThirdPartyNotices` writes them from the jk1 JSON
  report and the `LICENSE`/`NOTICE` files the plugin extracts from each jar,
  merging the `:app` and `:agentdebug` runtime classpaths.
  `:app:checkThirdPartyNotices` re-renders and diffs, and `tasks.check` depends
  on it next to `checkLicense`. Both depend on `checkLicense` rather than
  `generateLicenseReport`: only `checkLicense` writes the JSON report, and
  routing through the gate means notices are never cut from a classpath whose
  licenses have not been accepted. Generation fails rather than emitting a
  placeholder if a module has no declared license, or if a license type has no
  text in the appendix.
- **Console** — `npm run licenses:generate` / `licenses:check`, run in the
  console CI job. The inventory is taken from the production dependency graph
  in `package-lock.json` — every entry that is not `dev: true` — because the
  console image ships both the `.next/standalone` server bundle and the
  `.next/static` client bundles, and the production graph is the superset of
  what either can redistribute; because the lock is platform-independent, so
  the gate cannot drift between a darwin and a linux build's swc binaries; and
  because it needs no `npm run build` first, so drift fails fast. Dev
  dependencies are excluded: they attribute code that is never redistributed.
  `sharp` and `@img/*` are excluded too, together with everything nested under
  them — `console/Dockerfile` deletes those directories from the standalone
  tree before the runner copies it, which is what keeps Homer from conveying
  their LGPL-3.0 binaries.

Each file carries a generated region between marker comments, so the
hand-written framing (what the file covers, base-image and Python-client
pointers, the license texts appendix) survives regeneration.

The copyright-line extraction rule (which lines of a package's `LICENSE` /
`NOTICE` files count as a holder attribution) is deliberately implemented
twice — in `ThirdPartyNotices.kt` for Gradle and in
`console/scripts/third-party-licenses.mjs` for npm — because the two build
systems cannot share one implementation without a cross-language dependency.
Parity is a tested contract, not a convention:
[`app/config/third-party-copyright-cases.json`](../../app/config/third-party-copyright-cases.json)
is the shared truth table, and both test suites assert every case against
their own generator, so a change to one filter fails CI on whichever side
drifted. This is the one place the console tree reads into the backend tree
(`console/scripts/third-party-licenses.test.mjs` reads
`../../app/config/…`); the coupling is accepted so the table has a single
home next to `allowed-licenses.json`.

## More Information

- Plugin wiring: [`app/app/build.gradle.kts`](../../app/app/build.gradle.kts),
  [`app/agentdebug/build.gradle.kts`](../../app/agentdebug/build.gradle.kts).
- Shared allowlist: [`app/config/allowed-licenses.json`](../../app/config/allowed-licenses.json).
- Committed notices (addendum): [`NOTICE`](../../NOTICE),
  [`THIRD-PARTY-LICENSES.md`](../../THIRD-PARTY-LICENSES.md); generators
  [`app/buildSrc/src/main/kotlin/ThirdPartyNotices.kt`](../../app/buildSrc/src/main/kotlin/ThirdPartyNotices.kt)
  and [`console/scripts/third-party-licenses.mjs`](../../console/scripts/third-party-licenses.mjs);
  parity table [`app/config/third-party-copyright-cases.json`](../../app/config/third-party-copyright-cases.json).
- Parallels the existing npm-side gate (`build-homer-console` LGPL-3.0
  `libvips` check) noted in the `[1.17.0]` CHANGELOG entry under
  `### Build / CI` — this ADR is the Gradle-side counterpart.
