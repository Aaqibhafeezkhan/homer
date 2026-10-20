import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrapScoreTest {
    private val parser = CrapScore.Parser()

    @AfterTest
    fun close() = parser.close()

    private fun units(source: String) = CrapScore.units(parser.parse("Sample.kt", source.trimIndent()))

    private fun unit(
        source: String,
        name: String,
    ) = units(source).single { it.name == name }

    @Test
    fun `a straight-line function has complexity 1`() {
        assertEquals(1, unit("fun f() = 42", "f").complexity)
    }

    @Test
    fun `each decision point counts once`() {
        val source =
            """
            fun f(x: Int?, xs: List<Int>, flag: Boolean) {
                if (x == null) return
                if (x > 1 && flag || x < -1) println(x) else println(-x)
                when (x) {
                    1 -> println(1)
                    2, 3 -> println(2)
                    else -> println(0)
                }
                for (i in xs) println(i)
                while (flag) break
                do { } while (false)
                try { println() } catch (e: IllegalStateException) { } catch (e: Exception) { } finally { }
                val y = x ?: 0
            }
            """
        // if ×2, && + ||, two non-else when entries, for, while, do-while, 2 catches, elvis = 12
        assertEquals(13, unit(source, "f").complexity)
    }

    @Test
    fun `safe calls, not-null assertions and else branches add nothing`() {
        assertEquals(1, unit("fun f(s: String?) = s?.length!!.plus(1)", "f").complexity)
        assertEquals(2, unit("fun f(b: Boolean) { if (b) println(1) else println(2) }", "f").complexity)
    }

    @Test
    fun `a nested lambda is its own unit and its branches stay out of the parent`() {
        val units =
            units(
                """
                fun f(xs: List<Int>) = xs.map { if (it > 0) it else -it }
                """,
            )
        assertEquals(1, units.single { it.name == "f" }.complexity)
        assertEquals(2, units.single { it.name == "f.map@1" }.complexity)
    }

    @Test
    fun `units are named after their holder, class chain included`() {
        val names =
            units(
                """
                class Routes {
                    fun mount() {
                        get("/status/{id}") { println() }
                        val handler = { println() }
                        listOf(1).forEach { println(it) }
                    }
                    val size: Int
                        get() { return 1 }
                    init { println() }
                    fun get(path: String, body: () -> Unit) = body()
                }
                """,
            ).map { it.name }.toSet()
        assertTrue("Routes.mount" in names, names.toString())
        assertTrue("""Routes.mount.get("/status/{id}")@3""" in names, names.toString())
        assertTrue("Routes.mount.handler@4" in names, names.toString())
        assertTrue("Routes.mount.forEach@5" in names, names.toString())
        assertTrue("Routes.size.get" in names, names.toString())
        assertTrue("Routes.init" in names, names.toString())
    }

    @Test
    fun `coverage counts only the lines each unit owns`() {
        val units =
            units(
                """
                fun f(xs: List<Int>): Int {
                    val n = xs.size
                    return xs.sumOf {
                        it * 2
                    } + n
                }
                """,
            )
        // Line 4 sits inside the lambda (lines 3-5), so it is the lambda's alone; lines 2 and 6 are f's.
        val lines =
            mapOf(
                2 to CrapScore.LineCounts(missed = 0, covered = 3),
                4 to CrapScore.LineCounts(missed = 4, covered = 0),
                6 to CrapScore.LineCounts(missed = 1, covered = 0),
            )
        val (scored, unscored) = CrapScore.score("x/Sample.kt", units, lines)
        val f = scored.single { it.unit.name == "f" }
        val lambda = scored.single { it.unit.name == "f.sumOf@3" }
        assertEquals(0.75, f.coverage)
        assertEquals(0.0, lambda.coverage)
        assertEquals(0, unscored)
    }

    @Test
    fun `a unit without instrumented lines is unscored, a file Kover does not list is uncovered`() {
        val units = units("fun f() {\n}\nfun g() = 1")
        val (scored, unscored) = CrapScore.score("x/Sample.kt", units, mapOf(3 to CrapScore.LineCounts(0, 2)))
        assertEquals(listOf("g"), scored.map { it.unit.name })
        assertEquals(1, unscored)

        val (absent, none) = CrapScore.score("x/Sample.kt", units, null)
        assertEquals(listOf(0.0, 0.0), absent.map { it.coverage })
        assertEquals(0, none)
    }

    @Test
    fun `the score follows the CRAP formula and the threshold is exclusive`() {
        assertEquals(30.0, CrapScore.crap(5, 0.0))
        assertEquals(5.0, CrapScore.crap(5, 1.0))
        assertEquals(1.0 * 1.0 * 0.125 + 1, CrapScore.crap(1, 0.5))
        val atThreshold = CrapScore.Scored("p.kt", CrapScore.CodeUnit("f", 1, 1, 5, 0), 0.0, 30.0)
        assertEquals(emptyList(), CrapScore.Result(listOf(atThreshold), 0, 30.0).violations)
    }

    @Test
    fun `Kover XML lines are keyed by package path and file name`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="app">
              <package name="homer/app/x">
                <sourcefile name="Sample.kt">
                  <line nr="2" mi="1" ci="3" mb="0" cb="0"/>
                </sourcefile>
              </package>
            </report>
            """.trimIndent()
        assertEquals(
            mapOf("homer/app/x/Sample.kt" to mapOf(2 to CrapScore.LineCounts(1, 3))),
            CrapScore.coverageByFile(xml),
        )
        assertEquals("homer/app/x/Sample.kt", CrapScore.coverageKey(parser.parse("Sample.kt", "package homer.app.x\nfun f() = 1")))
    }

    @Test
    fun `outputs list violations worst first with stable distinct fingerprints`() {
        fun violation(
            name: String,
            line: Int,
            crap: Double,
        ) = CrapScore.Scored("app/x/A.kt", CrapScore.CodeUnit(name, line, line + 4, 8, 1), 0.0, crap)
        val result =
            CrapScore.Result(
                listOf(violation("f.map@3", 3, 72.0), violation("f.map@9", 9, 40.0), violation("g", 20, 12.0)),
                unscored = 2,
                threshold = 30.0,
            )

        val report = CrapScore.reportText(result).lines()
        assertEquals("CRAP report: 2 violation(s) over threshold 30 (3 units scored, 2 unscored)", report[0])
        assertEquals("    72.0  cc=8   cov=  0.0%  app/x/A.kt:3  f.map@3", report[1])
        assertEquals("violations=2\nscored=3\nthreshold=30\n", CrapScore.summary(result))

        val json = CrapScore.codeQualityJson(result)
        val fingerprints = Regex(""""fingerprint":"([0-9a-f]{40})"""").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(2, fingerprints.toSet().size, "two lambdas with one stripped name still get distinct fingerprints")
        assertTrue(""""description":"CRAP 72.0 (complexity 8, coverage 0.0%) > 30 in f.map@3"""" in json, json)
        assertTrue(""""severity":"major"""" in json && """"severity":"minor"""" in json, json)
        assertTrue(""""location":{"path":"app/x/A.kt","lines":{"begin":3,"end":7}}""" in json, json)
        // Moving the code keeps the fingerprint: the @line suffix is not part of it.
        val moved = CrapScore.Result(listOf(violation("f.map@30", 30, 72.0)), 0, 30.0)
        assertTrue(fingerprints.first() in CrapScore.codeQualityJson(moved))
        assertEquals("[]\n", CrapScore.codeQualityJson(CrapScore.Result(emptyList(), 0, 30.0)))
    }
}
