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
 * It also keeps the supply-chain steps of release.yml (ADR-0086): every image is published for
 * linux/amd64 and linux/arm64 as one index, the index and each platform image are signed and
 * attested by digest, and the release verifies both before it counts as done.
 */
class ReleaseWorkflowGateTest {
    private val release = ".github/workflows/release.yml"
    private val pages = ".github/workflows/pages.yml"

    /** The lines of job [job] in [workflow], its header excluded. */
    private fun jobLines(
        workflow: String,
        job: String,
    ): List<String> {
        val lines = File(repoRoot, workflow).readLines()
        val start = lines.indexOfFirst { it == "  $job:" }
        assertTrue(start >= 0, "$workflow no longer defines job `$job`; move this guard with it rather than deleting it.")
        return lines
            .drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith("    ") || it.trimStart().startsWith("#") }
    }

    /** The `if:` of job [job] in [workflow], or null when the job has none. */
    private fun jobCondition(
        workflow: String,
        job: String,
    ): String? =
        jobLines(workflow, job)
            .firstOrNull { it.startsWith("    if:") }
            ?.removePrefix("    if:")
            ?.trim()

    /** The names of every job in [workflow]. */
    private fun jobNames(workflow: String): List<String> {
        val lines = File(repoRoot, workflow).readLines()
        val jobs = lines.indexOf("jobs:")
        assertTrue(jobs >= 0, "$workflow has no `jobs:` block.")
        return lines.drop(jobs + 1).mapNotNull { Regex("""^  ([a-z][a-z0-9-]*):$""").find(it)?.groupValues?.get(1) }
    }

    @Test
    fun `release jobs run only on a protected ref`() {
        val jobs = jobNames(release)
        assertTrue(
            jobs.containsAll(listOf("ci", "docs", "image-tags", "image-builds", "images", "release-notes")),
            "$release defines jobs $jobs; a renamed job must keep this guard.",
        )
        for (job in jobs) {
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
        // Each platform image: the console's license gate first, then pushed by digest, then its own
        // SBOM, signature and provenance.
        assertSupplyChain(
            "image-builds",
            listOf(
                "- name: Build for the LGPL gate",
                "if: matrix.build.image == 'homer-console'",
                "--load -t homer-console:lgpl-gate",
                "- name: LGPL gate",
                "if: matrix.build.image == 'homer-console'",
                "docker run --rm --platform \"\$PLATFORM\" --entrypoint sh homer-console:lgpl-gate",
                "-name \"@img\" -o -name \"sharp\" -o -name \"libvips*\"",
                "refusing to push",
                "- name: Push",
                // The labels job `images` checks to bind each digest to its entry.
                "--label \"org.opencontainers.image.title=\$TITLE\"",
                "--label \"org.opencontainers.image.version=\$VERSION\"",
                "push-by-digest=true",
                "format: cyclonedx-json",
                "cosign sign --yes \"\$IMAGE@\$DIGEST\"",
                "- name: Attest provenance",
                "sbom-path: sbom-\${{ matrix.platform.arch }}.cdx.json",
            ),
        )
        // The index the release tags name: signed and attested by its own digest, then the index and
        // every platform image verified the way a consumer does.
        assertSupplyChain(
            "images",
            listOf(
                // Each platform digest must be this entry's build for that platform.
                "TITLE: \${{ matrix.image }}",
                "found=\$(find platform -name 'digest-*' | wc -l)",
                "[ \"\$found\" -eq 2 ]",
                "for arch in amd64 arm64; do",
                "digest=\$(cat \"platform/platform--\$arch--\$ENTRY/digest-\$arch\")",
                "[ \"\$(jq -r '.architecture' <<<\"\$config\")\" = \"\$arch\" ] &&",
                "[ \"\$(jq -r '.config.Labels[\"org.opencontainers.image.title\"]' <<<\"\$config\")\" = \"\$TITLE\" ] &&",
                "[ \"\$(jq -r '.config.Labels[\"org.opencontainers.image.version\"]' <<<\"\$config\")\" = \"\$VERSION\$SUFFIX\" ] ||",
                "docker buildx imagetools create",
                "cosign sign --yes \"\$IMAGE@\$DIGEST\"",
                "- name: Attest provenance",
                "subject-digest: \${{ steps.index.outputs.digest }}",
                "sbom-path: platform/platform--amd64--\${{ matrix.image }}\${{ matrix.tag-suffix }}/sbom-amd64.cdx.json",
                "sbom-path: platform/platform--arm64--\${{ matrix.image }}\${{ matrix.tag-suffix }}/sbom-arm64.cdx.json",
                "for digest in \"\$DIGEST\" \$PLATFORMS; do",
                "cosign verify \"\$IMAGE@\$digest\"",
                "--predicate-type https://slsa.dev/provenance/v1",
                "--predicate-type https://cyclonedx.org/bom",
            ),
        )
        val gateConditions = jobLines(release, "image-builds").filter { it.startsWith("        if:") }.map { it.trim() }
        assertTrue(
            gateConditions.all { it == "if: matrix.build.image == 'homer-console'" },
            "$release job `image-builds` conditions a step with $gateConditions; the LGPL gate must run for every " +
                "platform build of the console, since each platform image is pushed and conveyed on its own.",
        )
        for (job in listOf("image-builds", "images")) {
            val text = jobLines(release, job).joinToString("\n")
            for (permission in listOf("id-token: write", "attestations: write", "artifact-metadata: write")) {
                assertTrue(text.contains(permission), "$release job `$job` lacks `$permission`, so signing fails.")
            }
        }
    }

    /** Fails unless job [job] of release.yml has every one of [steps], each after the previous one. */
    private fun assertSupplyChain(
        job: String,
        steps: List<String>,
    ) {
        val text = jobLines(release, job).joinToString("\n")
        var from = 0
        for (step in steps) {
            val at = text.indexOf(step, from)
            assertTrue(
                at >= 0,
                "$release job `$job` no longer has `$step` after the previous supply-chain step. The console's " +
                    "license gate runs before the push, and consumers verify the cosign signature, the SLSA " +
                    "provenance and the CycloneDX SBOM before deploying (ADR-0086).",
            )
            from = at + step.length
        }
    }

    @Test
    fun `no image is built before the release's changelog section is checked`() {
        val tags = jobLines(release, "image-tags").joinToString("\n")
        val steps =
            listOf(
                "- name: Changelog section",
                "if: github.ref_type == 'tag'",
                "run: tools/release/notes.sh changelog \"\$TAG\" >/dev/null",
                "- name: Image tags",
                "echo \"version=",
            )
        var from = 0
        for (step in steps) {
            val at = tags.indexOf(step, from)
            assertTrue(
                at >= 0,
                "$release job `image-tags` no longer has `$step` after the previous step. A release tag whose " +
                    "version has no CHANGELOG.md section must fail before any image is built or pushed.",
            )
            from = at + step.length
        }
        assertTrue(
            "    needs: image-tags" in jobLines(release, "image-builds"),
            "$release job `image-builds` no longer needs `image-tags`, so it could push before the changelog check.",
        )
    }

    @Test
    fun `every image is built for linux amd64 and linux arm64 on native runners`() {
        val builds = jobLines(release, "image-builds").map { it.trim() }
        assertTrue(
            "TITLE: \${{ matrix.build.image }}" in builds &&
                "VERSION: \${{ needs.image-tags.outputs.version }}\${{ matrix.build.tag-suffix }}" in builds,
            "$release job `image-builds` no longer labels each platform image with its entry's image name and " +
                "version, tag suffix included; job `images` relies on them to refuse another entry's digest.",
        )
        for ((platform, runner) in listOf("linux/amd64" to "ubuntu-latest", "linux/arm64" to "ubuntu-24.04-arm")) {
            val at = builds.indexOf("- name: $platform")
            assertTrue(
                at >= 0 && "runner: $runner" in builds.drop(at).take(3),
                "$release job `image-builds` no longer builds $platform on `$runner`. Every release image ships " +
                    "for linux/amd64 and linux/arm64, each built natively rather than under emulation (ADR-0086).",
            )
        }
        assertTrue(
            "runs-on: \${{ matrix.platform.runner }}" in builds && builds.any { it.startsWith("timeout-minutes:") },
            "$release job `image-builds` must run on its platform's runner and have a timeout-minutes, so a stuck " +
                "arm64 build cannot hold the release for the 6-hour job limit.",
        )
        val index = jobLines(release, "images").map { it.trim() }
        assertTrue(
            "needs: [image-tags, image-builds]" in index && "for arch in amd64 arm64; do" in index,
            "$release job `images` must wait for every platform build and join the amd64 and arm64 digests into " +
                "the index; a release tag must not name an image that lacks a platform.",
        )
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
        val built = publishedImages(jobLines(release, "image-builds"))
        val indexed = publishedImages(jobLines(release, "images"))
        val listed =
            jobLines(release, "release-notes")
                .firstNotNullOfOrNull { Regex("""^\s+for entry in ([a-z0-9 :-]+); do$""").find(it)?.groupValues?.get(1) }
                ?.split(" ")
                ?.filter { it.isNotBlank() }
                ?.toSet()
        assertTrue(built.isNotEmpty(), "$release job `image-builds` no longer has a matrix of `- image:` entries.")
        assertTrue(
            indexed == built,
            "$release job `images` indexes $indexed, but job `image-builds` builds $built. A built image without " +
                "its index is never tagged; an index without its builds fails the release.",
        )
        assertTrue(
            listed == built,
            "$release job `release-notes` lists $listed, but job `images` publishes $built. An image missing from " +
                "the release page reaches consumers without its digest and verify command (ADR-0086).",
        )
    }

    @Test
    fun `every published tag carries the matrix entry's tag suffix`() {
        val index = jobLines(release, "images").map { it.trim() }
        assertTrue(
            "SUFFIX: \${{ matrix.tag-suffix }}" in index,
            "$release step `Index` no longer reads the matrix entry's `tag-suffix` into SUFFIX.",
        )
        val tags = index.filter { it.contains("-t \"\$IMAGE:") }
        assertTrue(
            tags.size == 2 &&
                tags.all { it.contains("-t \"\$IMAGE:\$VERSION\$SUFFIX\")") || it.contains("-t \"\$IMAGE:\$MOVING\$SUFFIX\")") },
            "$release step `Index` tags $tags; the version and the moving branch tag must each end with \$SUFFIX, " +
                "or a variant entry (homer-app -slim) pushes over the full image's tag.",
        )
        val tagOutputs =
            jobLines(release, "image-tags").map { it.trim() }.filter { it.startsWith("echo \"version=") || it.startsWith("echo \"moving=") }
        assertTrue(
            tagOutputs.size == 4 && tagOutputs.none { it.contains("SUFFIX") },
            "$release job `image-tags` writes $tagOutputs; it computes the tags shared by every image, and the " +
                "suffix belongs to the matrix entry that applies it.",
        )
    }

    @Test
    fun `each index downloads its own platform artifacts and no other entry's`() {
        val upload =
            jobLines(release, "image-builds")
                .map { it.trim() }
                .single { it.startsWith("name: platform--") }
                .removePrefix("name: ")
        val pattern =
            jobLines(release, "images")
                .map { it.trim() }
                .single { it.startsWith("pattern: platform--") }
                .removePrefix("pattern: ")
        val entries = publishedImages(jobLines(release, "image-builds"))
        val uploads =
            entries.associateWith { entry -> listOf("amd64", "arm64").map { arch -> expand(upload, entry, arch) }.toSet() }
        for (entry in entries) {
            val glob = Regex(expand(pattern, entry, arch = null).split("*").joinToString("[^/]*") { Regex.escape(it) })
            val matched =
                uploads.values
                    .flatten()
                    .filter { glob.matches(it) }
                    .toSet()
            assertTrue(
                matched == uploads.getValue(entry),
                "$release job `images` for `$entry` downloads $matched with pattern `$pattern`, but job " +
                    "`image-builds` uploads ${uploads.getValue(entry)} for it. An index built from another entry's " +
                    "digests tags and signs the wrong image (homer-app over homer-app -slim).",
            )
        }
    }

    /** [template] with the matrix expressions of [entry] (`IMAGE[:SUFFIX]`) and [arch] filled in. */
    private fun expand(
        template: String,
        entry: String,
        arch: String?,
    ): String {
        val image = entry.substringBefore(":")
        val suffix = entry.substringAfter(":", "")
        var text = template
        for ((expression, value) in listOf(
            "matrix.platform.arch" to arch,
            "matrix.build.image" to image,
            "matrix.image" to image,
            "matrix.build.tag-suffix" to suffix,
            "matrix.tag-suffix" to suffix,
        )) {
            if (value != null) text = text.replace("\${{ $expression }}", value)
        }
        assertTrue("\${{" !in text, "$release artifact name `$template` uses an expression this guard does not expand.")
        return text
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
