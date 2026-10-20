import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.Files

/**
 * Renders the committed third-party attributions (`NOTICE` and the backend region of
 * `THIRD-PARTY-LICENSES.md`) from the jk1 dependency-license report. Policy: ADR-0056.
 *
 * The rendering functions take already-read text so they stay unit testable; only [render] and
 * [withEmbeddedFiles] touch the filesystem, and the Gradle tasks in `app/app/build.gradle.kts`
 * own the reads and writes of the committed files themselves.
 */
object ThirdPartyNotices {
    /** Heading used for a module the license report left without a named license. */
    const val UNKNOWN_LICENSE = "Unknown"

    /** jk1 writes this next to the per-jar extracted files — from `checkLicense`, not `generateLicenseReport`. */
    const val REPORT_FILE_NAME = "project-licenses-for-check-license-task.json"

    /** The appendix heading the `### <name>` license-text sections live under. */
    const val LICENSE_TEXTS_HEADING = "## License texts"

    private const val MAX_COPYRIGHT_LINES = 5

    private val LEGAL_FILE_NAME = Regex("""(?i)(LICEN[CS]E|NOTICE|COPYING).*""")

    // An attribution line opens with "Copyright" (optionally behind a (c)/©/Portions marker).
    // Matching "copyright" anywhere instead pulls in license boilerplate that also carries a
    // digit — "2. Grant of Copyright License…" from every embedded Apache-2.0 LICENSE — and
    // those crowd the real holders out of the first MAX_COPYRIGHT_LINES.
    private val COPYRIGHT_LINE = Regex("""(?i)^(\(c\)|©|portions)?\s*copyright\b""")
    private val NAMES_A_HOLDER = Regex("""(?i)\d|\(c\)|©""")

    // Everything up to and including the copyright marker, so what remains is the holder.
    private val COPYRIGHT_MARKER = Regex("""(?i)^(\(c\)|©|portions)?\s*copyright\s*(\(c\)|©)?[\s,:;.-]*""")

    // A holder named without a year or marker ("Copyright Eemeli Aro"). Requiring the lowercase
    // second letter is what still rejects the all-caps warranty boilerplate ("COPYRIGHT HOLDERS
    // BE LIABLE").
    private val HOLDER_NAME = Regex("""^[A-Z][a-z]""")

    // A license's "how to apply these terms" template, reproduced inside the license text itself:
    // <year>, <YEAR>, [yyyy], {yyyy}, [name of copyright owner]. Case-sensitive on purpose — a
    // placeholder is written all-lowercase or all-caps, so a bracketed holder ("[Ohad Shai]") and
    // an e-mail or URL in angle brackets are not mistaken for one.
    private val TEMPLATE_BRACKETS = Regex("""[<\[{](?:[a-z][a-z _-]*|[A-Z][A-Z _-]*)[>\]}]""")

    // The same template written without brackets. Kept apart from [TEMPLATE_BRACKETS] so it can be
    // case-insensitive — the GPL's own text spells it in caps — without loosening the bracket forms.
    private val TEMPLATE_PHRASE = Regex("""(?i)\byear name of author\b""")

    // Steward of a license text, never a holder of the dependency shipping that text.
    private val LICENSE_STEWARD = Regex("""(?i)free software foundation""")

    private val WHITESPACE_RUN = Regex("""\s+""")

    data class License(
        val name: String,
        val url: String?,
    )

    /** What the two Gradle tasks write (generate) or diff against the committed files (check). */
    data class Rendered(
        val notice: String,
        val inventoryDocument: String,
    )

    data class Module(
        val group: String,
        val artifact: String,
        val version: String,
        val licenses: List<License>,
        val projectUrl: String?,
        val copyrightLines: List<String> = emptyList(),
        val noticeTexts: List<String> = emptyList(),
    ) {
        val key: String get() = "$group:$artifact:$version"

        val name: String get() = "$group:$artifact"

        /** Where jk1 extracts this module's jar under a report dir — no group, hence [withEmbeddedFiles]'s check. */
        val jarDirName: String get() = "$artifact-$version.jar"

        /** Grouping heading: the full declared expression, so a dual-licensed module is listed once. */
        val licenseHeading: String
            get() = if (licenses.isEmpty()) UNKNOWN_LICENSE else licenses.joinToString(" OR ") { it.name }
    }

    /**
     * Renders both files from the jk1 report directories of every module that redistributes
     * dependencies, given the committed inventory document to fill the region of.
     *
     * Fails rather than publishing an incomplete attribution: on a module with no named license
     * (ADR-0056's `checkLicense` should have rejected it) and on a license type the
     * `## License texts` appendix has no section for.
     */
    fun render(
        reportDirs: List<File>,
        committedInventory: String,
        regionBegin: String,
        regionEnd: String,
        homerNoticeHeader: String,
    ): Rendered {
        val reports =
            reportDirs.map { dir ->
                val report = dir.resolve(REPORT_FILE_NAME)
                require(report.isFile) {
                    "License report not found at $report — run ./gradlew :app:checkLicense :agentdebug:checkLicense first."
                }
                parseReport(report.readText())
            }
        val modules = withEmbeddedFiles(merge(reports), reportDirs)

        val unlicensed = modules.filter { it.licenses.isEmpty() }
        require(unlicensed.isEmpty()) {
            "The license report names no license for ${unlicensed.map { it.key }}. checkLicense should " +
                "have rejected these — fix the dependency or the allowlist instead of attributing them as " +
                "'$UNKNOWN_LICENSE'."
        }

        val document = replaceRegion(committedInventory, regionBegin, regionEnd, renderInventory(modules))
        val missingHeadings = licenseHeadingsMissing(document, licenseNames(modules))
        require(missingHeadings.isEmpty()) {
            "The '## License texts' appendix has no '### <name>' section for ${missingHeadings.sorted()}. " +
                "Add each one, copying the text from a license file on disk."
        }
        return Rendered(renderNotice(homerNoticeHeader, modules), document)
    }

    /**
     * Parses jk1's `project-licenses-for-check-license-task.json`. License entries with no name
     * are dropped: the plugin emits one per license URL it could not label, and every such entry
     * seen so far duplicates a named sibling. A module left with no named license renders under
     * [UNKNOWN_LICENSE], which fails generation.
     */
    fun parseReport(json: String): List<Module> {
        val root = JsonSlurper().parseText(json) as Map<*, *>
        val dependencies = root["dependencies"] as? List<*> ?: emptyList<Any>()
        return dependencies.filterIsInstance<Map<*, *>>().map { dependency ->
            val moduleName = dependency["moduleName"] as String
            val licenses =
                (dependency["moduleLicenses"] as? List<*> ?: emptyList<Any>())
                    .filterIsInstance<Map<*, *>>()
                    .mapNotNull { license ->
                        (license["moduleLicense"] as? String)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { License(it, license["moduleLicenseUrl"] as? String) }
                    }.distinctBy { it.name }
            Module(
                group = moduleName.substringBeforeLast(':'),
                artifact = moduleName.substringAfterLast(':'),
                version = dependency["moduleVersion"] as String,
                licenses = licenses,
                projectUrl = (dependency["moduleUrls"] as? List<*>)?.firstOrNull() as? String,
            )
        }
    }

    /** Merges per-module reports into one set keyed by `group:artifact:version`, first occurrence wins. */
    fun merge(reports: List<List<Module>>): List<Module> =
        reports
            .flatten()
            .distinctBy { it.key }
            .sortedBy { it.key }

    /** Copies of [modules] carrying the copyright lines and NOTICE texts jk1 extracted into [reportDirs]. */
    fun withEmbeddedFiles(
        modules: List<Module>,
        reportDirs: List<File>,
    ): List<Module> {
        // jk1 names each extracted directory `<artifact>-<version>.jar`, with no group, so two
        // modules differing only in group would each read the other's legal files.
        val collisions =
            modules
                .groupBy { it.jarDirName }
                .filterValues { sharing -> sharing.map { it.name }.distinct().size > 1 }
        require(collisions.isEmpty()) {
            val clashes =
                collisions.entries.joinToString("; ") { (jarDirName, sharing) ->
                    "$jarDirName is claimed by ${sharing.map { it.key }.distinct().sorted().joinToString(" and ")}"
                }
            "Two modules resolve to the same extracted jar directory, so attribution would read the " +
                "wrong files: $clashes."
        }
        return modules.map { module ->
            val jarDirs =
                reportDirs
                    .map { it.resolve(module.jarDirName) }
                    .filter { it.isDirectory }
            // The same jar is extracted under every report dir, so dedupe — but key on the path
            // inside the jar, not the bare file name: a jar shipping both META-INF/NOTICE and
            // META-INF/licenses/NOTICE must contribute both, and losing one would be silent.
            val legalFiles =
                jarDirs
                    .flatMap { jarDir -> legalFilesIn(jarDir).map { pathInJar(jarDir, it) to it } }
                    .distinctBy { (pathInJar, _) -> pathInJar }
                    .map { (_, file) -> file }
            val noticeFiles = legalFiles.filter { it.name.startsWith("NOTICE", ignoreCase = true) }
            // Attributions come from NOTICE files whenever the jar ships one. A LICENSE file is the
            // license text, and its own headers — the FSF's copyright on the GPL, the GPL's "how to
            // apply" template — attribute the license, not the dependency quoting it.
            module.copy(
                copyrightLines =
                    copyrightLines(
                        noticeFiles.ifEmpty { legalFiles }.map { it.readText() },
                        fromLicenseText = noticeFiles.isEmpty(),
                    ),
                noticeTexts = noticeFiles.map { it.readText().trim() }.filter { it.isNotEmpty() },
            )
        }
    }

    private fun pathInJar(
        jarDir: File,
        file: File,
    ): String = file.relativeTo(jarDir).invariantSeparatorsPath

    /**
     * The license and notice files jk1 extracted from one jar, sorted by name so the rendered
     * output does not depend on filesystem listing order. Recursive: a few jars (sqlite-jdbc)
     * keep theirs under `META-INF/maven/<group>/<artifact>/` rather than `META-INF/` directly.
     */
    private fun legalFilesIn(jarDir: File): List<File> =
        jarDir
            .walkTopDown()
            // CWE-59: onEnter prunes symlinked dirs (stat() follows them); filter rejects symlinked leaves.
            // Both use Files.isSymbolicLink, which is lstat()-backed. See ADR-0047.
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && LEGAL_FILE_NAME.matches(it.name) }
            .sortedBy { pathInJar(jarDir, it) }
            .toList()

    /**
     * Extracts attribution lines from license/notice file contents: lines opening with `copyright`
     * that either carry a year or a `(c)`/`©` marker, or name a holder outright, so neither prose
     * such as "copyright notice" nor a license's own numbered clauses and warranty boilerplate are
     * mistaken for an attribution. Lines naming no holder at all, and a license template's
     * `<year>` / `[yyyy]` placeholders, are dropped too.
     *
     * Set [fromLicenseText] when the contents are license texts rather than NOTICE files: a
     * license's steward holds the copyright on the license, not on the dependency quoting it.
     */
    fun copyrightLines(
        fileContents: List<String>,
        fromLicenseText: Boolean = false,
    ): List<String> =
        fileContents
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .filter {
                COPYRIGHT_LINE.containsMatchIn(it) &&
                    (
                        NAMES_A_HOLDER.containsMatchIn(it) ||
                            HOLDER_NAME.containsMatchIn(COPYRIGHT_MARKER.replace(it, ""))
                    )
            }.filterNot { TEMPLATE_BRACKETS.containsMatchIn(it) || TEMPLATE_PHRASE.containsMatchIn(it) }
            .filterNot { COPYRIGHT_MARKER.replace(it, "").isBlank() }
            .filterNot { fromLicenseText && LICENSE_STEWARD.containsMatchIn(it) }
            .distinct()
            .take(MAX_COPYRIGHT_LINES)

    /** The individual license names in use, for the `## License texts` coverage check. */
    fun licenseNames(modules: List<Module>): Set<String> =
        modules
            .flatMap { module -> module.licenses.map { it.name }.ifEmpty { listOf(UNKNOWN_LICENSE) } }
            .toSet()

    /** Renders the generated region body: modules grouped under their license heading. */
    fun renderInventory(modules: List<Module>): String =
        modules
            .groupBy { it.licenseHeading }
            .toSortedMap()
            .entries
            .joinToString("\n\n") { (heading, grouped) ->
                val bullets =
                    grouped.sortedBy { it.key }.joinToString("\n") { module ->
                        val bullet = "- **${module.name}** ${module.version} — ${module.projectUrl ?: "no project URL"}"
                        (listOf(bullet) + module.copyrightLines.map { "  - $it" }).joinToString("\n")
                    }
                "#### $heading\n\n$bullets"
            }

    /** Renders `NOTICE`: [homerHeader], then every distinct dependency NOTICE text, `----` separated. */
    fun renderNotice(
        homerHeader: String,
        modules: List<Module>,
    ): String {
        // Deduped per notice file, not per module: a jar shipping two notices (a shaded library's
        // plus its own) yields a combined text no standalone notice can match, which reproduces
        // that standalone notice a second time.
        val notices =
            modules
                .sortedBy { it.key }
                .flatMap { it.noticeTexts }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.replace(WHITESPACE_RUN, " ") }
        return buildString {
            appendLine(homerHeader.trim())
            // No preamble without notices: it would announce attributions that do not follow.
            if (notices.isEmpty()) return@buildString
            appendLine()
            appendLine("This product bundles third-party software whose NOTICE files require the")
            appendLine("following attributions to be reproduced:")
            notices.forEach { notice ->
                appendLine()
                appendLine("----")
                appendLine()
                appendLine(notice)
            }
        }
    }

    /** Replaces the text between the [begin] and [end] marker lines of [document] with [body]. */
    fun replaceRegion(
        document: String,
        begin: String,
        end: String,
        body: String,
    ): String {
        // The body is built from dependency-supplied text, so a license file carrying a marker
        // string would otherwise move the region boundary on the next generate.
        require(!body.contains(begin) && !body.contains(end)) {
            "The generated body contains a region marker — a dependency's own legal files must not " +
                "be able to move the '$begin' / '$end' boundary."
        }
        val beginAt = document.indexOf(begin)
        require(beginAt >= 0) { "Marker '$begin' not found — the region markers must stay in the document verbatim." }
        val endAt = document.indexOf(end, beginAt + begin.length)
        require(endAt >= 0) { "Marker '$end' not found after '$begin' — the region markers must stay in the document verbatim." }
        return document.substring(0, beginAt + begin.length) + "\n\n" + body.trim() + "\n\n" + document.substring(endAt)
    }

    /**
     * The subset of [licenseNames] that has no `### <name>` heading in [document]'s
     * [LICENSE_TEXTS_HEADING] appendix. Scoped to the appendix: a `### ` line before it — a future
     * section heading, or one inside a quoted license text — reproduces no license text, so
     * counting it would pass coverage for a license the document never carries.
     */
    fun licenseHeadingsMissing(
        document: String,
        licenseNames: Set<String>,
    ): Set<String> {
        val headings =
            document
                .lineSequence()
                .dropWhile { it.trimEnd() != LICENSE_TEXTS_HEADING }
                .filter { it.startsWith("### ") }
                .map { it.removePrefix("### ").trim() }
                .toSet()
        return licenseNames.filterNot { it in headings }.toSet()
    }
}
