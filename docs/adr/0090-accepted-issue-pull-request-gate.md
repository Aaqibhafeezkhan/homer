---
status: proposed
date: 2026-10-20
deciders: Lukáš Vlk
consulted: ADR-0084, ADR-0086
---

# ADR-0090: A pull request merges only for an issue a maintainer accepted

## Context and Problem Statement

Once the GitHub repository is public, anyone can open a pull request, and every
pull request costs review time: a maintainer's, and the AI review's tokens. The
flow agreed with security is that work is admitted before it is written: an
issue is discussed first, and a maintainer or triager decides that the change
belongs in Homer. The problem is how to make that decision a check that a pull
request cannot talk its way past, without adding a bot or a service to
operate.

## Decision Drivers

- The admitting act must be one only a maintainer or triager can perform.
- A pull request must not be able to change the rule it is checked against.
- The gate must not be skippable by breaking something else in the pull
  request.
- Dependency updates from Dependabot must keep flowing without an issue each.
- No secret: fork pull requests receive none (ADR-0084).

## Considered Options

- **A. A label on the pull request itself**, set by a maintainer.
- **B. Review alone**: code owners review every pull request, no admission step.
- **C. A linked issue labelled `accepted`**, checked by a required workflow
  job.

## Decision Outcome

Chosen option: **"C. A linked issue labelled `accepted`"**.

- **Admission:** only maintainers and triagers can set labels on GitHub, so the
  `accepted` label on an issue is the authorization act. A pull request links
  the issue (`Closes #123` or the *Development* field).
- **Check:** `.github/workflows/pr-policy.yml` asks GitHub for the pull
  request's closing issues and `tools/pr-policy/check.sh` passes it only when
  one of them is an issue of this repository with the `accepted` label. The
  job checks out the base branch's copy of the script, never the pull
  request's, so a pull request cannot rewrite its own rule.
- **Not skippable:** the job depends on the script's own tests
  (`pr-policy-tests`) but runs whatever they concluded (`!cancelled()`). A
  skipped required check counts as passed, so a pull request that broke the
  tests would otherwise skip the gate with them (CWE-285).
- **Dependabot:** its pull requests are exempt. The exemption compares the
  author's login with `dependabot[bot]`, the Dependabot App's account. GitHub
  reserves the `[bot]` suffix for Apps, so no user account can hold that login,
  which is what makes a login comparison safe here. Another bot must not be
  exempted by a name pattern; identify it by its App instead.
- **Dependencies:** the `dependency-review` job in the same workflow fails a
  pull request that adds a dependency with a known high or critical advisory,
  before any review reads it.
- The `main` ruleset makes `PR policy / accepted-issue` and
  `PR policy / dependency-review` required.

**Why not the others:**
- **A** puts the decision on the pull request, after the work is done. It
  admits code rather than intent, and the discussion happens in review.
- **B** gives no admission step at all: every pull request costs a review
  before anyone has agreed the change is wanted.

### Consequences

- Good: unsolicited work is refused before review time or AI tokens are spent
  on it.
- Good: the rule is enforced by GitHub's own permissions on labels; no bot or
  service holds a credential for it.
- Bad: labelling an issue fires no pull request event, so the check is not
  re-run when the label arrives; the author re-runs it by hand
  (`CONTRIBUTING.md` § Submitting changes).
- Bad: rulesets and `dependency-review` are free only on public repositories,
  so the gate takes effect when the repository goes public.
- Neutral: a maintainer's own change also needs an accepted issue. The cost is
  one issue; the gain is that the rule has no exception to abuse.

## Amendment (2026-10-24): the factory's release pull request

A release is cut by a pull request that turns `CHANGELOG.md`'s `[Unreleased]`
into the new version's section. The AIF factory opens that pull request after
a maintainer approved the release, and no issue stands behind it: the
maintainer's approval is the admission, and the merge is a second one. The
`accepted-issue` check lets it through only as a whole:

- the author is the factory App's bot account, identified by its user id and
  the type `Bot` (identified by its App, as the Dependabot rule above asks; a
  login can be renamed, an id cannot);
- the head branch is `aif/release-X.Y.Z` of this repository, not of a fork;
- `CHANGELOG.md` is the only file the pull request changes, and it modifies
  it in place (a rename onto it would delete another file).

The facts come from the pull request event and GitHub's file list, never from
the pull request's own files, and `tools/pr-policy/check.sh` runs from the base
branch. Any other change by the factory still needs an accepted issue. The
other required checks, review threads and the maintainer's merge all stay.

This retires the original decision's "the rule has no exception to abuse": the
release pull request is the one exception. Opening it takes the factory App's
own installation token, and what it can carry is one changelog edit that a
maintainer still merges.
