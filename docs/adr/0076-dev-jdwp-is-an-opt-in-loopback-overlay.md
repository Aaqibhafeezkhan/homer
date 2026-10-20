---
status: accepted
date: 2026-10-04
deciders: Lukáš Vlk
---
# ADR-0076: The dev JDWP agent is an opt-in, loopback-only overlay

## Context and Problem Statement

`docker-compose.dev.yml` started the backend JVM with
`-agentlib:jdwp=…,address=*:5005` in its always-on `JAVA_OPTS`, and published
`127.0.0.1:5005:5005` for a host IDE to attach. JDWP has no authentication: any
client that can open a TCP connection to the port can load classes and execute
code in the JVM. The dev overlay merges onto the base compose, where both
`backend` and `frontend` join `homer-network`, so the console container — a
**separate principal** — could reach `backend:5005` directly with no
credentials. The loopback host publish restricts only the host side of the
mapping; it does not change the wildcard bind inside the container.

The mechanism was introduced deliberately (commit `eac4ac1`) with an
"accepted for the dev overlay only" note in its commit message, but that
acceptance was never recorded where this repository's own process requires it
([`SECURITY.md`](../../SECURITY.md) accepted-risks table or an ADR
consequence), so it could not be dismissed as accepted. A security-review finding (CWE-489, active debug code) flags it. This ADR records the fix rather than an
acceptance.

## Decision Drivers

- **Principals are a boundary** ([ADR-0047](0047-security-by-design-and-threat-model.md)).
  The console is a distinct principal sharing `homer-network`; the
  same-UID reasoning ADR-0047 permits for co-located agent processes does not
  cover it, so a network-reachable JDWP port is a defect, not noise.
- **Prove the constraint with a test** ([ADR-0047](0047-security-by-design-and-threat-model.md)
  rule 8 / root `CLAUDE.md` §12 rule 8): the compose topology must be pinned by
  a denial-path test, not a comment.
- **The capability has value.** Host-side IDE attach to the dev backend is a
  real workflow (the reason `eac4ac1` exists), so the fix should keep it behind
  an explicit opt-in rather than delete it.

## Considered Options

- **Keep the agent always-on, accept the risk** — record a `SECURITY.md` row
  and amend ADR-0047. Rejected: it is a principal crossing that is cheap to
  close, and the network exposure is not needed for the debugging workflow.
- **Always-on agent, loopback bind, keep the host publish** — impossible:
  Docker forwards a published port to the container's `eth0` address, so the
  bind must be wildcard while the port is published.
- **Opt-in overlay bound to container loopback, nothing published, tunnel to
  attach** — chosen.

## Decision Outcome

Chosen option: **"Opt-in overlay bound to container loopback, nothing
published"**. `docker-compose.dev.yml` no longer loads the agent, so a plain
dev `up` starts no debug listener, and the base compose publishes only the two
loopback ports (`8080` and the console's `3000`).
`docker-compose.debug.yml` is a third overlay applied after dev; it sets the
dev `JAVA_OPTS` plus `-agentlib:jdwp=…,address=127.0.0.1:5005` and publishes
nothing. The IDE attaches through a host forwarder that relays over a
`docker exec` stdio bridge into the container's loopback, keeping the listener
reachable from the host but from no other container.

The constraint this ADR fixes for every future compose file: **no compose file
binds JDWP off container loopback, and no compose file publishes port 5005.**
`JdwpNetworkExposureTest` asserts both over every root `docker-compose*.yml`,
and additionally that the dev overlay carries no agent (no listener by default)
and that the debug overlay exists and does load it (so the denial checks cannot
pass vacuously).

### Consequences

- Good: no container on `homer-network` can reach JDWP; the console principal
  is no longer able to execute code in the backend JVM.
- Good: the host IDE workflow survives, behind an explicit overlay and a
  tunnel.
- Bad: attaching is one extra command (a forwarder) instead of a bare
  `localhost:5005` publish — accepted, since the convenience was the exposure.
- Bad: the agent is still unauthenticated **within** the backend container's
  own namespace; any same-namespace process could reach it. That is
  ADR-0047's same-UID case (an agent process in the backend container), fixed
  where cheap, and the loopback bind plus opt-in is the cheap fix available.

## More Information

- A security-review finding (CWE-489): the finding this fixes.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — principals are a
  boundary; rule 8 requires the denial test.
- [`DOCKER.md`](../../DOCKER.md) § Development Mode — the opt-in command and
  the tunnel attach path.
