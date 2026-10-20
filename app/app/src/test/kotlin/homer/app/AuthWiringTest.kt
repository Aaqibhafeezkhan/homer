package homer.app

import homer.app.infra.PasswordVerifier
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.HmacKeyConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

class AuthWiringTest {
    private val config = AppConfig(agentsSearchPath = "/agents", workspaceRootPath = "/workspace", databasePath = "/homer.db")

    private fun hexSha256(
        secret: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `configureAuth registers the HMAC provider over the body it captures, refusing an unsigned request`() =
        testApplication {
            val secret = "shh"
            val body = """{"event":"task.created"}"""
            val scheme = AuthConfig.Hmac(keys = mapOf("vikunja" to HmacKeyConfig(secret = secret)))
            var authNames = emptyList<String>()
            application {
                authNames = configureAuth(config, listOf(scheme), PasswordVerifier()).authNames
                routing {
                    authenticate(*authNames.toTypedArray()) {
                        post("/api/v1/agent/example") {
                            call.respond(HttpStatusCode.OK, call.receive<ByteArray>().toString(Charsets.UTF_8))
                        }
                    }
                }
            }

            val signed =
                client.post("/api/v1/agent/example") {
                    header("X-Homer-Signature", hexSha256(secret, body))
                    setBody(body)
                }
            val unsigned = client.post("/api/v1/agent/example") { setBody(body) }

            assertEquals(listOf("auth-hmac"), authNames)
            assertEquals(HttpStatusCode.OK, signed.status)
            assertEquals(body, signed.bodyAsText(), "the route still reads the body the capture stored")
            assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
        }
}
