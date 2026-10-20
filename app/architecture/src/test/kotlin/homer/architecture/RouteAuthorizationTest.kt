package homer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Root CLAUDE.md §12 rule 3 and app/CLAUDE.md "Adding a new HTTP endpoint" step 3: every handler of a
 * secured `/api/v1` resource calls the `Authorizer` itself — there is no implicit gate.
 */
class RouteAuthorizationTest {
    @Test
    fun `every secured api v1 handler calls the Authorizer`() {
        val routes = SecuredRoutes.load()
        val handlers = routes.handlers()
        // Proves the walk reached the mounted tree, and keeps the allowlist from outliving its endpoints.
        assertEquals(PUBLIC_ENDPOINTS, PUBLIC_ENDPOINTS.filter { key -> handlers.any { it.key == key } }.toSet())
        val violations = routes.violations(handlers, PUBLIC_ENDPOINTS)
        assertNoViolations("Secured handlers call the Authorizer (root CLAUDE.md §12 rule 3)", violations)
    }

    private companion object {
        /** Secured endpoints open to any authenticated caller, each documented so in app/README.md "Endpoints". */
        val PUBLIC_ENDPOINTS =
            mapOf(
                "GET /api/v1/heartbeat" to "liveness ping with version: \"Any authenticated caller\"",
                "GET /api/v1/schemas/agent" to "the bundled agent JSON Schema: \"Any authenticated caller\"",
            ).keys
    }
}

/** One `get`/`post`/… handler block, with the full path its `route(…)` nesting gives it. */
internal data class RouteHandler(
    val key: String,
    val path: String,
    val line: Int,
    val body: String,
)

/**
 * The `Route.*` builders of `homer.app.routes` reachable from `apiV1Routes` — the tree mounted under the
 * operator `authenticate(…)` wrapper (Application.kt `configureRouting`) — read from code with comments
 * and string contents blanked ([KotlinText.codeOnly]).
 *
 * A handler is a trailing lambda (`get("/x") { … }`) or a callable reference to a function of the
 * package (`get("/x", ::handle)`, whose body is then the handler's; only when exactly one function, local
 * ones included, has that name). It is gated when its body makes a denying call — `authorizer.<method>(`
 * or `deniedUnlessAdmin(` — or calls a function of the package every overload of which makes one. All
 * overloads of a builder name are walked. The walk fails closed, each of these being a finding of its own:
 * - a handler-shaped call ([SHAPE]) in a `Route` or `Routing` extension — block or expression body —
 *   outside the handler bodies, that the walk did not attribute to a route block or a handler (a
 *   callable reference to a function outside the package, or to an ambiguous name, is one);
 * - a [SHAPE]-named call on an explicit receiver (`routing.get(…)`, `x.staticResources(…)`), with or
 *   without a trailing lambda, anywhere in the package except inside handler bodies and inside route-free
 *   functions: top-level ones whose signature and body name no `Route`, `Routing` or `Application` type,
 *   no import alias or typealias of one, and no `route`, `routing` or `application` identifier — so
 *   `attributes.put(…)` in a span helper is no finding, but a cast or `call.application` is.
 */
internal class SecuredRoutes private constructor(
    /** Every builder per name: overloads are all walked, so none escapes. */
    private val builders: Map<String, List<Builder>>,
    private val gateFunctions: Set<String>,
    /** Body code of each non-builder function of the package by name, overloads and local functions included. */
    private val functionBodies: Map<String, List<String>>,
    /** Each file's code ([KotlinText.codeOnly]), for the receiver-qualified scan. */
    private val files: Map<String, String>,
    /** Per file, the bodies of functions that cannot register a route (no Route/Routing/Application in the signature). */
    private val routeFree: Map<String, List<IntRange>>,
) {
    private data class Builder(
        val path: String,
        val source: String,
        val code: String,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    /** Openers the walk recognised (route blocks and handlers), and the handler bodies, per file path. */
    private val recognised = mutableMapOf<String, MutableSet<Int>>()
    private val handlerBodies = mutableMapOf<String, MutableList<IntRange>>()

    fun handlers(root: String = ROOT): List<RouteHandler> = mutableListOf<RouteHandler>().also { walk(root, "", it, mutableSetOf()) }

    fun gates(body: String): Boolean = DENYING.containsMatchIn(body) || gateFunctions.any { callPattern(it).containsMatchIn(body) }

    /** Ungated [handlers] outside [publicEndpoints], plus every handler-shaped call the walk could not read. */
    fun violations(
        handlers: List<RouteHandler>,
        publicEndpoints: Set<String>,
    ): List<Violation> =
        handlers.filter { it.key !in publicEndpoints && !gates(it.body) }.map { handler ->
            violation(handler.path, handler.line, "${handler.key} does not call the Authorizer (no implicit gate)")
        } +
            unrecognised().map { (path, line, shape) ->
                violation(path, line, "unrecognised route shape `$shape`: the Authorizer check cannot read this handler")
            }

    /**
     * Handler-shaped calls in any builder, outside handler bodies, that no walk attributed to a route or
     * handler, and every receiver-qualified registration in the package.
     */
    fun unrecognised(): List<Triple<String, Int, String>> {
        builders.keys.forEach { walk(it, "", mutableListOf(), mutableSetOf()) }
        val qualified =
            files.flatMap { (path, code) ->
                val skipped = handlerBodies[path].orEmpty() + routeFree[path].orEmpty()
                QUALIFIED
                    .findAll(code)
                    .filter { m -> skipped.none { m.range.first in it } }
                    .map { Triple(path, lineOf(code, it.range.first), "." + it.groupValues[1]) }
                    .toList()
            }
        return qualified +
            builders.values.flatten().distinctBy { it.path to it.bodyStart }.flatMap { builder ->
                val seen = recognised[builder.path].orEmpty()
                val inHandler = handlerBodies[builder.path].orEmpty()
                SHAPE
                    .findAll(builder.code.substring(0, minOf(builder.bodyEnd + 1, builder.code.length)))
                    .filter { it.range.first > builder.bodyStart && it.range.first !in seen && inHandler.none { r -> it.range.first in r } }
                    .map { Triple(builder.path, lineOf(builder.code, it.range.first), it.groupValues[1]) }
                    .toList()
            }
    }

    private fun walk(
        name: String,
        prefix: String,
        out: MutableList<RouteHandler>,
        visiting: MutableSet<String>,
    ) {
        val overloads = builders[name] ?: return
        // Keyed by prefix too: a builder mounted under two prefixes is walked, and checked, under both.
        val key = "$name@$prefix"
        if (!visiting.add(key)) return
        overloads.forEach { walkBlock(it, it.bodyStart + 1, it.bodyEnd, prefix, out, visiting) }
        visiting.remove(key)
    }

    private fun walkBlock(
        builder: Builder,
        from: Int,
        until: Int,
        prefix: String,
        out: MutableList<RouteHandler>,
        visiting: MutableSet<String>,
    ) {
        val code = builder.code
        var i = from
        while (i < until) {
            val match = CALL.find(code, i)?.takeIf { it.range.first < until } ?: return
            val callee = match.groupValues[1]
            val literal =
                match.groups[2]
                    ?.range
                    ?.let { builder.source.substring(it) }
                    .orEmpty()
            val open = match.range.last
            when {
                callee == "route" && code[open] == '{' -> {
                    recognised.getOrPut(builder.path) { mutableSetOf() } += match.range.first
                    val close = KotlinText.closingBrace(code, open)
                    walkBlock(builder, open + 1, close, prefix + literal, out, visiting)
                    i = close + 1
                }

                callee in HTTP_METHODS && code[open] == '{' -> {
                    recognised.getOrPut(builder.path) { mutableSetOf() } += match.range.first
                    val close = KotlinText.closingBrace(code, open)
                    handlerBodies.getOrPut(builder.path) { mutableListOf() } += open..close
                    out +=
                        RouteHandler(
                            key = "${callee.uppercase()} ${prefix + literal}",
                            path = builder.path,
                            line = lineOf(code, match.range.first),
                            body = code.substring(open, close + 1),
                        )
                    i = close + 1
                }

                callee in HTTP_METHODS && REFERENCE.matchAt(code, match.range.first) != null -> {
                    val reference = REFERENCE.matchAt(code, match.range.first)!!
                    val target = reference.groupValues[3]
                    val path =
                        reference.groups[2]
                            ?.range
                            ?.let { builder.source.substring(it) }
                            .orEmpty()
                    // A reference the walk cannot follow — unknown, or ambiguous between overloads or a
                    // same-named local function — stays unattributed, so the shape guard reports it.
                    functionBodies[target]?.singleOrNull()?.let { body ->
                        recognised.getOrPut(builder.path) { mutableSetOf() } += match.range.first
                        out +=
                            RouteHandler(
                                key = "${callee.uppercase()} ${prefix + path}",
                                path = builder.path,
                                line = lineOf(code, match.range.first),
                                body = body,
                            )
                    }
                    i = reference.range.last + 1
                }

                callee in builders.keys -> {
                    walk(callee, prefix, out, visiting)
                    i = open + 1
                }

                else -> {
                    i = match.range.first + callee.length
                }
            }
        }
    }

    companion object {
        private const val ROOT = "apiV1Routes"
        private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "connect")
        private const val SHAPES = "get|post|put|patch|delete|head|options|connect|route|method|webSocket|sse|staticResources|staticFiles"

        /** `name("literal") {`, `name {`, or `name(` — a route/handler opener or a builder call. */
        private val CALL = Regex("""(?<![.\w])(\w+)\s*(?:\(\s*"([^"]*)"[^(){}"]*\)\s*\{|\{|\()""")

        /** Anything that may register a handler; each one in a builder must be one the walk recognised. */
        private val SHAPE = Regex("""(?<![.\w])($SHAPES)\s*[({]""")

        /** `receiver.get(…` / `receiver.route {`: a possible registration on an explicit receiver, which the walk does not follow. */
        private val QUALIFIED = Regex("""\.\s*($SHAPES)\s*[({]""")

        /** `get("/x", ::handle)` / `get(::handle)`: a handler given as a callable reference. */
        private val REFERENCE = Regex("""(\w+)\s*\(\s*(?:"([^"]*)"\s*,\s*)?::(\w+)\s*\)""")

        /** Names through which a function can reach a route registration: the types, and the receivers' usual names. */
        private val ROUTE_NAMES = listOf("Route", "Routing", "Application", "route", "routing", "application")

        /** `import a.b.Routing as R` and `typealias R = …Route…`: further names for a route type, per the given code. */
        private fun routeAliases(code: String): Set<String> {
            val types = ROUTE_NAMES.take(3).joinToString("|")
            return Regex(
                """^import\s+[\w.]*\.(?:$types)\s+as\s+(\w+)""",
                RegexOption.MULTILINE,
            ).findAll(code).map { it.groupValues[1] }.toSet() +
                Regex("""\btypealias\s+(\w+)[^=\n]*=[^\n]*\b(?:$types)\b""").findAll(code).map { it.groupValues[1] }.toSet()
        }

        private val DENYING = Regex("""\bauthorizer\s*\.\s*\w+\s*\(|\bdeniedUnlessAdmin\s*\(""")
        private val BUILDER = Regex("""\bfun\s+(?:Route|Routing)\.(\w+)\s*\(""")
        private val FUNCTION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]*?\.)?(\w+)\s*\(""")

        private fun callPattern(name: String) = Regex("""(?<!\w)$name\s*\(""")

        /** The real `homer.app.routes` package. */
        fun load(): SecuredRoutes =
            parse(
                Sources.appMain.files
                    .filter { it.packagee?.name == "homer.app.routes" }
                    .associate { it.path to File(it.path).readText() },
            )

        /** Builders and gate functions of the given sources (file path → Kotlin source). */
        fun parse(sources: Map<String, String>): SecuredRoutes {
            val builders = mutableMapOf<String, MutableList<Builder>>()
            val bodies = mutableMapOf<String, MutableList<String>>()
            val routeFree = mutableMapOf<String, MutableList<IntRange>>()
            val files = sources.mapValues { (_, source) -> KotlinText.codeOnly(source) }
            // Aliases are collected package-wide: a typealias declared in one file is usable in all of them.
            val aliases = files.values.flatMap(::routeAliases)
            val routeNames = Regex("""\b(?:${(ROUTE_NAMES + aliases).joinToString("|")})\b""")
            sources.forEach { (path, source) ->
                val code = files.getValue(path)
                BUILDER.findAll(code).forEach { m ->
                    val start = KotlinText.bodyStart(code, m.range.last) ?: return@forEach
                    // An expression body (`= route("/u") { … }`) is walked like a block body.
                    builders.getOrPut(m.groupValues[1]) { mutableListOf() } += Builder(path, source, code, start, bodyEnd(code, start))
                }
                FUNCTION.findAll(code).forEach { m ->
                    if (BUILDER.matchAt(code, m.range.first) != null) return@forEach
                    val start = KotlinText.bodyStart(code, m.range.last) ?: return@forEach
                    val end = bodyEnd(code, start)
                    val body = code.substring(start, end)
                    bodies.getOrPut(m.groupValues[1]) { mutableListOf() } += body
                    // Top-level only: a local function may still reach a builder's Route through its closure.
                    // Route-free only when neither signature nor body names a route type, an alias of one, or
                    // a route/routing/application receiver, so a cast or `call.application` cannot hide one.
                    val topLevel = code.lastIndexOf('\n', m.range.first).let { !code[it + 1].isWhitespace() }
                    if (topLevel && !routeNames.containsMatchIn(code.substring(m.range.first, end))) {
                        routeFree.getOrPut(path) { mutableListOf() } += start..end
                    }
                }
            }
            // A name gates only if every function of that name makes a denying call: an overload that does not
            // would otherwise pass for the one that does.
            val gates = bodies.filterValues { overloads -> overloads.all { DENYING.containsMatchIn(it) } }.keys
            return SecuredRoutes(builders, gates, bodies, files, routeFree)
        }

        /** End of the body opening at [start]: its closing brace, or for an expression body the next unindented line. */
        private fun bodyEnd(
            code: String,
            start: Int,
        ): Int =
            if (code[start] == '{') {
                KotlinText.closingBrace(code, start)
            } else {
                Regex("""\n(?=\S)""").find(code, start)?.range?.first ?: code.length
            }
    }
}
