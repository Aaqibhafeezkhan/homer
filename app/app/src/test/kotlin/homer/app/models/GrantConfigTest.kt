package homer.app.models

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrantConfigTest {
    @Test
    fun `bearer token parses role admin`() {
        val yaml =
            """
            tokens:
              admin:
                token: "t"
                role: admin
            """.trimIndent()
        val bearer = Yaml.default.decodeFromString(AuthConfig.Bearer.serializer(), yaml)
        assertEquals("admin", bearer.tokens.getValue("admin").role)
        assertTrue(
            bearer.tokens
                .getValue("admin")
                .grants
                .isEmpty(),
        )
    }

    @Test
    fun `bearer token parses grants`() {
        val yaml =
            """
            tokens:
              ci:
                token: "t"
                grants:
                  - agents: ["deploy-*"]
                    actions: [run, read]
            """.trimIndent()
        val bearer = Yaml.default.decodeFromString(AuthConfig.Bearer.serializer(), yaml)
        val g =
            bearer.tokens
                .getValue("ci")
                .grants
                .single()
        assertEquals(listOf("deploy-*"), g.agents)
        assertEquals(listOf(Action.RUN, Action.READ), g.actions)
    }

    @Test
    fun `server parses agentGrants`() {
        val yaml =
            """
            apiAuth: !<unsecured> {}
            agentGrants:
              router:
                - agents: ["worker-*"]
                  actions: [run]
            """.trimIndent()
        val server = Yaml.default.decodeFromString(Server.serializer(), yaml)
        assertEquals(
            listOf(Action.RUN),
            server.agentGrants
                .getValue("router")
                .single()
                .actions,
        )
    }

    @Test
    fun `password user with a role other than admin is refused`() {
        // ADR-0037: a principal declares `role: admin` or grants, never another role.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                PasswordUserConfig(passwordHash = "irrelevant", role = "editor")
            }
        assertTrue(
            ex.message!!.contains("PasswordUserConfig.role must be 'admin'"),
            "expected the role guard message, got: ${ex.message}",
        )
    }

    @Test
    fun `password user with role admin is accepted`() {
        assertEquals("admin", PasswordUserConfig(passwordHash = "irrelevant", role = "admin").role)
    }

    @Test
    fun `legacy endpoints field still deserializes into the sentinel for rejection`() {
        val yaml =
            """
            tokens:
              old:
                token: "t"
                endpoints: ["*"]
            """.trimIndent()
        val bearer = Yaml.default.decodeFromString(AuthConfig.Bearer.serializer(), yaml)
        assertEquals(listOf("*"), bearer.tokens.getValue("old").endpoints)
        assertNull(bearer.tokens.getValue("old").role)
    }
}
