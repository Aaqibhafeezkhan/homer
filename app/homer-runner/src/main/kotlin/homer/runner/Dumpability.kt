package homer.runner

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Denies same-uid `/proc` inspection of this JVM via `prctl(PR_SET_DUMPABLE, 0)`.
 * See ADR-0078.
 */
object Dumpability {
    private const val PR_GET_DUMPABLE = 3
    private const val PR_SET_DUMPABLE = 4

    sealed interface Result {
        /** The JVM is non-dumpable. */
        data object Hardened : Result

        /** Not Linux, where `prctl` is unavailable: warned about, never fatal. */
        data class Skipped(
            val reason: String,
        ) : Result

        /** Linux, but the call or its verification failed: fatal at startup. */
        data class Failed(
            val reason: String,
        ) : Result
    }

    /**
     * Makes this process non-dumpable and confirms it took effect. `osName`, the
     * `prctl` call and the state read are injectable so tests can drive every outcome.
     */
    fun harden(
        osName: String = System.getProperty("os.name"),
        setNonDumpable: () -> Int = { prctl(PR_SET_DUMPABLE, 0L) },
        readDumpable: () -> Int = { prctl(PR_GET_DUMPABLE, 0L) },
    ): Result {
        if (!osName.startsWith("Linux", ignoreCase = true)) {
            return Result.Skipped("prctl(PR_SET_DUMPABLE, 0) is Linux-only; running on $osName")
        }
        val rc =
            runCatching { setNonDumpable() }
                .getOrElse { return Result.Failed("prctl(PR_SET_DUMPABLE, 0) failed: ${it.message}") }
        if (rc != 0) return Result.Failed("prctl(PR_SET_DUMPABLE, 0) returned $rc")
        // Verified through PR_GET_DUMPABLE, not /proc/self/status: the kernel's
        // "Dumpable:" field is absent before Linux 6.2 and some containers mask it.
        val state = runCatching { readDumpable() }.getOrDefault(-1)
        if (state != 0) return Result.Failed("prctl(PR_SET_DUMPABLE, 0) did not take effect (PR_GET_DUMPABLE=$state)")
        return Result.Hardened
    }

    /** `PR_GET_DUMPABLE`: 1 when dumpable, 0 when not, negative on error. */
    internal fun dumpableState(): Int = prctl(PR_GET_DUMPABLE, 0L)

    private val prctlHandle: MethodHandle by lazy {
        val linker = Linker.nativeLinker()
        val symbol =
            linker
                .defaultLookup()
                .find("prctl")
                .orElseThrow { IllegalStateException("prctl is not available on this platform") }
        linker.downcallHandle(
            symbol,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.firstVariadicArg(1),
        )
    }

    private fun prctl(
        option: Int,
        arg: Long,
    ): Int = prctlHandle.invokeWithArguments(option, arg, 0L, 0L, 0L) as Int
}
