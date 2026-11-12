package homer.app.services

import homer.app.exceptions.CooldownKeyInvalidException
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.Agent

/**
 * Renders an agent's `cooldown.key` template into the subject its two cooldown windows
 * are counted per.
 *
 * The template is rendered **once**, at start, from the run's own env and resolved
 * inputs, and the result is stored on the process row inside the INSERT's transaction
 * — the same shape as the v13/v14 timestamps, and for the same reason: re-deriving it
 * later would read a definition that may have changed, or a `HMR_PROCESS_ID` that did
 * not exist when the row was created. See ADR-0051, ADR-0106.
 *
 * Every refusal here fails the run **before** any process row exists, so a key that
 * cannot be rendered never widens the bound to every subject.
 */
internal object CooldownKeyRenderer {
    /**
     * The longest rendered key the `cooldown_key` column and its index are asked to
     * hold. Local and fixed, not a database constraint: the refusal has to reach the
     * caller as a 400, and the column is caller-sized text (§12 rule 4).
     */
    const val MAX_KEY_LENGTH = 128

    /**
     * What a rendered key may contain. A key is a stored, indexed token compared by
     * exact equality, so the charset keeps it one: no whitespace to normalize, no
     * quote, `%` or `_` for a later LIKE to widen, and no brace that would let a
     * placeholder-looking *value* pass as an unrendered template.
     */
    private val allowedKeyChars = Regex("^[A-Za-z0-9._:/-]+$")

    private val compiler: TextCompiler = DefaultTextCompiler()

    /**
     * The rendered key for [agent] with [env] in scope, or null when it sets no key.
     * Throws [CooldownKeyInvalidException] when the template cannot produce one.
     *
     * [extraSecretInputKeys] carries the secret names a replay of **stored** inputs must
     * honour — what was secret for the source run stays secret for its retry, resume or
     * fire, whatever the definition says now (ADR-0052). The current definition's own
     * secret inputs are checked at load; this is the point-of-use backstop, not a
     * replacement for it.
     */
    fun render(
        agent: Agent,
        env: Map<String, String>,
        extraSecretInputKeys: Set<String> = emptySet(),
    ): String? {
        val cooldown = agent.cooldown ?: return null
        val template = cooldown.key ?: return null
        // A placeholder naming a secret must never render: the key is stored in plain text
        // on the row and in its index, outside the secret store. Checked against the
        // template's own placeholder names, not against the rendered text — the refusal then
        // names a name the agent author wrote and can never echo a value back. §12 rule 7.
        cooldown.keyRefs.firstOrNull { it in agent.secretEnvNames || it in extraSecretInputKeys }?.let { name ->
            throw CooldownKeyInvalidException(agent.name, "placeholder {{$name}} names a secret input")
        }
        cooldown.keyRefs.firstOrNull { it !in env }?.let { name ->
            throw CooldownKeyInvalidException(agent.name, "placeholder {{$name}} does not resolve from the run's env or inputs")
        }
        val rendered = compiler.replaceArguments(template, env)
        if (rendered.isBlank()) {
            throw CooldownKeyInvalidException(agent.name, "the rendered value is empty")
        }
        if (rendered.length > MAX_KEY_LENGTH) {
            throw CooldownKeyInvalidException(
                agent.name,
                "the rendered value is ${rendered.length} characters, the limit is $MAX_KEY_LENGTH",
            )
        }
        if (!allowedKeyChars.matches(rendered)) {
            throw CooldownKeyInvalidException(
                agent.name,
                "the rendered value uses characters outside ${allowedKeyChars.pattern}",
            )
        }
        return rendered
    }
}

/**
 * The rendered `cooldown.key` for a run with [inputs] resolved, or null when the agent
 * sets no key.
 *
 * Renders from `agent.env + inputs`: those are the two layers a key may name, and both
 * are in place before the process row exists. `HMR_AGENT_DIR` rides `agent.env`, so it
 * resolves here; `HMR_PROCESS_ID` does not exist yet and does not. See ADR-0106.
 */
internal fun Agent.renderCooldownKey(
    inputs: Map<String, String>,
    extraSecretInputKeys: Set<String> = emptySet(),
): String? = CooldownKeyRenderer.render(this, env + inputs, extraSecretInputKeys)
