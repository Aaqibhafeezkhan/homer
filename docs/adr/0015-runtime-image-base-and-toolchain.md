---
status: accepted
date: 2026-06-08
deciders: Lukáš Vlk
---
# ADR-0015: Runtime image base and toolchain — full alpine userland, distroless rejected

## Context and Problem Statement

Audit item #36 (P3) flags the backend image as large and proposes
evaluating `distroless` / `azul-zulu-alpine` to cut ~200 MB. The premise
is mostly stale: `app/Dockerfile`'s runtime stage is already the slim
`eclipse-temurin:25-jre-alpine`, so the ~200 MB figure (which assumed a
fat non-alpine base) is not on the table. The real question is whether
Homer can adopt a shell-less / package-manager-less base like distroless,
and how much can be slimmed without breaking anything.

## Decision Drivers

- Homer's execution model: agents run `cmd` / `script` / `claude`
  commands *inside* this container, which need a shell, `node` + the
  Claude Code CLI, and a general toolchain (`git`, `jq`, …).
- A downstream image extends this one and hard-depends on its userland.
- Slimming should be a free win — no behavior change, no broken agents.
- Don't re-litigate "why not distroless?" every audit cycle.

## Considered Options

- **A — Distroless runtime base.** Smallest base, no shell, no package
  manager.
- **B — `azul-zulu-alpine` JRE base.** Lateral swap of the alpine JRE.
- **C — Keep `eclipse-temurin:25-jre-alpine`, drop only interactive-only
  tools.**

## Decision Outcome

Chosen option: **"C — keep the alpine JRE base, slim only the
interactive-only tools"**. Distroless is **rejected**; `azul-zulu-alpine`
is not adopted.

**Why distroless is incompatible — two independent reasons:**

1. **Execution model.** Agents execute shell/script/Claude commands in
   this container. They need `bash`/`sh`, `node` + the Claude Code CLI,
   `git`, `jq`, etc. Distroless ships no shell and no package manager, so
   every non-trivial agent breaks.
2. **Downstream extension.** A dependent factory deployment's backend
   Dockerfile does
   `FROM ${HOMER_APP_IMAGE}` then `apk add --no-cache openjdk17 openjdk21`,
   `npm install -g firebase-tools`, and `chown homer:homer`. It
   hard-depends on the **alpine `apk`**, **npm/node**, and the
   **`homer:homer` user/group** — all three of which distroless removes.

`azul-zulu-alpine` is a lateral move: it is also a JRE-on-alpine base, so
there is no meaningful size delta versus `temurin-jre-alpine` and no
reason to take on the base-image churn.

**Slim actually taken:** removed the two interactive-only TUI tools
`vim` (~35 MiB) and `mc` (~13 MiB) from the runtime `apk add` block —
~48 MiB on disk (~15–20 MiB compressed). Neither is referenced by any
agent, script, `Dockerfile.backend`, or Homer's `entrypoint.sh`. The rest
of the toolchain agents and the downstream image rely on is unchanged:
`git, git-lfs, bash, nodejs, npm, curl, openssh-client, glab, su-exec,
jq, python3, unzip`. `git-lfs` backs `RUN git lfs install`;
`openssh-client` backs git-over-ssh remotes — both are deliberate
capabilities, not slack.

### Consequences

- Good: a free ~48 MiB slim with zero behavior change — no agent,
  script, or downstream build path uses `vim` or `mc`.
- Good: the execution-model and downstream constraints are recorded, so
  distroless does not get re-proposed each audit cycle.
- Bad: no in-image editor — a human debugging via `docker exec` must
  `apk add vim` themselves. Acceptable; no automated path uses an editor.
- Neutral: the audit's ~200 MB target is unreachable without breaking
  the execution model, because the base is already the slim alpine JRE.

## Pros and Cons of the Options

### Option A — Distroless

- Good: smallest possible base image.
- Bad: no shell and no package manager — breaks in-container agent
  execution and the downstream `apk` / `npm` extension.

### Option B — `azul-zulu-alpine`

- Neutral: also a JRE-on-alpine base.
- Bad: no meaningful size win over temurin-jre-alpine; pure base-image
  churn.

### Option C — Slim interactive-only tools (chosen)

- Good: free ~48 MiB, no behavior change, preserves every relied-on tool.
- Bad: no editor in the image for interactive debugging.

## Amendment (2026-10-25): the image's jar is musl-only

Two further trims, with no behavior change. The npm install step cleans npm's
download cache in the same layer (about 280 MB compressed). The Docker build
also deletes every SQLite native library from the fat jar except
`org/sqlite/native/Linux-Musl`, the one sqlite-jdbc loads on this Alpine base
(about 11 MB). The Gradle `shadowJar` keeps every platform, so a source-built
jar still runs with `java -jar` on macOS or glibc Linux.

Consequence: `/app/homer.jar` copied out of the image runs only on a musl
system. Portability is the source build's job; a glibc-based image needs its
own jar, built with `app/gradlew -p app :app:shadowJar`, not the one from
`homer-app`.

## More Information

- Audit item #36 (P3).
- ADR-0014 is the prior ADR in sequence; this ADR supersedes nothing.
