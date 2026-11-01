package homer.app.executors.llm

import homer.app.executors.addBoolOpt
import homer.app.executors.addRepeatableOpt
import homer.app.executors.addStringOpt
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.OpencodeCmdOptions

/**
 * Turns an [OpencodeCmdOptions] block into the opencode CLI's argv, `opencode run --format json`
 * plus whatever the block configures. [OpencodeHarness] and the agentdebug dry-run printer both
 * call it, so a printed dry run shows exactly the argv that would run.
 *
 * `{{VAR}}` placeholders (see AGENT.md) resolve here, against [env].
 */
class OpencodeArgvBuilder(
    private val textCompiler: TextCompiler = DefaultTextCompiler(),
) {
    fun build(
        cmd: String,
        opts: OpencodeCmdOptions,
        env: Map<String, String>,
    ): List<String> =
        buildList {
            add(cmd)
            add("run")

            addStringOpt("--model", opts.model)
            addStringOpt("--agent", opts.agent)
            addStringOpt("--dir", opts.dir)
            addRepeatableOpt("--file", opts.file)

            // Security: opt-in — auto-approves every permission the opencode
            // configuration does not explicitly deny (see OpencodeCmdOptions.auto).
            addBoolOpt("--auto", opts.auto)

            opts.options?.let { addAll(it) }
            add("--format")
            add("json")
            // No positional message: `opencode run` reads the prompt from stdin
            // when no message argument is given, as Codex's `-` sentinel arranges.
        }.map {
            // Resolve {{VAR}} placeholders in option values (see AGENT.md).
            textCompiler.replaceArguments(it, env)
        }
}
