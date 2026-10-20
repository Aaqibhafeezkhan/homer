# Runner pools on Kubernetes: performance and light

Two kinds of run share one Homer. A *heavy* run builds and tests a JVM
project and needs about 6 GiB at its peak. A *light* run is a short script or
a Claude session that reads a ticket or reviews a diff, at about 0.5 GiB. A
single pool of runners has to be sized for the worst case, so it either
wastes memory on the light runs or runs out of it when two builds land on the
same pod.

This case study splits the work into two pools of
[`homer-runner`](../../DOCKER.md#homer-runner-image) instances on Kubernetes.
The **performance** pool runs at most 2 runs at a time. The **light** pool
runs at most 8.

## The setup

```
Homer ── one !<external> domain "workers" ── claim queue
           │                                    │
           ├─ runner-performance pod  (tag performance, 2 slots, 14 GiB)
           └─ runner-light pod        (tag light,       8 slots,  5 GiB)
```

- **One trust domain, two pools.** Both pools register with the same
  `!<external>` domain, with the same token and image. What tells them apart
  is the tags and concurrency each instance announces at registration
  ([`app/SERVER.md`](../../app/SERVER.md#external-runners-pull-mode)). Adding a
  pool needs no Homer restart.
- **Agents ask for a pool by tag.** A build agent sets
  `runnerTags: [performance]`, everything else `runnerTags: [light]`. Homer
  queues each run until an instance with that tag has a free slot
  ([Runner claim queue](../../app/SERVER.md#runner-claim-queue)).
- **The slot count is the cap.** An instance never takes more runs than
  `HOMER_RUNNER_CONCURRENCY`, so the performance pool's 2 slots are a hard
  limit on concurrent builds, however many build agents fire.

```yaml
# Homer config (excerpt)
server:
  runnerClaimTimeoutSec: 1800      # a run waits up to 30 min for a free slot

runners:
  workers: !<external>
    token: "@SYSENV:HOMER_RUNNER_TOKEN"
    homerApiUrl: https://homer.example.com
    allowedTags: [performance, light]   # a typo guard, not a security boundary
    maxInstances: 4                     # both pools plus re-registration during a rollout
```

```yaml
# agents/build-and-test/agent.yaml (excerpt)
runnerTags: [performance]
```

Do not leave an agent on `runner: workers`. The alias matches **any**
instance in the domain, so a light run could land on the performance pod and
take a build slot. A check in the agent repository's CI rejects it.

The performance pool's Deployment; the light pool's differs in the name, the
tag, the concurrency and the resources:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: runner-performance
spec:
  replicas: 1
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxSurge: 0, maxUnavailable: 1 }
  selector:
    matchLabels: { app: runner-performance }
  template:
    metadata:
      labels: { app: runner-performance }
    spec:
      # Longer than HOMER_RUNNER_DRAIN_TIMEOUT: on SIGTERM the instance deregisters,
      # lets its runs finish for that long, then stops them.
      terminationGracePeriodSeconds: 660
      automountServiceAccountToken: false
      securityContext: { runAsUser: 1000, runAsGroup: 1000, runAsNonRoot: true, fsGroup: 1000 }
      containers:
        - name: homer-runner
          image: registry.example.com/homer-runner-with-tools:1.34.0   # derived from homer-runner
          env:
            - { name: HOMER_RUNNER_URL, value: https://homer.example.com }
            - name: HOMER_RUNNER_TOKEN
              valueFrom: { secretKeyRef: { name: homer-runner, key: token } }
            - { name: HOMER_RUNNER_TAGS, value: performance }
            - { name: HOMER_RUNNER_CONCURRENCY, value: "2" }
            - name: HOMER_RUNNER_NAME
              valueFrom: { fieldRef: { fieldPath: metadata.name } }
            - { name: HOMER_RUNNER_DRAIN_TIMEOUT, value: "600" }
            - { name: JAVA_OPTS, value: "-Xmx256m" }
          resources:
            requests: { cpu: 300m, memory: 256Mi }
            limits:   { cpu: "4", memory: 14Gi }
          volumeMounts:
            - { name: sessions, mountPath: /var/homer-runner }
      volumes:
        - name: sessions
          emptyDir: { sizeLimit: 40Gi }
```

| Pool | Tag | Slots | Memory limit | Sized as |
| --- | --- | --- | --- | --- |
| performance | `performance` | 2 | 14 GiB | 2 builds × ~6 GiB, plus the runner JVM and headroom |
| light | `light` | 8 | 5 GiB | 8 runs × ~0.5 GiB, plus the runner JVM and headroom |

## Details that matter

- **Size a pool for its slots, not its average.** On cgroup v2, Kubernetes
  kills every process in a container that runs out of memory, not just the
  largest one. One build that overshoots ends every run on that pod and the
  runner with them. Size each pool for all of its slots busy at their peak,
  then read the real `memory.peak` of the container after a few days and
  tune from it.
- **No build process may outlive its run.** A Gradle or Kotlin compile daemon
  left behind keeps gigabytes of memory on a pod with no run on it. The build
  agents write `org.gradle.daemon=false` and
  `kotlin.compiler.execution.strategy=in-process` into the run's Gradle user
  home before the first build, so every build process exits with its run.
- **Rollouts delay work instead of failing it.** `maxSurge: 0` keeps a
  rollout inside a tight namespace quota. While the performance pod restarts,
  build runs wait in the claim queue (up to `runnerClaimTimeoutSec`) and
  start on the new pod. Light work keeps two pods (`replicas: 2`) if pickers
  and reviews must survive a rollout. Each of them then has 4 slots, still
  8 in total.
- **Long builds and rollouts.** A runner drains for
  `HOMER_RUNNER_DRAIN_TIMEOUT` seconds and then stops what is still running.
  Roll the performance pool when no long build is in flight.
- **Watch the pools.** `GET /api/v1/runners` lists each instance with its
  tags and slots in use, `GET /api/v1/runners/queue` the runs waiting for a
  slot and the oldest wait. The console's `/runners` page shows both. A queue
  that keeps growing for one tag means that pool needs more slots or more
  pods.

## What it does not solve

- **Tags are routing, not isolation.** Both pools share one trust domain and
  its token. An instance could announce a tag it cannot serve, and the cost
  is a failed run inside a domain you already trust
  ([ADR-0066](../adr/0066-external-pull-runners-and-unified-claim-queue.md)).
  Work that needs a separate trust boundary needs a separate domain.
- **One performance pod pauses all builds while it restarts.** A second pod
  removes that pause but doubles the memory the pool reserves.
- **Memory per run is an estimate.** A run heavier than expected on a full
  light pod can still take the pod down. Give the light pool memory back
  from the performance pool if its `oom_kill` count grows.
