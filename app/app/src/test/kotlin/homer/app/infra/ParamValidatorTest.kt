package homer.app.infra

import homer.app.models.Param
import homer.app.models.ParamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParamValidatorTest {
    private val validator = ParamValidator()

    private fun param(
        type: ParamType,
        name: String = "p",
        values: List<String>? = null,
        pattern: String? = null,
        min: Double? = null,
        max: Double? = null,
        minLength: Int? = null,
        maxLength: Int? = null,
        secret: Boolean = false,
    ) = Param(
        paramName = name,
        type = type,
        values = values,
        pattern = pattern,
        min = min,
        max = max,
        minLength = minLength,
        maxLength = maxLength,
        secret = secret,
    )

    private fun assertValid(
        expected: String,
        value: String,
        p: Param,
    ) {
        val r = validator.validate(value, p)
        assertIs<ParamValidation.Valid>(r, "expected valid, got $r")
        assertEquals(expected, r.normalized)
    }

    private fun assertInvalid(
        value: String,
        p: Param,
        messagePart: String,
    ) {
        val r = validator.validate(value, p)
        assertIs<ParamValidation.Invalid>(r, "expected invalid, got $r")
        assertTrue(r.message.contains(messagePart), "message '${r.message}' should contain '$messagePart'")
    }

    // Legacy generation — byte-for-byte parity with the old AgentsService.validateParameter.

    @Test
    fun `legacy STRING rejects values containing a space`() {
        assertValid("nospace", "nospace", param(ParamType.STRING))
        assertInvalid("has space", param(ParamType.STRING), "is not valid STRING")
    }

    @Test
    fun `legacy STRING rejects control characters`() {
        assertInvalid("line1\nline2", param(ParamType.STRING), "is not valid STRING")
        assertInvalid("tab\there", param(ParamType.STRING), "is not valid STRING")
    }

    @Test
    fun `legacy NUMBER accepts only what Int parsing accepts`() {
        assertValid("42", "42", param(ParamType.NUMBER))
        assertValid("-7", "-7", param(ParamType.NUMBER))
        assertInvalid("4.2", param(ParamType.NUMBER), "is not valid NUMBER")
        assertInvalid("abc", param(ParamType.NUMBER), "is not valid NUMBER")
        // Long-range value overflows Int — legacy stays Int-ranged.
        assertInvalid("3000000000", param(ParamType.NUMBER), "is not valid NUMBER")
    }

    @Test
    fun `legacy ANY accepts anything`() {
        assertValid("free text with spaces", "free text with spaces", param(ParamType.ANY))
    }

    // string

    @Test
    fun `string accepts spaces`() {
        assertValid("free text", "free text", param(ParamType.STR))
    }

    @Test
    fun `string pattern evaluation is time-bounded against ReDoS`() {
        val evil = param(ParamType.STR, name = "code", pattern = "(a+)+\\1b")
        assertInvalid("a".repeat(30), evil, "timed out")
    }

    @Test
    fun `string enforces pattern as a whole-value match`() {
        val p = param(ParamType.STR, name = "ticket", pattern = "^[A-Z]+-[0-9]+$")
        assertValid("AB-12", "AB-12", p)
        assertInvalid("xAB-12x", p, "does not match pattern")
    }

    @Test
    fun `string enforces length bounds`() {
        assertInvalid("ab", param(ParamType.STR, minLength = 3), "at least 3")
        assertInvalid("abcd", param(ParamType.STR, maxLength = 3), "at most 3")
        assertValid("abc", "abc", param(ParamType.STR, minLength = 3, maxLength = 3))
    }

    @Test
    fun `string values are matched textually`() {
        val p = param(ParamType.STR, name = "environment", values = listOf("dev", "staging", "prod"))
        assertValid("dev", "dev", p)
        assertInvalid("qa", p, "must be one of [dev, staging, prod]")
    }

    @Test
    fun `a secret param's values are enforced but never echoed (ADR-0071)`() {
        val s = param(ParamType.STR, name = "token", values = listOf("abc", "def"), secret = true)
        assertValid("abc", "abc", s)
        val r = validator.validate("shh-e2e", s)
        assertIs<ParamValidation.Invalid>(r, "expected invalid, got $r")
        assertEquals("Parameter 'token' is not one of the allowed values", r.message)
        val i = param(ParamType.INT, values = listOf("1", "2"), secret = true)
        assertInvalid("3", i, "is not one of the allowed values")
        assertTrue(!validator.validate("3", i).let { it is ParamValidation.Invalid && it.message.contains('[') })
        val n = param(ParamType.NUM, values = listOf("10"), secret = true)
        assertInvalid("10.5", n, "is not one of the allowed values")
    }

    private fun assertInvalidWithoutValue(
        value: String,
        p: Param,
        messagePart: String,
    ) {
        assertInvalid(value, p, messagePart)
        val message = (validator.validate(value, p) as ParamValidation.Invalid).message
        assertFalse(message.contains(value), "secret value leaked into '$message'")
    }

    @Test
    fun `no check's denial message contains a secret param's value (ADR-0071)`() {
        val int = "987654321"
        assertInvalidWithoutValue(int, param(ParamType.INT, max = 10.0, secret = true), "must be <= 10")
        assertInvalidWithoutValue("-$int", param(ParamType.INT, min = 1.0, secret = true), "must be >= 1")
        val num = "98765.4321"
        assertInvalidWithoutValue(num, param(ParamType.NUM, max = 10.0, secret = true), "must be <= 10")
        assertInvalidWithoutValue("-$num", param(ParamType.NUM, min = 1.0, secret = true), "must be >= 1")
        val str = "S3NTINEL-value"
        assertInvalidWithoutValue(str, param(ParamType.STR, maxLength = 4, secret = true), "must be at most 4")
        assertInvalidWithoutValue(str, param(ParamType.STR, minLength = 64, secret = true), "must be at least 64")
        assertInvalidWithoutValue(str, param(ParamType.STR, pattern = "[0-9]+", secret = true), "does not match pattern")
        assertInvalidWithoutValue(str, param(ParamType.INT, secret = true), "must be an integer")
        assertInvalidWithoutValue(str, param(ParamType.NUM, secret = true), "must be a number")
        assertInvalidWithoutValue(str, param(ParamType.BOOL, secret = true), "must be 'true' or 'false'")
        assertInvalidWithoutValue("$str x", param(ParamType.STRING, secret = true), "is not valid STRING")
        assertInvalidWithoutValue(str, param(ParamType.NUMBER, secret = true), "is not valid NUMBER")
    }

    @Test
    fun `a non-secret param's range denial still names the value`() {
        assertInvalid("987654321", param(ParamType.INT, max = 10.0), "must be <= 10, got 987654321")
    }

    // integer

    @Test
    fun `integer parses as Long and rejects decimals`() {
        assertValid("42", "42", param(ParamType.INT))
        assertValid("3000000000", "3000000000", param(ParamType.INT)) // beyond Int range
        assertInvalid("4.2", param(ParamType.INT), "must be an integer")
        assertInvalid("abc", param(ParamType.INT), "must be an integer")
    }

    @Test
    fun `integer enforces inclusive min and max`() {
        val p = param(ParamType.INT, name = "count", min = 1.0, max = 100.0)
        assertValid("1", "1", p)
        assertValid("100", "100", p)
        assertInvalid("0", p, "must be >= 1")
        assertInvalid("250", p, "must be <= 100")
    }

    @Test
    fun `integer values are matched numerically`() {
        val p = param(ParamType.INT, values = listOf("1", "2"))
        assertValid("2", "2", p)
        assertInvalid("3", p, "must be one of")
    }

    // number

    @Test
    fun `number accepts decimals and rejects non-finite values`() {
        assertValid("4.2", "4.2", param(ParamType.NUM))
        assertValid("-0.5", "-0.5", param(ParamType.NUM))
        assertInvalid("abc", param(ParamType.NUM), "must be a number")
        assertInvalid("NaN", param(ParamType.NUM), "must be a number")
        assertInvalid("Infinity", param(ParamType.NUM), "must be a number")
    }

    @Test
    fun `number values match numerically not textually`() {
        val p = param(ParamType.NUM, values = listOf("10"))
        assertValid("10.0", "10.0", p)
        assertInvalid("10.5", p, "must be one of")
    }

    @Test
    fun `number enforces bounds`() {
        val p = param(ParamType.NUM, name = "ratio", min = 0.0, max = 1.0)
        assertValid("0.5", "0.5", p)
        assertInvalid("1.5", p, "must be <= 1")
    }

    // boolean

    @Test
    fun `boolean accepts true and false case-insensitively and normalizes to lowercase`() {
        assertValid("true", "TRUE", param(ParamType.BOOL))
        assertValid("false", "False", param(ParamType.BOOL))
        assertInvalid("yes", param(ParamType.BOOL), "must be 'true' or 'false'")
    }

    // validateDeclaration — load-time shape checks

    private fun declErrors(p: Param) = validator.validateDeclaration(p)

    @Test
    fun `a secret param's invalid default or values entry is not echoed in load errors (ADR-0071)`() {
        val sentinel = "987654321"
        val errors =
            declErrors(
                Param(paramName = "pin", type = ParamType.INT, required = false, secret = true, max = 10.0, default = sentinel),
            ) + declErrors(param(ParamType.INT, name = "pin", values = listOf(sentinel), max = 10.0, secret = true))
        assertEquals(2, errors.size, "both declarations must be rejected: $errors")
        assertTrue(errors.none { it.contains(sentinel) }, "secret value leaked into load errors: $errors")
    }

    @Test
    fun `a non-secret param's invalid default or values entry is still quoted in load errors`() {
        val errors =
            declErrors(Param(paramName = "n", type = ParamType.INT, required = false, max = 10.0, default = "42")) +
                declErrors(param(ParamType.INT, name = "n", values = listOf("43"), max = 10.0))
        assertEquals(2, errors.size, "both declarations must be rejected: $errors")
        assertTrue(errors[0].contains("'42'"), "non-secret default should be quoted: ${errors[0]}")
        assertTrue(errors[1].contains("'43'"), "non-secret values entry should be quoted: ${errors[1]}")
    }

    @Test
    fun `well-formed typed declarations produce no errors`() {
        assertEquals(emptyList(), declErrors(param(ParamType.STR, values = listOf("a", "b"))))
        assertEquals(emptyList(), declErrors(param(ParamType.INT, min = 1.0, max = 10.0)))
        assertEquals(
            emptyList(),
            declErrors(Param(paramName = "p", type = ParamType.INT, required = false, default = "5", min = 1.0)),
        )
        assertEquals(emptyList(), declErrors(param(ParamType.STRING))) // legacy, no constraints
    }

    @Test
    fun `constraint fields on legacy types are rejected`() {
        val errors = declErrors(param(ParamType.STRING, pattern = "x"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("lowercase type"))
    }

    @Test
    fun `inapplicable constraints are rejected per type`() {
        assertTrue(declErrors(param(ParamType.STR, min = 1.0)).isNotEmpty())
        assertTrue(declErrors(param(ParamType.INT, pattern = "x")).isNotEmpty())
        assertTrue(declErrors(param(ParamType.NUM, minLength = 1)).isNotEmpty())
        assertTrue(declErrors(param(ParamType.BOOL, values = listOf("true"))).isNotEmpty())
    }

    @Test
    fun `malformed pattern is rejected`() {
        assertTrue(declErrors(param(ParamType.STR, pattern = "[unclosed")).any { it.contains("pattern") })
    }

    @Test
    fun `inverted or non-integral bounds are rejected`() {
        assertTrue(declErrors(param(ParamType.INT, min = 10.0, max = 1.0)).any { it.contains("min") })
        assertTrue(declErrors(param(ParamType.INT, min = 1.5)).any { it.contains("integral") })
        assertTrue(declErrors(param(ParamType.STR, minLength = 5, maxLength = 2)).isNotEmpty())
        assertTrue(declErrors(param(ParamType.STR, minLength = -1)).isNotEmpty())
    }

    @Test
    fun `values entries must satisfy the params own type and constraints`() {
        assertTrue(declErrors(param(ParamType.INT, values = listOf("1", "abc"))).any { it.contains("abc") })
        assertTrue(
            declErrors(param(ParamType.STR, pattern = "^[a-z]+$", values = listOf("ok", "NOT-OK")))
                .any { it.contains("NOT-OK") },
        )
    }

    @Test
    fun `default must satisfy the constraints and requires required false`() {
        assertTrue(
            declErrors(Param(paramName = "p", type = ParamType.INT, required = false, default = "abc"))
                .any { it.contains("default") },
        )
        assertTrue(
            declErrors(Param(paramName = "p", type = ParamType.INT, required = true, default = "5"))
                .any { it.contains("required: false") },
        )
        assertTrue(
            declErrors(
                Param(paramName = "p", type = ParamType.STR, required = false, default = "qa", values = listOf("dev")),
            ).any { it.contains("default") },
        )
    }

    @Test
    fun `each typed param names the constraint fields its type does not accept`() {
        assertEquals(listOf("input 'p': min/max apply only to integer/number params"), declErrors(param(ParamType.STR, max = 1.0)))
        assertEquals(
            listOf("input 'p': pattern/minLength/maxLength apply only to string params"),
            declErrors(param(ParamType.NUM, maxLength = 3)),
        )
        for (bad in listOf(param(ParamType.BOOL, pattern = "x"), param(ParamType.BOOL, min = 0.0), param(ParamType.BOOL, maxLength = 1))) {
            assertTrue("input 'p': boolean params accept only 'default'" in declErrors(bad), "$bad")
        }
        assertEquals(emptyList(), declErrors(Param(paramName = "p", type = ParamType.BOOL, required = false, default = "true")))
    }
}
