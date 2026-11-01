# Docker Deployment Guide

This guide covers deploying Homer using Docker for both development and production environments.

## Overview

Homer is deployed using Docker Compose with two main services:
- **Backend**: Kotlin/Gradle application running on port 8080. Its image also
  bundles the `homer_langgraph` Python runtime (pip-installed from
  `clients/python`), which backs the `!<langgraph>` command type. For this the
  backend build context is the **repo root** (`docker build --target runner -f app/Dockerfile .`),
  not `./app`.
- **Frontend (Console)**: Next.js application running on port 3000

### Architecture

```
┌─────────────────────────────────────────────────┐
│                   Docker Host                    │
│                                                  │
│  ┌──────────────┐         ┌──────────────┐     │
│  │   Frontend   │────────>│   Backend    │     │
│  │  (Console)   │         │   (Homer)    │     │
│  │  Port 3000   │         │  Port 8080   │     │
│  └──────────────┘         └──────────────┘     │
│         │                         │             │
│         │                         │             │
│         └─────────┬───────────────┘             │
│                   │                             │
│           ┌───────▼────────┐                    │
│           │  homer-network │                    │
│           └────────────────┘                    │
│                                                  │
│  Volume Mounts:                                 │
│  - example/config  → /app/config                 │
│  - example/agents  → /app/agents                 │
│  - example/workspace → /app/workspace            │
└─────────────────────────────────────────────────┘
```

## Quick Start

Get Homer running with Docker:

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Set a strong admin API token (required — startup fails if it is empty)
echo "HOMER_ADMIN_TOKEN=$(openssl rand -hex 24)" >> .env

# 3. Start all services
docker compose up -d

# 4. Access the application (bound to localhost by default)
# Frontend: http://localhost:3000 — sign in at http://localhost:3000/login?mode=bearer,
#           Token tab, with the HOMER_ADMIN_TOKEN value from .env
# Backend API: http://localhost:8080
```

The example config makes the backend bearer-only, so `.env.example` turns on
console sign-in with the token option (`AUTH_ENABLED=true`,
`CONSOLE_ALLOW_BEARER=true`). The console keeps that token in the browser's
`localStorage`, which is acceptable for this loopback-only stack; for a real
deployment use cookie sign-in with a password scheme
([`app/SERVER.md`](app/SERVER.md) § *Password Authentication*) and leave
`CONSOLE_ALLOW_BEARER` off.

`docker compose up` **pulls** the published release images
(`ghcr.io/livesport/homer/homer-app`, `ghcr.io/livesport/homer/homer-console`)
and builds nothing locally, so no JDK, Gradle or Maven Central access is needed.
`HOMER_VERSION` (see [Docker Variables](#docker-variables)) selects the release
tag and defaults to the pinned current release; the default is bumped as part of
each release, so a fresh clone of `main` pulls the latest one.

To **build from source** instead, stack the build overlay — it carries the
`build:` sections the base file no longer has, and tags what it builds locally
so a local build never shadows the pulled GHCR tag:

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

The services publish only on `127.0.0.1` by default, since the example config
runs arbitrary shell/Claude. For remote access, put a reverse proxy in front
(see `console/DEPLOYMENT.md`) rather than changing the port bindings.

Check service status:
```bash
docker compose ps
```

View logs:
```bash
docker compose logs -f frontend
docker compose logs -f backend      # or: tail -f example/workspace/app.log
```

Stop services:
```bash
docker compose down
```

## Directory Structure

```
homer/
├── app/                          # Backend source code
│   ├── Dockerfile               # Backend Dockerfile (build context = repo root)
│   └── (Kotlin/Gradle app)
├── clients/python/               # homer_langgraph runtime (pip-installed into the backend image)
├── console/                      # Frontend source code
│   ├── Dockerfile               # Frontend Dockerfile
│   ├── .dockerignore
│   └── (Next.js app)
├── example/                       # Backend operational files
│   ├── config/                  # Configuration
│   │   ├── config.json         # Main config file
│   │   ├── config-custom.json  # Alternate example config
│   │   └── config-local.json   # Local dev config (unsecured auth)
│   ├── agents/                  # Agent definitions
│   │   ├── hello_world/
│   │   ├── quickstart/
│   │   └── ...
│   └── workspace/               # Runtime data, logs
├── .dockerignore                # Backend build-context ignore file (context = repo root)
├── docker-compose.yml           # Full-stack orchestration (pulls the published release images)
├── docker-compose.build.yml     # Build-from-source overlay (adds the `build:` sections back)
├── docker-compose.dev.yml       # Development overrides (stacked on the build overlay)
├── docker-compose.debug.yml     # Opt-in JVM debugger overlay (applied on top of dev)
├── docker-compose.prod.yml      # Production overrides
└── .env.example                 # Environment template
```

## Development Mode

Development mode includes:
- Hot-reload for frontend changes
- Enhanced logging
- The `agentdebug` tool bundled into the backend image (`HOMER_DEBUG=1` build argument; rebuild with `--build`)
- Read-write volume mounts

Start in development mode — the dev overlay sets *build* arguments, so it needs
the build overlay underneath it (the base file only pulls images):

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml -f docker-compose.dev.yml up
```

This starts **no** JVM debug listener. The JDWP agent is opt-in, because it has
no authentication: any peer that can open a TCP connection to it can execute
code in the backend JVM, and the dev stack shares `homer-network` with the
console.

### Development Features

**Backend Debug Port (opt-in)**:

JDWP is unauthenticated remote code execution, so it is not enabled by the
default dev stack. To debug the backend, add the debug overlay on top of dev:

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml \
  -f docker-compose.dev.yml -f docker-compose.debug.yml up
```

When enabled, the agent binds `127.0.0.1:5005` **inside the backend container**
(`suspend=n`) and nothing is published — a published port would NAT to the
container's `eth0` address and put the unauthenticated agent back on
`homer-network`.

Attach your IDE through a tunnel to that loopback port. The tunnel is a stdio
bridge over `docker exec`, so the debug port never leaves the container's own
loopback; run it in a second terminal while the debug stack is up:

```bash
# Host side: listen on loopback and pipe each connection through `docker exec`
# into the container, whose bash opens the loopback debug socket. Nothing is
# published, so no other container can reach JDWP.
socat TCP-LISTEN:5005,bind=127.0.0.1,reuseaddr,fork \
  SYSTEM:'docker exec -i homer-backend bash -c "exec 3<>/dev/tcp/127.0.0.1/5005; cat <&3 & cat >&3; wait"'
```

Then point the IDE's remote-JVM debugger at `localhost:5005`.

`bash` is in the image, so the container half needs no extra tooling; only the
host needs `socat`. Without it, any equivalent host forwarder works — the
contract is just "listen on host loopback, then relay bytes to
`docker exec -i homer-backend bash -c '…'`".

Never expose the debug port off-host: JDWP executes code in the JVM, so a
non-loopback publish is remote code execution.

**Frontend Hot Reload**:
- Runs `npm run dev` for Next.js development server
- Source code mounted as volume
- Changes reflect immediately without rebuild
- Full Next.js development features enabled
- The read-only root filesystem from the base compose is lifted in dev
  (`read_only: false`) so `npm install` and the dev server can write
- If a previous dev run left a broken `node_modules` volume behind
  (e.g. `npm install` was interrupted), reset it once with
  `docker compose down -v` before starting again

**Enhanced Logging**:
- DEBUG level logging for backend
- Detailed console output

## Production Mode

Production mode includes:
- Resource limits (CPU, memory)
- Log rotation
- Optimized JVM settings

Start in production mode:
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

### Production Features

**Resource Limits**:
- Backend: 2 CPU cores, 2GB RAM
- Frontend: 1 CPU core, 512MB RAM

**Log Rotation**:
- Container stdout (`docker compose logs`): max 10MB per log file, keep 3
  most recent files. The backend's `example/workspace/app.log` and `api.log`
  are rotated by Homer itself (10MB × 3) in every mode.

**JVM Tuning**:
- G1 garbage collector
- Optimized heap sizes
- Max GC pause 200ms

## E2E test stack

`docker-compose.e2e.yml` (compose project name `homer-e2e`, so it never
collides with the dev stack) runs the black-box E2E suite against the
**production backend image**. Runbook and suite design:
[`app/e2e/README.md`](app/e2e/README.md).

```bash
docker compose -f docker-compose.e2e.yml build
docker compose -f docker-compose.e2e.yml run --name homer-e2e-runner e2e-runner
docker compose -f docker-compose.e2e.yml down -v
```

`compose run` starts the backend first (healthcheck-gated via `depends_on`)
and returns the test JVM's exit code directly. (`up --exit-code-from` is not
used: the one-shot `fixtures-init` service breaks its abort detection.) The
explicit `--name` keeps the runner container addressable for `docker cp` of
the JUnit results afterwards.

**Topology** — five services:

| Service | Image | Role |
|---------|-------|------|
| `fixtures-init` | `alpine:3.21` | One-shot: copies `app/e2e/fixtures/agents/` into the `e2e-agents` volume and chowns it to `1000:1000` (the image's `homer` user) |
| `homer-backend` | `app/Dockerfile --target runner` (context = repo root) | The system under test, config bind-mounted read-only from `app/e2e/fixtures/config/` |
| `e2e-runner` | `app/e2e/Dockerfile` | Gradle test JVM running `:e2e:test` against `http://homer-backend:8080` |
| `homer-runner` | `app/Dockerfile --target homer-runner` | Pull-mode runner instance `e2e-runner` (tag `e2e-external`, concurrency 2) for `ExternalRunnerTest`; `network_mode: "service:homer-backend"` because homer-runner refuses plain `http://` except to loopback, and the stack has no TLS |
| `homer-runner-crash` | `app/Dockerfile --target homer-runner` | Instance `e2e-crash-runner` (tag `e2e-crash`) that the crash test kills; same shared network namespace, `init: true` because the kernel does not deliver `SIGKILL` from inside a PID namespace to its PID 1 (the image `exec`s java), `restart: "no"` so it stays dead |

**Volumes** — `e2e-agents` is a *named volume* (not a bind mount) because the
agent CRUD tests write to `/app/agents` at runtime: a `:ro` bind would make
those writes 500 and a `:rw` bind would dirty the checkout. `e2e-workspace`
holds the SQLite database, run workspaces, and logs. `down -v` drops both —
every run starts pristine.

**Ports** — the backend is published on loopback only,
`127.0.0.1:18080 → 8080` (override with `E2E_BACKEND_PORT`), so it never
clashes with a dev server on 8080.

**Logs** — `docker logs homer-e2e-backend` shows the application log; Homer
also writes it, with the access log, into the workspace volume:

```bash
docker exec homer-e2e-backend tail -f /app/workspace/app.log   # application log
docker exec homer-e2e-backend tail -f /app/workspace/api.log   # per-request access log
```

After the stack has exited, `docker cp homer-e2e-backend:/app/workspace/app.log .`
still works (containers keep their volumes until `down -v`).

## Individual Services

### Backend Only

Run just the backend service:
```bash
docker compose up backend
```

Access the API at http://localhost:8080

Liveness check (unauthenticated):
```bash
curl http://localhost:8080/api/v1/livez
```

### Frontend Only

Start the frontend service (Compose starts `backend` too, because `frontend`
waits for it to be healthy):
```bash
docker compose up frontend
```

**Note**: The console calls the backend from the browser, so the URL in `CONSOLE_HOMER_API_URL` (default: `http://localhost:<BACKEND_PORT>`) must be reachable from the browser, not just from the container.

Access the console at http://localhost:3000

### Standalone Builds

Build images without Compose:

**Backend** (build context is the repo root, so the image can bundle the
`clients/python` runtime — run from the repo root with `-f app/Dockerfile`):
```bash
docker build --target runner -t homer-backend -f app/Dockerfile .
# Add --secret id=artifactory_url,env=ARTIFACTORY_URL --secret
# id=artifactory_user,env=ARTIFACTORY_USER --secret
# id=artifactory_token,env=ARTIFACTORY_TOKEN to resolve through Artifactory
# when the three are exported (see § Artifactory build secrets).
docker run -p 127.0.0.1:8080:8080 \
  -v $(pwd)/example/config:/app/config:ro \
  -v $(pwd)/example/agents:/app/agents:ro \
  -v $(pwd)/example/workspace:/app/workspace \
  homer-backend
```

> **Bind to `127.0.0.1`, as above.** A bare `-p 8080:8080` publishes on all
> interfaces, and Homer runs arbitrary shell/Claude by design. `example/config/config.json`
> does require a bearer token (`@SYSENV:HOMER_ADMIN_TOKEN`, so add
> `-e HOMER_ADMIN_TOKEN=...` or startup fails; its CORS origins likewise need
> `-e FRONTEND_PORT=3000`, the console's port), but an explicit
> `"apiAuth": {"type": "unsecured"}` **opts out of every check** — swap in a
> config that sets it, such as `example/config/config-custom.json`, and a bare `-p` publishes
> unauthenticated remote code execution. Keep the loopback bind and put a
> reverse proxy in front for remote access, per the note above.

**Frontend**:
```bash
cd console
docker build -t homer-console .
docker run -p 3000:3000 \
  -e CONSOLE_HOMER_API_URL=http://localhost:8080 \
  homer-console
```

## Environment Variables

### Backend Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BACKEND_PORT` | 8080 | Host port for the backend (published on `127.0.0.1` only) |
| `HOMER_ADMIN_TOKEN` | _(required)_ | Admin API bearer token; resolved by `@SYSENV` in the example config. Startup fails if empty or unset. |
| `FRONTEND_PORT` | 3000 | Compose passes the console's port to the backend too: the example configs allow the console origin `http://localhost:<FRONTEND_PORT>` (and `127.0.0.1`) via `@SYSENV:FRONTEND_PORT`. Startup fails if unset |
| `JAVA_OPTS` | `-Xmx512m -Xms256m` | JVM options |
| `CONFIG_PATH` | `config/config.json` | Path to config file in container |

### Build Arguments

Build arguments are set at **image build time** (not runtime). They must be passed under `build.args` in your compose file — setting them only in `environment:` has no effect on the build. The default `docker-compose.yml` builds nothing, so these apply when you build from source: `docker-compose.build.yml` carries them, and it is where a new one goes.

| Argument | Default | Description |
|----------|---------|-------------|
| `HOMER_VERSION` | `dev` | Version label embedded in the image. Distinct from the `HOMER_VERSION` *environment* variable above, which selects the pulled release tag. |
| `HOMER_DEBUG` | `0` | Set to `1` to include the `agentdebug` tool in the image |
| `CLAUDE_CODE_VERSION` | `latest` | Claude Code CLI version to install. Pin it (e.g. `1.2.3`) for reproducible builds and to match `server.requireClaudeCodeVersion`. |
| `CODEX_VERSION` | `0.166.0` | Codex CLI version to install. Pinned by default: the CLI runs agent prompts with the workspace's secrets in its environment, so a build ships a version someone chose, not whatever npm had. |
| `OPENCODE_VERSION` | `1.19.5` | opencode CLI version to install. Pinned by default, for the same reason. |

### Artifactory build secrets (optional)

The backend and E2E images run Gradle inside the build. When
`ARTIFACTORY_URL` (the proxy's bare `https://` origin; another scheme, a path
or embedded credentials fail the build) and the `ARTIFACTORY_USER` / `ARTIFACTORY_TOKEN` credentials
are available, pass them as **BuildKit secrets** so that resolution goes
through the Artifactory proxy instead of Maven Central (which rate-limits
shared CI egress IPs with `429 Too Many Requests`):

```bash
docker build --target runner -f app/Dockerfile \
  --secret id=artifactory_url,env=ARTIFACTORY_URL \
  --secret id=artifactory_user,env=ARTIFACTORY_USER \
  --secret id=artifactory_token,env=ARTIFACTORY_TOKEN .

# docker-compose.e2e.yml declares all three under `build.secrets`, sourced from
# the environment — exporting the three variables is enough.
docker compose -f docker-compose.e2e.yml build
```

They are **secrets, not build arguments**: a `--build-arg` value stays readable
in `docker history` of the pushed image and an `ENV` is baked into its
metadata, while a secret mount leaves nothing in any layer.

Each Gradle `RUN` exports the three afresh from its own mounts (two per
Dockerfile), never for the build as a whole. The suppliers are the
`--secret id=…,env=…` flags on the CI pipeline's `docker build` lines and
`build.secrets` in `docker-compose.e2e.yml`. `ArtifactorySecretExposureTest`
fails if either Dockerfile gains an `ARG`/`ENV` Artifactory credential or a
`./gradlew` build step without the mounts.

The E2E runner also receives the three as ordinary run-time `environment`
pass-throughs, because its `ENTRYPOINT` runs Gradle again inside the container:
the repository set has to match the one the image was built with, or Gradle
re-resolves everything against Maven Central and the 429 moves from build time
to `:e2e:test`. Unset on the host stays unset in the container, so both phases
fall back together.

That pass-through is an accepted risk (listed in
[`SECURITY.md`](SECURITY.md)): the credential pair (`ARTIFACTORY_USER` /
`ARTIFACTORY_TOKEN`) is visible to `docker inspect` and to
the test JVM's children for the life of the container, where a build secret
lives for one `RUN`. It crosses no principal — the CI job already holds the pair
and starts the container itself — and the container runs this repo's own test
suite, not agent code. A compose *runtime* secret would not improve it: the
Gradle gate reads `providers.environmentVariable`, so a `/run/secrets/...` file
needs an entrypoint shim that exports it back into Gradle's environment, which
its test workers inherit. That hides the pair from `docker inspect` only, and
reading that already needs root-equivalent daemon access. Revisit if the runner
ever executes untrusted code.

All three are optional. Without them the Gradle gate in
`app/gradle/artifactory.settings.gradle.kts` stays off — at build time and run
time alike — and everything resolves from Maven Central exactly as before. No
extra flags are needed for a local build.

### Pinning the Claude CLI

The published full `homer-app` images install the Claude Code CLI at `@latest`
as of build time, so each release ships whatever version npm had that day (the
`-slim` variant ships no CLI). The Codex and opencode CLIs in the same image are
pinned instead — `CODEX_VERSION` and `OPENCODE_VERSION` in
[`app/Dockerfile`](app/Dockerfile), bumped deliberately.
There are two ways to pin, depending on how you build:

**Building homer-app from source** — pass the build argument:

```bash
docker build --target runner --build-arg CLAUDE_CODE_VERSION=2.1.245 \
  -t homer-backend -f app/Dockerfile .
```

**Building a derived image** (`FROM homer-app:<tag>`) — reinstall the CLI
in your own Dockerfile; an npm global install replaces the version the
base image shipped, in either direction (upgrade or hold back):

```dockerfile
FROM ghcr.io/livesport/homer/homer-app:1.38.0
ARG CLAUDE_CODE_VERSION=2.1.245
USER root
RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} && \
    npm cache clean --force
USER homer
```

In both cases, also set `server.requireClaudeCodeVersion` to the same
version in your server config (see [`app/SERVER.md`](app/SERVER.md)) —
Homer then fails any `!<claude>` run whose CLI reports a different
version (without retrying it), turning a forgotten build arg or a stale
base image into a clear per-run error instead of silent version drift.

### Verifying a published image

Every release image on GHCR is a multi-arch index of a `linux/amd64` and a
`linux/arm64` image, so Docker pulls the one that matches the host. The index
is signed with cosign and carries SLSA provenance of the release run that
assembled it and the CycloneDX SBOM of each platform, all bound to the index
digest; each platform image carries its own signature, SLSA build provenance
and SBOM ([ADR-0086](docs/adr/0086-sign-and-attest-release-images.md)). Verify an
image by digest before deploying it. The stack pulls by tag
(`HOMER_VERSION`), so resolve the digest first —
`docker buildx imagetools inspect ghcr.io/livesport/homer/homer-app:1.38.0 --format '{{.Manifest.Digest}}'`
prints the index digest — and pin that digest in your own override (see [Best Practices → Security](#security)):

```bash
image=ghcr.io/livesport/homer/homer-app@sha256:<digest>
cosign verify "$image" \
  --certificate-identity-regexp '^https://github\.com/livesport/homer/\.github/workflows/release\.yml@refs/tags/[0-9]+\.[0-9]+\.[0-9]+$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify "oci://$image" --repo livesport/homer \
  --signer-workflow livesport/homer/.github/workflows/release.yml \
  --predicate-type https://slsa.dev/provenance/v1
gh attestation verify "oci://$image" --repo livesport/homer \
  --signer-workflow livesport/homer/.github/workflows/release.yml \
  --predicate-type https://cyclonedx.org/bom
```

The provenance on the index records the release run that assembled and signed
it, not a build: the build provenance of what you run is on the platform
images. The index lists each of them by its own digest
(`docker buildx imagetools inspect ghcr.io/livesport/homer/homer-app:1.38.0`
shows them), and each is signed and attested the same way, so the same
commands verify a platform digest: run them on the one for your host to check
how it was built, or if you pin it instead of the index.

### Frontend Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FRONTEND_PORT` | 3000 | Host port for the console (published on `127.0.0.1` only) |
| `CONSOLE_HOMER_API_URL` | `http://localhost:<BACKEND_PORT>` | Backend API URL, as reached from the browser (`docker-compose.dev.yml` pins the default) |
| `AUTH_ENABLED` | false (`.env.example`: true) | Passed to the console as `CONSOLE_AUTH_ENABLED`: turns on console sign-in |
| `CONSOLE_ALLOW_BEARER` | false (`.env.example`: true) | Offers Token sign-in on the console's login screen; off means cookie (password) sign-in only. A bearer token is kept in the browser's `localStorage`, so leave it off outside a loopback setup |
| `CONSOLE_PLUGINS` | *(unset)* | Comma-separated plugin entries — a same-origin module path under `public/`, optionally `@<requirement>` (`/plugins/costs.js@admin`). See [Console plugins in the image](#console-plugins-in-the-image) |
| `CONSOLE_THEMES` | *(unset)* | Comma-separated theme entries as `name:path` (`acme:/themes/acme.css`) — a same-origin stylesheet under `public/`. See [Console themes in the image](#console-themes-in-the-image) |
| `CONSOLE_THEME_MODES` | *(unset)* | `light:<name>,dark:<name>` — names two `CONSOLE_THEMES` entries as the light/dark pair; they replace the built-in themes. See [Console themes in the image](#console-themes-in-the-image) |

### Docker Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `COMPOSE_PROJECT_NAME` | homer | Docker Compose project name |
| `HOMER_VERSION` | the pinned current release | Release tag of the published images the base `docker-compose.yml` pulls, for both services. Set a released version to move; irrelevant when you build from source (`docker-compose.build.yml` labels what it builds `${APP_VERSION:-dev}` instead). |

### Console plugins in the image

Runtime console plugins (`CONSOLE_PLUGINS`) are ES module files the console
serves from its own `public/plugins/` directory. Two consequences for the image:

- **`console/Dockerfile` copies `public/` explicitly.** Next's
  `output: 'standalone'` does not include it, so without that `COPY` every
  `CONSOLE_PLUGINS` path 404s in the container while working fine in `npm run dev`.
- **The console's root filesystem is read-only** (`read_only: true`, see
  [Security](#security)). A plugin therefore either gets baked in at
  image build time — `console/public/plugins/` is inside the build context — or
  is bind-mounted at runtime:

  ```yaml
  volumes:
    - ./my-plugins:/app/public/plugins:ro
  ```

  Mount it **read-only**. The console never writes there, and that directory
  holds code executing with the operator's session: a process that can write to
  it owns the console. See the trust model in
  [`console/README.md`](console/README.md#plugins).

### Console themes in the image

Runtime console themes (`CONSOLE_THEMES`) are CSS files the console serves from
its own `public/themes/` directory, alongside any fonts a theme declares. The
delivery story is the same as for plugins, for the same reasons:

- **`console/Dockerfile` copies `public/` explicitly.** Next's
  `output: 'standalone'` does not include it, so without that `COPY` every
  `CONSOLE_THEMES` path 404s in the container while working fine in `npm run dev`.
- **The console's root filesystem is read-only** (`read_only: true`, see
  [Security](#security)). A theme therefore either gets baked in at image build
  time — `console/public/themes/` is inside the build context — or is
  bind-mounted at runtime:

  ```yaml
  volumes:
    - ./my-themes:/app/public/themes:ro
  ```

  Mount it **read-only**. The console never writes there, and that directory
  holds stylesheets that restyle the console for every user of it: a process
  that can write to it can redress what everyone sees. See the trust model in
  [`console/README.md`](console/README.md#custom-themes).

  A theme needs no rebuild to take effect — drop the file in, set
  `CONSOLE_THEMES`, restart the console.

  An operator can also replace the four built-in themes with one light/dark
  pair drawn from its own entries: set
  `CONSOLE_THEME_MODES=light:<name>,dark:<name>`, naming two `CONSOLE_THEMES`
  entries. The switcher then lists only the configured entries plus `System`
  (which follows the OS colour scheme); unset, the built-ins stay. A malformed
  value is ignored whole and logged once. Compose passes the variable through
  like `CONSOLE_THEMES`, so setting it in `.env` is enough.

## Configuration

### Backend Configuration

The backend reads configuration from `example/config/config.json`. To customize:

1. Edit `example/config/config.json` with your settings
2. Restart services: `docker compose restart backend`

### Agents Configuration

Agents are defined in `example/agents/`. Each agent has its own directory with:
- `agent.json` - Agent definition
- Additional agent-specific files

To add a new agent:
1. Create directory in `example/agents/your_agent_name/`
2. Add `agent.json` with agent definition
3. Restart backend: `docker compose restart backend`

## Volume Mounts

### Config Volume (Read-Only; Read-Write in Dev)
- Host: `./example/config`
- Container: `/app/config`
- Purpose: Application configuration

### Agents Volume (Read-Only; Read-Write in Dev)
- Host: `./example/agents`
- Container: `/app/agents`
- Purpose: Agent definitions

### Workspace Volume (Read-Write)
- Host: `./example/workspace`
- Container: `/app/workspace`
- Purpose: Runtime data, logs, temporary files, **and the durable state database**

Two log files land here:
- `app.log` — the full application log (rolling, 10MB × 3; the same lines
  also go to the container's stdout, so `docker compose logs backend` shows them).
- `api.log` — the per-request access log (rolling, 10MB × 3).

Their directory is set by the `HOMER_LOG_DIR` env var, which the Dockerfile
points at `/app/workspace`.

### Durable State Database

Homer persists process records and operator questions to the SQLite database at
`databasePath` (see [SERVER.md](app/SERVER.md) and
[ADR-0020](docs/adr/0020-durable-state-in-sqlite.md)). **This file MUST live on
a persisted, read-write volume** — on the ephemeral container filesystem it is
recreated empty on every restart, defeating the durability guarantee and
silently dropping all process history.

The example config sets `databasePath: "/app/workspace/homer.db"`, placing it
inside the read-write workspace volume above, so no extra mount is needed.
SQLite also writes `-wal` / `-shm` sidecar files next to it, so the directory
must be writable (not a read-only mount). To isolate it from workspace data,
mount a dedicated volume (e.g. host `./example/data` → container `/app/data`)
and set `databasePath` to `/app/data/homer.db` instead.

### LangGraph Checkpoint Directory

The `!<langgraph>` command type persists graph state to a SQLite checkpoint
store under `server.langGraph.stateDir`. A parked (human-in-the-loop) graph is
run and later resumed by **separate processes**, so these checkpoints MUST
survive across runs — like the durable state database, `stateDir` must live on a
persisted, read-write volume **outside** the ephemeral per-process workspace.
When `stateDir` is `null` (the default), the effective directory is a
`langgraph/` subdirectory beside `databasePath`, so placing `databasePath` in
the workspace volume above persists checkpoints too. Operators who set a custom
`stateDir` must ensure that path is mounted on a durable volume. See
[SERVER.md](app/SERVER.md).

## Networking

Services communicate over a dedicated bridge network `homer-network`.

**Internal Communication**:
- None between containers: the console calls the backend from the browser at
  `CONSOLE_HOMER_API_URL` (see [Frontend Can't Reach Backend](#frontend-cant-reach-backend))

**External Access**:
- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`

## Health Checks

The backend service includes a health check:
- Endpoint: `/api/v1/livez` (unauthenticated by design — the probe carries no token)
- Interval: 30 seconds
- Timeout: 10 seconds
- Retries: 3
- Start period: 40 seconds

The frontend depends on the backend being healthy before starting.

## Troubleshooting

### Services Won't Start

Check logs:
```bash
docker compose logs backend             # backend application log
tail -n 100 example/workspace/app.log   # the same log, on disk
docker compose logs frontend
```

Verify configuration:
```bash
docker compose config
```

### Frontend Can't Reach Backend

**Important**: The frontend makes API calls from the browser (client-side), not from the Docker container. This means:

- **Development mode**: Use `http://localhost:<BACKEND_PORT>` (already configured in docker-compose.dev.yml)
- **Production mode**: Use your actual backend URL accessible from the browser

If you see errors like "Failed to fetch" or "backend: not found":

1. Verify the API URL is accessible from your browser:
```bash
curl http://localhost:8080/api/v1/livez
```

2. Check the environment variable in the container:
```bash
docker compose exec frontend env | grep CONSOLE_HOMER_API_URL
```

3. For production deployments behind a reverse proxy, set `CONSOLE_HOMER_API_URL` to your public backend URL (e.g., `https://api.yourdomain.com`)

### CORS Errors in the Browser Console

Because the console calls the API cross-origin (port 3000 → port 8080), the
**backend's** CORS policy must allow the console origin. The example configs
take the port from `FRONTEND_PORT`, which compose passes to the backend:

```json
"corsAllowedOrigins": ["http://localhost:@SYSENV:FRONTEND_PORT", "http://127.0.0.1:@SYSENV:FRONTEND_PORT"]
```

If the browser console shows errors like "blocked by CORS policy", the console
is served from an origin that list does not name — another host name, or a config of your own.
Set `server.corsAllowedOrigins` to the exact console origin(s), e.g.
`["https://console.example.com"]`. An empty list answers with a wildcard
origin, which browsers reject for the console's credentialed (cookie-mode)
requests. See `app/SERVER.md` ("Cross-origin / multi-instance consoles") for
details.

### Backend Config Not Found

Verify volume mount:
```bash
docker compose exec backend ls -la /app/config
```

Check config file exists on host:
```bash
ls -la example/config/config.json
```

### Permission Issues

Ensure workspace directory is writable:
```bash
chmod -R 755 example/workspace
```

### Port Already in Use

Change the port in the `.env` file and restart; nothing else needs editing:
```bash
echo "BACKEND_PORT=8081" >> .env
echo "FRONTEND_PORT=3001" >> .env
docker compose up -d
```

The console's API URL follows `BACKEND_PORT` and the backend's allowed CORS
origin follows `FRONTEND_PORT`, unless `.env` sets `CONSOLE_HOMER_API_URL`
or `CONFIG_PATH` points at a config with literal origins.

### Build Failures

A plain `docker compose up` builds nothing, so a failure there is a failed
**pull**, not a build: check that `HOMER_VERSION` names a released tag and that
the registry is reachable. The images are public, so a stale `ghcr.io` login is
the usual cause — `docker logout ghcr.io` and retry.

Building from source (the build overlay) failing: clean the Docker cache and
rebuild:
```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml down
docker system prune -f
docker compose -f docker-compose.yml -f docker-compose.build.yml build --no-cache
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d
```

### Out of Memory

Increase resource limits in `docker-compose.prod.yml` or adjust `JAVA_OPTS`:
```bash
export JAVA_OPTS="-Xmx1024m -Xms512m"
docker compose up -d
```

## Maintenance

### View Logs

All services:
```bash
docker compose logs -f
```

Specific service:
```bash
docker compose logs -f backend      # or: tail -f example/workspace/app.log
docker compose logs -f frontend
```

### Restart Services

All services:
```bash
docker compose restart
```

Specific service:
```bash
docker compose restart backend
docker compose restart frontend
```

### Update Images

The default stack runs published images, so an update is a pull:

```bash
git pull                     # picks up the HOMER_VERSION default of the new release
docker compose pull
docker compose up -d
```

To stay on a version you chose, set `HOMER_VERSION` in `.env` and pull that tag
— or build the working tree instead, with the build overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

### Clean Up

Remove containers and networks:
```bash
docker compose down
```

Remove containers, networks, and volumes:
```bash
docker compose down -v
```

Remove containers, networks, and images:
```bash
docker compose down --rmi all
```

## Best Practices

### Development
- Use development compose file for hot-reload
- Keep workspace directory in .gitignore
- Don't commit .env file
- Use debug ports for troubleshooting

### Production
- Use production compose file for resource limits
- Enable log rotation
- Use read-only mounts for config and agents
- Monitor resource usage
- Regularly update base images
- Back up workspace directory

### Security
- Don't expose debug ports in production
- Use secrets management for tokens
- Base images are pinned by digest (`tag@sha256:…`) in every `Dockerfile` and compose `image:`; `renovate.json` keeps the digests bumped via PRs so pinning stays reproducible without freezing out security patches. When updating a base image manually, refresh both the tag and the digest.
- The two **Homer** services are the exception, by design: they track a release tag through `HOMER_VERSION` so a fresh clone runs the latest release, and `renovate.json` does not bump them — the tag is bumped in the release commit (ADR-0093). A deployment that must be reproducible pins what it verified by digest instead, in its own override (`image: ghcr.io/livesport/homer/homer-app@sha256:<digest>`, from § [Verifying a published image](#verifying-a-published-image)); that override is yours alone — nothing bumps it for you, so the pin holds until you re-verify the new release and re-pin its digest by hand (§ [Verifying a published image](#verifying-a-published-image)).
- Limit container resources
- Backend runs as dedicated `homer` user (UID 1000); frontend runs as `nextjs` user. The `entrypoint.sh` starts as root to fix bind-mounted volume ownership, then drops privileges via `su-exec` before launching the JVM. The recursive `chown` runs **only** when the workspace is not already `homer`-owned (i.e. first run against a fresh host directory); a normal restart skips it, so the root window is a single ownership check plus the `su-exec` drop rather than a root-recursive filesystem walk.
- The image hands `homer` only the directories it must write at runtime — `/app/workspace` (runs, SQLite db, logs) and `/app/agents` (agent CRUD) plus its home. `/app` itself, `/app/config`, `homer.jar`, and the root-executed `entrypoint.sh` stay root-owned, so an in-container agent process (which runs as `homer`) cannot replace the entrypoint or the jar and gain root on the next container start.
- Both services set `no-new-privileges:true`. The console additionally drops all Linux capabilities (`cap_drop: [ALL]`) and runs on a **read-only root filesystem** with just two `tmpfs` mounts (`/tmp` and `/app/.next/cache` for the Next.js standalone server). The backend keeps capabilities and a writable root because its entrypoint chowns the workspace and `su-exec`s as root (needs `CHOWN`/`SETUID`/`SETGID`), and it runs agents/Claude that write to `$HOME`, caches, and `/tmp` on unpredictable paths — a read-only root would break the core function.

## agentdebug in Docker

The `agentdebug` CLI can be included in the backend image and executed inside the running container.

### Enable agentdebug

`agentdebug` is opt-in — the image must be built with `HOMER_DEBUG=1` as a **build argument**. The base compose pulls a published image, so this only applies when you build from source: `docker-compose.dev.yml` already sets that argument, and it needs `docker-compose.build.yml` underneath it for the `build:` section to merge into.

```yaml
# your docker-compose override
services:
  backend:
    build:
      args:
        HOMER_DEBUG: "1"
```

Then rebuild:
```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml build backend
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d
```

> **Important**: `HOMER_DEBUG=1` in `environment:` is a runtime env var and has no effect on the build. It must be under `build.args`.

### Usage

Once the container is running, use `docker exec`:

```bash
# List available agents
docker exec homer-backend agentdebug list

# Validate an agent
docker exec homer-backend agentdebug validate my_agent

# Dry run
docker exec homer-backend agentdebug run my_agent --dry-run

# Run a single command (index 0)
docker exec homer-backend agentdebug run my_agent --cmd 0

# Override an env variable for this debug session. The value reaches
# commands only if MY_TOKEN is on parentEnvAllowlist or referenced via
# @SYSENV:MY_TOKEN in globalEnv / the agent's env.
docker exec -e MY_TOKEN=abc homer-backend agentdebug run my_agent

# Use a different config
docker exec homer-backend agentdebug --config config/other.yaml list
```

The wrapper inside the container automatically uses `$CONFIG_PATH` from the container environment as the default config, so you don't need to pass `--config` explicitly.

If the image was built without `HOMER_DEBUG=1`, you'll get a clear error:
```
Error: agentdebug not available. Rebuild the container with HOMER_DEBUG=1.
```

## Slim homer-app image

Each release also publishes `homer-app:<version>-slim`, for a server whose
agents run on external pull runners (ADR-0066), Kubernetes or SSH rather than
in the server container
([ADR-0094](docs/adr/0094-slim-server-image-for-pull-runner-deployments.md)).
It is about 205 MB compressed against 547 MB for the full image (linux/arm64,
measured for ADR-0094). It holds a JRE, Homer, `sh`/`bash`, `su-exec`, `curl`
and `python3` with pip and the `homer_langgraph` runtime, so the console's
LangGraph graph view and LangGraph status and resume, which run on the server,
keep working. It has no Claude Code, Codex, opencode, Node.js/npm, git, git-lfs, glab,
jq or SSH client, and only busybox's basic `unzip`: a run on the built-in
`local` runner that calls one of them fails with `command not found`, so route
every agent to a runner that has them. The full image is built `FROM` the slim
one and adds exactly that toolchain, with Info-ZIP `unzip`.

```bash
docker build --target slim -t homer-backend:slim -f app/Dockerfile .
```

To run the default stack on it, set the backend image in an override:

```yaml
# docker-compose.override.yml
services:
  backend:
    image: ghcr.io/livesport/homer/homer-app:${HOMER_VERSION:-1.38.0}-slim
```

## homer-runner image

`homer-runner` is the reference pull-mode runner instance (ADR-0066): it runs on
a machine outside Homer, registers with an `!<external>` domain and executes
the runs it claims. It is a stage of `app/Dockerfile` and a release
artifact next to `homer-app` and `homer-console`:

```bash
docker build --target homer-runner -t homer-runner -f app/Dockerfile .
docker run -d --name homer-runner --restart on-failure \
  -e HOMER_RUNNER_URL=https://homer.internal \
  -e HOMER_RUNNER_TOKEN="$HOMER_WORKERS_TOKEN" \
  -e HOMER_RUNNER_TAGS=linux,gpu \
  -e HOMER_RUNNER_CONCURRENCY=2 \
  -e HOMER_RUNNER_NAME="$(hostname)" \
  homer-runner
```

A runner that can no longer start processes or write its root exits `3`;
`--restart on-failure` brings it back (see `app/SERVER.md`). It also restarts
on exit `1` and `2`, so a bad token or a disallowed tag loops with Docker's
restart backoff until the configuration is fixed.

The image is deliberately small (`bash`, `curl`, `git`, `tar`, `gzip`,
`util-linux` for `setsid`, a JRE). An agent that needs `claude`, `node`,
`python` or a Docker CLI on the runner needs a derived image:

```dockerfile
FROM registry.example.com/homer/homer-runner:1.25.0
USER root
RUN apk add --no-cache nodejs npm && \
    npm install -g @anthropic-ai/claude-code && \
    npm cache clean --force
USER homer
```

Runs execute as the unprivileged `homer` user, each session in a fresh,
owner-only (`0700`) `/var/homer-runner/<processId>-<random id>` directory that
is never reused;
mount a volume there if the workspaces should outlive the container. At startup
the JVM makes itself non-dumpable (`prctl(PR_SET_DUMPABLE, 0)`) so a run cannot
read `HOMER_RUNNER_TOKEN` out of `/proc`; the entrypoint grants
`--enable-native-access=ALL-UNNAMED` for that hardening's `prctl` call, and a
refused startup exits `1` with the reason on stderr. The
container stops cleanly on `SIGTERM`: it deregisters, finishes in-flight runs
up to `HOMER_RUNNER_DRAIN_TIMEOUT` seconds (default 600) and exits, so give it
a `stop_grace_period` at least that long. Exit code `2` means Homer rejected
the registration (bad token, disallowed tag) or `HOMER_RUNNER_URL` is neither
`https://` nor loopback `http://`, or carries embedded credentials; the message
is on stderr. Options and the protocol: `app/SERVER.md` § *External runners
(pull mode)*.

## Advanced Usage

### Custom Network

Use an external network:
```yaml
networks:
  homer-network:
    external: true
    name: my-custom-network
```

### Reverse Proxy

Example nginx configuration:
```nginx
upstream homer-backend {
    server localhost:8080;
}

upstream homer-frontend {
    server localhost:3000;
}

server {
    listen 80;
    server_name homer.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name homer.example.com;

    ssl_certificate     /etc/ssl/certs/homer.pem;
    ssl_certificate_key /etc/ssl/private/homer.key;

    location /api {
        proxy_pass http://homer-backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;

        # SSE endpoints stream for the life of a run.
        proxy_buffering off;
        proxy_read_timeout 3600s;
    }

    location / {
        proxy_pass http://homer-frontend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

Terminate TLS at the proxy: Homer never serves HTTPS itself, and cookie auth
issues `Secure` cookies that a plain-HTTP deployment will never receive back.
Configure a real auth scheme before exposing the backend this way — the
backend refuses to start without `server.apiAuth`, but an explicit
`!<unsecured>` opts out of every check. Note the proxy becomes the TCP peer Homer's rate
limiters key on, so all clients share one bucket; see
[`console/DEPLOYMENT.md`](console/DEPLOYMENT.md) § *Reverse Proxy*.

### Data Persistence

For production deployments, consider using Docker volumes:
```yaml
volumes:
  homer-workspace:

services:
  backend:
    volumes:
      - homer-workspace:/app/workspace
```

### Multi-Stage Deployment

Deploy to multiple environments:
```bash
# Development (builds the working tree)
docker compose -f docker-compose.yml -f docker-compose.build.yml -f docker-compose.dev.yml up -d

# Your own environment (create the override file first)
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d

# Production (pulls the release images)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

## Support

For issues and questions:
- Check the troubleshooting section above
- Review `example/workspace/app.log` and `docker compose logs`
- Check the [GitHub issue tracker](https://github.com/livesport/homer/issues)
- Consult main README.md for general Homer documentation
