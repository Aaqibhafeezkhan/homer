package homer.app.services

import homer.app.models.Action
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.Principaled
import homer.app.models.flattenAuthSchemes

/**
 * Authorization authority for the grants model (ADR-0037). Built once from the
 * resolved config; all lookups are in-memory. `enabled = false` means no auth
 * schemes are configured (unsecured mode) — every check passes, matching the
 * pre-grants behavior.
 */
class Authorizer(
    private val principals: Map<String, PrincipalGrants>,
    private val agentGrants: Map<String, List<Grant>>,
    private val enabled: Boolean,
) {
    data class PrincipalGrants(
        val admin: Boolean,
        val grants: List<Grant>,
    )

    fun isAdmin(principal: String?): Boolean = !enabled || principals[principal]?.admin == true

    fun hasAction(
        principal: String?,
        action: Action,
        agentName: String,
    ): Boolean {
        if (!enabled) return true
        val p = principals[principal] ?: return false
        if (p.admin) return true
        return p.grants.any { it.matches(agentName, action) }
    }

    /** READ-granted agent-name patterns for [principal]; null = admin (no filter). */
    fun readableAgents(principal: String?): Set<String>? {
        if (!enabled || principals[principal]?.admin == true) return null
        val p = principals[principal] ?: return emptySet()
        return p.grants
            .filter { Action.READ in it.actions }
            .flatMap { it.agents }
            .toSet()
    }

    /** Whether [principal] holds ANY action grant on [agentName] (admin: always true). Used to filter agent listings and hide agent existence on detail reads. */
    fun anyGrant(
        principal: String?,
        agentName: String,
    ): Boolean = Action.entries.any { hasAction(principal, it, agentName) }

    /** Whether agent [agentName]'s own agentGrants permit dispatching to [target] (Plan 2). */
    fun agentCanDispatch(
        agentName: String,
        target: String,
    ): Boolean = agentGrants[agentName].orEmpty().any { it.matches(target, Action.RUN) }

    /**
     * Whether the hop `dispatchingAgent → target` is authorized: [root] holds RUN
     * on [target] (admin / disabled short-circuit via [hasAction]), OR
     * [dispatchingAgent] is delegated RUN on [target] via `server.agentGrants`.
     * The write-path counterpart of the read-side [canSee]. See ADR-0039.
     */
    fun canDispatchHop(
        root: String?,
        dispatchingAgent: String,
        target: String,
    ): Boolean = hasAction(root, Action.RUN, target) || agentCanDispatch(dispatchingAgent, target)

    /** Process-visibility union rule (ADR-0037): admin, owner match, or a READ grant. */
    fun canSee(
        principal: String?,
        owner: String?,
        agentName: String,
    ): Boolean = isAdmin(principal) || (owner != null && owner == principal) || hasAction(principal, Action.READ, agentName)

    /** Process-control union rule (ADR-0037): admin, owner match, or visible + a CONTROL grant. */
    fun canControl(
        principal: String?,
        owner: String?,
        agentName: String,
    ): Boolean =
        isAdmin(principal) ||
            (owner != null && owner == principal) ||
            (canSee(principal, owner, agentName) && hasAction(principal, Action.CONTROL, agentName))

    /** Process-retry rule (ADR-0037): visible AND a RUN grant (owner alone does not grant retry). */
    fun canRetry(
        principal: String?,
        owner: String?,
        agentName: String,
    ): Boolean = canSee(principal, owner, agentName) && hasAction(principal, Action.RUN, agentName)

    companion object {
        fun from(config: AppConfig): Authorizer {
            val schemes = flattenAuthSchemes(config.server.apiAuth)
            val enabled = schemes.isNotEmpty()
            val map = HashMap<String, PrincipalGrants>()

            fun add(
                name: String,
                p: Principaled,
            ) {
                map[name] = PrincipalGrants(admin = p.role == "admin", grants = p.grants)
            }
            schemes.forEach { s ->
                when (s) {
                    is AuthConfig.Bearer -> {
                        s.tokens.forEach { (n, c) -> add(n, c) }
                    }

                    is AuthConfig.Hmac -> {
                        s.keys.forEach { (n, c) -> add("hmac:$n", c) }
                    }

                    is AuthConfig.Password -> {
                        s.users.forEach { (n, c) -> add("session:$n", c) }
                    }

                    else -> {}
                }
            }
            return Authorizer(map, config.server.agentGrants, enabled)
        }
    }
}
