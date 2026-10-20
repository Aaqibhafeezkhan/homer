package homer.app.runners

import homer.app.EnvConstants

/**
 * Produces the env overrides that make a workspace synced to [remoteProjectRoot]
 * usable on the remote host / pod. Translation is env-only (materialized files embed
 * no absolute local paths — see the design spec):
 *
 *  - substitute every occurrence of [serverProjectRoot] with [remoteProjectRoot] in
 *    all env values (fixes HMR_WORK_DIR / HMR_ARTIFACTS_DIR / HMR_MCP_CONFIG and
 *    PATH's .homer/bin; multiple occurrences in a single value are all rewritten),
 *  - override HMR_AGENT_DIR to [remoteAgentDir] (the agent dir is copied there;
 *    it lives outside projectRoot on the server so the prefix swap misses it),
 *  - override HMR_API_URL to [apiUrl] when set, so remote homer-ask / MCP callbacks
 *    reach Homer rather than 127.0.0.1.
 *
 * Returns ONLY changed/added keys; [homer.app.infra.Workspace.appendToEnv]
 * override-merges them onto the workspace env.
 */
class RemotePathTranslator(
    private val serverProjectRoot: String,
    private val remoteProjectRoot: String,
    private val remoteAgentDir: String,
    private val apiUrl: String?,
) {
    fun overrides(env: Map<String, String>): Map<String, String> =
        buildMap {
            env.forEach { (key, value) ->
                if (value.contains(serverProjectRoot)) {
                    put(key, value.replace(serverProjectRoot, remoteProjectRoot))
                }
            }
            put(EnvConstants.AGENT_DIR, remoteAgentDir)
            apiUrl?.takeIf { it.isNotBlank() }?.let { put(EnvConstants.API_URL, it) }
        }
}
