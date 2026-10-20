package homer.app.services

import homer.app.models.Action
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.Server
import homer.app.models.TokenConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorizerTest {
    private fun authz(vararg p: Pair<String, Authorizer.PrincipalGrants>) =
        Authorizer(principals = p.toMap(), agentGrants = emptyMap(), enabled = true)

    @Test
    fun `admin has every action on every agent`() {
        val a = authz("root" to Authorizer.PrincipalGrants(admin = true, grants = emptyList()))
        assertTrue(a.hasAction("root", Action.DEBUG, "anything"))
        assertNull(a.readableAgents("root"))
    }

    @Test
    fun `grant scopes action to matching agents`() {
        val a = authz("ci" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("deploy-*"), listOf(Action.RUN, Action.READ)))))
        assertTrue(a.hasAction("ci", Action.RUN, "deploy-web"))
        assertTrue(a.hasAction("ci", Action.READ, "deploy-web"))
        assertFalse(a.hasAction("ci", Action.DEBUG, "deploy-web"))
        assertFalse(a.hasAction("ci", Action.RUN, "other"))
    }

    @Test
    fun `unknown principal has nothing`() {
        val a = authz("ci" to Authorizer.PrincipalGrants(false, emptyList()))
        assertFalse(a.hasAction("ghost", Action.RUN, "x"))
    }

    @Test
    fun `disabled authorizer allows everything (unsecured mode)`() {
        val a = Authorizer(emptyMap(), emptyMap(), enabled = false)
        assertTrue(a.hasAction(null, Action.DEBUG, "x"))
        assertNull(a.readableAgents(null))
    }

    @Test
    fun `readableAgents returns the READ-granted patterns for a scoped principal`() {
        val a = authz("bob" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("deploy-*"), listOf(Action.READ)))))
        // Resolution against a concrete agent set is the caller's job; here we expose the raw READ patterns.
        assertTrue(a.readableAgents("bob")!!.contains("deploy-*"))
    }

    @Test
    fun `anyGrant is true for any action matching the agent, regardless of which one`() {
        val a = authz("ci" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("deploy-*"), listOf(Action.DEBUG)))))
        assertTrue(a.anyGrant("ci", "deploy-web"))
        assertFalse(a.anyGrant("ci", "other"))
    }

    @Test
    fun `anyGrant is true for admin and false for unknown principal`() {
        val a = authz("root" to Authorizer.PrincipalGrants(admin = true, grants = emptyList()))
        assertTrue(a.anyGrant("root", "anything"))
        assertFalse(a.anyGrant("ghost", "anything"))
    }

    @Test
    fun `anyGrant allows everything when the authorizer is disabled (unsecured mode)`() {
        val a = Authorizer(emptyMap(), emptyMap(), enabled = false)
        assertTrue(a.anyGrant(null, "x"))
    }

    @Test
    fun `owner match sees and controls without a read grant, but not retry without a run grant`() {
        val a = authz("bob" to Authorizer.PrincipalGrants(false, emptyList()))
        assertTrue(a.canSee("bob", "bob", "x"))
        assertTrue(a.canControl("bob", "bob", "x"))
        assertFalse(a.canRetry("bob", "bob", "x"))
    }

    @Test
    fun `read grant sees but needs a control grant to control`() {
        val a = authz("bob" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("x"), listOf(Action.READ)))))
        assertTrue(a.canSee("bob", "alice", "x"))
        assertFalse(a.canControl("bob", "alice", "x"))

        val withControl =
            authz("bob" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("x"), listOf(Action.READ, Action.CONTROL)))))
        assertTrue(withControl.canControl("bob", "alice", "x"))
    }

    @Test
    fun `retry needs both visibility and a run grant`() {
        val visibleViaRead =
            authz("bob" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("x"), listOf(Action.READ, Action.RUN)))))
        assertTrue(visibleViaRead.canRetry("bob", "alice", "x"))

        val visibleViaOwner = authz("bob" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("x"), listOf(Action.RUN)))))
        assertTrue(visibleViaOwner.canRetry("bob", "bob", "x"))

        val runOnlyNotVisible = authz("bob" to Authorizer.PrincipalGrants(false, listOf(Grant(listOf("x"), listOf(Action.RUN)))))
        assertFalse(runOnlyNotVisible.canRetry("bob", "alice", "x"))
    }

    @Test
    fun `admin can see, control, and retry regardless of owner or grants`() {
        val a = authz("root" to Authorizer.PrincipalGrants(admin = true, grants = emptyList()))
        assertTrue(a.canSee("root", "alice", "x"))
        assertTrue(a.canControl("root", "alice", "x"))
        assertTrue(a.canRetry("root", "alice", "x"))
    }

    @Test
    fun `disabled authorizer allows canSee, canControl, and canRetry for everyone`() {
        val a = Authorizer(emptyMap(), emptyMap(), enabled = false)
        assertTrue(a.canSee(null, "alice", "x"))
        assertTrue(a.canControl(null, "alice", "x"))
        assertTrue(a.canRetry(null, "alice", "x"))
    }

    @Test
    fun `no owner match and no read grant denies canSee, canControl, and canRetry`() {
        val a = authz("bob" to Authorizer.PrincipalGrants(false, emptyList()))
        assertFalse(a.canSee("bob", null, "x"))
        assertFalse(a.canControl("bob", null, "x"))
        assertFalse(a.canRetry("bob", null, "x"))
    }

    @Test
    fun `null principal and null owner cannot see, control, or retry (enabled)`() {
        val a = authz("bob" to Authorizer.PrincipalGrants(false, emptyList()))
        assertFalse(a.canSee(null, null, "some-agent"))
        assertFalse(a.canControl(null, null, "some-agent"))
        assertFalse(a.canRetry(null, null, "some-agent"))
    }

    @Test
    fun `canDispatchHop allows when root holds RUN on the target`() {
        val a =
            Authorizer.from(
                AppConfig(
                    server =
                        Server(
                            apiAuth =
                                AuthConfig.Bearer(
                                    tokens =
                                        mapOf(
                                            "alice" to TokenConfig(token = "t", grants = listOf(Grant(listOf("b"), listOf(Action.RUN)))),
                                        ),
                                ),
                        ),
                    agentsSearchPath = "unused",
                    workspaceRootPath = "unused",
                    databasePath = "unused",
                ),
            )
        assertTrue(a.canDispatchHop("alice", dispatchingAgent = "a", target = "b"))
    }

    @Test
    fun `canDispatchHop allows when agentGrants delegates A to the target even without root RUN`() {
        val a =
            Authorizer.from(
                AppConfig(
                    server =
                        Server(
                            apiAuth =
                                AuthConfig.Bearer(
                                    tokens =
                                        mapOf(
                                            "alice" to TokenConfig(token = "t", grants = listOf(Grant(listOf("a"), listOf(Action.RUN)))),
                                        ),
                                ),
                            agentGrants = mapOf("a" to listOf(Grant(listOf("b"), listOf(Action.RUN)))),
                        ),
                    agentsSearchPath = "unused",
                    workspaceRootPath = "unused",
                    databasePath = "unused",
                ),
            )
        // root can run a, not b; but agentGrants[a] delegates run on b
        assertTrue(a.canDispatchHop("alice", dispatchingAgent = "a", target = "b"))
    }

    @Test
    fun `canDispatchHop denies when neither root RUN nor agentGrants permit the target`() {
        val a =
            Authorizer.from(
                AppConfig(
                    server =
                        Server(
                            apiAuth =
                                AuthConfig.Bearer(
                                    tokens =
                                        mapOf(
                                            "alice" to TokenConfig(token = "t", grants = listOf(Grant(listOf("a"), listOf(Action.RUN)))),
                                        ),
                                ),
                        ),
                    agentsSearchPath = "unused",
                    workspaceRootPath = "unused",
                    databasePath = "unused",
                ),
            )
        assertFalse(a.canDispatchHop("alice", dispatchingAgent = "a", target = "b"))
    }

    @Test
    fun `canDispatchHop cron root (system colon cron) relies only on agentGrants`() {
        val cfg = { grants: Map<String, List<Grant>> ->
            AppConfig(
                server = Server(apiAuth = AuthConfig.Bearer(tokens = mapOf("alice" to TokenConfig(token = "t"))), agentGrants = grants),
                agentsSearchPath = "unused",
                workspaceRootPath = "unused",
                databasePath = "unused",
            )
        }
        // system:cron is not a principal in the map -> hasAction false -> only agentGrants applies
        assertTrue(
            Authorizer.from(cfg(mapOf("a" to listOf(Grant(listOf("b"), listOf(Action.RUN)))))).canDispatchHop("system:cron", "a", "b"),
        )
        assertFalse(Authorizer.from(cfg(emptyMap())).canDispatchHop("system:cron", "a", "b"))
    }

    @Test
    fun `canDispatchHop admin root and disabled mode both allow any hop`() {
        val admin =
            Authorizer.from(
                AppConfig(
                    server = Server(apiAuth = AuthConfig.Bearer(tokens = mapOf("root" to TokenConfig(token = "t", role = "admin")))),
                    agentsSearchPath = "unused",
                    workspaceRootPath = "unused",
                    databasePath = "unused",
                ),
            )
        assertTrue(admin.canDispatchHop("root", "a", "b"))
        val disabled = Authorizer(emptyMap(), emptyMap(), enabled = false)
        assertTrue(disabled.canDispatchHop(null, "a", "b"))
    }
}
