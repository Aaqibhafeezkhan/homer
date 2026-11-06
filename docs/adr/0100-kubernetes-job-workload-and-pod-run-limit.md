---
status: proposed
date: 2026-11-06
deciders: Lukáš Vlk
consulted: ADR-0043, ADR-0047
---

# ADR-0100: The Kubernetes runner can run a session in a one-pod Job, and its run limit is on the pod

## Context and Problem Statement

The `kubernetes` runner creates one bare pod per session, runs every command in it with `exec`, and
deletes it at close (ADR-0043). Two needs do not fit that shape. An unattended LLM agent run that never
ends costs money and holds a runner slot, and only per-command timeouts bound it today; a limit for
the whole run must hold even when Homer is stuck or down, so Kubernetes has to enforce it. And
operators want the standard batch object: under a namespace `ResourceQuota` a bare pod create fails at
once with 403 where the Job controller retries it, a Job keeps its end state for `kubectl get jobs`
and kube-state-metrics, and `ttlSecondsAfterFinished` removes a finished run without Homer. Other
teams use the runner as it is, so neither may change a config that does not ask for it (issue #122).

## Decision Drivers

- No breaking change: a config without the new keys keeps the same pod spec, labels, names, cleanup,
  sweep, API calls and RBAC needs.
- The limit must be enforced by the cluster, not by a Homer timer.
- The limit must mean the same in both modes and must not double-count what `podStartTimeoutSeconds`
  already bounds.
- The execution model (one long-running container, commands over `exec`, tar-over-exec sync) stays.

## Considered Options

Where the limit lives:

- **Pod `spec.activeDeadlineSeconds`** (on the pod template in Job mode).
- **Job `spec.activeDeadlineSeconds`.**

What a session runs in:

- **A bare pod by default, a Job opt-in** (`workload: Pod | Job`).
- **Always a Job.**

How Homer finds a Job's pod:

- **By the selector the Job controller put on the created Job** (`spec.selector.matchLabels`, else
  `batch.kubernetes.io/controller-uid=<uid>`).
- **By a predictable pod name.**

## Decision Outcome

Chosen: **the limit on the pod**, **a Job opt-in**, and **the pod found by the Job's selector**.

- `maxRunSeconds` sets the pod's `activeDeadlineSeconds`. The kubelet counts it from pod start and
  stops the pod at the limit in either mode. A Job deadline would also count the time the pod waits
  for quota or scheduling, which `podStartTimeoutSeconds` already bounds, so the same value would mean
  different run times depending on cluster load.
- `workload: Job` is opt-in, because always using a Job would change every existing deployment's API
  calls and RBAC needs. The Job wraps the unchanged pod spec with `parallelism: 1`, `completions: 1`,
  `backoffLimit: 0` (a retry would start an empty pod in the middle of a run, with none of the
  synced workspace) and `ttlSecondsAfterFinished` (`jobTtlSecondsAfterFinished`, default 3600). The
  Job and its template carry the pod's Homer labels and annotations.
- The Job controller names the pod, so Homer lists pods with the created Job's own selector, which
  carries the controller-uid label of the cluster's version (`batch.kubernetes.io/controller-uid` on
  1.27+, `controller-uid` before), falling back to `batch.kubernetes.io/controller-uid=<uid>` when
  the create response has none. The uid is unique per Job; a name prefix is not, and a pod someone
  else creates with a matching name could be taken for the run's. Waiting for the pod to exist and
  waiting for it to be ready share one `podStartTimeoutSeconds` budget, and the run's `runner.podName`
  stays `null` until the pod exists, with `runner.jobName` naming the Job.
- `BatchV1Api` is built only for a Job-mode runner, so a Pod-mode runner makes no `batch` call at all.

### Consequences

- Good: a run's wall-clock limit holds while Homer is down; `maxRunSeconds` works without Jobs.
- Good: a Pod-mode config is untouched: same pod spec, calls and RBAC; a test proves no batch call.
- Good: Job mode gets the Job controller's quota retry, end state and TTL cleanup.
- Bad: Homer's Role gains a `batch` `jobs` rule (`get`, `list`, `watch`, `create`, `delete`,
  `patch`) when, and only when, a runner uses Job mode.
- Bad: two lookups instead of one name: a pod list by selector before the readiness wait.
- Neutral: `!<runnerCmd>` steps open their own session, so in Job mode each gets its own Job and
  its own limit.

### Security (root CLAUDE.md §12)

- No new principal: the Job's pod runs under the same `serviceAccountName`, image, security context
  and env delivery as today's pod; the Job adds no credential and no new path for agent data.
- Homer's own ServiceAccount is widened by the `batch` `jobs` rule only in a namespace a Job-mode
  runner uses; the rule covers Jobs, not pods it could not already create.
- Leaked Jobs are bounded by four mechanisms, none of them alone:
  - Homer deletes the Job on every Job-mode startup failure and at session close.
  - The boot-time startup sweep deletes this instance's orphan Jobs.
  - `maxRunSeconds`, when set, ends a running pod, and with it the Job, even while Homer is down.
  - `ttlSecondsAfterFinished` removes a *finished* Job and its pod while Homer is down. Its clock
    starts only when the Job finishes, so it bounds nothing for a Job whose pod is still running or
    was never created (the controller keeps retrying a quota-blocked create, and the pod may start
    later).

  Without `maxRunSeconds`, a Job whose Homer died is bounded only by the next boot sweep, the same as
  a bare pod today.
- The pod is identified by a selector Homer reads from its own create response, never by a name an
  agent or another tenant could choose.

## More Information

- [Issue #122](https://github.com/livesport/homer/issues/122) and its
  [full proposal](https://github.com/livesport/homer/issues/122#issuecomment-6658217003).
- [ADR-0043](0043-k8s-sync-workspaces.md) — the pod-per-session model this builds on.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — the threat model.
- Operator docs: `app/SERVER.md` § Job workload and § RBAC.
