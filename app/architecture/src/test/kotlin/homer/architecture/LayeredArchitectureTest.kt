package homer.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoImportDeclaration
import kotlin.test.Test

/**
 * The dependency direction of app/CLAUDE.md "Layered architecture" and the package graph it implies.
 * Both rules read imports: a fully qualified reference in code is not seen.
 */
class LayeredArchitectureTest {
    @Test
    fun `layers depend only downward and on the shared kernel`() {
        val scope = Sources.appMain
        val index = DeclarationIndex(scope)
        val violations =
            scope.files.flatMap { file ->
                val from = Layer.of(file)
                file.imports.flatMap { import ->
                    index
                        .filesOf(import.name)
                        .filter { it.path != file.path }
                        .map { Layer.of(it) to it }
                        .filterNot { (to, _) -> from.mayDependOn(to) }
                        .distinctBy { (to, _) -> to }
                        .map { (to, target) ->
                            violation(import, "${from.label} imports ${to.label}-layer ${import.name} (${target.name}.kt)")
                        }
                }
            }
        assertNoViolations("Layers depend only downward (app/CLAUDE.md \"Layered architecture\")", violations)
    }

    @Test
    fun `top-level packages of homer app are free of cycles`() {
        val edges = PackageGraph.of(Sources.appMain.files)
        val violations =
            PackageGraph.feedbackEdges(edges).flatMap { (from, to) ->
                val cycle = PackageGraph.cycleThrough(edges, from, to).joinToString(" → ")
                val reverse = edges[to]?.get(from)?.size ?: 0
                edges.getValue(from).getValue(to).map { import ->
                    violation(
                        import,
                        "$from → $to closes the package cycle $cycle ($reverse import(s) the other way): ${import.name}",
                    )
                }
            }
        assertNoViolations("Top-level packages are free of cycles (app/CLAUDE.md \"Layered architecture\")", violations)
    }
}

/**
 * The import graph between the top-level packages of `homer.app` (`homer.app.(*)..`; the root package is
 * the HTTP wiring, which imports every layer, and is no slice).
 */
internal object PackageGraph {
    /** The shared kernel of app/CLAUDE.md "Layered architecture": usable by all, so it depends on no other slice. */
    private val KERNEL = setOf("models", "config", "exceptions")

    fun of(files: List<KoFileDeclaration>): Map<String, Map<String, List<KoImportDeclaration>>> =
        files
            .mapNotNull { file -> slice("${file.packagee?.name}.${file.name}")?.let { it to file } }
            .flatMap { (from, file) ->
                file.imports.mapNotNull { import -> slice(import.name)?.takeIf { it != from }?.let { Triple(from, it, import) } }
            }.groupBy({ it.first }, { it })
            .mapValues { (_, triples) -> triples.groupBy({ it.second }, { it.third }) }

    /**
     * Edges whose removal leaves the graph acyclic, chosen greedily inside each remaining cycle: first a
     * kernel slice's edge to a non-kernel slice, then the edge with the fewest imports (ties by name). The
     * edges reported are thus the cheapest ones to cut.
     */
    fun feedbackEdges(edges: Map<String, Map<String, List<Any>>>): List<Pair<String, String>> {
        val remaining = edges.mapValues { (_, targets) -> targets.mapValues { it.value.size }.toMutableMap() }.toMutableMap()
        val cut = mutableListOf<Pair<String, String>>()
        while (true) {
            val candidates =
                stronglyConnected(remaining).flatMap { component ->
                    component.flatMap { from ->
                        remaining[from].orEmpty().filterKeys { it in component }.map { (to, weight) -> Triple(from, to, weight) }
                    }
                }
            val next =
                candidates.minWithOrNull(
                    compareBy<Triple<String, String, Int>>({ (from, to) -> if (from in KERNEL && to !in KERNEL) 0 else 1 })
                        .thenBy { it.third }
                        .thenBy { it.first }
                        .thenBy { it.second },
                ) ?: return cut
            cut += next.first to next.second
            remaining.getValue(next.first).remove(next.second)
        }
    }

    /** `from → to → … → from`: the shortest way back from [to] in the full graph; `from → to` when there is none. */
    fun cycleThrough(
        edges: Map<String, Map<String, List<Any>>>,
        from: String,
        to: String,
    ): List<String> {
        val previous = mutableMapOf(to to to)
        val queue = ArrayDeque(listOf(to))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node == from) break
            edges[node].orEmpty().keys.sorted().filter { it !in previous }.forEach {
                previous[it] = node
                queue += it
            }
        }
        if (from !in previous) return listOf(from, to)
        val back = generateSequence(from) { node -> if (node == to) null else previous[node] }.toList().reversed()
        return listOf(from) + back
    }

    private fun slice(name: String): String? =
        name
            .removePrefix("homer.app.")
            .takeIf { name.startsWith("homer.app.") && it.contains('.') && it.first().isLowerCase() }
            ?.substringBefore('.')

    private fun stronglyConnected(graph: Map<String, Map<String, Int>>): List<Set<String>> {
        val nodes = (graph.keys + graph.values.flatMap { it.keys }).sorted()
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val components = mutableListOf<Set<String>>()

        fun visit(node: String) {
            index[node] = index.size
            low[node] = index.getValue(node)
            stack.addLast(node)
            graph[node].orEmpty().keys.sorted().forEach { next ->
                if (next !in index) {
                    visit(next)
                    low[node] = minOf(low.getValue(node), low.getValue(next))
                } else if (next in stack) {
                    low[node] = minOf(low.getValue(node), index.getValue(next))
                }
            }
            if (low[node] == index[node]) {
                val component = mutableSetOf<String>()
                do {
                    val member = stack.removeLast()
                    component += member
                } while (member != node)
                if (component.size > 1) components += component
            }
        }
        nodes.filter { it !in index }.forEach(::visit)
        return components
    }
}
