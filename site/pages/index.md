---
layout: home
hero:
  name: Homer
  text: Run agents in the background, with guardrails
  tagline: An asynchronous execution orchestrator. Define an agent as a list of commands — shell, Claude Code, Codex, LangGraph, other agents — and trigger it over HTTP, from cron or through MCP.
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started
    - theme: alt
      text: Feature atlas
      link: /atlas/
      target: _self
    - theme: alt
      text: Agent reference
      link: /agents/reference
features:
  - title: Async HTTP API
    details: A run call returns a process id at once. Follow the run through status, SSE streams or the web console.
  - title: Seven command types
    details: Shell commands and scripts, Claude Code and Codex sessions, nested agents, durable LangGraph workflows, and steps on another runner.
  - title: Pluggable runners
    details: Run locally, on ssh hosts, as Kubernetes pods, or on pull-mode runners, with tag-based routing and per-runner concurrency.
  - title: Concurrency and cost control
    details: Lock strategies, parallel-run caps, cron schedules, and LLM spend caps with a persistent cost ledger.
  - title: Human in the loop
    details: An agent can ask the operator a question and wait for the answer, or fire a follow-up agent once it arrives.
  - title: Secure by design
    details: Bearer, HMAC and password auth with per-agent grants, a documented threat model, and signed release images with an SBOM.
---
