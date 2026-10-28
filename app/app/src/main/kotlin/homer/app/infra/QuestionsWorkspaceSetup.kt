package homer.app.infra

import homer.app.EnvConstants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * Materializes the question-asking tooling into a process workspace before
 * commands run: the `homer-ask` shell helper (prepended to `PATH` when the
 * workspace env defines one) and a `.homer/mcp.json` for manual Claude wiring
 * via `HMR_MCP_CONFIG` (only when the MCP server is enabled). Lives under
 * `{projectRoot}/.homer/`, which is deleted together with `workdir/` when the
 * run ends with `cleanAfterFinish` (it is run tooling, not output). See
 * ADR-0019 and AGENT.md "Asking the operator questions".
 */
class QuestionsWorkspaceSetup(
    private val mcpEnabled: Boolean,
) {
    fun materialize(workspace: Workspace) {
        val homerDir = workspace.config.projectRoot.resolve(HOMER_DIR)
        val binDir = homerDir.resolve("bin")
        binDir.createDirectories()

        val helper = binDir.resolve("homer-ask")
        writeAtomically(helper, helperScript, PosixFilePermissions.fromString("rwxr-xr-x"))

        val dispatchHelper = binDir.resolve("homer-dispatch")
        writeAtomically(dispatchHelper, dispatchHelperScript, PosixFilePermissions.fromString("rwxr-xr-x"))

        val resultHelper = binDir.resolve("homer-result")
        writeAtomically(resultHelper, resultHelperScript, PosixFilePermissions.fromString("rwxr-xr-x"))

        val joinHelper = binDir.resolve("homer-join")
        writeAtomically(joinHelper, joinHelperScript, PosixFilePermissions.fromString("rwxr-xr-x"))

        val tagHelper = binDir.resolve("homer-tag")
        writeAtomically(tagHelper, tagHelperScript, PosixFilePermissions.fromString("rwxr-xr-x"))

        val inboxHelper = binDir.resolve("homer-inbox")
        writeAtomically(inboxHelper, inboxHelperScript, PosixFilePermissions.fromString("rwxr-xr-x"))

        val env = mutableMapOf<String, String>()
        // Prepend the helper dir only when the composed env defines PATH.
        // Inventing a PATH when the operator excluded it would override a
        // container image's built-in PATH (k8s pod env wins over ENV PATH);
        // without PATH the helper is still reachable at its absolute path.
        workspace.config.env["PATH"]?.let { env["PATH"] = "$binDir:$it" }

        if (mcpEnabled) {
            val mcpConfig = homerDir.resolve("mcp.json")
            writeAtomically(mcpConfig, MCP_CONFIG_JSON)
            env[EnvConstants.MCP_CONFIG] = mcpConfig.toString()
        }
        workspace.appendToEnv(env)
    }

    /**
     * Temp-file + atomic rename: concurrent runs sharing a workspace path may
     * be executing the previous copy while this one materializes — a plain
     * truncate-rewrite would let their shell read a half-written file.
     */
    private fun writeAtomically(
        target: Path,
        content: String,
        permissions: Set<PosixFilePermission>? = null,
    ) {
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            tmp.writeText(content)
            permissions?.let { tmp.setPosixFilePermissions(it) }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private val helperScript: String by lazy {
        checkNotNull(this::class.java.classLoader.getResource("scripts/homer-ask.sh")) {
            "scripts/homer-ask.sh missing from resources"
        }.readText()
    }

    private val dispatchHelperScript: String by lazy {
        checkNotNull(this::class.java.classLoader.getResource("scripts/homer-dispatch.sh")) {
            "scripts/homer-dispatch.sh missing from resources"
        }.readText()
    }

    private val resultHelperScript: String by lazy {
        checkNotNull(this::class.java.classLoader.getResource("scripts/homer-result.sh")) {
            "scripts/homer-result.sh missing from resources"
        }.readText()
    }

    private val joinHelperScript: String by lazy {
        checkNotNull(this::class.java.classLoader.getResource("scripts/homer-join.sh")) {
            "scripts/homer-join.sh missing from resources"
        }.readText()
    }

    private val tagHelperScript: String by lazy {
        checkNotNull(this::class.java.classLoader.getResource("scripts/homer-tag.sh")) {
            "scripts/homer-tag.sh missing from resources"
        }.readText()
    }

    private val inboxHelperScript: String by lazy {
        checkNotNull(this::class.java.classLoader.getResource("scripts/homer-inbox.sh")) {
            "scripts/homer-inbox.sh missing from resources"
        }.readText()
    }

    companion object {
        /** Run tooling directory under `projectRoot`; also deleted by `Workspace.cleanup`. */
        const val HOMER_DIR = ".homer"

        /**
         * `${VAR}` placeholders are expanded by Claude Code itself when it
         * reads the `--mcp-config` file, so the callback token is read from
         * the process env at run time and never written to disk.
         */
        private val MCP_CONFIG_JSON =
            """
            {
              "mcpServers": {
                "homer-questions": {
                  "type": "http",
                  "url": "${'$'}{HMR_API_URL}/mcp/questions",
                  "headers": {
                    "Authorization": "Bearer ${'$'}{HMR_CALLBACK_TOKEN}"
                  }
                }
              }
            }
            """.trimIndent() + "\n"
    }
}
