package homer.runner

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("homer.runner")

/**
 * `homer-runner`: registers this machine as a pull-mode runner instance and
 * executes the runs it claims. Every option has an `HOMER_RUNNER_*` env var. The
 * token is read only from `HOMER_RUNNER_TOKEN`, never a flag, so it stays out of argv
 * (`ps`, `/proc/<pid>/cmdline`, shell history). CWE-522.
 */
class HomerRunnerCli(
    // Seams for tests (a test run must not leave a drain hook on its own JVM). Both are
    // declared before `harden` so a trailing lambda still binds to `harden`.
    private val addShutdownHook: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    private val healthCheck: (Path) -> Unit = RunnerHealth::check,
    // Seam for tests; production uses Dumpability.harden's own defaults.
    private val harden: () -> Dumpability.Result = Dumpability::harden,
) : CliktCommand(name = "homer-runner") {
    override fun help(context: Context) =
        """
        Homer external runner — claim runs from a Homer server and execute them here.

        homer-runner --url https://homer.internal --tags linux,gpu --concurrency 2 --root /var/homer-runner
        (token via HOMER_RUNNER_TOKEN)
        """.trimIndent()

    val url by option("--url", envvar = "HOMER_RUNNER_URL", help = "Homer base URL").required()
    val tags by option("--tags", envvar = "HOMER_RUNNER_TAGS", help = "Comma-separated tags this instance serves")
        .convert { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
        .default(emptyList())
    val concurrency by option("--concurrency", envvar = "HOMER_RUNNER_CONCURRENCY", help = "Runs at once").int().default(1)
    val root by option(
        "--root",
        envvar = "HOMER_RUNNER_ROOT",
        help = "Directory for per-run workspaces",
    ).path(canBeFile = false).default(Path.of("/var/homer-runner"))
    val name by option("--name", envvar = "HOMER_RUNNER_NAME", help = "Instance name shown in Homer (default: hostname)")
    val drainTimeout by option(
        "--drain-timeout",
        envvar = "HOMER_RUNNER_DRAIN_TIMEOUT",
        help = "Seconds to finish in-flight runs on SIGTERM or a failed health check",
    ).long().default(600)

    override fun run() {
        // Fail closed before the token read; see ADR-0078.
        hardenOrRefuse()
        val token =
            currentContext.readEnvvar("HOMER_RUNNER_TOKEN")?.takeIf { it.isNotBlank() }
                ?: throw UsageError("set the runner domain token in HOMER_RUNNER_TOKEN; it is not accepted as a flag")
        val client =
            try {
                HomerClient(url, token)
            } catch (e: IllegalArgumentException) {
                echo(e.message, err = true)
                throw ProgramResult(2)
            }
        val options = RunnerOptions(name ?: hostName(), tags, concurrency, root.toAbsolutePath(), drainTimeout.seconds)
        val agent = RunnerAgent(client, options, healthCheck = healthCheck)
        addShutdownHook(
            Thread {
                runBlocking { agent.drain() }
            }.apply { name = "homer-runner-drain" },
        )
        val code =
            try {
                runBlocking(Dispatchers.Default) { agent.run() }
                0
            } catch (e: RegistrationRejectedException) {
                echo(e.message, err = true)
                2
            } catch (e: RunnerUnhealthyException) {
                echo("homer-runner cannot work on this host: ${e.message}", err = true)
                3
            } catch (e: Exception) {
                echo("homer-runner failed: ${e.message}", err = true)
                1
            } finally {
                client.close()
            }
        throw ProgramResult(code)
    }

    /** Makes the JVM non-dumpable where it can; a failed attempt refuses to start. */
    private fun hardenOrRefuse() {
        when (val result = harden()) {
            is Dumpability.Result.Hardened -> logger.info("homer-runner JVM is non-dumpable")
            is Dumpability.Result.Skipped -> logger.warn("homer-runner JVM left dumpable: {}", result.reason)
            is Dumpability.Result.Failed -> throw CliktError("homer-runner refuses to start: ${result.reason}")
        }
    }
}

/**
 * Default instance name: this host's name, shaped so the server accepts it. Homer requires
 * `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$` and rejects the registration otherwise, and a raw
 * hostname need not match it — so a default the operator never typed cannot be what makes
 * the runner refuse to start. An explicit `--name` is passed through untouched and validated
 * by the server.
 */
internal fun hostName(): String =
    runCatching { InetAddress.getLocalHost().hostName }
        .getOrElse { "homer-runner" }
        .let(::sanitizeInstanceName)

internal fun sanitizeInstanceName(raw: String): String {
    fun Char.isAllowedStart() = code < 128 && isLetterOrDigit()
    val mapped = raw.map { if (it.isAllowedStart() || it in "_.-") it else '-' }.joinToString("")
    val trimmed = mapped.dropWhile { !it.isAllowedStart() }.take(64)
    return trimmed.ifEmpty { "homer-runner" }
}

fun main(args: Array<String>) = HomerRunnerCli().main(args)
