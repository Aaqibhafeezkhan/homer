---
status: accepted
date: 2026-06-10
deciders: Lukáš Vlk
---
# ADR-0018: MCP server endpoint — stateless Streamable HTTP, opt-in agent tools

## Context and Problem Statement

Homer agents were invocable only via the REST API. LLM clients (Claude Code,
other MCP-capable hosts) increasingly consume capabilities via the Model
Context Protocol, and bridging Homer through a hand-written MCP shim per
deployment duplicates auth, schemas, and run-management plumbing. Homer needs
a first-party MCP surface that lets an LLM start a run, check it, fetch
artifacts, and cancel it — without weakening the existing auth and cost-cap
boundaries.

## Decision Drivers

- LLM clients need structured, self-describing tools, not raw HTTP recipes.
- Existing guarantees (auth, cost caps, locks, parallel-run limits, recursion
  guard) must apply identically to MCP-started runs.
- Agents are user-authored; exposing all of them to LLM clients by default
  would silently widen the attack surface.
- Homer is single-node (multi-node is off the roadmap); session state and
  scale-out machinery would be dead weight.
- Agent definitions reload at runtime; the tool list must not go stale.

## Considered Options

- **Stateless Streamable HTTP endpoint in-process, opt-in per agent** — chosen.
- **Stateful Streamable HTTP (SDK session management + SSE streams)** —
  rejected: sessions and `listChanged` notifications add state and complexity
  with no payoff for request/response tool calls on a single node.
- **Separate MCP sidecar process bridging the REST API** — rejected: second
  deployable, second auth surface, and the sidecar would lag agent reloads.
- **Expose every agent automatically (no opt-in)** — rejected: enabling the
  endpoint would silently publish all agents, including internal/cron ones,
  to any connected LLM.

## Decision Outcome

Chosen option: **stateless Streamable HTTP endpoint at `/mcp`, opt-in per
agent**, implemented with the official `io.modelcontextprotocol:kotlin-sdk-server`.

Key shape (all in force together):

- **Transport**: `StreamableHttpServerTransport` in JSON-response mode
  (`enableJsonResponse = true`, null session-id generator). GET/DELETE → 405.
  No SSE plugin, no session state. A fresh transport **and** a fresh MCP
  `Server` are built per POST, so `tools/list` always reflects
  `AgentsService.reload()` without `listChanged` machinery.
- **Opt-in**: agents are exposed only with `mcp: { enabled: true }` in the
  agent definition; the endpoint itself is gated by `server.mcp.enabled`
  (default off).
- **Tool schema**: QUERY + BODY inputs become top-level tool arguments;
  `inputSchema` properties/required merge in; HEADER inputs are excluded
  (headers carry auth material an LLM must not be prompted to supply). The
  reserved `_wait` argument (clamped to `server.mcp.maxWaitSec`) blocks until
  the run finishes — implemented as a `ProcessEventBroadcaster` subscription
  merged with a 2 s poll fallback covering the subscribe race and
  DROP_OLDEST event loss.
- **Shared start path**: the call-independent core of agent execution lives
  in `AgentRunStarter` (span with `request.source` = `http`/`mcp`,
  cycle/depth checks, cost-cap preflight, process create + execute). Both the
  HTTP route and MCP tools call it, so no policy can drift between entry
  points.
- **Error model**: domain failures (validation, cost caps, unknown runId)
  return `isError = true` tool results with `"ExceptionName: message"` text —
  visible to the LLM for self-correction — never JSON-RPC protocol errors.
- **Auth**: `/mcp` mounts inside the existing `authenticate` + per-caller
  rate-limit wrapper. The CSRF guard is session-cookie-scoped (ADR-0016), so
  bearer/HMAC MCP clients pass. The caller's bearer token is captured into
  the tool closures, giving MCP-started runs the same auth context as
  HTTP-started ones. DNS-rebinding protection is not installed — the endpoint
  is bearer-authenticated and deployments are not localhost-bound. *(Amended
  2026-10-09: the closures capture the caller's principal name, not a token;
  the `authToken` plumbing was removed in the
  [ADR-0030](0030-runagent-in-process-dispatch.md) follow-up.)*
- **Serialization**: route-scoped `install(ContentNegotiation) { json(McpJson) }`
  keeps the SDK wire format out of Homer's global Json configuration. This
  forces the global ContentNegotiation install to live at the routing root
  rather than the application level — Ktor throws `DuplicatePluginException`
  at startup when a route-scoped plugin is installed at both the application
  level and a route. StatusPages responds on the application-level call where
  routing-scoped plugins do not apply, so it serializes `ErrorResponse`
  explicitly via `apiJson` instead of relying on ContentNegotiation.

### Consequences

- Good: any MCP client can drive Homer agents with structured results
  (`structuredContent` + prose) and no bespoke glue.
- Good: opt-in twice over (server kill switch + per-agent flag) keeps the
  default deployment surface unchanged.
- Good: per-request Server construction makes agent reloads instantly
  visible and keeps the endpoint stateless and restart-safe.
- Bad: per-request Server/transport construction does redundant work per
  call — acceptable at Homer's request rates; revisit with a cached
  tool-list keyed on the agents map if it ever shows up in profiles.
- Bad: `_wait` holds the HTTP request open up to `maxWaitSec` (default
  300 s); reverse-proxy idle timeouts must exceed it (documented in
  SERVER.md), and each waiting call occupies a server connection.
- Bad: the Ktor pin moved 3.4.1 → 3.4.3 to match the SDK's transitive
  requirement.

## More Information

- [ADR-0007](0007-cost-meter-as-in-memory-soft-throttle.md) — the cost-cap
  preflight MCP calls now share via `AgentRunStarter`.
- [ADR-0008](0008-recursion-guard-via-invocation-chain-header.md) — MCP calls
  always start with an empty invocation chain (external entry point).
- [ADR-0016](0016-cookie-auth-csrf-protection.md) — why the CSRF guard does
  not block MCP clients.
- Reference docs: `app/SERVER.md` § *MCP endpoint*, `app/AGENT.md`
  § *MCP Exposure*, `app/README.md` § *MCP Server*.
