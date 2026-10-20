# Agent changes without downtime on Kubernetes

Agent definitions change far more often than Homer does. When the agents
are baked into the image, every agent change means a new image and a pod
restart. The server restarts too: on SQLite with a `ReadWriteOnce` volume it
uses the `Recreate` strategy, so the API, the console and cron are down until
the new pod is ready, and every runner pod drains its runs first. In the
setup this case study comes from, about two thirds of the commits touched
only agents and their scripts.

This case study keeps the agents on a volume of their own. A change that
touches only agents replaces the files on that volume and asks Homer to
reload. No pod restarts and no run is killed.

## The setup

```
content PVC (ReadWriteMany)
├─ releases/3f2a1c9/agents/…  scripts/…
├─ releases/8be04d1/agents/…  scripts/…
└─ current -> releases/8be04d1          (swapped atomically)

Homer pod    /app/content (read-only)   /app/agents -> /app/content/current/agents
runner pods  /app/content (read-only)   /app/scripts -> /app/content/current/scripts
release Job  /content (read-write)      stage → wait for idle → swap → POST /api/v1/agents/reload
```

- **Agents live on their own volume.** The images carry only Homer and the
  server config. The agents, the scripts they call and any tool
  configuration are on a `ReadWriteMany` volume, one directory per release
  plus a `current` symlink. The image has fixed symlinks into it
  (`/app/agents -> /app/content/current/agents`), so no path in an agent or
  a script changes. `agentsSearchPath` may itself be a symlink; Homer never
  follows a symlinked directory *below* it
  ([`app/SERVER.md`](../../app/SERVER.md#agents-search-path)).
- **Mounted read-only everywhere a run executes.** The Homer pod and the
  runner pods mount the volume read-only. A taken-over run cannot rewrite the
  scripts or settings that later runs will use. Only the release Job mounts
  it read-write.
- **Homer reloads on request.** `POST /api/v1/agents/reload` rescans the
  agents directory, swaps the catalog atomically and returns
  `{added, updated, removed, errors}`
  ([`app/README.md`](../../app/README.md#api-endpoints)). Running runs keep
  going. Cron reads the new catalog on its next tick. An agent whose new
  definition does not load keeps its previous one, and the reload reports
  it under `errors`.
- **The release Job does the rest.** CI runs it for every merge that changes
  only content:

```bash
# 1. Stage the release next to the others; `current` is untouched.
cp -R /content-src "$ROOT/releases/.tmp-$SHA"
chmod -R a+rX,go-w "$ROOT/releases/.tmp-$SHA"
mv "$ROOT/releases/.tmp-$SHA" "$ROOT/releases/$SHA"

# 2. Wait until no run is queued or running (give up after an hour).
until [ "$(curl -fsS -H "Authorization: Bearer $DEPLOY_TOKEN" \
    "$HOMER_URL/api/v1/status/all?types=CREATED,WORKING&limit=1" | jq .total)" = 0 ]; do
  sleep 30
done

# 3. Swap `current` atomically: a new symlink renamed over the old one.
ln -sfn "releases/$SHA" "$ROOT/.current.tmp"
mv -T "$ROOT/.current.tmp" "$ROOT/current"

# 4. Reload, and fail the release when an agent did not load.
curl -fsS -X POST -H "Authorization: Bearer $DEPLOY_TOKEN" "$HOMER_URL/api/v1/agents/reload" \
  | tee /dev/stderr | jq -e '.errors | length == 0'
```

The Job calls Homer as its own bearer principal with `role: admin`, because
the reload needs admin. Its token is in a Secret that only the Homer
container and the Job read:

```yaml
apiAuth: !<bearer>
  tokens:
    deploy:
      token: "@SYSENV:HOMER_DEPLOY_TOKEN"
      role: admin
```

## Details that matter

- **Why wait for idle.** A reload kills runs still queued (`CREATED`) for an
  agent it updated or removed. A running run reads its scripts live from
  `current`, so a swap in the middle of it would mix two releases. Staging
  first keeps the gap between the idle check and the swap to milliseconds. A
  LangGraph flow parked at a human gate is not running: it resumes later as
  a new run, so it does not block a release.
- **Decide content or full release from the files.** A change to the server
  config, the images or the manifests still needs a full release: Homer
  reads its config file once, at startup. CI hashes every file outside the
  content directories and compares the hash with the one the last full
  release recorded on the Deployment. If they match, the release is content
  only. Rolling back is re-running the release of an older commit.
- **Keep a few old releases.** The Job keeps the three newest releases
  besides `current`, so a manual rollback is a symlink swap and a reload.
- **The console's agent editor goes read-only.** It cannot write to a
  read-only volume. Git is the source of truth, and every change goes
  through review and CI.
- **Bootstrap.** The first release has no `current` yet and no pod reading
  the volume, so the Job skips the wait. The pods started after it find the
  release in place.

## What it does not solve

- **A run that starts in the gap** between the idle check and the swap runs
  the old definition against the new files. The gap is milliseconds, and the
  risk was accepted.
- **A busy Homer can delay a release.** The Job waits up to an hour for
  idle and then fails without touching `current`. Run it again later.
- **Server config changes still restart Homer.** Homer reads its config
  file once, at startup, and has no endpoint to reload it.
- **`ReadWriteMany` storage is needed** when the Homer pod and the runner
  pods sit on different nodes. With a single node, a `ReadWriteOnce` volume
  works the same way.
