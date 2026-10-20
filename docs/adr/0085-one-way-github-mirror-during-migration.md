---
status: proposed
date: 2026-10-14
deciders: Lukáš Vlk
consulted: ADR-0084
---

# ADR-0085: A one-way GitHub mirror from a single initial commit during the migration

## Context and Problem Statement

Homer moves from a private upstream repository to a public GitHub repository
(ADR-0084). Development stays upstream for a while. Meanwhile GitHub must hold
the same code, so its CI can be tuned before the switch. The upstream history
names people, internal tickets, hosts and projects throughout. The agreement is
to publish none of it: the public history starts from one initial commit. The
problem is how GitHub keeps following the upstream after that commit without leaking
what the history held, and without manual steps per merge.

## Decision Drivers

- No upstream history and no internal string reaches GitHub. A leak must stop the
  sync rather than be reported after it.
- The upstream stays the only source until the switch. Nobody develops on GitHub yet.
- Every merge to `main` reaches GitHub without a manual step.
- Nothing is force-pushed. GitHub history is append-only. *Amended 2026-10-16:
  only once the repository is public; see the amendment below.*
- The credential that writes to GitHub reaches one repository and protected
  refs only.

## Considered Options

- **A. Rewrite the upstream history with `git filter-repo`** and keep GitHub in
  step by re-running the same deterministic filter on every merge.
- **B. The upstream forge's push mirroring** of the repository to GitHub.
- **C. A sync run from a maintainer's machine.**
- **D. A CI job that copies the merged tree onto GitHub `main`**, one commit
  per merge, on top of a single initial commit.

## Decision Outcome

Chosen option: **"D. A CI job"**.

- **Job:** `github-sync` runs on protected `main` after the test and e2e stages,
  one run at a time (`resource_group`).
- **Copy:** `tools/github-sync/sync.sh` clones GitHub `main`, replaces its whole
  tree with the merged one (deletions included), and leaves out the upstream-only
  files (the upstream pipeline definition and the sync tooling).
- **Commit:** one commit per merge, titled after the merged merge request (or a
  direct push's subject) with one leading ticket key (`KEY-123:`) dropped: the
  key is an internal id, and factory merge requests always lead with one. The
  rest of the title is still checked against the denylist. *(Amended 2026-10-16: in `append` mode only; see the amendment below.)* Its
  identity is the maintainer's GitHub noreply address. Nothing else of the merge
  commit leaves, because it names the internal project. The script pushes
  without force. *(Amended 2026-10-16: in `append` mode only; see the amendment below.)*
- **Initial commit:** the maintainer made it by hand from the same tree.

**Trust boundary.** The job holds a deploy key that can write to that one GitHub
repository. It lives in a protected, masked upstream CI/CD variable, so only
protected refs read it. GitHub's SSH host keys are pinned. Before every push, a protected
denylist of extended regexps is checked against everything the push publishes:
file contents (binary files included), path names, symlink targets and the
commit title *(Amended 2026-10-16: in `append` mode only; see the amendment below.)*. gitleaks then scans the tree for secrets, with only the docs' and
tests' exact placeholder values allowed, because GitHub's own secret scanning is
not available to a private repository on the organization's plan. The script
has no switch that turns the scan off or swaps its rules. It finds the gitleaks
binary on `PATH` inside the pinned job image, so a protected variable that
overrides `PATH` could swap the binary; setting one already takes maintainer
rights, which also reach the deploy key, so that risk is accepted. The job fails
closed when the denylist is missing, empty or invalid, or when the scan itself
fails.
`tools/github-sync/sync-test.sh` proves those denial paths in the
`github-sync-tests` job. It also proves that a commit landing on GitHub while a
sync runs makes the push fail instead of being overwritten.

**Why not the others:**
- **A** must find every internal reference in years of history, and it would
  publish the history the agreement keeps private.
- **B** pushes full history and every branch with no filter.
- **C** depends on one person's machine and has no gate the merge must pass.

### Consequences

- Good: a leak in the tree, or in a title *(Amended 2026-10-16: in `append` mode only; see the amendment below.)*, stops the push. Only what the denylist
  misses can leave, and the denylist is checked on every sync.
- Neutral: a leading ticket key is dropped rather than refused, so a factory
  merge does not stop the sync for its title's prefix alone; the key never
  reaches GitHub. Narrowing the denylist's ticket pattern instead was rejected,
  because it would also let a key through anywhere else.
- Good: no history is published, and GitHub history is never rewritten. *(Amended 2026-10-16: in `append` mode only; see the amendment below.)*
- Good: the job's image is pinned by digest, so a retargeted tag or a compromised publisher
  cannot run its own code in the job that holds the deploy key. That pin is what makes the
  "pinned job image" of the trust boundary true, and it keeps the accepted `PATH`-override risk
  a maintainer-only one — a tag is mutable without any repository rights. The same pin covers
  every other image the pipeline pulls, and `renovate.json` keeps the digests current. CWE-494; `tools/github-sync/ci-parity-test.sh` fails when a pin is dropped.
  Accepted risk: a renovate digest bump is itself merged as a trusted update, so the pin closes
  tag retargeting only between bumps — publisher compromise is caught only by review of those
  bump merge requests, signature verification of the pulled images being the stronger control
  that is not set up here.
- Bad: GitHub gets one commit per merged merge request, not its individual
  commits. *(Amended 2026-10-16: in `append` mode only; see the amendment below.)* A merge whose pipeline fails reaches GitHub only with the next green
  one, under that one's title. *(Amended 2026-10-16: in `append` mode only; see the amendment below.)*
- Bad: a commit that reached GitHub `main` another way is not refused. The next
  sync fast-forwards over it: the commit stays in history and its tree is
  replaced. *(Amended 2026-10-16: in `append` mode only; see the amendment below.)* A ruleset on GitHub `main` that lets only the deploy key push is
  what keeps such commits out, and the organization's plan offers rulesets
  only once the repository is public; until then, write access to the GitHub
  repository is the control.
- Bad: the gitleaks allowlist (`tools/github-sync/gitleaks.toml`) lives in the
  tree it scans, so a merge request could add a secret and widen the allowlist
  together. Accepted: the secret scan guards against accidents, and review of
  the merge request is the control against intent. The denylist, which guards
  internal names, is kept out of the tree in a protected variable for that
  reason.
- Bad: the denylist is maintained by hand in a protected variable. A new kind of
  internal string is caught only once someone adds it.
- Bad: both pipelines run the same checks and are edited by hand.
  `tools/github-sync/ci-parity-test.sh` fails the `github-sync-tests` job when
  their gradle, quality-script or npm commands differ. It compares the upstream
  pipeline against all GitHub workflows together, not `ci.yml` alone, because the
  upstream scheduled checks live in the upstream pipeline definition while GitHub
  keeps them in `nightly.yml`; a check moving between GitHub workflows is caught by review.
- Neutral: when development moves to GitHub, the job, the scripts and the
  deploy key are removed.

## Amendment 2026-10-16: one commit until the repository is public

Security asked that the repository name no third-party security tool, and
removing the mentions from the tree leaves them in every earlier synced commit.
While the GitHub repository is private, nobody outside the organization has
cloned it and no fork exists, so its history can still be replaced.

- `sync.sh` gains `GITHUB_SYNC_HISTORY`. `append`, the default, keeps the
  behaviour above. With `squash`, every sync replaces GitHub `main` with one
  parentless commit, "Initial commit", of the scanned tree. Whatever an
  earlier tree held, a string removed since included, leaves the branch's
  history with the next sync. A sync whose tree already matches a single-commit
  branch pushes nothing. A branch with history is squashed even when its tree
  matches, which is the case right after the mode is switched on, because the
  sync tooling itself never reaches GitHub.
- The push uses `--force-with-lease` on the head the run cloned. A commit that
  reaches GitHub `main` while a sync runs still makes the push fail instead of
  being overwritten, as in `append` mode; a commit that reached it before the
  sync is replaced. `sync-test.sh` proves both, and every denial path still
  stops the push.
- The commit title is not scanned in `squash` mode, because it is never
  published: the commit is always "Initial commit".
- The `github-sync` job sets `squash`. Before the repository goes public, the
  job is switched back to `append`, so the public history starts from the last
  squashed commit and is append-only from then on, as the decision drivers ask.
  GitHub keeps commits it no longer reaches until its own garbage collection
  runs, so a replaced commit can still be fetched by its hash for a while.
  Accepted by the maintainer: nothing in those commits is secret, and the
  hashes are not published anywhere.
- Bad: until then, GitHub `main` has no history to read: one commit per merge
  becomes one commit in all. The full history stays upstream.
