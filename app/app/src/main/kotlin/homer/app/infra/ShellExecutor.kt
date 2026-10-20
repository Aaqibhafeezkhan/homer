package homer.app.infra

import homer.app.models.CommandResult
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Interface for shell command execution
 */
interface ShellExecutor {
    /**
     * Execute a shell command
     * @param command List of command and arguments
     * @param stdOut Path to redirect stdout
     * @param stdErr Path to redirect stderr
     * @param stdIn Optional input to pipe to command
     * @param env Environment variables to set
     * @param timeout Maximum execution time
     * @param stdOutFilter Optional line filter applied to stdout before it is written
     *   to [stdOut]; null writes every byte. See ADR-0063.
     * @return Command result with exit code
     */
    suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String? = null,
        env: Map<String, String> = emptyMap(),
        timeout: Duration,
        stdOutFilter: OutputLineFilter? = null,
    ): CommandResult
}
