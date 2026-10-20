# Linting agent definitions in CI

Homer refuses an agent definition it cannot load: `agent.yaml` is parsed
strictly, an unknown key is a load error, and the agent is skipped with the
reason in the reload result. Many mistakes load fine, though, and show up only
when a run reaches them: in a cron run at night, in a graph that never
resumes, or in a run that waits half an hour in the claim queue and then
fails.

Homer's maintainers, who develop Homer with Homer, and a team that builds a
mobile app on Homer both hit this and both ended up with the same answer: a
check in the agent repository's CI that knows Homer's runtime rules. This case
study collects the checks that paid off, with the Homer behaviour behind each.

## The setup

Three layers, from cheapest to most specific:

1. **Homer's JSON schemas.**
   [`app/schemas/homer-agent.schema.json`](../../app/schemas/homer-agent.schema.json)
   and `homer-config.schema.json` describe every key and type. Wire them into
   the editor with a `# yaml-language-server: $schema=…` line and into CI with
   any JSON-schema validator. A running Homer serves the schema of its own
   version at `GET /api/v1/schemas/agent`, which is the one to check against
   when the repository's Homer and the deployed one differ.
2. **Homer's own loader.** `agentdebug validate <agent>` loads a definition
   with the server's parser and schema
   ([`app/agentdebug/README.md`](../../app/agentdebug/README.md)). It needs a
   JDK, which a slim CI image may not have.
3. **A repository lint** for the rules a schema cannot see: rules that span
   two files (an agent and the server config), or that describe what happens
   at runtime. That is this case study.

```yaml
# CI job (excerpt)
test:agents:
  stage: test
  image: python:3.12-alpine
  script:
    - pip install --quiet pyyaml pytest
    - python3 scripts/validate_agents.py     # one script, one error per line
    - python3 -m pytest tests -q             # one test per rule and agent
```

Both deployments read the YAML with PyYAML. `SafeLoader` refuses Homer's
verbatim tags (`!<script>`, `!<claude>`, `!<bearer>`, …), so a multi-constructor
accepts all of them and keeps the tag name, which lets a rule tell a
`!<langgraph>` command from a `!<script>` one:

```python
class Tagged(dict):
    tag = ""

class HomerLoader(yaml.SafeLoader):
    pass

def _any_tag(loader, tag, node):
    if isinstance(node, yaml.MappingNode):
        value = Tagged(loader.construct_mapping(node, deep=True))
        value.tag = tag                       # "script", "claude", "langgraph", …
        return value
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node, deep=True)
    return loader.construct_scalar(node)

HomerLoader.add_multi_constructor("", _any_tag)
```

## The checks

- **A cron agent needs a lock.** A tick fires even while the previous cron
  run is still going, and the default lock key is unique per run, so up to
  `maxParallelRuns` (default 10) copies can overlap
  ([Cron Scheduling](../../app/AGENT.md#cron-scheduling)). Rule: `cron`
  requires `lockStrategy`. The same section has a second trap: an agent whose
  required input has no default loads without an active schedule.
- **A LangGraph agent takes no required inputs.** A parked graph resumes by
  re-running the agent from the top
  ([Resume re-runs the whole agent](../../app/AGENT.md#resume-re-runs-the-whole-agent)).
  The resume run carries the thread id, the interrupt id and the resume
  value, not the caller's original inputs. A `required: true` input refuses
  that run, and the graph never continues.
- **Every dispatching agent has an `agentGrants` entry.** A hop from A to B
  (`!<agent>`, `homer-dispatch`, ask-and-dispatch, a LangGraph fan-out) is
  allowed only if the run's root principal holds `run` on B or
  `server.agentGrants[A]` reaches B. A cron-started chain has no root
  principal, so it depends on `agentGrants` alone
  ([Grants and roles](../../app/SERVER.md#grants-and-roles),
  [ADR-0039](../adr/0039-nested-dispatch-authority.md)). A denied hop fails at
  runtime, and it fails like an unknown agent (`404`, or exit `255` from
  `!<agent>`), so nothing reports it at startup. The maintainers' lint looks
  for dispatch helpers in script steps and requires an `agentGrants` key for
  that agent. The mobile team parses the graph module with Python's `ast`,
  collects the literal first argument of every `homer_fanout(...)` call, and
  checks that each target exists and is covered by the graph agent's grant.
- **`runnerTags` name a pool that exists.** An agent whose `runnerTags` no
  runner serves still loads, with a warning. Its runs wait for the claim TTL
  and then fail ([Runner selection](../../app/AGENT.md#runner-selection)).
  With a 1800 s TTL, that is half an hour per run before the error shows. The
  maintainers' lint requires every worker agent to carry exactly its pool's
  tag, checks that the tag is in some runner's `allowedTags` in the server
  config, and rejects `runner: <external domain>`, which lets any instance in
  the domain take the run ([Runner pools](runner-pools.md)). LangGraph
  parents must have neither: a parent holds its runner permit for the whole
  fan-out, so on a pool its children can wait for a permit that never frees
  ([ADR-0066](../adr/0066-external-pull-runners-and-unified-claim-queue.md)).
- **Every Claude step runs with its guard hook.** The maintainers block
  builds from Claude steps outside the build pool with a hook in the Claude
  settings file. The lint requires every `!<claude>` step to set
  `options.settings` to a file that registers that hook. It also requires the
  build-pool agents, and only them, to set the env variable that lifts it. A
  step without the settings file runs unguarded, and one build on the light
  pool can take every run on that pod down with it.
- **Referenced files exist where the run will look.** `settings`,
  `mcpConfig` and `appendSystemPromptFile` under `{{HMR_AGENT_DIR}}` resolve
  to the agent's directory in the repository. The mobile team also checks
  every absolute path an agent or the server config names against the
  image's `COPY` lines and the declared volume mounts, and checks that the
  file is tracked by git. A plausible path that no `COPY` ever put in the
  image had cost them a fix merge request.
- **`finishExitCode` matches the script, both ways.** A script that ends the
  run on purpose (`exit 3` on its own line) must declare
  `finishExitCode: 3`. Without it, the graceful exit is an ordinary failure.
  A declared code that no line exits with is dead config that looks like a
  graceful path. The mobile team checks both directions.
- **`tags.fromInputs` names a declared, non-secret input.** Homer checks this
  at load and skips the whole agent when it fails
  ([Run tags](../../app/AGENT.md#run-tags)), so a typo takes a stage out of
  service instead of dropping a tag. The mobile team also requires tagged
  inputs to be `required`: an optional input that arrives empty adds no tag,
  and the run silently drops out of every `?tag=` query.
- **No upstream token is reachable from a run.** No `@SYSENV:` upstream token
  in `globalEnv` or an agent's `env`, and no token variable or direct
  upstream API URL in agents and scripts. Integrations go through the
  credential proxy ([Runs that hold no credentials](credential-proxy.md)).
- **A `resultEnvParam` line is printed before the noise.** Under an external
  runner, `homer-runner` streams a step's output to Homer in chunks while the
  step runs. After the process exits, it waits at most 5 s for the uploads
  still in flight, and then drops the rest. A step that prints `BRANCH=…`
  after a clone or a build can lose exactly that line. The `src: STD_OUT`
  mapping then finds nothing, and since Homer leaves an unknown `{{BRANCH}}`
  placeholder as it is, the next step receives it literally. The maintainers'
  lint flags a `NAME=` print that comes after a slow helper in the same
  script.

## Details that matter

- **Put the Homer reason in the message.** Each error names the behaviour it
  prevents ("resume runs carry no inputs", "holds a slot its own children
  wait for"), so whoever hits it can fix it without reading the lint.
- **Guard the guards.** A rule that silently matches nothing passes forever.
  The mobile team asserts a floor for each one: agents found, at least eight
  fan-out targets parsed from the graph, `COPY` lines found.
- **Make exemptions visible.** The graph parents that stay on the local
  runner and the agents allowed to build are named sets at the top of the
  lint. Adding an agent to one is a reviewed diff, not a comment in YAML.
- **Lint generated changes too.** The mobile team's improver agent
  ([A factory that proposes its own fixes](self-improving-factory.md)) opens
  merge requests that edit `agent.yaml`. The same CI job runs on those
  branches, so a bad proposal fails in its own merge request.

## What it does not solve

- **The lint models Homer's rules; it does not run them.** When Homer
  changes a rule, the lint goes stale without warning. Run
  `agentdebug validate`, or reload against a staging Homer, before relying on
  it for a version bump. The reload result lists every agent that did not
  load under `errors` ([Agent changes without downtime](agent-releases.md)).
- **Text matching has false negatives.** A dispatch hidden behind a helper the
  lint does not know, or a token read under another name, passes.
- **Behaviour is not covered.** A prompt that makes a stage loop, or a
  timeout that is too short for real data, only shows in runs.

## Adapting it

- Start with the checks whose failure is silent or slow: grants, runner tags,
  LangGraph inputs, cron locks. Each one is a few lines over the parsed YAML.
- One more check worth adding: every agent that is a dispatch target needs
  `dispatch: { enabled: true }`. Without it the hop is refused with `403`.
- Keep the lint in the agent repository, next to the server config it reads.
  Most of these rules compare the two.
