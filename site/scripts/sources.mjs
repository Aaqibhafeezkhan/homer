// The repository files the site publishes, and where each lands. Everything else a page links to
// points at the file on GitHub, at the ref being built.
export const pages = [
  { from: 'README.md', to: 'guide/getting-started.md', title: 'Getting started' },
  { from: 'app/README.md', to: 'guide/backend.md', title: 'Backend' },
  { from: 'app/AGENT.md', to: 'agents/reference.md', title: 'Agent reference' },
  { from: 'app/agentdebug/README.md', to: 'agents/debugging.md', title: 'Debugging agents' },
  { from: 'clients/python/homer_langgraph/README.md', to: 'agents/langgraph.md', title: 'LangGraph workflows' },
  { from: 'app/SERVER.md', to: 'operate/server.md', title: 'Server configuration' },
  { from: 'DOCKER.md', to: 'operate/docker.md', title: 'Docker deployment' },
  { from: 'console/README.md', to: 'operate/console.md', title: 'Console' },
  { from: 'console/DEPLOYMENT.md', to: 'operate/console-deployment.md', title: 'Console deployment' },
  { from: 'example/console-plugin-react/README.md', to: 'operate/console-plugins.md', title: 'Console plugins' },
  { from: 'docs/case-studies/README.md', to: 'case-studies/index.md', title: 'Case studies' },
  { from: 'docs/case-studies/durable-pipeline.md', to: 'case-studies/durable-pipeline.md', title: 'A ticket-to-merge-request pipeline on LangGraph' },
  { from: 'docs/case-studies/long-run-salvage.md', to: 'case-studies/long-run-salvage.md', title: 'Keeping long runs from losing work' },
  { from: 'docs/case-studies/chat-questions.md', to: 'case-studies/chat-questions.md', title: 'Answering operator questions from chat' },
  { from: 'docs/case-studies/review-loop.md', to: 'case-studies/review-loop.md', title: 'A review → fix loop that converges' },
  { from: 'docs/case-studies/self-improving-factory.md', to: 'case-studies/self-improving-factory.md', title: 'A factory that proposes its own fixes' },
  { from: 'docs/case-studies/credential-proxy.md', to: 'case-studies/credential-proxy.md', title: 'Runs that hold no credentials' },
  { from: 'docs/case-studies/chat-intake.md', to: 'case-studies/chat-intake.md', title: 'A public chat intake for untrusted requests' },
  { from: 'docs/case-studies/claude-tool-limits.md', to: 'case-studies/claude-tool-limits.md', title: 'Containing a Claude step: disallowedTools, not allowedTools' },
  { from: 'docs/case-studies/proof-carrying-decisions.md', to: 'case-studies/proof-carrying-decisions.md', title: 'LLM decisions that must carry proof' },
  { from: 'docs/case-studies/agent-as-mcp-tool.md', to: 'case-studies/agent-as-mcp-tool.md', title: 'An agent as an MCP tool for another team' },
  { from: 'docs/case-studies/runner-pools.md', to: 'case-studies/runner-pools.md', title: 'Runner pools on Kubernetes' },
  { from: 'docs/case-studies/cross-agent-locks.md', to: 'case-studies/cross-agent-locks.md', title: 'One lock across several agents' },
  { from: 'docs/case-studies/single-lane.md', to: 'case-studies/single-lane.md', title: 'One lane at a time, without parked runs blocking it' },
  { from: 'docs/case-studies/warm-caches.md', to: 'case-studies/warm-caches.md', title: 'Warm caches for short runs' },
  { from: 'docs/case-studies/macos-build-hosts.md', to: 'case-studies/macos-build-hosts.md', title: 'macOS build hosts for mobile builds' },
  { from: 'docs/case-studies/agent-releases.md', to: 'case-studies/agent-releases.md', title: 'Agent changes without downtime on Kubernetes' },
  { from: 'docs/case-studies/agent-linting.md', to: 'case-studies/agent-linting.md', title: 'Linting agent definitions in CI' },
  { from: 'docs/case-studies/cost-accounting.md', to: 'case-studies/cost-accounting.md', title: 'Accurate cost through an LLM gateway' },
  { from: 'SECURITY.md', to: 'security.md', title: 'Security' },
  { from: 'CONTRIBUTING.md', to: 'contributing/index.md', title: 'Contributing' },
  { from: 'docs/quality-checks.md', to: 'contributing/quality-checks.md', title: 'Quality checks' },
  { from: 'CHANGELOG.md', to: 'changelog.md', title: 'Changelog' },
  { from: 'docs/adr/README.md', to: 'adr/index.md', title: 'Decision records' },
]

// Every ADR is published next to the index.
export const adrDir = 'docs/adr'

// The feature atlas, turned into the site page /atlas/ (atlas.mjs).
export const atlas = 'docs/homer-feature-atlas.html'

// Copied verbatim into the site.
export const assets = [
  { from: 'app/schemas/homer-agent.schema.json', to: 'schemas/homer-agent.schema.json' },
  { from: 'app/schemas/homer-config.schema.json', to: 'schemas/homer-config.schema.json' },
]
