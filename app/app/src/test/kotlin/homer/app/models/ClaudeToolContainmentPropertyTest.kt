package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ClaudeToolContainmentPropertyTest {
    /** The permission modes an agent author may set, plus the empty string the schema rejects. */
    private val modes = Arb.of("bypassPermissions", "default", "acceptEdits", "plan", "auto", "dontAsk", "").orNull()

    private val toolLists = Arb.list(Arb.string(0..10), 0..3).orNull()

    @Test
    fun `the predicate holds exactly when permissions are bypassed and only allowedTools is set`() =
        runTest {
            checkAll(modes, Arb.boolean(), toolLists, toolLists, toolLists) { mode, skipFlag, allowed, disallowed, tools ->
                val opts =
                    ClaudeCmdOptions(
                        permissionMode = mode,
                        dangerouslySkipPermissions = skipFlag,
                        allowedTools = allowed,
                        disallowedTools = disallowed,
                        tools = tools,
                    )
                val bypasses = mode == "bypassPermissions" || (mode == null && skipFlag)
                val expected = bypasses && allowed != null && disallowed == null && tools == null
                assertEquals(expected, opts.bypassesPermissionsWithoutToolLimit(), "opts: $opts")
            }
        }

    @Test
    fun `a step that limits its tools with tools or disallowedTools never counts as uncontained`() =
        runTest {
            checkAll(modes, Arb.boolean(), toolLists, Arb.list(Arb.string(0..10), 0..3)) { mode, skipFlag, allowed, limit ->
                val withTools =
                    ClaudeCmdOptions(
                        permissionMode = mode,
                        dangerouslySkipPermissions = skipFlag,
                        allowedTools = allowed,
                        tools = limit,
                    )
                val withDenied = withTools.copy(tools = null, disallowedTools = limit)
                assertFalse(withTools.bypassesPermissionsWithoutToolLimit(), "opts: $withTools")
                assertFalse(withDenied.bypassesPermissionsWithoutToolLimit(), "opts: $withDenied")
            }
        }
}
