---
status: accepted
date: 2026-04-10
deciders: Lukáš Vlk
---
# ADR-0006: Carve `agentdebug` out as a separate Gradle module

## Context and Problem Statement

Operators need to load and execute a Homer agent locally — without
booting the Ktor server, hitting a real Langfuse endpoint, or running
the full lifecycle machinery — so they can iterate on agent
definitions and reproduce production runs from a developer laptop or a
running container.

The natural place for this would be a `main()` inside the existing
`:app` module, since `:app` already owns every executor and helper the
debugger reuses. We chose instead to carve out a separate Gradle
subproject `:agentdebug`. This ADR captures why, so the next person
asking "why isn't this just a `main()` in `:app`?" gets the answer in
under a minute.

## Decision Drivers

- **Server JAR cleanliness.** The Ktor `homer-all-*.jar` should not
  drag in Clikt or any debug-only code; it ships to production.
- **Distinct deployable.** `agentdebug` is its own shadow JAR
  (`agentdebug-0.1.0-all.jar`) with its own `Main-Class`. Bundling it
  into `:app:shadowJar` would either silently grow the production
  artifact or require excluding paths from the shadow plugin — both
  fragile.
- **Reuse without inversion.** `agentdebug` should depend on `:app`,
  not the other way round. Production code must never reach back into
  debug stubs.
- **Explicit debug stubs.** A handful of production services
  (`FileOperations`, `LangfuseClient`, `RunAgentExecutor`) need
  debug-mode replacements (`DebugFileOperations`,
  `DebugLangfuseClient`, `SkipRunAgentExecutor`). Keeping these in
  their own module makes the substitution unambiguous and prevents
  accidental imports from production code paths.

## Considered Options

- **Option A — Separate `:agentdebug` Gradle subproject**, depends on
  `:app`, ships its own shadow JAR.
- **Option B — `main()` in `:app`** with a build flag or
  classpath trick to omit Clikt at production build time.
- **Option C — Standalone repo** for the debugger, vendoring or
  publishing the executor classes as a library.

## Decision Outcome

Chosen option: **"Option A — Separate `:agentdebug` Gradle
subproject"**, because it gives clean dependency direction
(`:agentdebug` → `:app`), keeps the production fat JAR free of debug
code, and makes the swapped-in stubs (`DebugFileOperations`,
`DebugLangfuseClient`, `SkipRunAgentExecutor`) syntactically
impossible to reach from `:app`.

The module declares its own deps explicitly because `:app` only
exposes them at `implementation` scope:
[`agentdebug/build.gradle.kts`](../../app/agentdebug/build.gradle.kts)
re-pulls `kotlinxSerialization`, `kotlinXSerializationYaml`,
`kotlinxCoroutines`, `slf4jSimple`, and `opentelemetryApi`.

A wrapper script
[`app/agentdebug.sh`](../../app/agentdebug.sh) is committed at the app
module root so the contributor-facing entry point is one path, not
"`java -jar agentdebug/build/libs/...`".

### Consequences

- Good: `:app:shadowJar` stays focused on the server payload; no
  Clikt, no debug stubs, no extra entry-point class on the classpath.
- Good: dependency direction is enforced by Gradle — production code
  can never accidentally import a `Debug*` stub.
- Good: the debugger can grow CLI surface (Clikt subcommands) without
  touching the server module's dependency graph.
- Bad: explicit transitive-dep duplication in
  `agentdebug/build.gradle.kts` is easy to forget when bumping
  versions; both modules currently track the same `libs.versions.toml`
  entries so the cost is small but real.
- Bad: two shadow JARs to build and version (`homer-all-*.jar` and
  `agentdebug-*-all.jar`); release docs and Docker builds need to
  know about both.

## More Information

- Module at [`app/agentdebug/`](../../app/agentdebug/), wrapper at
  [`app/agentdebug.sh`](../../app/agentdebug.sh).
- See [`app/agentdebug/README.md`](../../app/agentdebug/README.md) for
  CLI usage and the list of stub services.
