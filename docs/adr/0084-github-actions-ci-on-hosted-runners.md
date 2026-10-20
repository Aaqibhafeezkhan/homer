---
status: proposed
date: 2026-10-14
deciders: Lukáš Vlk
---

# ADR-0084: GitHub Actions CI on GitHub-hosted runners, without Artifactory

## Context and Problem Statement

Homer is moving from a private upstream repository to a public GitHub
repository. The upstream pipeline runs on the team's own runners and resolves Gradle through an in-cluster
Artifactory proxy, added because Maven Central rate-limited (`429`) the
office's shared egress IP. A public repository takes pull requests from forks,
and a fork's workflow runs code that nobody has reviewed. The per-commit checks
need a home that is safe for that code and that does not reintroduce the
`429`.

## Decision Drivers

- Fork pull requests must get the full per-commit checks without any secret:
  GitHub withholds secrets from them, and any credential they could reach is
  exposed to code nobody has reviewed.
- Unreviewed fork code must not run on the team's own infrastructure or network.
- Maven Central must not rate-limit CI again.
- The checks and their gate semantics stay those of the upstream pipeline
  and ADR-0082.

## Considered Options

- GitHub-hosted runners, resolving from Maven Central with the Gradle cache in
  GitHub's Actions cache.
- Self-hosted runners on the team's infrastructure, keeping Artifactory.
- GitHub-hosted runners that trigger the existing upstream pipeline and wait
  for its result.

## Decision Outcome

Chosen option: **GitHub-hosted runners without Artifactory**
(`.github/workflows/ci.yml`). Every job runs on a fresh VM, so fork code never
reaches the team's network. No job needs a secret, so fork pull requests run
the same checks as maintainers' branches. The `429` came from one egress IP
fetching an uncached dependency graph on every job. Hosted runners have
different IPs, and `setup-gradle` restores the Gradle user home from the
Actions cache. Maven Central is therefore contacted only when dependencies
change. `setup-gradle` writes that cache only from the default branch, so a
pull request cannot seed entries that `main` later reuses. This matches the
Artifactory build cache's protected-refs-only rule.

The Artifactory gate stays in the Gradle settings for builds that set
`ARTIFACTORY_URL`, `ARTIFACTORY_USER` and `ARTIFACTORY_TOKEN`, such as the
upstream pipeline during the migration. Its origin moved from a literal host into
`ARTIFACTORY_URL`, so the public tree names no internal host. Anything but a
bare `https://` origin (another scheme, a path, embedded credentials) fails the
build (`ArtifactoryTransportTest`).

Until development moves to GitHub, GitHub is a one-way mirror of the upstream `main`,
fed from a single initial commit by a CI job; see
[ADR-0085](0085-one-way-github-mirror-during-migration.md).

Self-hosted runners were rejected because they would run fork code inside the
team's network. Triggering the upstream pipeline was rejected for the same reason, and it
would also need a credential that fork pull requests do not receive.

### Consequences

- Good: fork pull requests are checked automatically; nothing they run can
  read a secret or reach internal hosts.
- Good: no runners or proxy to operate for the public repository.
- Bad: the upstream merge-request widgets (JUnit, coverage, Code Quality) have
  no GitHub equivalent here. Reports are workflow artifacts, and the quality gate
  marks warn-mode findings with an annotation instead of "passed with warnings".
- Bad: if Maven Central throttles GitHub's runner IPs, the fallback is a mirror
  in the settings scripts, not a new proxy.
- Neutral: E2E runs in parallel with the unit-test jobs instead of after them.
  Hosted minutes are free for a public repository, and feedback comes sooner.
- Neutral: image publishing (`release.yml`, GHCR on a semver tag), Pages
  (`pages.yml`) and the scheduled checks (`nightly.yml`: dependency check,
  mutation runs) are ported. While the repository is private, Pages is skipped,
  as the organization's plan does not serve it, and the schedule does not run,
  to keep Actions minutes for per-commit CI. Releases publish only from a
  protected ref (`github.ref_protected`), as the upstream pipeline requires a
  protected tag, so
  the tag and `main` rulesets are what allow a release once the repository is
  public. The Slack release announcement is not ported.
