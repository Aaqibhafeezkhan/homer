# macOS build hosts for mobile builds

A team that builds a mobile app runs Homer on Kubernetes. Its agents build
and test the Android app in Linux containers. The iOS app is another matter:
Xcode, the simulators and code signing exist only on macOS, and macOS is
licensed only on Apple hardware, so no container image can build it. Homer
had to run those stages on a Mac.

The runner shape changed twice in about a month. It started as an `ssh`
runner reached through a reverse tunnel. Its workspace teardown then turned
out to hold the host for up to 18 minutes after each run. It ended as
pull-mode runners on macOS VMs that register themselves. This case study
follows that path and what each step needed from Homer.

## The setup

### Step 1: an `ssh` runner through a reverse tunnel

Homer's [`ssh` runner](../../app/SERVER.md#ssh-runner) always dials out from
Homer. The cluster had no route to the Mac's network, but the Mac could reach
the cluster's HTTPS ingress. So the Mac kept a reverse tunnel open over that
ingress, and the runner pointed at the tunnel's end inside the cluster:

```yaml
# Homer config (excerpt)
runners:
  macos-ssh: !<ssh>
    host: mac-tunnel.homer.svc.cluster.local   # the tunnel's cluster-side end, not the Mac
    port: 2222
    user: runner
    auth: !<key>
      privateKeyPath: /etc/homer/ssh/id_ed25519
    knownHostsPath: /etc/homer/ssh/known_hosts # the Mac's own key, also under [mac-tunnel…]:2222
    remoteWorkspaceRoot: /Users/runner/homer-workspaces
    homerApiUrl: https://homer.example.com     # how homer-ask and dispatch reach Homer from the Mac
    extraUploads:
      - { local: /app/scripts, remote: .homer/scripts, env: SCRIPTS_DIR }
      - { local: /app/agents/shared, remote: .homer/shared, env: SHARED_DIR }
```

The tunnel only carries bytes, so the host key Homer checks is still the
Mac's. With `strictHostKeyChecking` on (the default), `known_hosts` must list
that key under the name and port Homer actually dials.

Homer installs nothing on an `ssh` host
([Remote runner requirements](../../app/SERVER.md#remote-runner-requirements)).
The Mac was provisioned with Xcode, a JDK, bash 5, `git`, `claude`, `curl`
and `setsid`, the last so that Homer can kill the remote process group.
Three things in the environment still bit:

- **Linux values reached the Mac.** `globalEnv` held a `PATH`, a `JAVA_HOME`
  and a `CI` flag meant for the Linux containers. Homer sends the run's env
  with every remote command, so a `PATH` from `globalEnv` replaces the login
  shell's and hides `/opt/homebrew/bin`. Before Homer 1.24.0 the backend's
  `HOME=/home/homer` was forwarded too, and on macOS `/home` is an autofs
  mount nothing can be created under. Since 1.24.0 remote runners no longer
  receive `parentEnvAllowlist` keys, and a per-runner `env` overrides the
  rest ([Remote runners](../../app/SERVER.md#remote-runners)).
- **Only the agent's own directory is uploaded.** It arrives as
  `.homer/agent`, so `{{HMR_AGENT_DIR}}/../shared/…`, which works on the
  local runner, points at nothing on the Mac. The shared scripts and the
  shared Claude settings travel through `extraUploads`, with `env` repointed
  at the remote copy
  ([ADR-0042](../adr/0042-runner-extra-uploads.md)).
- **No drain.** An `ssh` runner has no drain switch. Host maintenance waited
  until no SSH session but its own was open, and could not stop Homer from
  starting a new run meanwhile.

### Step 2: the teardown that held the host

Every Mac agent set `cleanAfterFinish: true`. On an `ssh` runner, Homer up to
1.24 removed the remote project root over SFTP, one round trip per file,
serialized on the session and here through the tunnel. An iOS checkout after
dependency resolution is tens of thousands of files. The run stayed
`WORKING` and kept its lock until the sweep ended, 14 to 18 minutes after its
last command. A second build that shared the lock waited 650 s for the first
one's sweep before its first command ran. On one evening, two tickets spent
58 minutes of a 2 h 52 min run deleting files one SFTP message at a time.

Homer 1.25.0 fixed this
([ADR-0067](../adr/0067-ssh-cleanup-via-rm-rf.md)): the `ssh` runner now
removes the project root with one `rm -rf` over the exec channel and falls
back to the SFTP walk only when that fails. Config load also rejects a
`remoteWorkspaceRoot` that is not absolute and normalized, or is `/`.

The team's workaround on the older version still shaped what came next. The
last step of each Mac agent removed the checkout itself in an `EXIT` trap.
Later the agents stopped cloning into the workspace at all and kept one
checkout per host at a fixed path outside `remoteWorkspaceRoot`. Xcode keys
its build cache on the project's absolute path, and a workspace path with the
process id in it is new every run, so no build had ever found a previous
one's cache.

### Step 3: pull-mode runners on immutable VMs

Homer 1.26.0 added `!<external>` runner domains
([ADR-0066](../adr/0066-external-pull-runners-and-unified-claim-queue.md)).
The basics, one trust domain and instances that announce their own tags and
concurrency, are in [Runner pools](runner-pools.md). For the Mac they removed
the tunnel and the SSH key: the Mac runs `homer-runner` itself and connects
out to Homer over HTTPS, and another Mac is another instance with the same
token, with no Homer restart.

```yaml
# Homer config (excerpt)
runners:
  macos: !<external>
    token: "@SYSENV:HOMER_RUNNER_MACOS_TOKEN"
    homerApiUrl: https://homer.example.com   # HMR_API_URL for runs on any instance
    allowedTags: [macos, ios]
    env:                                     # host facts; above agent env for tag-filtered runs
      HOME: /Users/runner
      CI: ""                                 # globalEnv's value is for the Linux containers
    extraUploads:                            # every instance gets these: no secrets
      - { local: /app/scripts, remote: .homer/scripts, env: SCRIPTS_DIR }
      - { local: /app/agents/shared, remote: .homer/shared, env: SHARED_DIR }
```

```yaml
# agents/ios-build/agent.yaml (excerpt)
runnerTags: [ios]
runnerClaimTimeoutSec: 900
maxParallelRuns: 1
lockStrategy:
  key: macos-build-host       # the same constant in every agent that builds on the Mac
  onCollision: WAIT
```

The hosts are macOS VMs (on [Tart](https://tart.run)) cloned from one
immutable image. The image carries Xcode, the toolchain and the
`homer-runner` jar from the `homer-runner` image. At boot a clone reads its
domain token and name from a read-only shared directory and starts the runner
as a daemon:

```bash
HOMER_RUNNER_TOKEN="$(cat /path/to/shared/token)" \
  java -jar homer-runner.jar --url https://homer.example.com \
    --tags macos,ios --concurrency 1 --root /Users/runner/homer-runner \
    --name "$(hostname -s)"
```

A new Xcode, macOS or Homer version is a new image, and VMs are replaced,
not updated in place. A clone shows up on the console's `/runners` page once
it has registered. macOS allows at most two macOS VMs on one Mac.

## Details that matter

- **The lock comes before the claim.** Homer takes a run's lock before the
  run enters the [claim queue](../../app/SERVER.md#runner-claim-queue), so a
  second build waits on the lock, not on the claim TTL. Lock keys are global
  across agents, so one constant key makes every Mac agent exclude the
  others. `runnerClaimTimeoutSec` then covers only the wait for a free
  instance: a lock-exempt smoke run holding the one slot, or `homer-runner`
  restarting. The 300 s default was too short for that.
- **Domain `env` wins over agent `env`.** For a run that picked its runner by
  `runnerTags`, the serving runner's `env` is merged above the agent's when
  the run is claimed. Put only host facts there, values no agent should
  override.
- **Domain `extraUploads` go to every instance.** They are uploaded in full
  (a domain has no `syncExcludes`) into whichever instance claims the run, so
  the folders must hold no secrets
  ([ExternalRunner fields](../../app/SERVER.md#externalrunner-fields)).
- **The runner protocol needs its own proxy settings.** An instance posts
  workspace and artifact tars up to `maxTransferBytes` (1 GiB by default).
  nginx's default 1 MB body limit answers `413` to all but the smallest. The
  team routes `/api/v1/runner/` through a separate ingress with a body limit
  above `maxTransferBytes`, request buffering off, and read and send
  timeouts of 600 s, above the 30 s claim long-poll and the 300 s
  `transferTimeoutSec`.
- **Build caches need a fixed path.** `homer-runner` gives each session a
  fresh `<processId>-<random id>` directory under `--root` and deletes it
  when the session ends. A cache keyed on the absolute path never hits
  there. The shared checkout lives outside `--root`, and the host-wide lock
  is what makes sharing it safe.
- **`globalEnv` reaches the Mac, and artifacts are not redacted.** The app's
  build tool printed its whole environment into a log the run kept as an
  artifact. Every token in `globalEnv` ended up there in plain text, kept
  until the workspace was purged. Homer redacts `@SYSENV:` values in
  telemetry, not in files a step writes. The fix was a wrapper that drops
  every variable named like a credential before calling the build tool. Not
  having the tokens in the run's environment at all is better, see
  [Runs that hold no credentials](credential-proxy.md).
- **Stop the runner before the VM.** An instance that stops polling is
  dropped after `heartbeatTimeoutSec`, and its runs fail. On `SIGTERM`,
  `homer-runner` deregisters and lets in-flight runs finish for up to
  `HOMER_RUNNER_DRAIN_TIMEOUT` seconds. Send it first, then stop the VM.

## What it does not solve

- **The domain token is within the runs' reach on macOS.** On Linux,
  `homer-runner` makes its JVM non-dumpable so a run cannot read the token
  from it ([ADR-0078](../adr/0078-runner-jvm-is-non-dumpable.md)). macOS has
  no `prctl`, so the runner logs a warning and goes on. Do not count on the
  token being out of a run's reach there, and keep the domain for work you
  trust.
- **Tags are routing, not isolation.** An instance may announce a tag it
  cannot serve. The run then fails inside a domain you already trust.
- **One Mac is a queue.** With one instance at a time per VM and a host-wide
  lock, concurrent tickets wait for each other.
- **A shared checkout can carry bad state.** A refresh resets tracked files
  and removes untracked ones, but ignored build output stays. Deleting the
  checkout between runs is always safe; the next run clones again.
- **The `ssh` path stays as a fallback,** with its tunnel and without a drain.
