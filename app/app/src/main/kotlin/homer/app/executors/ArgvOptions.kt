package homer.app.executors

// Option-emitting helpers shared by the CLI argv builders (ClaudeArgvBuilder,
// CodexArgvBuilder). Each adds nothing when its value is null / false / empty.

internal fun MutableList<String>.addStringOpt(
    optionName: String,
    value: String?,
) {
    if (value != null) {
        add(optionName)
        add(value)
    }
}

internal fun MutableList<String>.addListOpt(
    optionName: String,
    value: List<String>?,
) {
    if (value != null) {
        addStringOpt(optionName, value.joinToString(","))
    }
}

/**
 * Emits `name v1 v2 v3` for CLI flags whose commander option is variadic
 * (`--add-dir`, `--mcp-config`, `--file`). Safe because Homer always appends
 * `--output-format`/`--print`/`--verbose` after the option block, so commander
 * stops consuming values at the next flag.
 */
internal fun MutableList<String>.addVariadicOpt(
    optionName: String,
    values: List<String>?,
) {
    if (!values.isNullOrEmpty()) {
        add(optionName)
        addAll(values)
    }
}

/**
 * Emits `name v1 name v2` for CLI flags that take a single value but may be
 * repeated (`--plugin-dir`, `--plugin-url`).
 */
internal fun MutableList<String>.addRepeatableOpt(
    optionName: String,
    values: List<String>?,
) {
    values?.forEach { value ->
        add(optionName)
        add(value)
    }
}

internal fun MutableList<String>.addBoolOpt(
    optionName: String,
    enabled: Boolean?,
) {
    if (enabled == true) {
        add(optionName)
    }
}

internal fun MutableList<String>.addDoubleOpt(
    optionName: String,
    value: Double?,
) {
    if (value != null) {
        add(optionName)
        add(value.toString())
    }
}
