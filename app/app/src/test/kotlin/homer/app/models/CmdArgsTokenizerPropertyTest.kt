package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CmdArgsTokenizerPropertyTest {
    @Test
    fun `single-quoting any argv and tokenizing it returns the same argv`() =
        runTest {
            checkAll(Arb.list(Arb.string(0..20), 0..8)) { argv ->
                val command = argv.joinToString(" ") { "'" + it.replace("'", """'\''""") + "'" }
                assertEquals(argv, CmdArgsTokenizer.tokenize(command), "command: $command")
            }
        }
}
