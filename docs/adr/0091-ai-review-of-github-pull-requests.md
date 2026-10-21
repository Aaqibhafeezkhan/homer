---
status: proposed
date: 2026-10-21
deciders: Lukáš Vlk
consulted: ADR-0047, ADR-0084, ADR-0090
---

# ADR-0091: The AI review of GitHub pull requests, gated before any model runs

## Context and Problem Statement

Homer's merge requests have been reviewed by an AI review that the maintainers
run on Homer itself. Now the repository is public on GitHub, and anyone can
open a pull request there. A review model reads the pull request's text, its
diff and its comments. On a public repository all of that can be written by a
stranger, including text addressed to the model. The review also holds a
GitHub credential, because it has to post its findings.

The problem is how to review pull requests on GitHub without letting the
author of a pull request decide when a model runs, what it reads, or what the
review's credential does.

## Decision Drivers

- No model runs before a deterministic check allows it (CLAUDE.md §12,
  ADR-0047).
- An outside author's code is read by a person before a model reads it.
- The credential can write review comments and a commit status, and nothing
  else: no approval, no merge, no settings.
- Each reviewer owns one area, so findings neither overlap nor contradict.
- The result is a commit status the `main` ruleset can require.

## Considered Options

- **A. A GitHub Actions workflow** that runs the review on `pull_request`.
- **B. A GitHub App with a webhook** that starts the review on each event.
- **C. A poller outside GitHub** that reads pull requests every minute, decides
  deterministically, and only then starts the review.

## Decision Outcome

Chosen option: **"C. A poller outside GitHub"**.

- **The gate.** Every minute the poller lists open pull requests on `main` and
  decides without a model.
  - An author who is an `OWNER` or `MEMBER` of the organization is reviewed
    after 30 quiet minutes. An organization member's `@homer review` comment
    starts a round at once.
  - Anyone else waits until a maintainer submits an APPROVED review of exactly
    the head commit. A new push needs a new approval.
- **State.** The only state is the `homer-ai-review` commit status on the head
  commit. Only a status the review's own account wrote counts.
- **The pre-check.** An outside author's pull request is checked before any
  reviewer reads it. A change to a file that runs in the build, the tests or
  CI stops the review, and no model is involved in that check. The same holds
  for a change to the instructions the reviewers read (`CLAUDE.md`), and for a
  diff too large or unreadable to check. A model that sees only the diff then
  looks for network calls or environment reads in tests, obfuscated code, and
  text addressed to an AI. On a stop, a maintainer reviews by hand.
- **The reviewers.** Each reviewer owns one area and names the areas it leaves
  to the others: architecture, code, docs, threat model, performance, tests,
  and, from the second round, the open threads. A deterministic scan checks
  the change against the sync denylist (ADR-0085). The model steps have file
  tools only, with no shell and no network, and they stay inside their own
  work directory.
- **The credential.** The review reaches GitHub through a proxy that holds the
  credential and allows one repository and a fixed list of paths:
  - reads;
  - review comments, replies, issue comments, comment edits and commit
    statuses;
  - a review only with the event `COMMENT`, never `APPROVE` or
    `REQUEST_CHANGES`;
  - GraphQL only as two exact documents, the thread listing and the thread
    resolution.
- **Publishing.** One review per round carries the new findings. A summary
  comment is edited each round. Threads of findings that a later round finds
  fixed are resolved, and only threads the review opened. Text a model wrote
  is neutralised before it is posted, so it can neither forge the review's
  markers nor mention anyone.
- **The status.** `homer-ai-review` is `success` when no blocker or major
  finding is open, and `failure` otherwise. The `main` ruleset requires it.

**Why not the others:**
- **A** runs in the pull request's own context. On a fork it gets no secret
  (ADR-0084), so it cannot post. With `pull_request_target` it gets the
  secret next to the author's code, which is the setup the gate exists to
  avoid.
- **B** starts the review when the event arrives, not when the deterministic
  checks allow it. It also needs a public endpoint and an App's private key to
  operate.

### Confirmation

Each gate has a test that asserts the denial, in the review service's own suite:

- a pull request from outside the organization, without a maintainer's
  approval of its head commit, starts no round;
- an approval of an earlier commit counts for nothing once the author pushes;
- an approval by the author, or by someone outside the organization, does not
  count;
- a status written by any other account is ignored;
- a pull request that changes a build, test or CI file, or `CLAUDE.md`, never
  reaches a reviewer;
- the pre-check also stops at a diff over 8,000 lines or 2 MiB, or at a line
  over 1,500 characters;
- the proxy refuses a review with any event but `COMMENT`, and any GraphQL body
  but the two documents. A test against a running proxy covers this, with near
  misses: a nested or escaped `event`, a form body, a query string.

### Consequences

- Good: an outsider cannot make a model run, and cannot make the review
  approve, merge or change anything but its own comments and status.
- Good: a maintainer's approval of a commit is the act that admits an outside
  change to the AI review. It is the same act GitHub already requires before
  a merge.
- Bad: a round starts up to a minute after the event that allows it, plus the
  30-minute quiet period for members.
- Bad: the credential belongs to a maintainer's account today. The review
  therefore tells its own threads, replies and summary apart by marker, not by
  author. A dedicated account or a GitHub App would remove that coupling.
- Bad: the pre-check is a filter, not a boundary. Its model reads untrusted
  input itself, which is why the checks that matter most need no model.
- Neutral: a finding whose line is outside the diff goes into the review body
  instead of a thread, since GitHub refuses a review comment there.
