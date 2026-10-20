package homer.app.runners.local

import homer.app.infra.LocalShellExecutor
import homer.app.infra.ShellExecutor
import homer.app.runners.RunnerSession
import java.nio.file.Path

class LocalRunnerSession(
    workdir: Path,
    maxOutputBytes: Long = Long.MAX_VALUE,
    override val runnerAlias: String = "local",
    override val env: Map<String, String> = emptyMap(),
) : RunnerSession {
    override val shell: ShellExecutor = LocalShellExecutor(workdir, maxOutputBytes)

    override fun close() {
        // No resources to release for local execution.
    }
}
