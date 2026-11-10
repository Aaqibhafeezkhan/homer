package homer.app.runners.ssh

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two containment layers of [WorkspaceSync.containedChild] pinned apart
 * (ADR-0047): the name guard refuses every remote-supplied name that is not one
 * plain segment, and every name it lets through resolves to a direct child of
 * the base. The example cases live in `WorkspaceSyncTest`; these properties
 * cover the names an editor would not think of.
 */
class WorkspaceSyncGuardPropertyTest {
    /** The separators a remote-supplied name can carry, plus NUL, which `Path.resolve` refuses outright. */
    private val separators = Arb.of("/", "\\", "\u0000")

    private val reservedNames = Arb.of("", ".", "..")

    /** A name holding at least one separator, any position — leading and trailing included. */
    private val separatedNames: Arb<String> =
        Arb.bind(Arb.list(Arb.string(0..4), 2..3), separators) { parts, separator -> parts.joinToString(separator) }

    private val unsafeNames: Arb<String> = Arb.choice(reservedNames, separatedNames)

    /** A single segment the guard must accept: no separator, no NUL, and neither `.` nor `..`. */
    private val plainNames: Arb<String> =
        Arb
            .list(Arb.element(PLAIN_CHARS), 1..8)
            .map { it.joinToString("") }
            .filter { it != "." && it != ".." }

    @Test
    fun `every name that is not one plain segment is refused by the name guard`(
        @TempDir base: Path,
    ) = runTest {
        checkAll(unsafeNames) { name ->
            val refusal = assertFailsWith<SecurityException> { WorkspaceSync.containedChild(base, name) }
            assertTrue(
                refusal.message.orEmpty().contains("is not a plain file name"),
                "name '$name' must be refused by the name guard, got: ${refusal.message}",
            )
        }
    }

    @Test
    fun `every name the guard accepts resolves to a direct child of the base`(
        @TempDir base: Path,
    ) = runTest {
        val normalized = base.toAbsolutePath().normalize()
        checkAll(plainNames) { name ->
            val child = WorkspaceSync.containedChild(base, name)
            assertEquals(normalized, child.parent, "name '$name' must resolve to a direct child of the base")
        }
    }

    private companion object {
        private val PLAIN_CHARS = listOf('a', 'Z', '0', '-', '_', '.', ' ')
    }
}
