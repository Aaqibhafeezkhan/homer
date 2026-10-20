package homer.e2e

import homer.e2e.support.ArtifactListing
import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactsTest : E2eTestBase() {
    @Test
    fun `written artifacts are listed and fetchable with status headers`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-writer")
            assertEquals("FINISHED", Awaits.awaitTerminal(processId).status)

            val listing: ArtifactListing = HomerApi.decode(HomerApi.artifactsList(processId))
            val paths = listing.files.map { it.path }.toSet()
            assertTrue(
                paths.containsAll(setOf("hello.txt", "nested/dir/deep.txt", "big.txt", "cmd_0.out")),
                "artifact listing incomplete: $paths",
            )

            val hello = HomerApi.artifact(processId, "hello.txt")
            assertEquals(HttpStatusCode.OK, hello.status)
            assertTrue(hello.bodyAsText().contains("hello artifact"))
            assertEquals("FINISHED", hello.headers["X-Process-Status"])
            assertEquals("true", hello.headers["X-File-Complete"])

            val nested = HomerApi.artifact(processId, "nested/dir/deep.txt")
            assertEquals(HttpStatusCode.OK, nested.status)
            assertTrue(nested.bodyAsText().contains("nested content"))
        }

    @Test
    fun `artifact over the configured cap is rejected with 413`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-writer")
            Awaits.awaitTerminal(processId)

            // big.txt is 128 KiB; the fixture config caps artifacts at 64 KiB.
            val response = HomerApi.artifact(processId, "big.txt")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `path traversal and unknown artifacts are rejected`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-writer")
            Awaits.awaitTerminal(processId)

            assertEquals(HttpStatusCode.BadRequest, HomerApi.artifact(processId, "../workdir/anything").status)
            assertEquals(HttpStatusCode.NotFound, HomerApi.artifact(processId, "no-such-file.txt").status)
        }

    @Test
    fun `cleanAfterFinish true removes the workdir between runs of a shared workspace`() =
        runBlocking {
            val firstId = HomerApi.startRun("workspace-clean")
            Awaits.awaitTerminal(firstId)
            val secondId = HomerApi.startRun("workspace-clean")
            Awaits.awaitTerminal(secondId)

            // Both runs share a fixed workspace path; the marker written by run 1
            // must have been cleaned away before run 2 probed for it.
            assertTrue(HomerApi.artifact(firstId, "cmd_0.out").bodyAsText().contains("marker=absent"))
            assertTrue(
                HomerApi.artifact(secondId, "cmd_0.out").bodyAsText().contains("marker=absent"),
                "cleanAfterFinish=true must delete the workdir after run 1",
            )
        }

    @Test
    fun `artifactNameTemplate renders custom per-command artifact stems`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-name-template")
            assertEquals("FINISHED", Awaits.awaitTerminal(processId).status)

            val listing: ArtifactListing = HomerApi.decode(HomerApi.artifactsList(processId))
            val paths = listing.files.map { it.path }.toSet()
            assertTrue(
                paths.containsAll(setOf("step_0.out", "step_0.err", "step_1.out", "step_1.err")),
                "artifact listing incomplete: $paths",
            )
            assertTrue(
                paths.none { it.startsWith("cmd_") },
                "default cmd_N names must not appear alongside a custom artifactNameTemplate: $paths",
            )

            assertTrue(HomerApi.artifact(processId, "step_0.out").bodyAsText().contains("first stdout"))
            assertTrue(HomerApi.artifact(processId, "step_0.err").bodyAsText().contains("first stderr"))
            assertTrue(HomerApi.artifact(processId, "step_1.out").bodyAsText().contains("second stdout"))
            assertTrue(HomerApi.artifact(processId, "step_1.err").bodyAsText().contains("second stderr"))
        }

    @Test
    fun `cleanAfterFinish false keeps the workdir between runs of a shared workspace`() =
        runBlocking {
            val firstId = HomerApi.startRun("workspace-keep")
            Awaits.awaitTerminal(firstId)
            val secondId = HomerApi.startRun("workspace-keep")
            Awaits.awaitTerminal(secondId)

            assertTrue(
                HomerApi.artifact(secondId, "cmd_0.out").bodyAsText().contains("marker=present"),
                "cleanAfterFinish=false must keep run 1's marker for run 2",
            )
        }
}
