package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Denial-path guard for the GitHub publishing workflows (CWE-285). A tag or branch anyone with
 * write access can push must not publish a release image or the organization's Pages site, so
 * release.yml runs only on a protected ref and pages.yml only on the default branch. The upstream
 * pipeline gates the same jobs on a protected ref and the default branch. See ADR-0084.
 *
 * It also keeps the supply-chain steps of release.yml (ADR-0086): a pushed image is signed and
 * attested by its digest, and the release verifies both before it counts as done.
 */
class ReleaseWorkflowGateTest {
    private val release = ".github/workflows/release.yml"
    private val pages = ".github/workflows/pages.yml"

    /** The `if:` of job [job] in [workflow], or null when the job has none. */
    private fun jobCondition(
        workflow: String,
        job: String,
    ): String? {
        val lines = File(repoRoot, workflow).readLines()
        val start = lines.indexOfFirst { it == "  $job:" }
        assertTrue(start >= 0, "$workflow no longer defines job `$job`; move this guard with it rather than deleting it.")
        return lines
            .drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith("    ") || it.trimStart().startsWith("#") }
            .firstOrNull { it.startsWith("    if:") }
            ?.removePrefix("    if:")
            ?.trim()
    }

    @Test
    fun `release jobs run only on a protected ref`() {
        for (job in listOf("ci", "docs", "images", "release-notes")) {
            val condition = jobCondition(release, job)
            assertTrue(
                condition != null && condition.contains("github.ref_protected"),
                "$release job `$job` runs without `if: github.ref_protected` (got: $condition). An unprotected " +
                    "tag or branch could then publish an image under the release names (CWE-285).",
            )
        }
    }

    @Test
    fun `released images are signed, attested and verified after the push`() {
        val text = File(repoRoot, release).readText()
        val steps =
            listOf(
                "- name: Push",
                "format: cyclonedx-json",
                "cosign sign --yes \"\$IMAGE@\$DIGEST\"",
                "- name: Attest provenance",
                "sbom-path: sbom.cdx.json",
                "cosign verify \"\$IMAGE@\$DIGEST\"",
                "--predicate-type https://slsa.dev/provenance/v1",
                "--predicate-type https://cyclonedx.org/bom",
            )
        var from = 0
        for (step in steps) {
            val at = text.indexOf(step, from)
            assertTrue(
                at >= 0,
                "$release no longer has `$step` after the previous supply-chain step. Consumers verify the " +
                    "cosign signature, the SLSA provenance and the CycloneDX SBOM before deploying (ADR-0086).",
            )
            from = at + step.length
        }
        for (permission in listOf("id-token: write", "attestations: write", "artifact-metadata: write")) {
            assertTrue(text.contains(permission), "$release job `images` lacks `$permission`, so signing fails.")
        }
    }

    @Test
    fun `pages publishes a release's docs only when release yml attested the archive`() {
        assertInOrder(
            File(repoRoot, release).readText(),
            release,
            listOf("tar --format=ustar -czf homer-docs.tar.gz", "subject-path: homer-docs.tar.gz", "gh release create"),
        )
        assertInOrder(
            File(repoRoot, pages).readText(),
            pages,
            listOf(
                "persist-credentials: false",
                "gh release download",
                "gh attestation verify archive.tar.gz",
                "--signer-workflow \"\$REPO/.github/workflows/release.yml\"",
                "--source-ref \"refs/tags/\$tag\"",
                "--deny-self-hosted-runners",
                "node site/scripts/check-archive.mjs archive.tar.gz",
                "tar -xzf archive.tar.gz",
                "! -type f ! -type d",
            ),
        )
    }

    /** Fails unless every one of [steps] occurs in [text], each after the previous one. */
    private fun assertInOrder(
        text: String,
        workflow: String,
        steps: List<String>,
    ) {
        var from = 0
        for (step in steps) {
            val at = text.indexOf(step, from)
            assertTrue(
                at >= 0,
                "$workflow no longer has `$step` after the previous step. Anyone with write access can replace a " +
                    "release asset, so only an archive attested by release.yml for its tag may reach Pages (ADR-0087).",
            )
            from = at + step.length
        }
    }

    @Test
    fun `the release page lists every image the release publishes`() {
        val lines = File(repoRoot, release).readLines()
        val built = publishedImages(lines)
        val listed =
            lines
                .firstNotNullOfOrNull { Regex("""^\s+for entry in ([a-z0-9 :-]+); do$""").find(it)?.groupValues?.get(1) }
                ?.split(" ")
                ?.filter { it.isNotBlank() }
                ?.toSet()
        assertTrue(built.isNotEmpty(), "$release job `images` no longer has a matrix of `- image:` entries.")
        assertTrue(
            listed == built,
            "$release job `release-notes` lists $listed, but job `images` publishes $built. An image missing from " +
                "the release page reaches consumers without its digest and verify command (ADR-0086).",
        )
    }

    @Test
    fun `every published tag carries the matrix entry's tag suffix`() {
        val lines = File(repoRoot, release).readLines().map { it.trim() }
        assertTrue(
            "SUFFIX: \${{ matrix.tag-suffix }}" in lines,
            "$release step `Image tags` no longer reads the matrix entry's `tag-suffix` into SUFFIX.",
        )
        val outputs = lines.filter { it.startsWith("echo \"version=") || it.startsWith("echo \"moving=") }
        val tagged = outputs.filterNot { it.startsWith("echo \"moving=\"") }
        assertTrue(
            tagged.size == 3 && tagged.all { it.contains("\$SUFFIX\" >>\"\$GITHUB_OUTPUT\"") },
            "$release step `Image tags` writes $tagged; the tag version, the branch version and the moving branch " +
                "tag must each end with \$SUFFIX, or a variant entry (homer-app -slim) pushes over the full image's tag.",
        )
    }

    /** Each `- image:` matrix entry of [lines] as `IMAGE`, or `IMAGE:SUFFIX` when it sets a `tag-suffix`. */
    private fun publishedImages(lines: List<String>): Set<String> {
        val entries = mutableListOf<String>()
        for (line in lines) {
            val image = Regex("""^\s+- image: ([a-z0-9-]+)$""").find(line)?.groupValues?.get(1)
            val suffix = Regex("""^\s+tag-suffix: '?(-[a-z0-9]+)'?$""").find(line)?.groupValues?.get(1)
            when {
                image != null -> entries += image
                suffix != null && entries.isNotEmpty() -> entries[entries.lastIndex] += ":$suffix"
            }
        }
        return entries.toSet()
    }

    @Test
    fun `pages deploys only from the default branch`() {
        val condition = jobCondition(pages, "deploy")
        assertTrue(
            condition != null && condition.contains("github.event.repository.default_branch"),
            "$pages job `deploy` is not limited to the default branch (got: $condition). A manual run from " +
                "another branch could then publish arbitrary HTML on the organization's Pages site (CWE-285).",
        )
    }
}
