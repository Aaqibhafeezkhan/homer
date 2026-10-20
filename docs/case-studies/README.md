# Case studies

How Homer is run in practice: a real setup, the problem it solves, the
configuration that does it, and what it leaves open. Each one is a pattern you
can copy, not a feature of Homer; the reference documents stay the authority on
what Homer itself does.

## Workflows and people

| Case study | In short |
| --- | --- |
| [A ticket-to-merge-request pipeline on LangGraph](durable-pipeline.md) | An orchestrator whose state lasts days: fan-out to worker agents, human approval gates, and saving paid work when a step hits its turn cap. |
| [Keeping long runs from losing work](long-run-salvage.md) | A per-ticket journal and work-in-progress snapshots, so a killed or re-triggered run continues instead of starting over. |
| [Answering operator questions from chat](chat-questions.md) | A bridge over Homer's questions API mirrors every open question into chat, and reminders nudge only in working hours. |
| [A review → fix loop that converges](review-loop.md) | An arbiter, deterministic rails and a round cap that hands over to a human through ask-and-dispatch. |
| [A factory that proposes its own fixes](self-improving-factory.md) | An agent that reads run history and opens one merge request per proposed fix, without slowing the pipeline it studies. |

## Security and isolation

| Case study | In short |
| --- | --- |
| [Runs that hold no credentials](credential-proxy.md) | A proxy beside each run adds the tokens, so a prompt-injected run has no token to leak. |
| [A public chat intake for untrusted requests](chat-intake.md) | Anyone may start runs from chat: per-thread locks, global caps, a path guard and a maintainer release gate. |
| [Containing a Claude step: disallowedTools, not allowedTools](claude-tool-limits.md) | With permissions skipped, `allowedTools` restricts nothing; `disallowedTools` keeps a step from spawning subagents. |
| [LLM decisions that must carry proof](proof-carrying-decisions.md) | A model may dismiss a security finding only with a verbatim quote that a script checks. |
| [An agent as an MCP tool for another team](agent-as-mcp-tool.md) | Another team's agent calls one Homer agent over MCP, with a token that may run that agent and nothing else. |

## Capacity and scheduling

| Case study | In short |
| --- | --- |
| [Runner pools on Kubernetes](runner-pools.md) | A performance pool (2 runs at a time) for builds and a light pool (8 at a time) for everything else, picked by `runnerTags`. |
| [One lock across several agents](cross-agent-locks.md) | `maxParallelRuns` is per agent: a lock key shared by every agent that uses one build host. |
| [One lane at a time, without parked runs blocking it](single-lane.md) | One pipeline computing at a time, where a run parked at a human gate does not hold the lane, and a watchdog for dead runs. |
| [Warm caches for short runs](warm-caches.md) | Cache roots on a volume and a cron agent that keeps them fresh: minutes saved per stage. |
| [macOS build hosts for mobile builds](macos-build-hosts.md) | From an ssh runner behind a tunnel to self-registering pull-mode runners on macOS VMs. |

## Operations

| Case study | In short |
| --- | --- |
| [Agent changes without downtime on Kubernetes](agent-releases.md) | Agents on their own volume: an agent-only change swaps the files and calls the reload endpoint, with no pod restart. |
| [Linting agent definitions in CI](agent-linting.md) | The checks that catch, before merge, the agent mistakes Homer would only report at runtime. |
| [Accurate cost through an LLM gateway](cost-accounting.md) | Pricing runs from the gateway's real spend, which arrives late, plus cost by tag and cost limits. |

Have a setup worth sharing? Open an issue describing it; see
[CONTRIBUTING.md](../../CONTRIBUTING.md).
