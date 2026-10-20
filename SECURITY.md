# Security Policy

## Supported Versions

Only the latest release is supported with security fixes.

## Reporting a Vulnerability

Please report security vulnerabilities privately through GitHub's
**Report a vulnerability** form:
https://github.com/livesport/homer/security/advisories/new. Do not open
public issues for security reports.

There is no bug bounty program.

## Accepted risks

Weaknesses a scanner will legitimately keep reporting, which the maintainers
have consciously decided not to fix. Each row names the decision record that
argues the trade-off — the ADR is the authority, this table is the index.

Record a risk here **at the same time** as the decision, not when a scanner
first raises it: the security intake automation reads this file and
`docs/adr/` to avoid opening a ticket for something already settled.

| Finding | Where | Decision | Why accepted |
|---|---|---|---|
| The output cap counts kept bytes, not raw process output: a command whose output is entirely dropped by `claudeDropSystemSubtypes` cannot trip `maxOutputBytes` (CWE-770, CVSS 5.3) | `LineFilteringOutputStream`, all four `ShellExecutor` backends | [ADR-0063](docs/adr/0063-filter-claude-stdout-at-write-time.md) | The run stays bounded by its `timeout` and the filter's memory is constant, so the failure mode is bounded CPU and I/O rather than exhaustion. Counting raw bytes would kill the runs the filter exists to keep alive. No confidentiality or integrity impact; reaching the path needs an authenticated `Action.RUN`. |
| A Claude session with more than 1 000 model calls is charged the spend endpoint's best figure observed at poll time while its later calls remain unpriced, so the recorded cost can understate actual spend (CWE-693/CWE-400, CVSS 2.3) | `LlmHarnessExecutor.accountAttempt` capped-poll branch | [ADR-0061](docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md), amendment 2026-09-16 | Every session figure priced from a capped poll is forced to an `incomplete:` `fallbackReason`, visible in the status API and spans, so the under-count is never silent, and no path charges below the best session figure observed. Lifting the cap would let crafted stdout drive the full poll schedule. Reaching the path needs an authenticated Claude run and favorable ingestion lag. |
| The one-shot E2E runner receives the Artifactory read credentials as container environment, visible to `docker inspect` and to every process in the container for its lifetime (CWE-526, CVSS 4.8) | `docker-compose.e2e.yml` `e2e-runner.environment` | [`DOCKER.md`](DOCKER.md) § Artifactory build secrets | No principal is crossed: the CI job that holds the pair starts the container itself, and the container runs this repo's own test suite, not agent code. A `/run/secrets` file plus an entrypoint shim would only hide the pair from `docker inspect` / `docker exec`, which need root-equivalent daemon access that can read the secret file or `/proc/1/environ` anyway; Gradle's test workers inherit the shim's env, so in-container code still sees it. Revisit if the runner ever executes untrusted code. |
| An ancestor directory of a packed file can be swapped for a symlink between the pack walk's containment check and `addFile`'s by-name open, so a file outside the upload root can enter a downloaded archive (same-UID TOCTOU; `NOFOLLOW_LINKS` binds only the final component) (CWE-59/CWE-367, CVSS 2.3) | `TarStreams.addDirContents` + `TarStreams.addFile` (pack path, all transports); `WorkspaceSync.uploadDirInto` + `uploadFile` (ssh SFTP upload) | [ADR-0047](docs/adr/0047-security-by-design-and-threat-model.md), amendment 2026-09-23 | No principal is crossed: the race needs a concurrent writer holding the runner's own UID, which can already read every file it could disclose. Closing it needs an `openat`-style dirfd walk plus an `fstat` on the opened handle, neither of which `java.nio` exposes portably (ADR-0047 rule 6). Escaping symlinks are skipped, the walk descends on the checked realpath, hard links are skipped, so the exposure is confidentiality-only at the runner's own UID. |
| A target or ancestor directory of an extracted file can be swapped for a symlink between the extract walk's containment check and its by-name open, so sync-down can write a file outside the workspace (same-UID TOCTOU; the directory branch must follow contained links) (CWE-59/CWE-367, CVSS 5.8) | `TarStreams.extractEntry` directory branch (Kubernetes sync-down, external-runner tar download) | [ADR-0047](docs/adr/0047-security-by-design-and-threat-model.md), amendment 2026-09-30 | No principal is crossed: the tar stream cannot create links (link entries are skipped), so the race needs a concurrent writer holding Homer's own UID, which can already write every file it could reach. Closing it needs an `openat`-style dirfd walk plus `fstat`, which `java.nio` does not expose portably; a point-of-use re-check only narrows the window and costs a realpath walk per entry. The single-file branch writes with `NOFOLLOW_LINKS`, so the residual is the directory branch only. |
| A caller cancelled while a LangGraph inspect/state interpreter runs keeps its permit, the child and its readers until the child exits or the 15 s timeout fires, because the wait is one blocking `waitFor` (CWE-772, CVSS 5.3) | `runBoundedGraphSubprocess` (`ProcessGraphInspectRunner`, `ProcessGraphStateRunner`) | [ADR-0073](docs/adr/0073-langgraph-topology-is-structured-data.md), amendment 2026-10-09 | Cancellation gives no amplification: an uncancelled caller holds the same single permit and interpreter for the same bounded time. Total work stays capped by the four permits per service and the 5 s queue shed, which cancellation cannot bypass, and the process-group reap still runs when the wait returns. No confidentiality or integrity impact; reaching the path needs an authenticated grant. |
| A deeply nested brace pattern exhausts the stack while `braces` compiles it, so whoever shapes a glob pattern can stall the process (CWE-674, CVSS 8.7) | `braces` 3.0.3 ← `micromatch` 4.0.8 ← `fast-glob` 3.3.1 ← `@next/eslint-plugin-next`, dev-only lint toolchain | [ADR-0088](docs/adr/0088-dependency-check-on-the-nvd-mirror.md), amendment 2026-10-17 | No patched `braces`, `micromatch`, `fast-glob` or `@next/eslint-plugin-next` release exists yet, and the package is a transitive development dependency, so it ships in no image (the console image carries only the `.next/standalone` trace). The only patterns it compiles are the repository's own ESLint globs — repository-controlled input, never untrusted data — so no principal is crossed. Revisit when a fixed release ships. |
