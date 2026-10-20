package homer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SecuredRoutesTest {
    private val path = File(System.getProperty("homer.repoRoot"), "fixture/Routes.kt").path

    private val source =
        """
        package homer.app.routes

        fun Route.apiV1Routes(deps: ApiDeps) {
            route("/api/v1") {
                guarded(deps)
                route("/v2") {
                    shared(deps)
                }
                shared(deps)
            }
        }

        fun Route.guarded(deps: ApiDeps) {
            with(deps) {
                get("/admin") {
                    if (call.deniedUnlessAdmin(authorizer)) return@get
                }
                post("/visible") {
                    checkVisible(call, authorizer) // authorizer.canSee( in a comment does not count
                }
                delete("/logged") {
                    log(authorizer)
                    call.respond("authorizer.canSee(")
                }
            }
        }

        fun Route.shared(deps: ApiDeps) {
            get("/s") { call.respond(deps.version) }
        }

        private suspend fun checkVisible(
            call: ApplicationCall,
            authorizer: Authorizer,
        ): Boolean = authorizer.canSee(call.principalName(), null, "x")

        private fun log(authorizer: Authorizer) {
            println(authorizer)
        }
        """.trimIndent()

    @Test
    fun `the walk finds every handler with its full path, a builder mounted twice under both prefixes`() {
        val keys = SecuredRoutes.parse(mapOf(path to source)).handlers().map { it.key }

        assertEquals(
            listOf("GET /api/v1/admin", "POST /api/v1/visible", "DELETE /api/v1/logged", "GET /api/v1/v2/s", "GET /api/v1/s"),
            keys,
        )
    }

    @Test
    fun `a denying call or a helper making one gates, a bare authorizer mention does not`() {
        val routes = SecuredRoutes.parse(mapOf(path to source))

        val violations = routes.violations(routes.handlers(), publicEndpoints = emptySet())

        assertEquals(
            listOf(
                "DELETE /api/v1/logged does not call the Authorizer (no implicit gate)",
                "GET /api/v1/v2/s does not call the Authorizer (no implicit gate)",
                "GET /api/v1/s does not call the Authorizer (no implicit gate)",
            ),
            violations.map { it.text },
        )
        assertEquals(listOf(21, 29, 29), violations.map { it.line })
    }

    @Test
    fun `an allowlisted endpoint is not a finding`() {
        val routes = SecuredRoutes.parse(mapOf(path to source))

        val violations = routes.violations(routes.handlers(), setOf("DELETE /api/v1/logged", "GET /api/v1/s", "GET /api/v1/v2/s"))

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `a handler shape the walk cannot read fails closed`() {
        val unreadable =
            """
            package homer.app.routes

            fun Route.apiV1Routes(deps: ApiDeps) {
                get(deps.path) { call.respond("open") }
                webSocket("/ws") { send("x") }
                get("/fine") { authorizer.canSee(null, null, "x") }
            }
            """.trimIndent()
        val routes = SecuredRoutes.parse(mapOf(path to unreadable))

        val violations = routes.violations(routes.handlers(), emptySet())

        assertEquals(
            listOf(
                4 to "unrecognised route shape `get`: the Authorizer check cannot read this handler",
                5 to "unrecognised route shape `webSocket`: the Authorizer check cannot read this handler",
            ),
            violations.map { it.line to it.text },
        )
    }

    @Test
    fun `a connect handler is checked like any other method`() {
        val routes =
            SecuredRoutes.parse(
                mapOf(
                    path to
                        """
                        fun Route.apiV1Routes(deps: ApiDeps) {
                            connect("/tunnel") { call.respond("open") }
                            connect("/gated") { if (call.deniedUnlessAdmin(authorizer)) return@connect }
                        }
                        """.trimIndent(),
                ),
            )

        val violations = routes.violations(routes.handlers(), emptySet())

        assertEquals(listOf(2 to "CONNECT /tunnel does not call the Authorizer (no implicit gate)"), violations.map { it.line to it.text })
    }

    @Test
    fun `an expression-body builder is registered and walked`() {
        val routes =
            SecuredRoutes.parse(
                mapOf(
                    path to
                        """
                        fun Route.apiV1Routes(deps: ApiDeps) {
                            users(deps)
                        }

                        fun Route.users(deps: ApiDeps) =
                            route("/u") {
                                get("/open") { call.respond("x") }
                                get("/gated") { authorizer.canSee(null, null, "x") }
                            }
                        """.trimIndent(),
                ),
            )

        val violations = routes.violations(routes.handlers(), emptySet())

        assertEquals(listOf("GET /u/open", "GET /u/gated"), routes.handlers().map { it.key })
        assertEquals(listOf(7 to "GET /u/open does not call the Authorizer (no implicit gate)"), violations.map { it.line to it.text })
    }

    @Test
    fun `a call named like a registration on an explicit receiver is a finding, lambda or not`() {
        val routes =
            SecuredRoutes.parse(
                mapOf(
                    path to
                        """
                        fun Route.apiV1Routes(deps: ApiDeps) {
                            get("/in") {
                                val id = call.parameters.get("id")
                                authorizer.canSee(null, null, id)
                            }
                        }

                        fun Application.extra(routing: Routing) {
                            routing.get("/admin") { call.respond("open") }
                            routing.get("/x", ::handler)
                            routing.staticResources("/s", "assets")
                        }

                        private fun span(attributes: Attributes) {
                            attributes.put("k", "v")
                        }
                        """.trimIndent(),
                ),
            )

        val violations = routes.violations(routes.handlers(), emptySet())

        assertEquals(
            listOf(
                9 to "unrecognised route shape `.get`: the Authorizer check cannot read this handler",
                10 to "unrecognised route shape `.get`: the Authorizer check cannot read this handler",
                11 to "unrecognised route shape `.staticResources`: the Authorizer check cannot read this handler",
            ),
            violations.map { it.line to it.text },
        )
    }

    @Test
    fun `a callable-reference handler is checked through the function it names`() {
        val routes =
            SecuredRoutes.parse(
                mapOf(
                    path to
                        """
                        fun Route.apiV1Routes(deps: ApiDeps) {
                            get("/gated", ::gated)
                            get("/open", ::open)
                            get("/elsewhere", ::notInRoutes)
                        }

                        private suspend fun gated(call: ApplicationCall) {
                            if (call.deniedUnlessAdmin(authorizer)) return
                        }

                        private suspend fun open(call: ApplicationCall) = call.respond("open")
                        """.trimIndent(),
                ),
            )

        val violations = routes.violations(routes.handlers(), emptySet())

        assertEquals(listOf("GET /gated", "GET /open"), routes.handlers().map { it.key })
        assertEquals(
            listOf(
                3 to "GET /open does not call the Authorizer (no implicit gate)",
                4 to "unrecognised route shape `get`: the Authorizer check cannot read this handler",
            ),
            violations.map { it.line to it.text },
        )
    }

    @Test
    fun `a reference to an overloaded name is unattributed, and a half-gated overload set is no gate`() {
        val routes =
            SecuredRoutes.parse(
                mapOf(
                    path to
                        """
                        fun Route.apiV1Routes(deps: ApiDeps) {
                            get("/ref", ::handle)
                            get("/via") { check(call) }
                        }

                        private suspend fun handle(call: ApplicationCall) {
                            if (call.deniedUnlessAdmin(authorizer)) return
                        }

                        private suspend fun handle(call: ApplicationCall, id: Int) = call.respond(id)

                        private fun check(call: ApplicationCall) = authorizer.canSee(null, null, "x")

                        private fun check(id: Int) = id
                        """.trimIndent(),
                ),
            )

        val violations = routes.violations(routes.handlers(), emptySet())

        assertEquals(
            listOf(
                3 to "GET /via does not call the Authorizer (no implicit gate)",
                2 to "unrecognised route shape `get`: the Authorizer check cannot read this handler",
            ),
            violations.map { it.line to it.text },
        )
    }

    @Test
    fun `a helper typed through an import alias or typealias of a route type is not route-free`() {
        val routes =
            SecuredRoutes.parse(
                mapOf(
                    path to
                        """
                        import io.ktor.server.routing.Routing as R

                        typealias Mount = io.ktor.server.routing.Route

                        fun Route.apiV1Routes(deps: ApiDeps) {
                        }

                        private fun viaImport(r: R) {
                            r.get("/a") { }
                        }

                        private fun viaTypealias(m: Mount) {
                            m.get("/b") { }
                        }

                        private fun viaCast(x: Any) {
                            (x as Mount).post("/c") { }
                        }

                        private fun span(attributes: Attributes) {
                            attributes.put("k", "v")
                        }
                        """.trimIndent(),
                ),
            )

        val violations = routes.violations(routes.handlers(), emptySet())

        assertEquals(listOf(9, 13, 17), violations.map { it.line })
    }
}
