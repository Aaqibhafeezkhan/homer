---
status: proposed
date: 2026-10-25
deciders: Lukáš Vlk
consulted: ADR-0066, ADR-0086, ADR-0093
---

# ADR-0094: A slim server image for deployments whose agents run on pull runners

## Context and Problem Statement

`homer-app` bundles the agent toolchain next to the server: Claude Code, Codex,
Node.js/npm, git, git-lfs, glab, jq, an SSH client and Info-ZIP unzip. A run on the built-in `local`
runner needs them; a run on an external pull runner (ADR-0066), Kubernetes or
SSH does not, because its commands execute on that runner. Pull runners are the
execution model Homer intends to favour: they keep agent code away from the
server's config, secrets and database. A deployment built that way still pulls
the whole image, about 550 MB compressed, of which the toolchain it never runs
is about 340 MB, and keeps those tools beside the server, where any run that
falls back to the local runner can use them.

## Decision Drivers

- An orchestration-only server pulls and carries only what the server process
  itself runs.
- Features the server itself provides keep working on that image: the console's
  LangGraph graph view (`homer_langgraph inspect`, ADR-0027) and LangGraph
  status and resume (`homer_langgraph state`, ADR-0044) run Python on the
  server host, whichever runner ran the graph.
- Existing deployments and the Quick start, which run `!<claude>` locally, keep
  working with no change.
- The two images cannot drift apart: whatever the server needs is in both.

## Considered Options

- A slim stage the full image is built `FROM`, published as `homer-app:<version>-slim`
- The same slim stage, published under a separate name (`homer-app-slim`)
- Make the slim image the default `homer-app` now
- Keep one image and document deriving a smaller one

## Decision Outcome

Chosen option: **a slim stage the full image is built `FROM`, published as
`homer-app:<version>-slim`**. It serves the pull-runner deployment without
changing what `homer-app:<version>` means. Building the full image from the
slim one keeps the full image a superset.

The slim image promises a JRE, Homer, `sh`/`bash` (local commands and `when:`
predicates), `su-exec` (the entrypoint's privilege drop), `curl` (health
checks) and `python3` with pip and the `homer_langgraph` runtime. It promises
nothing else: `!<claude>`, `!<codex>` and any command calling Node.js/npm,
git, git-lfs, glab, jq, an SSH client or Info-ZIP unzip (busybox's basic
`unzip` remains) work only on a runner that has them. The full image adds
exactly that toolchain. A new server-side dependency goes into the slim stage; a new agent
tool goes into the full one.

### Consequences

- Good: a pull-runner deployment pulls about 205 MB instead of 547 MB
  (compressed, linux/arm64, when this ADR was written) and keeps no agent CLI
  beside the server.
- Good: one Dockerfile path for the server; the full image inherits every
  change to the slim one.
- Good: `-slim` is the tag convention users already look for.
- Bad: each release builds and signs one more image.
- Bad: a run that falls back to the local runner on the slim image fails with
  "command not found" rather than being refused before it starts. Turning the
  local runner off is a separate change (issue #33).
- Neutral: the release workflow, its notes script and `ReleaseWorkflowGateTest`
  carry a tag suffix per matrix entry.

## Pros and Cons of the Options

### Separate image name `homer-app-slim`

- Good: fits the release matrix with no new mechanics.
- Bad: two names for one server; users look for a `-slim` tag first.

### Slim as the default `homer-app` now

- Good: the smallest image is the one people get.
- Bad: breaks every deployment that runs `!<claude>` or `!<codex>` on the local
  runner, the Quick start included. Revisit once a runner image with the agent
  toolchain is published, so the default stack can pair a slim server with it.

### Document deriving a smaller image

- Bad: a derived image only adds layers; it cannot remove the toolchain the base
  ships.

## More Information

- Issue #32; the size analysis in issue #28.
- ADR-0066 (external pull runners), ADR-0086 (signed and attested release
  images), ADR-0093 (the default compose stack runs published images).
