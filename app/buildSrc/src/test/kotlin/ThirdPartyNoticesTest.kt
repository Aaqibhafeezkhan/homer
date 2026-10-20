import ThirdPartyNotices.License
import ThirdPartyNotices.Module
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BEGIN = "<!-- BEGIN GENERATED: backend -->"
private const val END = "<!-- END GENERATED: backend -->"

// The copyright-line rule exists twice, here and in console/scripts/third-party-licenses.mjs.
// This file is the shared truth table both suites assert against, so a change to one generator's
// filter that the other does not follow fails whichever side drifted. Relative to the buildSrc
// project directory, which is a Gradle Test task's working directory.
private val COPYRIGHT_CASES_FILE = File("../config/third-party-copyright-cases.json")

class ThirdPartyNoticesTest {
    @TempDir
    lateinit var tempDir: File

    private fun reportDirWith(reportJson: String): File =
        tempDir
            .resolve("report-${reportJson.hashCode()}")
            .also { it.mkdirs() }
            .also { it.resolve(ThirdPartyNotices.REPORT_FILE_NAME).writeText(reportJson) }

    private fun mitReport(
        moduleName: String,
        version: String,
    ) = """
        {"dependencies":[{"moduleName":"$moduleName","moduleVersion":"$version","moduleUrls":[],
        "moduleLicenses":[{"moduleLicense":"MIT License","moduleLicenseUrl":null}]}]}
        """.trimIndent()

    private val appendixDocument =
        """
        $BEGIN
        stale
        $END

        ## License texts

        ### MIT License

        text
        """.trimIndent()

    private val twoModuleReport =
        """
        {
          "dependencies": [
            {
              "moduleName": "org.example:beta",
              "moduleVersion": "2.0",
              "moduleUrls": ["https://example.org/beta", "https://example.org/beta-mirror"],
              "moduleLicenses": [
                { "moduleLicense": "MIT License", "moduleLicenseUrl": "https://opensource.org/licenses/MIT" }
              ]
            },
            {
              "moduleName": "org.example:alpha",
              "moduleVersion": "1.0",
              "moduleUrls": [],
              "moduleLicenses": [
                { "moduleLicense": "Eclipse Public License - v 2.0", "moduleLicenseUrl": "https://eclipse.org/epl-2.0" },
                { "moduleLicense": null, "moduleLicenseUrl": "https://example.org/unnamed" },
                { "moduleLicense": "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1", "moduleLicenseUrl": null }
              ]
            }
          ],
          "importedModules": []
        }
        """.trimIndent()

    @Test
    fun `parseReport reads coordinates, the first project URL and only named licenses`() {
        val modules = ThirdPartyNotices.parseReport(twoModuleReport)

        assertEquals(2, modules.size)
        val beta = modules.single { it.artifact == "beta" }
        assertEquals("org.example", beta.group)
        assertEquals("2.0", beta.version)
        assertEquals("https://example.org/beta", beta.projectUrl)
        assertEquals(listOf(License("MIT License", "https://opensource.org/licenses/MIT")), beta.licenses)

        val alpha = modules.single { it.artifact == "alpha" }
        assertNull(alpha.projectUrl)
        assertEquals(
            listOf("Eclipse Public License - v 2.0", "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1"),
            alpha.licenses.map { it.name },
        )
    }

    @Test
    fun `merge keys modules by group artifact version`() {
        val shared = Module("g", "a", "1", listOf(License("MIT License", null)), null)
        val onlyInSecond = Module("g", "b", "1", listOf(License("MIT License", null)), null)

        val merged = ThirdPartyNotices.merge(listOf(listOf(shared), listOf(shared, onlyInSecond)))

        assertEquals(listOf("g:a:1", "g:b:1"), merged.map { it.key })
    }

    @Test
    fun `renderInventory groups by license, sorts, and renders a dual license as one expression`() {
        val modules =
            listOf(
                Module(
                    "org.example",
                    "zeta",
                    "1.0",
                    listOf(License("MIT License", null)),
                    "https://example.org/zeta",
                    copyrightLines = listOf("Copyright 2020 Zeta Authors", "Copyright (c) 2021 Someone Else"),
                ),
                Module("org.example", "alpha", "3.1", listOf(License("MIT License", null)), null),
                Module(
                    "ch.qos.logback",
                    "logback-core",
                    "1.5.38",
                    listOf(
                        License("Eclipse Public License - v 2.0", null),
                        License("GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1", null),
                    ),
                    "https://logback.qos.ch",
                ),
            )

        assertEquals(
            """
            #### Eclipse Public License - v 2.0 OR GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1

            - **ch.qos.logback:logback-core** 1.5.38 — https://logback.qos.ch

            #### MIT License

            - **org.example:alpha** 3.1 — no project URL
            - **org.example:zeta** 1.0 — https://example.org/zeta
              - Copyright 2020 Zeta Authors
              - Copyright (c) 2021 Someone Else
            """.trimIndent(),
            ThirdPartyNotices.renderInventory(modules),
        )
    }

    @Test
    fun `renderInventory groups a module with no named license under Unknown`() {
        val modules = listOf(Module("g", "a", "1", emptyList(), null))

        assertTrue(ThirdPartyNotices.renderInventory(modules).startsWith("#### Unknown\n"))
    }

    @Test
    fun `renderNotice emits only the Homer header when no dependency ships a notice`() {
        val modules = listOf(Module("g", "a", "1", listOf(License("MIT License", null)), null, noticeTexts = emptyList()))

        assertEquals("Homer\nCopyright 2026 Someone\n", ThirdPartyNotices.renderNotice("Homer\nCopyright 2026 Someone", modules))
    }

    @Test
    fun `renderNotice keeps one copy of notices that differ only in whitespace`() {
        val modules =
            listOf(
                Module("g", "a", "1", listOf(License("MIT License", null)), null, noticeTexts = listOf("Acme Corp\nCopyright 2020 Acme")),
                Module(
                    "g",
                    "b",
                    "1",
                    listOf(License("MIT License", null)),
                    null,
                    noticeTexts = listOf("Acme Corp\n  Copyright 2020   Acme  \n"),
                ),
                Module("g", "c", "1", listOf(License("MIT License", null)), null, noticeTexts = listOf("Other Corp")),
                Module("g", "d", "1", listOf(License("MIT License", null)), null, noticeTexts = emptyList()),
            )

        assertEquals(
            """
            Homer
            Copyright 2026 Someone

            This product bundles third-party software whose NOTICE files require the
            following attributions to be reproduced:

            ----

            Acme Corp
            Copyright 2020 Acme

            ----

            Other Corp
            """.trimIndent() + "\n",
            ThirdPartyNotices.renderNotice("Homer\nCopyright 2026 Someone", modules),
        )
    }

    @Test
    fun `renderNotice dedupes each notice file of a multi-notice module separately`() {
        // A jar that shades another library ships that library's notice next to its own. Deduping
        // the module's concatenated text would reproduce the shaded library's notice twice.
        val modules =
            listOf(
                Module("g", "standalone", "1", listOf(License("MIT License", null)), null, noticeTexts = listOf("Acme Corp")),
                Module(
                    "g",
                    "with-shaded-copy",
                    "1",
                    listOf(License("MIT License", null)),
                    null,
                    noticeTexts = listOf("Shaded Corp", "Acme Corp"),
                ),
            )

        val notice = ThirdPartyNotices.renderNotice("Homer", modules)

        assertEquals(1, Regex("^Acme Corp$", RegexOption.MULTILINE).findAll(notice).count(), notice)
        assertEquals(1, Regex("^Shaded Corp$", RegexOption.MULTILINE).findAll(notice).count(), notice)
    }

    @Test
    fun `withEmbeddedFiles keeps same-named notices from different subdirectories of one jar`() {
        val reportDir = tempDir.resolve("report-nested").also { it.mkdirs() }
        val jarDir = reportDir.resolve("zeta-1.0.jar")
        jarDir.resolve("META-INF/licenses").mkdirs()
        jarDir.resolve("META-INF/NOTICE").writeText("Outer notice")
        jarDir.resolve("META-INF/licenses/NOTICE").writeText("Nested notice")
        val module = Module("org.example", "zeta", "1.0", listOf(License("MIT License", null)), null)

        // The same report dir twice stands in for the :app / :agentdebug pair: cross-dir duplicates
        // still collapse, while the two distinct paths inside the jar both survive.
        val embedded = ThirdPartyNotices.withEmbeddedFiles(listOf(module), listOf(reportDir, reportDir)).single()

        assertEquals(listOf("Outer notice", "Nested notice"), embedded.noticeTexts)
    }

    // Places a symlinked legal file whose target holds sentinel text into a temp jar dir. Skips when
    // the build filesystem cannot create symlinks so the guard test does not become a flaky failure.
    private fun linkLegalFile(
        jarDir: File,
        linkName: String,
        target: File,
    ) {
        jarDir.resolve("META-INF").mkdirs()
        try {
            Files.createSymbolicLink(jarDir.resolve("META-INF/$linkName").toPath(), target.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "filesystem cannot create symlinks: ${e.message}")
        }
    }

    @Test
    fun `withEmbeddedFiles rejects a symlinked legal file, reading no attribution from it`() {
        // A symlinked NOTICE pointing outside the extracted jar dir would disclose the target's contents
        // into the committed notices if isFile alone (which follows symlinks) gated the read.
        val secret = tempDir.resolve("host-secret").also { it.writeText("Copyright 2020 Sentinel Secret") }
        val reportDir = tempDir.resolve("report-symlink-only").also { it.mkdirs() }
        val jarDir = reportDir.resolve("zeta-1.0.jar")
        linkLegalFile(jarDir, "NOTICE", secret)
        val module = Module("org.example", "zeta", "1.0", listOf(License("MIT License", null)), null)

        val embedded = ThirdPartyNotices.withEmbeddedFiles(listOf(module), listOf(reportDir)).single()

        assertEquals(emptyList(), embedded.noticeTexts)
        assertEquals(emptyList(), embedded.copyrightLines)
    }

    @Test
    fun `withEmbeddedFiles reads only the real legal file from a mixed dir, skipping the symlink`() {
        val secret = tempDir.resolve("host-secret-mixed").also { it.writeText("Copyright 2020 Sentinel Secret") }
        val reportDir = tempDir.resolve("report-symlink-mixed").also { it.mkdirs() }
        val jarDir = reportDir.resolve("zeta-1.0.jar")
        jarDir.resolve("META-INF").mkdirs()
        jarDir.resolve("META-INF/NOTICE").writeText("Zeta\nCopyright 2020 Zeta Authors")
        linkLegalFile(jarDir, "NOTICE-linked", secret)
        val module = Module("org.example", "zeta", "1.0", listOf(License("MIT License", null)), null)

        val embedded = ThirdPartyNotices.withEmbeddedFiles(listOf(module), listOf(reportDir)).single()

        assertEquals(listOf("Zeta\nCopyright 2020 Zeta Authors"), embedded.noticeTexts)
        assertEquals(listOf("Copyright 2020 Zeta Authors"), embedded.copyrightLines)
    }

    @Test
    fun `withEmbeddedFiles skips a symlinked directory, reading no attribution from its target`() {
        // A symlinked *directory* (e.g. META-INF -> a host dir) is not itself yielded as a leaf, and
        // its real children are not symlinks, so the leaf guard alone would let walkTopDown descend and
        // readText() their contents. onEnter must prune the symlinked directory before that recursion.
        val hostDir = tempDir.resolve("host-legal-dir").also { it.mkdirs() }
        hostDir.resolve("NOTICE").writeText("Copyright 2020 Sentinel Secret")
        val reportDir = tempDir.resolve("report-symlink-dir").also { it.mkdirs() }
        val jarDir = reportDir.resolve("zeta-1.0.jar").also { it.mkdirs() }
        try {
            Files.createSymbolicLink(jarDir.resolve("META-INF").toPath(), hostDir.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "filesystem cannot create symlinks: ${e.message}")
        }
        val module = Module("org.example", "zeta", "1.0", listOf(License("MIT License", null)), null)

        val embedded = ThirdPartyNotices.withEmbeddedFiles(listOf(module), listOf(reportDir)).single()

        assertEquals(emptyList(), embedded.noticeTexts)
        assertEquals(emptyList(), embedded.copyrightLines)
    }

    @Test
    fun `withEmbeddedFiles refuses two modules that resolve to the same extracted jar directory`() {
        val modules =
            listOf(
                Module("org.example", "zeta", "1.0", listOf(License("MIT License", null)), null),
                Module("com.other", "zeta", "1.0", listOf(License("MIT License", null)), null),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                ThirdPartyNotices.withEmbeddedFiles(modules, listOf(tempDir))
            }
        assertTrue(error.message!!.contains("zeta-1.0.jar"), error.message!!)
        assertTrue(error.message!!.contains("com.other:zeta:1.0"), error.message!!)
        assertTrue(error.message!!.contains("org.example:zeta:1.0"), error.message!!)
    }

    @Test
    fun `withEmbeddedFiles attributes no holder from a GPL license text`() {
        val reportDir = tempDir.resolve("report-gpl").also { it.mkdirs() }
        val jarDir = reportDir.resolve("zeta-1.0.jar")
        jarDir.resolve("META-INF").mkdirs()
        jarDir.resolve("META-INF/LICENSE").writeText(
            """
            The GNU General Public License (GPL) Version 2, June 1991

            Copyright (C) 1989, 1991 Free Software Foundation, Inc. 59 Temple Place, Boston, MA 02111-1307 USA

            How to Apply These Terms to Your New Programs

               Copyright (C)
               Copyright (C) <year> <name of author>
               Gnomovision version 69, Copyright (C) year name of author
            """.trimIndent(),
        )
        val module = Module("org.example", "zeta", "1.0", listOf(License("MIT License", null)), null)

        val embedded = ThirdPartyNotices.withEmbeddedFiles(listOf(module), listOf(reportDir)).single()

        assertEquals(emptyList(), embedded.copyrightLines)
    }

    @Test
    fun `withEmbeddedFiles reads attributions from NOTICE and ignores the license text beside it`() {
        val reportDir = tempDir.resolve("report-both").also { it.mkdirs() }
        val jarDir = reportDir.resolve("zeta-1.0.jar")
        jarDir.resolve("META-INF").mkdirs()
        jarDir.resolve("META-INF/LICENSE").writeText("Copyright (C) 1989, 1991 Free Software Foundation, Inc.")
        jarDir.resolve("META-INF/NOTICE").writeText("Zeta\nCopyright 2020 Zeta Authors")
        val module = Module("org.example", "zeta", "1.0", listOf(License("MIT License", null)), null)

        val embedded = ThirdPartyNotices.withEmbeddedFiles(listOf(module), listOf(reportDir)).single()

        assertEquals(listOf("Copyright 2020 Zeta Authors"), embedded.copyrightLines)
    }

    @Test
    fun `copyrightLines from a license text keeps a real holder but drops the license steward`() {
        val text =
            """
            Copyright (C) 1989, 1991 Free Software Foundation, Inc.
            Copyright 2001-2020 The Apache Software Foundation
            """.trimIndent()

        assertEquals(
            listOf("Copyright 2001-2020 The Apache Software Foundation"),
            ThirdPartyNotices.copyrightLines(listOf(text), fromLicenseText = true),
        )
        assertEquals(
            listOf(
                "Copyright (C) 1989, 1991 Free Software Foundation, Inc.",
                "Copyright 2001-2020 The Apache Software Foundation",
            ),
            ThirdPartyNotices.copyrightLines(listOf(text)),
        )
    }

    @Test
    fun `replaceRegion swaps the text between the markers and keeps the rest`() {
        val document =
            """
            # Title

            <!-- BEGIN -->
            stale body
            more stale body
            <!-- END -->

            trailer
            """.trimIndent()

        assertEquals(
            """
            # Title

            <!-- BEGIN -->

            fresh body

            <!-- END -->

            trailer
            """.trimIndent(),
            ThirdPartyNotices.replaceRegion(document, "<!-- BEGIN -->", "<!-- END -->", "fresh body"),
        )
    }

    @Test
    fun `replaceRegion rejects a document whose markers are missing or out of order`() {
        val missingEnd = "<!-- BEGIN -->\nbody\n"

        val error =
            assertFailsWith<IllegalArgumentException> {
                ThirdPartyNotices.replaceRegion(missingEnd, "<!-- BEGIN -->", "<!-- END -->", "fresh")
            }
        assertTrue(error.message!!.contains("<!-- END -->"), "message should name the missing marker: ${error.message}")

        val reversed = "<!-- END -->\nbody\n<!-- BEGIN -->\n"
        assertFailsWith<IllegalArgumentException> {
            ThirdPartyNotices.replaceRegion(reversed, "<!-- BEGIN -->", "<!-- END -->", "fresh")
        }
    }

    @Test
    fun `replaceRegion refuses a body carrying either marker`() {
        val document = "$BEGIN\nstale\n$END\n"

        listOf("body\n$BEGIN\nforged", "body\n$END\nforged").forEach { forged ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    ThirdPartyNotices.replaceRegion(document, BEGIN, END, forged)
                }
            assertTrue(error.message!!.contains("region marker"), error.message!!)
        }
    }

    @Test
    fun `licenseHeadingsMissing ignores a heading before the appendix`() {
        val document =
            """
            ### MIT License

            not the appendix — this is quoted above it

            ## License texts

            ### Apache License, Version 2.0

            text
            """.trimIndent()

        assertEquals(
            setOf("MIT License"),
            ThirdPartyNotices.licenseHeadingsMissing(document, setOf("MIT License", "Apache License, Version 2.0")),
        )
    }

    @Test
    fun `licenseHeadingsMissing reports only license names without a heading in the appendix`() {
        val document =
            """
            ## License texts

            ### MIT License

            text

            ### Apache License, Version 2.0

            text
            """.trimIndent()

        assertEquals(
            setOf("MIT-0"),
            ThirdPartyNotices.licenseHeadingsMissing(
                document,
                setOf("MIT License", "Apache License, Version 2.0", "MIT-0"),
            ),
        )
    }

    @Test
    fun `licenseNames collects the individual license names of every module`() {
        val modules =
            listOf(
                Module("g", "a", "1", listOf(License("MIT License", null)), null),
                Module(
                    "g",
                    "b",
                    "1",
                    listOf(License("Eclipse Public License - v 2.0", null), License("MIT License", null)),
                    null,
                ),
                Module("g", "c", "1", emptyList(), null),
            )

        assertEquals(
            setOf("MIT License", "Eclipse Public License - v 2.0", "Unknown"),
            ThirdPartyNotices.licenseNames(modules),
        )
    }

    @Test
    fun `render fills the region from the report and pulls copyright lines out of the extracted jar files`() {
        val reportDir = reportDirWith(mitReport("org.example:zeta", "1.0"))
        reportDir.resolve("zeta-1.0.jar/META-INF").mkdirs()
        reportDir.resolve("zeta-1.0.jar/META-INF/NOTICE").writeText("Zeta\nCopyright 2020 Zeta Authors\n")

        val rendered =
            ThirdPartyNotices.render(
                listOf(reportDir),
                appendixDocument,
                BEGIN,
                END,
                "Homer\nCopyright 2026 Someone",
            )

        assertTrue(
            rendered.inventoryDocument.contains(
                "- **org.example:zeta** 1.0 — no project URL\n  - Copyright 2020 Zeta Authors",
            ),
            rendered.inventoryDocument,
        )
        assertTrue(rendered.notice.contains("Zeta\nCopyright 2020 Zeta Authors"), rendered.notice)
    }

    @Test
    fun `render refuses a module the report left without a named license`() {
        val report =
            """
            {"dependencies":[{"moduleName":"org.example:zeta","moduleVersion":"1.0","moduleUrls":[],"moduleLicenses":[]}]}
            """.trimIndent()

        val error =
            assertFailsWith<IllegalArgumentException> {
                ThirdPartyNotices.render(listOf(reportDirWith(report)), appendixDocument, BEGIN, END, "Homer")
            }
        assertTrue(error.message!!.contains("org.example:zeta:1.0"), error.message!!)
    }

    @Test
    fun `render refuses a license type with no section in the appendix`() {
        val reportDir = reportDirWith(mitReport("org.example:zeta", "1.0"))
        val appendixWithoutMit = appendixDocument.replace("### MIT License", "### Apache License, Version 2.0")

        val error =
            assertFailsWith<IllegalArgumentException> {
                ThirdPartyNotices.render(listOf(reportDir), appendixWithoutMit, BEGIN, END, "Homer")
            }
        assertTrue(error.message!!.contains("MIT License"), error.message!!)
    }

    @Test
    fun `copyrightLines keeps an undated holder name but not boilerplate or a bracket template`() {
        val text =
            """
            Copyright Eemeli Aro <eemeli@gmail.com>
            Copyright [2018] [Ohad Shai]
            Copyright <YEAR> <COPYRIGHT HOLDER>
            Copyright [yyyy] [name of copyright owner]
            Copyright {yyyy} Foo
            COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM
            """.trimIndent()

        assertEquals(
            listOf("Copyright Eemeli Aro <eemeli@gmail.com>", "Copyright [2018] [Ohad Shai]"),
            ThirdPartyNotices.copyrightLines(listOf(text)),
        )
    }

    @Test
    fun `copyrightLines drops the how-to-apply placeholder whatever its case`() {
        val text =
            """
            Copyright (C) YEAR NAME OF AUTHOR
            Copyright (C) Year Name Of Author
            Copyright (C) 2001 Year One Ltd
            """.trimIndent()

        assertEquals(
            listOf("Copyright (C) 2001 Year One Ltd"),
            ThirdPartyNotices.copyrightLines(listOf(text)),
        )
    }

    @Test
    fun `copyrightLines agrees with the shared cross-generator cases`() {
        assertTrue(COPYRIGHT_CASES_FILE.isFile, "shared cases not found at ${COPYRIGHT_CASES_FILE.absolutePath}")
        @Suppress("UNCHECKED_CAST")
        val cases = JsonSlurper().parseText(COPYRIGHT_CASES_FILE.readText()) as List<Map<String, Any>>
        assertTrue(cases.size >= 16, "expected the full truth table, got ${cases.size} cases")

        cases.forEach { case ->
            val line = case["line"] as String
            val fromNotice = case["fromNotice"] as Boolean
            val kept = ThirdPartyNotices.copyrightLines(listOf(line), fromLicenseText = !fromNotice)
            assertEquals(
                if (case["keep"] as Boolean) listOf(line) else emptyList(),
                kept,
                "case '$line' (fromNotice=$fromNotice)",
            )
        }
    }

    @Test
    fun `copyrightLines keeps dated copyright lines, trimmed and deduplicated, up to five`() {
        val text =
            """
            Apache Commons Lang
            Copyright 2001-2020 The Apache Software Foundation

            This product includes software developed at
            copyright without a year or marker
            2. Grant of Copyright License. Subject to the terms and conditions of
            1. Redistributions of source code must retain the above copyright
            Copyright (C)
            Copyright (C) <year> <name of author>
            Copyright (c) Acme
              Copyright 2001-2020 The Apache Software Foundation
            Copyright 2001 A
            Copyright 2002 B
            Copyright 2003 C
            Copyright 2004 D
            Copyright 2005 E
            """.trimIndent()

        assertEquals(
            listOf(
                "Copyright 2001-2020 The Apache Software Foundation",
                "Copyright (c) Acme",
                "Copyright 2001 A",
                "Copyright 2002 B",
                "Copyright 2003 C",
            ),
            ThirdPartyNotices.copyrightLines(listOf(text)),
        )
    }
}
