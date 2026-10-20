package homer.app.infra

import homer.app.models.MIN_SCREENED_SECRET_LENGTH
import kotlinx.serialization.json.JsonPrimitive

/**
 * Masks known secret values in free text before it is emitted to telemetry
 * (Langfuse spans) or logs. Built from the workspace env restricted to
 * `secretEnvNames`, which [WorkspaceConfigFactory] composes from three sources:
 * the agent's declared `secret: true` inputs, every `globalEnv` / `agent.env`
 * value the operator sourced from the host via `@SYSENV:`, and Homer's own
 * per-process `HMR_CALLBACK_TOKEN`.
 *
 * `@SYSENV:` counts as a secret declaration by construction — reaching into the
 * host environment for a value is how an operator forwards a credential, and the
 * composed value is exactly what the child process can echo. A `globalEnv` entry
 * with a literal value is *not* redacted: Homer cannot tell a token from a URL,
 * and redacting every global would replace ordinary strings with `***`
 * throughout telemetry. See SERVER.md's content-egress note.
 *
 * A name that is not an env key is resolved from the host environment instead.
 * `SysEnvResolver.secretNamesFor` contributes the host variable names a value
 * referenced, which for a composite like `"Bearer @SYSENV:TOKEN"` is the only
 * way the bare credential becomes a needle — the resolved env value is
 * `Bearer <token>`, and output holding just the token would match nothing.
 *
 * [from]'s `minNeedleLength` defaults to `1` (today's behavior: every non-empty
 * value is a needle) — see [ARTIFACT_STEM_MIN_NEEDLE_LENGTH] for the one caller
 * that must raise it.
 */
class SecretRedactor private constructor(
    private val secrets: List<String>,
) {
    /** Replaces every known secret value in [text] with `***`. Returns [text] unchanged when there are none. */
    fun redact(text: String): String = secrets.fold(text) { acc, secret -> acc.replace(secret, REDACTED) }

    /** Union of two redactors' needles — for callers composing two independently-built needle sources. */
    operator fun plus(other: SecretRedactor): SecretRedactor = SecretRedactor(secrets + other.secrets)

    companion object {
        const val REDACTED = "***"
        private const val MAX_ESCAPE_LEVELS = 2

        // Minimum needle length for ArtifactNameResolver's rendered-stem screen (layer 2,
        // defense in depth): a short @SYSENV-sourced global ("0", "1", "prod") would
        // otherwise match as a substring of nearly any stem — including the default
        // `cmd_{{HMR_CMD_INDEX}}` template — and hard-fail every command (§12:
        // availability is in scope). Not used by telemetry/tail redaction, which must
        // keep screening every non-empty secret value regardless of length.
        const val ARTIFACT_STEM_MIN_NEEDLE_LENGTH = MIN_SCREENED_SECRET_LENGTH

        /** No-op redactor (nothing flagged secret). */
        val NONE = SecretRedactor(emptyList())

        fun from(
            env: Map<String, String>,
            secretEnvNames: Set<String>,
            hostEnv: (String) -> String? = System::getenv,
            minNeedleLength: Int = 1,
        ): SecretRedactor {
            // Env first, host second: a workspace key shadows a same-named host
            // variable, and the workspace value is the one the child can echo.
            val values = secretEnvNames.mapNotNull { env[it] ?: hostEnv(it) }.filter { it.length >= minNeedleLength }
            if (values.isEmpty()) return NONE
            // Callers redact JSON-encoded text (JsonElement.toString()), where a secret
            // containing \n, ", \ etc. appears escaped — match those forms too, produced
            // by the same kotlinx escaper the encoding uses. Two levels: attribute
            // encoding, plus a JSON document carrying the secret stored as a string value.
            val needles =
                values.flatMap { secret ->
                    generateSequence(secret) { prev ->
                        JsonPrimitive(prev).toString().removeSurrounding("\"").takeIf { it != prev }
                    }.take(MAX_ESCAPE_LEVELS + 1).toList()
                }
            return SecretRedactor(needles)
        }
    }
}
