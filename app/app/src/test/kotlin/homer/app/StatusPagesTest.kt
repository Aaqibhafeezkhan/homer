package homer.app

import homer.app.exceptions.AgentException
import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.BodylessBadRequestException
import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.ClaudeCliVersionMismatchException
import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.ConfigurationException
import homer.app.exceptions.ContinuationNotFoundException
import homer.app.exceptions.ContinuationNotPendingException
import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.CsrfValidationException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchNotFoundException
import homer.app.exceptions.DispatchNotPendingException
import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.exceptions.InvocationDepthExceededException
import homer.app.exceptions.MaintenanceQueueFullException
import homer.app.exceptions.MaintenanceWaitTimeoutException
import homer.app.exceptions.MalformedLlmOutputException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.PayloadSchemaValidationException
import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.QuestionNotFoundException
import homer.app.exceptions.QuestionNotOpenException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.exceptions.RunnerOutputSequenceException
import homer.app.exceptions.RunnerRegistrationRejectedException
import homer.app.exceptions.RunnerTransferTimeoutException
import homer.app.exceptions.RunnerTransferTooLargeException
import homer.app.exceptions.TooManyConcurrentWaitsException
import homer.app.exceptions.TooManyOpenQuestionsException
import homer.app.exceptions.TooManyPendingContinuationsException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.TooManyRunnerInstancesException
import homer.app.exceptions.TooManyRunnerPollsException
import homer.app.exceptions.TooManyRunnerTransfersException
import homer.app.exceptions.TooManySessionPollsException
import homer.app.exceptions.ValidationException
import homer.app.exceptions.WorkspaceException
import homer.app.models.RunnerFilter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * L2 (security review 2026-07-12): 5xx responses must not leak internal detail
 * (paths, command strings, config) to the client; 4xx messages stay actionable.
 */
class StatusPagesTest {
    @Test
    fun `500 errors return a generic body without internal detail`() =
        testApplication {
            application {
                configureStatusPages()
                routing {
                    get("/boom") { throw AgentException("failed reading /etc/homer/secret.conf", agentName = "x") }
                }
            }
            val resp = client.get("/boom")
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("Internal server error"), "generic message expected")
            assertFalse(body.contains("/etc/homer/secret.conf"), "internal path must not leak")
        }

    @Test
    fun `4xx errors keep their actionable message`() =
        testApplication {
            application {
                configureStatusPages()
                routing {
                    get("/bad") { throw ValidationException("field 'name' is required") }
                }
            }
            val resp = client.get("/bad")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(resp.bodyAsText().contains("field 'name' is required"))
        }

    @Test
    fun `DispatchAgentNotFoundException maps to 404`() =
        testApplication {
            application {
                configureStatusPages()
                routing {
                    get("/boom") { throw DispatchAgentNotFoundException("b") }
                }
            }
            val resp = client.get("/boom")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            assertTrue(resp.bodyAsText().contains("Dispatch target agent 'b' not found"))
        }

    @Test
    fun `every HomerException maps to its documented status`() {
        val cases =
            listOf(
                QueryParamValidationException("bad", HttpStatusCode.NotAcceptable) to HttpStatusCode.NotAcceptable,
                BodylessBadRequestException() to HttpStatusCode.BadRequest,
                ValidationException("bad") to HttpStatusCode.BadRequest,
                PayloadSchemaValidationException("bad") to HttpStatusCode.UnprocessableEntity,
                CsrfValidationException("no header") to HttpStatusCode.Forbidden,
                AnotherProcessStillActive(1) to HttpStatusCode.Conflict,
                MaxParallelRunsExceededException("a", 1) to HttpStatusCode.TooManyRequests,
                MaxQueuedRunsExceededException("a", 1) to HttpStatusCode.TooManyRequests,
                ProcessManagerAtCapacityException(1) to HttpStatusCode.ServiceUnavailable,
                InvocationDepthExceededException("a", 1, listOf("a")) to HttpStatusCode.UnprocessableEntity,
                InvocationCycleDetectedException("a", listOf("a")) to HttpStatusCode.UnprocessableEntity,
                MalformedLlmOutputException("bad") to HttpStatusCode.BadGateway,
                ClaudeCliVersionMismatchException("1", "2") to HttpStatusCode.InternalServerError,
                CostCapExceededException("daily", 1.0, 2.0) to HttpStatusCode.TooManyRequests,
                CooldownActiveException("a", 5, "cooling down") to HttpStatusCode.TooManyRequests,
                QuestionNotFoundException("q") to HttpStatusCode.NotFound,
                QuestionNotOpenException("q", "ANSWERED") to HttpStatusCode.Conflict,
                QuestionsClosedForProcessException(1) to HttpStatusCode.Conflict,
                TooManyOpenQuestionsException(1, 1) to HttpStatusCode.TooManyRequests,
                TooManyConcurrentWaitsException(1, 1) to HttpStatusCode.TooManyRequests,
                DispatchAgentNotFoundException("a") to HttpStatusCode.NotFound,
                AgentNotDispatchableException("a") to HttpStatusCode.Forbidden,
                DispatchNotFoundException(1) to HttpStatusCode.NotFound,
                DispatchNotPendingException(1, "FIRED") to HttpStatusCode.Conflict,
                TooManyPendingDispatchesException(1) to HttpStatusCode.TooManyRequests,
                ContinuationNotFoundException(1) to HttpStatusCode.NotFound,
                ContinuationNotPendingException(1, "FIRED") to HttpStatusCode.Conflict,
                TooManyPendingContinuationsException(1) to HttpStatusCode.TooManyRequests,
                ClaimQueueFullException(1) to HttpStatusCode.TooManyRequests,
                MaintenanceQueueFullException(1) to HttpStatusCode.TooManyRequests,
                MaintenanceWaitTimeoutException(1.seconds) to HttpStatusCode.ServiceUnavailable,
                RunnerClaimTimeoutException(1.seconds, RunnerFilter.Alias("local"), "none", "none") to HttpStatusCode.ServiceUnavailable,
                RunnerRegistrationRejectedException("d", "no") to HttpStatusCode.UnprocessableEntity,
                TooManyRunnerInstancesException("d", 1) to HttpStatusCode.TooManyRequests,
                TooManyRunnerPollsException("i", 1) to HttpStatusCode.TooManyRequests,
                TooManySessionPollsException("i", 1) to HttpStatusCode.TooManyRequests,
                TooManyRunnerTransfersException("i") to HttpStatusCode.TooManyRequests,
                RunnerOutputSequenceException(1, 2) to HttpStatusCode.Conflict,
                RunnerTransferTooLargeException(1) to HttpStatusCode.PayloadTooLarge,
                RunnerTransferTimeoutException("d", 1) to HttpStatusCode.RequestTimeout,
                ConfigurationException("bad") to HttpStatusCode.InternalServerError,
                CommandExecutionException("bad", 1, "cmd") to HttpStatusCode.InternalServerError,
                AgentException("bad", "a") to HttpStatusCode.InternalServerError,
                WorkspaceException("bad") to HttpStatusCode.InternalServerError,
            )
        for ((exception, expected) in cases) {
            assertEquals(expected, homerExceptionStatus(exception), exception::class.simpleName)
        }
    }

    @Test
    fun `a bodyless bad request answers 400 with an empty body`() =
        testApplication {
            application {
                configureStatusPages()
                routing {
                    get("/bad") { throw BodylessBadRequestException() }
                }
            }
            val resp = client.get("/bad")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("", resp.bodyAsText())
        }
}
