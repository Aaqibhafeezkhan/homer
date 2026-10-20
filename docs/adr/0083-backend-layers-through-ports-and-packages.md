---
status: proposed
date: 2026-10-13
deciders: Lukáš Vlk
consulted: ADR-0082
---

# ADR-0083: Backend layers are enforced through kernel types, ports and one package per layer

## Context and Problem Statement

`app/CLAUDE.md` draws the backend as five layers over a shared kernel:

1. HTTP;
2. Services;
3. Execution;
4. Executors;
5. Infra.

Two pending Konsist rules check this picture (ADR-0082). The layering rule says each layer imports only itself, the layers below it and the kernel. The cycle rule says the top-level packages `homer.app.*` import each other without cycles. When they were introduced, the code broke both: 39 layering and 11 cycle findings. The code paths behind them:

- **The kernel imported upward.** `models/` used `services.TagValidation`, `services.stripUrlSecrets` and `runners.external.RunnerProtocol`, which closed the `models ↔ services` and `models ↔ runners` cycles.
- **Execution and Executors call Services directly.** `ProcessExecutor`, `AgentExecutor`, `CommandExecutorFactory`, `LlmHarnessExecutor` and `RunAgentExecutor` use these Services types directly:
  - `ProcessManager`;
  - `QuestionsService`;
  - `LlmTelemetryService`;
  - `AgentRunStarter`;
  - `AgentsService`;
  - `Authorizer`;
  - the telemetry interfaces.
  
  `ProcessManager` is also where the run's domain types live: `ProcessInfo`, `ExecutionInfo`, `ProcessStatus` and `ProcessCreationResult`. Any lower layer that names one of them imports Services.
- **Infra calls up.** The Kubernetes runner and its state sink write through `ProcessManager`, and the external runner domain feeds the claim queue (Execution).
- **One package holds two layers.** `services/` contains the Services layer and most of the Infra layer: `Workspace`, `SecretRedactor`, `FileOperations`, `TextCompiler`, `WhenEvaluator`, the LLM stream helpers and others. `Process` (Execution) lives there too. Executors import about 70 of these names, so `services ↔ executors` and `services ↔ runners` are package cycles no single import can break.

## Decision Drivers

- The diagram should describe the code and be checked, rather than left as a hope (ADR-0082).
- Dependencies should point down. A layer that needs something from above declares what it needs, and the layer above provides it.
- Each step should land on its own and keep the build green, because other branches are in flight.
- Behavior, the HTTP API, persistence and the security gates must not change.

## Considered Options

1. **Ports and kernel types, then a package per layer**, in three steps.
2. **Relax the rules**: drop the cycle rule and let Execution and Executors import Services.
3. **One big move**: all of option 1 in a single merge request.

## Decision Outcome

Chosen option: **1**, in three merge requests. Each one leaves `main` green.

### Step 1: kernel

- `TagValidation` and `stripUrlSecrets` move to `models/`.
- The secret-screen floor becomes `MIN_SCREENED_SECRET_LENGTH`, defined in the kernel and reused by `SecretRedactor` and `TagValidation`.
- The pull-runner long-poll window becomes `RUNNER_LONG_POLL_SEC` in `models/`, and `RunnerProtocol.POLL_SEC` is defined as that value.
- The kernel imports nothing outside itself.

### Step 2: layering

The layering rule becomes `required`.

- **The run's domain types move to the kernel.** `ProcessInfo`, `ExecutionInfo`, `ProcessStatus`, `ProcessCreationResult` and the other data classes nested in `ProcessManager` move to `models/`. They describe a run, and every layer reads them.
- **A lower layer that needs a higher one declares a port.** The port is a narrow interface in the lower layer's own package that lists only the calls it makes. The higher layer implements the port, and `di/AppModule.kt` binds it. Two examples:
  - `ProcessManager` implements the Execution-side record of a run's steps and status.
  - The services implement the sub-run start that `RunAgentExecutor` needs.
- **The diagram is corrected only where a type's nature was misfiled.** A wrapper over an external system, such as the OpenTelemetry and Langfuse clients, is Infra. An in-memory execution gate is Execution. Each move between rows names its reason in `app/CLAUDE.md`. A type is never reclassified only to silence a finding.

### Step 3: cycles

The cycle rule becomes `required`, and the pending lane goes away.

- The Infra code in `services/` moves to its own package, `homer.app.infra`.
- `Process` moves next to the rest of Execution.
- With the ports from step 2, `executors` and `runners` then import nothing from `services`, and `infra` imports only the kernel.
- The pending lane is then empty. The `architecture-backend-pending` check and its `quality-gate` line are removed, as `app/CLAUDE.md` § Architecture tests prescribes.

*Done (2026-10-13):* the cycle rule is `required` with zero findings, and the lane is empty.

- 53 files moved to `infra/`, `services/interfaces/` included.
- `Process`, `LockManager`, `ParallelRunsManager` and their implementations moved to `executors/`. Three more ports keep them out of `services`:
  - `RunWorkRecords` and `RunKiller`, implemented by `ProcessManager`. `RunWorkRecords` shares the guarded status write, `GuardedStatusWrite`, with `RunRecords`;
  - `QuestionRegistration`, implemented by `QuestionsService`.
- Nine files the package rule had filed as Infra stayed in `services/` and count as Services. Eight call services: the MCP tool factories and `McpRunTools`, `DispatchGates`, the agent-file gates, `RunLiveness` and `ProcessGraphInspectRunner`. The ninth, `LlmSpanEmitter`, calls none; it is the span writer `LlmTelemetryService` was split into for size, and only that service builds it.
- The package now gives the default layer: `services/` is Services, and `infra/` is Infra.

### Consequences

- Good: the diagram becomes a checked fact, and a new dependency that points up fails the build at the import that adds it.
- Good: ports document exactly what Execution needs from Services, which also makes the executors testable against small fakes.
- Bad: step 2 renames widely used types (`ProcessManager.ProcessStatus` becomes `ProcessStatus`), and step 3 moves dozens of files. Both mean import churn, and branches open at the time must merge through it. Splitting the work into three steps keeps each conflict set small.
- Bad: a port is one more interface to keep in step with its implementation. Each port holds only the calls its caller makes.

## Pros and Cons of the Options

### Relax the rules

- Good: no churn.
- Bad: the diagram stays prose, which is the drift ADR-0082 exists to stop. The package cycles also hide real coupling, for example an executor reaching into `AgentsService`.

### One big move

- Good: done once.
- Bad: one diff too large to review, and every other branch hits all of its conflicts at once.

## More Information

- ADR-0082 covers the report-first mechanism and the pending lane.
- `app/CLAUDE.md` § Layered architecture and § Architecture tests describe the diagram and how each file's layer is read.
