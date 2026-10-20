package homer.app.routes

import homer.app.apiJson
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.models.AgentTelemetry
import homer.app.models.Param
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.TelemetryExport
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Retry builds its own root span outside AgentRunStarter, so it has to stamp
 * the Langfuse trace name and the `traceTag` subject tags itself — otherwise a
 * retried run is invisible to the tag/name filters normal runs support.
 * Resume and debug share the same literal pattern in RunHandlers.kt.
 */
class RetryTraceAttributesTest {
    @Test
    fun `retry root span carries the agent name and traceTag subject tags`() =
        testApplication {
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
                        telemetry = AgentTelemetry(traceTags = listOf("mr")),
                    )
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "4211"),
                    owner = "alice",
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.OK, response.status)
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals("triage-decision", attributes.captured.get(AttributeKey.stringKey("langfuse.trace.name")))
            assertEquals(
                listOf("retry", "mr:4211"),
                attributes.captured.get(AttributeKey.stringArrayKey("langfuse.trace.tags")),
            )
        }

    @Test
    fun `retry of a muted agent produces no exported span`() =
        testApplication {
            // The bypass this file documents cuts both ways: because retry builds its own
            // root span, `telemetry.export = OFF` used to apply to normal runs only, and a
            // retry of a muted agent still exported a full trace. Now it goes through the
            // same startAgentRootSpan helper, so the real tracer is never asked for a span
            // and the mock below stays untouched.
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
                        telemetry = AgentTelemetry(export = TelemetryExport.OFF, traceTags = listOf("mr")),
                    )
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "4211"),
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            // Muting changes telemetry only — the retry itself still runs.
            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 0) { span.setAllAttributes(any<Attributes>()) }
            coVerify { mockProcessExecutor.execute(any(), any()) }
        }

    @Test
    fun `retry does not tag a value that was secret for the source run`() =
        testApplication {
            // Drift: MR_IID was `secret: true` when process 42 ran, has since been flipped
            // to `secret: false` and added to traceTags. Agent load only checks the CURRENT
            // flags, so nothing upstream stops it — the stored historical value would be
            // exported to Langfuse as a cleartext tag. The source run's snapshot is what
            // says otherwise. See ADR-0052.
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID", secret = false)),
                        telemetry = AgentTelemetry(traceTags = listOf("mr")),
                    )
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "tok-historical-secret"),
                    secretInputKeys = setOf("MR_IID"),
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.OK, response.status)
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(
                listOf("retry"),
                attributes.captured.get(AttributeKey.stringArrayKey("langfuse.trace.tags")),
                "a value that was secret for the source run must not become a tag",
            )
            // The same union also rides onto the new row, so the retried run's own
            // /processes/{id} response keeps masking the value it replayed.
            verify {
                mockProcessFactory.create(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    extraSecretInputKeys = setOf("MR_IID"),
                )
            }
        }

    @Test
    fun `retry keeps the source run's parent on the new row and on its root span`() =
        testApplication {
            // A replay is part of the flow that caused the source run (ADR-0058): the new
            // row and the trace both point at the source's parent, not at the source.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), parentProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
            assertEquals(42L, attributes.captured.get(AttributeKey.longKey("retry.of_process_id")))
        }

    @Test
    fun `resume keeps the source run's parent on the new row and on its root span`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/resume").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), parentProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
        }

    @Test
    fun `retry of a root run stays a root run`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(id = 42, status = ProcessStatus.FAILED, agentName = "triage-decision")
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), parentProcessId = null) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
        }

    @Test
    fun `retry keeps the source run's root on the new row and on its root span`() =
        testApplication {
            // A replay stays in the flow the source run belonged to (ADR-0058): the new row
            // carries the source's root, not the source's own id — otherwise every retry
            // would start a new flow and the flow view would fragment on each replay.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            // The root-visibility gate resolves root 7: a live row, visible to the
            // disabled authorizer, so the derived id stays 7.
            val rootRow =
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "triage-decision",
                )
            val mockProcessManager = mockk<ProcessManager>(relaxed = true)
            coEvery { mockProcessManager.getProcessInfo(7) } returns rootRow

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockProcessManager,
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        }

    @Test
    fun `resume keeps the source run's root on the new row and on its root span`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            // Same root-row stub as the retry counterpart: the gate must resolve root 7.
            val rootRow =
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "triage-decision",
                )
            val mockProcessManager = mockk<ProcessManager>(relaxed = true)
            coEvery { mockProcessManager.getProcessInfo(7) } returns rootRow

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockProcessManager,
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/resume").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        }

    @Test
    fun `retry of a run whose flow root the caller cannot see is stamped rootless`() =
        testApplication {
            // [information_disclosure / CWE-200] canRetry admits a caller who merely
            // holds RUN on the source run's agent — the flow root above it may belong
            // to somebody else. Stamping the source's root would publish that foreign
            // id through the new row, its HMR_ROOT_PROCESS_ID env and its span, the
            // disclosure the status responses already deny.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            val hiddenRootAuthorizer =
                mockk<Authorizer> {
                    every { canSee(any(), any(), any()) } returns false
                }

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = hiddenRootAuthorizer,
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            // No root on the row either: the replay starts a flow of its own rather
            // than carrying an id its caller was never entitled to see.
            verify {
                mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = null)
            }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        }

    @Test
    fun `resume of a run whose flow root the caller cannot see is stamped rootless`() =
        testApplication {
            // Same denial path as retry: the resuming caller may control the source run
            // without being able to see the flow root it points at.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            val hiddenRootAuthorizer =
                mockk<Authorizer> {
                    every { canSee(any(), any(), any()) } returns false
                }

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = hiddenRootAuthorizer,
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/resume").status)

            verify {
                mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = null)
            }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        }

    @Test
    fun `retry of a root run stays rootless`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(id = 42, status = ProcessStatus.FAILED, agentName = "triage-decision")
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            // No root on the row either: a replay of a flow's first run starts a flow.
            verify {
                mockProcessFactory.create(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = null,
                    rootProcessId = null,
                )
            }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        }
}
