package homer.agentdebug.execution

import homer.app.infra.SecretRedactor
import homer.app.infra.WhenEvaluator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WhenActionTest {
    @Test
    fun `Run result maps to Run action with no message`() {
        val action = presentWhenResult(WhenEvaluator.Result.Run)
        assertEquals(WhenAction.Run, action)
    }

    @Test
    fun `Skip result formats SKIP when prefix and carries the reason`() {
        val action =
            presentWhenResult(
                WhenEvaluator.Result.Skip("when.all expression #1 false: [ -n \"\$REGION\" ]"),
            )
        val skip = assertIs<WhenAction.Skip>(action)
        assertEquals(
            "[SKIP when] when.all expression #1 false: [ -n \"\$REGION\" ]",
            skip.message,
        )
    }

    @Test
    fun `EvalError result formats ERROR prefix with expression and stderr summary`() {
        val action =
            presentWhenResult(
                WhenEvaluator.Result.EvalError(
                    expression = "[ -n \"\$X\"",
                    exitCode = 2,
                    stderrSummary = "bash: line 1: [: missing `]'",
                ),
            )
        val abort = assertIs<WhenAction.Abort>(action)
        assertEquals(
            "[ERROR] when-predicate evaluation failed for '[ -n \"\$X\"': bash: line 1: [: missing `]'",
            abort.message,
        )
    }

    @Test
    fun `EvalError redacts a credential carried in the expression`() {
        // CWE-532: the timeout EvalError names an
        // expression that was still mid-execution, and an operator's `when` can
        // carry a credential. The debug console must not print it raw.
        val secret = "sup3r-secret-token-value"
        val redactor = SecretRedactor.from(mapOf("TOKEN" to secret), setOf("TOKEN"))

        val action =
            presentWhenResult(
                WhenEvaluator.Result.EvalError(
                    expression = "curl -H 'Authorization: $secret' https://example.test",
                    exitCode = -1,
                    stderrSummary = "predicate timed out after 5s",
                ),
                redactor,
            )

        val abort = assertIs<WhenAction.Abort>(action)
        assertTrue(!abort.message.contains(secret), "raw credential leaked: ${abort.message}")
        assertTrue(abort.message.contains(SecretRedactor.REDACTED))
    }

    @Test
    fun `Skip redacts a credential carried in the reason`() {
        val secret = "sup3r-secret-token-value"
        val redactor = SecretRedactor.from(mapOf("TOKEN" to secret), setOf("TOKEN"))

        val action =
            presentWhenResult(
                WhenEvaluator.Result.Skip("when.all expression #1 false: [ -n '$secret' ]"),
                redactor,
            )

        val skip = assertIs<WhenAction.Skip>(action)
        assertTrue(!skip.message.contains(secret), "raw credential leaked: ${skip.message}")
    }

    @Test
    fun `EvalError with empty stderr still produces a well-formed message`() {
        val action =
            presentWhenResult(
                WhenEvaluator.Result.EvalError(
                    expression = "no-such-cmd",
                    exitCode = 127,
                    stderrSummary = "",
                ),
            )
        val abort = assertIs<WhenAction.Abort>(action)
        assertEquals(
            "[ERROR] when-predicate evaluation failed for 'no-such-cmd': ",
            abort.message,
        )
    }
}
