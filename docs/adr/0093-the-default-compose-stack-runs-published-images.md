---
status: proposed
date: 2026-10-23
deciders: Lukáš Vlk
consulted: ADR-0084, ADR-0086
---

# ADR-0093: The default compose stack runs published release images

## Context and Problem Statement

The README Quick start runs `docker compose up -d`, and `docker-compose.yml`
built both images from source: the backend with Gradle, the console with
Next.js. A first-time visitor waited several minutes and needed a working
Maven Central connection before seeing anything, on a machine that may not
have a JDK at all. Meanwhile every release already publishes signed images
with SLSA provenance and a CycloneDX SBOM to GHCR (ADR-0086), pullable
anonymously — so the default path was the slow one, and it also bypassed the
verification story the release machinery exists to enable.

## Decision Drivers

- A fresh clone reaches a running Homer in seconds, with no JDK, Gradle or
  Maven Central access.
- The default path is the one a release verified and signed, not one each
  consumer built for itself (ADR-0086).
- The images a release publishes are the artifacts CI tested; building the
  working tree stays available for development.
- The E2E suite keeps its "does `app/Dockerfile` still build" gate, so
  switching the default cannot let a broken image build ship.

## Considered Options

- **A: `docker-compose.yml` pulls `ghcr.io/livesport/homer/homer-{app,console}`
  at `${HOMER_VERSION:-<pinned release>}`; the `build:` sections move to a
  `docker-compose.build.yml` overlay.**
- **B: keep building by default and document the pull as the faster option.**
- **C: default `HOMER_VERSION` to a moving `latest` tag.**
- **D: default the compose `image:` to a digest-pinned reference.**

## Decision Outcome

Chosen: **A**. The base `docker-compose.yml` declares only `image:` for both
services and keeps its ports, volumes, environment, healthcheck and hardening
(`no-new-privileges`, console `cap_drop: [ALL]` + `read_only` + tmpfs)
unchanged. Building from source moves to `docker-compose.build.yml`, which
carries the `build:` sections (context, `target: runner`, build args) and tags
what it builds `homer-backend` / `homer-console` locally, so a local build
never shadows the pulled GHCR tag. `docker-compose.dev.yml` sets build
arguments, so it is documented and scripted stacked on the build overlay
(`rebuild-docker.sh`); `docker-compose.debug.yml` and `docker-compose.prod.yml`
are env-only and work on top unchanged.

`HOMER_VERSION` is a **pinned semver**, bumped as part of each release commit,
so a fresh clone of `main` pulls the latest release. `docker-compose.e2e.yml`
and the CI jobs keep their own `build:` sections and `build.secrets`: they test
the working tree, which is exactly what a pulled image cannot.

**Why not the others:**

- **B** leaves the slow, toolchain-hungry path as the one a visitor takes, and
  keeps the build as the only thing the docs exercise.
- **C** does not work: a tag push publishes `$IMAGE:<semver>` and no moving tag
  (`release.yml`), so `latest` would resolve to nothing. A moving tag would also
  make "which digest did I run" unanswerable without the verification step
  ADR-0086 is built around.
- **D** cannot be the *default*: the digest changes with every release, so it
  would have to be re-pinned by hand each time and renovate cannot bump a
  digest inside a `${HOMER_VERSION:-…}` default. A digest-pinned deployment is
  still the right choice for a consumer that needs reproducibility, and
  `DOCKER.md` documents pinning the verified digest in an override.

### Consequences

- Good: `cp .env.example .env`, an admin token and `docker compose up -d` start
  Homer without building anything locally.
- Good: the default deployment runs an image that was signed and attested by
  the release, and `README.md` / `DOCKER.md` show how to verify it and pin the
  digest before the first pull.
- Bad: the default stack now needs GHCR reachability instead of a local
  toolchain — the same network a pull-based deployment needed anyway, but a
  build-only environment must add the build overlay.
- Bad: a per-release manual bump. Forgetting it means a fresh clone keeps
  pulling the previous release, so `ComposeImageSourceTest` compares the
  compose default with the newest released heading in `CHANGELOG.md` and fails
  the build instead. The same guard reads the release pins in `.env.example`,
  `README.md` and `DOCKER.md`, and requires **every** version each of those
  names — `DOCKER.md` carries two, its `FROM` example and its `imagetools
  inspect` example — to equal that heading; the looser rules (at least one
  match is current, or the pin matched exactly once) were rejected, because a
  doc that also names an older release still sends a reader to the previous
  version.
- Neutral: the repo convention of pinning compose `image:` by digest
  (`renovate.json`) does not apply to the two Homer services, which track a
  release tag on purpose; base images and any digest-pinned override still do.

## Amendment (2026-10-25): the console origin follows `FRONTEND_PORT`, with no fallback

Moving the console off port 3000 left it unable to load, because the example
configs allowed only the `:3000` origin (issue #29). The three example configs
now take the console's origin from the environment,
`"http://localhost:@SYSENV:FRONTEND_PORT"` (and `127.0.0.1`), and compose passes
`FRONTEND_PORT=${FRONTEND_PORT:-3000}` to the backend. The console's API URL
defaults to `http://localhost:${BACKEND_PORT:-8080}` in the same way.

The placeholder has no default on purpose: an unset `FRONTEND_PORT` is a
startup error, as an unset `HOMER_ADMIN_TOKEN` already is. Inside compose it is
always set. A standalone `docker run` with the stock config must now pass
`-e FRONTEND_PORT=3000` as well, and `DOCKER.md` says so.

Rejected:

- **A `@SYSENV:VAR:-default` fallback syntax.** It would be a new placeholder
  grammar for every `@SYSENV` consumer, secrets included. A default there turns
  a missing secret into a silent fallback instead of a startup error.
- **A separate backend-only example config with literal origins.** That is a
  fourth config to keep in step with the other three, for the one setup that
  doesn't serve the console from compose. One documented extra `-e` costs less.

