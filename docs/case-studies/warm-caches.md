# Warm caches for short runs

A team that builds a mobile app runs its ticket pipeline on Homer: a
LangGraph orchestrator dispatches one worker agent per stage, and most stages
start by checking out the app repository. One small ticket took 1 h 58 min
from start to merge request. Reading the stage timings from
`GET /api/v1/status/all` showed where a large part of it went:

| Where | Time | Why |
| --- | --- | --- |
| Setup of five stages | ~11 min | five clones of a ~2.5 GB repository, minutes apart, at the same tip |
| One stage's code-index sync | 73 s | the cached index was 3,053 files behind the default branch |
| The build tool in the dev stage | 8.5 min | a cold build-tool home: distribution and dependencies downloaded again |
| Large-file pull in the dev stage | part of a 4 min setup | every Git LFS object fetched again |

All of it was state that a persistent volume could have kept. The build
tool's home was on the container filesystem and was lost with every pod
restart. Every stage cloned from the remote. The code index was saved back by
whichever stage ran last, on whatever branch that stage was on, so the next
ticket inherited a stale one.

## The setup

```
workspace volume (PVC, mounted at /app/workspace in the Homer pod)
├─ runs/          workspaceRootPath: one directory per run, purged by Homer
└─ cache/         never touched by Homer
   ├─ gradle/     GRADLE_USER_HOME
   ├─ git/        GIT_MIRROR_ROOT: one bare --mirror clone per repository
   └─ lfs/        GIT_LFS_CACHE_ROOT: shared lfs.storage
```

- **Cache roots in `globalEnv`.** The Linux stages run on the built-in local
  runner, in the Homer pod that mounts the volume. Three variables point
  their tools at directories on it:

```yaml
# Homer config (excerpt)
workspaceRootPath: /app/workspace/runs

globalEnv:
  SCRIPTS_DIR: /app/scripts
  GRADLE_USER_HOME: /app/workspace/cache/gradle
  GIT_MIRROR_ROOT: /app/workspace/cache/git
  GIT_LFS_CACHE_ROOT: /app/workspace/cache/lfs
```

- **Runs clone from the mirror.** The shared clone helper borrows objects from
  the mirror when one exists and fetches only the difference from the remote.
  Without a mirror it clones as before:

```bash
# scripts/git.sh (excerpt)
mirror_args() {   # prints clone flags, or nothing when there is no usable mirror
  local m="$GIT_MIRROR_ROOT/$(repo_slug "$1").git"
  [ -n "${GIT_MIRROR_ROOT:-}" ] && [ -f "$m/HEAD" ] && [ -d "$m/objects" ] \
    && printf '%s\n' --reference-if-able "$m"
  return 0
}

mapfile -t MIRROR < <(mirror_args "$PROJECT")
git clone --depth 50 "${MIRROR[@]}" "$URL" "$HMR_WORK_DIR/repo"
cd "$HMR_WORK_DIR/repo"
[ -n "${GIT_LFS_CACHE_ROOT:-}" ] && git config --local lfs.storage "$GIT_LFS_CACHE_ROOT"
```

- **A cron agent keeps the caches warm.** Every 20 minutes it refreshes the
  mirrors, clones the default branch from the mirror, rebuilds the code index
  on it and prefetches that head's LFS objects. It is a script, with no LLM
  step:

```yaml
# agents/warm-cache/agent.yaml (excerpt)
cron:
  expression: "6,26,46 * * * *"   # off :00, and off the other crons' minutes

lockStrategy:
  key: "warm-cache"
  onCollision: SKIP_CURRENT       # a run still fetching has nothing newer to fetch

maxParallelRuns: 1

workspace:
  path: "{{HMR_PROCESS_ID}}"      # only the throwaway checkout lives here
  cleanAfterFinish: true
  purgeAfter: 6h                  # 72 runs a day; the records go quickly

commands:
  - !<script>
    shell: bash
    timeout: 1800                 # the first run clones full history
    content: |
      set -euo pipefail
      source "$SCRIPTS_DIR/git.sh"
      [ -n "${GIT_MIRROR_ROOT:-}" ] || { echo '{"skipped":true}'; exit 0; }
      update_git_mirror "$APP_PROJECT"     # the only failure that fails the run
      update_git_mirror "$WEB_PROJECT" || echo "web mirror failed" >&2
      cd "$HMR_WORK_DIR" && clone_repo     # from the mirror: local disk I/O
      CODE_INDEX_SAVE=true prepare_code_index
      prefetch_lfs_objects HEAD
```

`update_git_mirror` runs `git remote update --prune` in an existing mirror and
`git clone --mirror` only when the directory has no `HEAD`.

## Details that matter

- **Keep caches out of Homer's workspaces.** Homer deletes a run's `workdir/`
  when it finishes (`cleanAfterFinish`) and the whole project directory at
  `purgeAfter` ([Workspace Cleanup](../../app/AGENT.md#workspace-cleanup)).
  A fixed `workspace.path` shared by every run would survive one run, but it
  is still purged once the last record that references it goes. A directory
  outside `workspaceRootPath`, named in `globalEnv`, has no Homer lifecycle at
  all.
- **One writer per cache.** The cron agent is the only run that writes the
  mirrors and the only one that saves the code index. Stages restore the
  index and sync it against their own branch, but never save it back. That is
  what fixed the stale index: it no longer depends on which stage ran last.
- **Shared caches still need safe concurrent use.** Several stages read the
  caches at once (two dev runs, three verifier runs). The build tool locks
  entries in its own home, and Git LFS writes through temp files and renames,
  so both are safe to share. The mirror is read-only for clones. Check this
  for any tool before giving it a shared directory.
- **A mirror must outlive every clone that borrows from it.** A clone made
  with `--reference` reads objects from the mirror through
  `objects/info/alternates`. Deleting or re-creating a mirror while a run is
  in flight breaks that run's checkout, so the agent only ever fetches into an
  existing one.
- **Stagger the cron and let the lock absorb overlap.** Cron ticks fire even
  while the previous run is still going
  ([Cron Scheduling](../../app/AGENT.md#cron-scheduling)). The first run, a
  full-history mirror, can take longer than 20 minutes; the next tick hits the
  `SKIP_CURRENT` lock and is recorded as a `FAILED` run that never started.
  Minutes `6,26,46` keep it off `:00` and off the minutes of the other cron
  agents, so they do not compete for the same runner at the same time.
- **`globalEnv` reaches every runner.** On a remote host that does not mount
  the volume, the paths point at nothing. The helpers check that the mirror
  exists, and the macOS build host overrides all three: the build tool keeps
  a home in the host user's directory, the mirror root is empty and the LFS
  store is a local directory. The team sets these in the runner's `env`, the
  per-host override point, and repeats them in the macOS agents' `env`.
- **Know which `env` wins.** For a run addressed with `runner:`, the runner's
  `env` sits between `globalEnv` and the agent's `env`
  ([Environment Variable Precedence](../../app/AGENT.md#environment-variable-precedence)).
  For a run addressed with `runnerTags:`, the serving runner's `env` is merged
  after the session opens, above the agent's `env`
  ([Runner claim queue](../../app/SERVER.md#runner-claim-queue)). Put only
  values no agent should override into a pull-mode runner's `env`.
- **Fire it once after a restart.** Cron state resets with the server, and a
  fresh pod waits for the next tick. `POST /api/v1/agents/warm-cache/cron-run`
  (admin) runs it at once with cron semantics; `POST /api/v1/agent/warm-cache`
  works for a principal with `run` on the agent.
- **Setting the roots changes nothing on its own.** Until the first warm-cache
  run has created a mirror, every stage clones from the remote as before.

## What it does not solve

- **Each stage still makes its own checkout.** It is a local copy from the
  mirror now, not a network transfer, but stages do not share a working
  tree.
- **Disk use grows.** Two mirrors, a build-tool home and an LFS store take
  several gigabytes on a 50 GiB volume that also holds the run workspaces,
  and nothing prunes them yet. `du -sh /app/workspace/cache/*` is the thing
  to watch.
- **Remote hosts are not warmed.** The macOS build host keeps its own caches
  in its own home directory, and the warm-cache agent does not refresh them.
- **A `ReadWriteOnce` volume ties the caches to the Homer pod.** Runner pods
  on other nodes need a `ReadWriteMany` volume, or their own caches.
