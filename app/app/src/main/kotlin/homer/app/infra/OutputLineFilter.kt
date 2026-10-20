package homer.app.infra

/**
 * Decides, per complete stdout line, whether [ShellExecutor] writes it to the
 * stdout file. [line] is one line without its trailing newline, decoded as UTF-8;
 * the bytes written on `true` are the original ones. May be called from an I/O
 * pump thread. See ADR-0063.
 */
fun interface OutputLineFilter {
    fun keep(line: String): Boolean
}
