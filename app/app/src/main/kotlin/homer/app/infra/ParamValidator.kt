package homer.app.infra

import homer.app.models.Param
import homer.app.models.ParamType

/** Outcome of validating one input value against its [Param] declaration. */
sealed interface ParamValidation {
    /** [normalized] is the value to place in the run env (booleans lowercased). */
    data class Valid(
        val normalized: String,
    ) : ParamValidation

    data class Invalid(
        val message: String,
    ) : ParamValidation
}

/**
 * Owns all input-param value semantics: the typed generation (string / integer /
 * number / boolean plus the declarative constraint fields on [Param]) and the
 * legacy generation (STRING / NUMBER / ANY heuristics, frozen until removal).
 * See ADR-0031.
 */
class ParamValidator {
    fun validate(
        value: String,
        param: Param,
    ): ParamValidation =
        when (param.type) {
            ParamType.STRING -> {
                validateLegacyString(value, param)
            }

            ParamType.NUMBER -> {
                validateLegacyNumber(value, param)
            }

            ParamType.ANY -> {
                ParamValidation.Valid(value)
            }

            ParamType.STR -> {
                validateString(value, param)
            }

            ParamType.INT -> {
                validateInteger(value, param)
            }

            ParamType.NUM -> {
                validateNumber(value, param)
            }

            ParamType.BOOL -> {
                validateBoolean(value, param)
            }
        }

    private fun validateLegacyString(
        value: String,
        param: Param,
    ): ParamValidation =
        // Legacy STRING historically rejected only spaces. Also reject control
        // characters (newlines, etc.): a newline in a value templated into a
        // `when` bash predicate can inject a second predicate line. ANY stays
        // unsanitized by definition (documented in AGENT.md).
        if (value.contains(" ") || value.any { it.isISOControl() }) {
            legacyInvalid(param)
        } else {
            ParamValidation.Valid(value)
        }

    private fun validateLegacyNumber(
        value: String,
        param: Param,
    ): ParamValidation = if (value.toIntOrNull() == null) legacyInvalid(param) else ParamValidation.Valid(value)

    /**
     * Load-time shape check of one param declaration. Empty list = well-formed.
     * Callers (AgentsService) prefix messages with the agent name and reject the
     * agent — authors find out at registration, not at first request.
     */
    fun validateDeclaration(param: Param): List<String> {
        val errors = mutableListOf<String>()
        val prefix = "input '${param.paramName}':"
        val constraintsSet =
            listOf(param.values, param.pattern, param.min, param.max, param.minLength, param.maxLength, param.default)
                .any { it != null }

        if (param.type.isLegacy) {
            if (constraintsSet) {
                errors +=
                    "$prefix constraint fields (values/pattern/min/max/minLength/maxLength/default) " +
                    "require a lowercase type (string, integer, number, boolean), not ${param.type}"
            }
            return errors
        }

        errors += listOfNotNull(typeConstraintError(param, prefix))

        param.pattern?.let { p ->
            runCatching { Regex(p) }.onFailure { errors += "$prefix invalid pattern: ${it.message}" }
        }
        errors += numericBoundErrors(param, prefix)
        errors += lengthBoundErrors(param, prefix)
        if (errors.isNotEmpty()) return errors // don't check entries against broken constraints

        param.values?.forEach { v ->
            val r = validate(v, param.copy(values = null))
            if (r is ParamValidation.Invalid) errors += "$prefix values entry${quoted(v, param)} is invalid: ${r.message}"
        }
        param.default?.let { d ->
            if (param.required) {
                errors += "$prefix 'default' requires 'required: false'"
            }
            val r = validate(d, param)
            if (r is ParamValidation.Invalid) errors += "$prefix default${quoted(d, param)} is invalid: ${r.message}"
        }
        return errors
    }

    private fun numericBoundErrors(
        param: Param,
        prefix: String,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (param.min != null && param.max != null && param.min > param.max) {
            errors += "$prefix min (${param.min}) > max (${param.max})"
        }
        if (param.type == ParamType.INT) {
            listOfNotNull(param.min, param.max).forEach {
                if (it % 1.0 != 0.0) errors += "$prefix min/max must be integral for integer params, got $it"
            }
        }
        return errors
    }

    private fun lengthBoundErrors(
        param: Param,
        prefix: String,
    ): List<String> {
        val errors = mutableListOf<String>()
        listOfNotNull(param.minLength, param.maxLength).forEach {
            if (it < 0) errors += "$prefix minLength/maxLength must be >= 0"
        }
        if (param.minLength != null && param.maxLength != null && param.minLength > param.maxLength) {
            errors += "$prefix minLength (${param.minLength}) > maxLength (${param.maxLength})"
        }
        return errors
    }

    /** The constraint fields [param]'s (non-legacy) type does not accept, as one error; `null` = none. */
    private fun typeConstraintError(
        param: Param,
        prefix: String,
    ): String? =
        when (param.type) {
            ParamType.STR -> {
                if (param.hasNumericBounds()) {
                    "$prefix min/max apply only to integer/number params"
                } else {
                    null
                }
            }

            ParamType.INT, ParamType.NUM -> {
                if (param.hasStringConstraints()) {
                    "$prefix pattern/minLength/maxLength apply only to string params"
                } else {
                    null
                }
            }

            ParamType.BOOL -> {
                if (param.hasNonDefaultConstraints()) {
                    "$prefix boolean params accept only 'default'"
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }

    private fun Param.hasNumericBounds() = min != null || max != null

    private fun Param.hasStringConstraints() = pattern != null || minLength != null || maxLength != null

    private fun Param.hasNonDefaultConstraints() = values != null || hasStringConstraints() || hasNumericBounds()

    private fun legacyInvalid(param: Param) = ParamValidation.Invalid("Parameter '${param.paramName}' is not valid ${param.type}")

    private fun invalid(
        param: Param,
        detail: String,
    ) = ParamValidation.Invalid("Parameter '${param.paramName}' $detail")

    private fun validateString(
        value: String,
        param: Param,
    ): ParamValidation {
        param.minLength?.let {
            if (value.length < it) return invalid(param, "must be at least $it characters")
        }
        param.maxLength?.let {
            if (value.length > it) return invalid(param, "must be at most $it characters")
        }
        param.pattern?.let {
            // Bounded match: the pattern is author-supplied but `value` is caller-controlled,
            // so a vulnerable pattern + crafted value is a ReDoS vector. Fail closed on timeout.
            val matched =
                try {
                    BoundedRegex.matches(Regex(it), value)
                } catch (_: BoundedRegex.RegexTimeoutException) {
                    return invalid(param, "pattern evaluation timed out")
                }
            if (!matched) return invalid(param, "does not match pattern '$it'")
        }
        runValuesCheck(value, param)?.let { return it }
        return ParamValidation.Valid(value)
    }

    private fun validateInteger(
        value: String,
        param: Param,
    ): ParamValidation {
        val n = value.toLongOrNull() ?: return invalid(param, "must be an integer")
        param.min?.let { if (n < it) return invalid(param, "must be >= ${formatNumber(it)}${got(value, param)}") }
        param.max?.let { if (n > it) return invalid(param, "must be <= ${formatNumber(it)}${got(value, param)}") }
        runValuesCheck(value, param)?.let { return it }
        return ParamValidation.Valid(value)
    }

    private fun validateNumber(
        value: String,
        param: Param,
    ): ParamValidation {
        val d =
            value.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: return invalid(param, "must be a number")
        param.min?.let { if (d < it) return invalid(param, "must be >= ${formatNumber(it)}${got(value, param)}") }
        param.max?.let { if (d > it) return invalid(param, "must be <= ${formatNumber(it)}${got(value, param)}") }
        runValuesCheck(value, param)?.let { return it }
        return ParamValidation.Valid(value)
    }

    private fun validateBoolean(
        value: String,
        param: Param,
    ): ParamValidation {
        val normalized = value.lowercase()
        if (normalized != "true" && normalized != "false") {
            return invalid(param, "must be 'true' or 'false'")
        }
        return ParamValidation.Valid(normalized)
    }

    /** The `values` check for the typed arms; a secret param's denial never lists the set (ADR-0071). */
    private fun runValuesCheck(
        value: String,
        param: Param,
    ): ParamValidation.Invalid? {
        val allowed = param.values ?: return null
        val matched =
            when (param.type) {
                ParamType.INT -> allowed.any { it.toLongOrNull() == value.toLongOrNull() }
                ParamType.NUM -> allowed.any { it.toDoubleOrNull() == value.toDoubleOrNull() }
                else -> value in allowed
            }
        return when {
            matched -> null
            param.secret -> invalid(param, "is not one of the allowed values")
            else -> invalid(param, mustBeOneOf(allowed))
        }
    }

    // A secret param's value never appears in error text (ADR-0071).
    private fun got(
        value: String,
        param: Param,
    ) = if (param.secret) "" else ", got $value"

    private fun quoted(
        value: String,
        param: Param,
    ) = if (param.secret) "" else " '$value'"

    private fun mustBeOneOf(allowed: List<String>) = "must be one of ${allowed.joinToString(", ", "[", "]")}"

    private fun formatNumber(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
}
