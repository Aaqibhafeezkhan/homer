# Black-box E2E suite

Dockerized end-to-end tests for the Homer backend. The suite builds the
**production image** (`app/Dockerfile --target runner`), boots it with the
fixtures in [`fixtures/`](fixtures/), and runs this Gradle module's JUnit
tests against it over real HTTP — real Netty, real subprocesses, real SQLite.
Building the image on every run is intentional: the suite doubles as the
always-on "does the production Dockerfile still build" gate.

## Design

- **Black box by design.** The module has **no `:app` dependency**. Response
  shapes are re-declared as minimal DTOs in
  [`src/test/kotlin/homer/e2e/support/Dto.kt`](src/test/kotlin/homer/e2e/support/Dto.kt)
  and decoded with `ignoreUnknownKeys = true`. Reusing the `:app` models
  would let a serialization regression hide — both sides would share the bug.
- **Self-skipping.** Every test class extends `E2eTestBase`, which
  `Assumptions.assumeTrue`s that `E2E_BASE_URL` is set. Plain
  `./gradlew check` (unit-test CI, pre-commit) skips the suite cleanly.
- **No sleep-and-assert.** Async state is always polled (`Awaits.awaitStatus`,
  30 s timeout / 250 ms interval); SSE assertions subscribe **before**
  triggering (`SseCollector`).
- **Excluded on purpose:** Claude, Codex, opencode and LangGraph commands — the
  runner image ships all four toolchains (Claude CLI, Codex CLI, opencode CLI,
  `homer_langgraph`), but running them needs LLM credentials at run time, which
  the suite doesn't provision — and
  cron (1-minute granularity makes it flaky). The `/mcp` endpoint itself *is*
  covered — it is plain HTTP and needs no Claude. The read-only
  `GET /api/v1/agents/{name}/langgraph` inspect endpoint also needs no
  credentials and is covered by `LangGraphInspectTest` against graph modules
  the `langgraph-*` fixture agents ship; running a LangGraph command stays
  excluded. `/llm-events` is covered only for
  its `404` path (non-LLM commands); LLM command streaming stays excluded.
  The workspace purge sweep is excluded for the same reason as cron: the sweep
  runs on a 60-second tick against minute-granularity `purgeAfter` /
  `retain.recordFor` durations, which makes a black-box wait-and-assert flaky.
  Its lifecycle coverage lives in `WorkspacePurgeServiceTest` /
  `ProcessRepositoryTest`. Three of the five pre-start failure causes are
  excluded for the same flakiness reason: the claim timeout and both full
  queues need a claim that is accepted but never answered, or saturating the
  bounded wait/claim queues with concurrent runs — a timing race, not a
  black-box assertion. Their sentences are pinned by
  `PreStartFailureReasonTest` and the executor tests; the other two causes
  and the withholding of the blocking run's id from a caller who cannot see
  it are covered (`ConcurrencyAndLimitsTest`, `ProcessIsolationTest`).
- **External pull-mode runners are covered; ssh and kubernetes are not.**
  `ExternalRunnerTest` runs against two real `homer-runner` containers
  (`homer-runner` serving `e2e-external`, `homer-runner-crash` serving
  `e2e-crash`) registered with the fixture config's `e2e-ext` external
  domain. Both share the backend's network namespace, so they reach it on
  loopback `http://127.0.0.1:8080` without TLS. `RunnerCmdTest` only
  exercises `runner: local`; ssh and kubernetes runners aren't exercised by
  this suite. Runner selection by `runnerTags` against the local runner is
  covered by `RunnerSelectionTest`: the fixture config tags it via
  `server.localRunner.tags`.

## Running

Image builds resolve dependencies from Maven Central; no credentials are
needed. Optionally, exporting `ARTIFACTORY_URL` / `ARTIFACTORY_USER` / `ARTIFACTORY_TOKEN` routes
resolution through an Artifactory proxy instead; see [DOCKER.md § Artifactory
build secrets](../../DOCKER.md#artifactory-build-secrets-optional). Stack
topology (services, volumes, ports): [DOCKER.md § E2E test
stack](../../DOCKER.md#e2e-test-stack).

```bash
# Full run, exactly like CI (from the repo root). `compose run` starts the
# backend (healthcheck-gated) and returns the runner's exit code directly —
# `up --exit-code-from` does not reliably abort with the one-shot
# fixtures-init service in the stack.
docker compose -f docker-compose.e2e.yml build
docker compose -f docker-compose.e2e.yml run --name homer-e2e-runner e2e-runner
docker compose -f docker-compose.e2e.yml down -v

# Fast dev loop — backend and runners in docker, tests from the host / IDE:
# (18080 is the default host port; override by setting E2E_BACKEND_PORT
# before `up` and updating E2E_BASE_URL below to match)
docker compose -f docker-compose.e2e.yml up -d --build homer-backend homer-runner homer-runner-crash
E2E_BASE_URL=http://localhost:18080 E2E_TOKEN=e2e-token \
  JAVA_HOME=<path to a JDK 25> app/gradlew -p app :e2e:test

# The crash test kills homer-runner-crash for good (restart: "no") —
# recreate it before rerunning ExternalRunnerTest:
docker compose -f docker-compose.e2e.yml up -d --force-recreate homer-runner-crash

# homer-runner logs go to the container output:
docker compose -f docker-compose.e2e.yml logs homer-runner homer-runner-crash

# Server log (also on `docker logs homer-e2e-backend`):
docker exec homer-e2e-backend tail -f /app/workspace/app.log

# Per-request access log — the one to check for 4xx debugging:
docker exec homer-e2e-backend tail -f /app/workspace/api.log
```

`docker compose down -v` drops the named agents/workspace volumes — every
`up` starts from a pristine SQLite database and fresh fixture agents.
Tests must still not assume process ids start at 1.

## Fixtures

`fixtures/config/config.json` runs the server with deliberately small limits
(256 KiB request bodies, 64 KiB artifacts) so limit tests are cheap — but a
deliberately *large* `rateLimit` **and** `perCallerRateLimit`: the suite polls
statuses every 250 ms on a single bearer identity, and the default budgets
(1000/min global, 300/min per caller) starve a run. The
`globalEnv.PATH` entry pins `PATH` to a deterministic minimal value
(`/usr/local/bin:/usr/bin:/bin`), overriding the container's inherited
`PATH` — it is not load-bearing for `homer-ask` discovery: the workspace
env always has a `PATH` (`parentEnvAllowlist` defaults to it) even without
this entry, so deleting it would not break the questions/dispatch agents.

`fixtures/agents/` holds one purpose-built agent per behavior under test
(happy path, failure, locks, inputs, artifacts, questions, runnerCmd, …).
`StatusQueryTest`'s `/api/v1/costs*` coverage doesn't need a dedicated
fixture — it reuses the generic `echo` agent (`seedRuns()`) to generate
spend history. Agent CRUD
tests write to the agents volume at runtime — which is why the compose stack
copies fixtures into a named volume instead of bind-mounting the checkout.
