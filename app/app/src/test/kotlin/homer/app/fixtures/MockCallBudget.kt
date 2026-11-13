package homer.app.fixtures

import io.mockk.MockKGateway
import io.mockk.clearAllMocks
import io.mockk.impl.JvmMockKGateway
import io.mockk.impl.stub.Stub
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File

/**
 * After every `:app` test: counts the calls each mock method took in the test, then clears mockk's
 * recorded calls (answers stay). mockk keeps every call it records, with its stack trace, for as long
 * as the mock is reachable, and the suite runs in one JVM: a stub that answers at once inside a
 * polling loop (a fake `waitFor` returning "not yet" without waiting) calls it about 140,000 times a
 * second, and about a million such calls kept until the suite ended once filled the 2g test heap
 * (#198, #200).
 *
 * A method called [FAIL_CALLS] times or more fails the test: only a busy loop gets there, as the
 * busiest ordinary test calls one method a few hundred times. One called [WARN_CALLS] times or more
 * is a line in `mock-calls.jsonl` under [DIR_PROPERTY], which the warn-mode `mock-calls` check
 * reports (ADR-0082). Registered through `META-INF/services`; the test task enables autodetection
 * for this class only.
 */
class MockCallBudget(
    private val warnAt: Int = WARN_CALLS,
    private val failAt: Int = FAIL_CALLS,
    private val reportDir: () -> File? = { System.getProperty(DIR_PROPERTY)?.let(::File) },
) : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        val stubs = registeredStubs() ?: return
        val counts = HashMap<String, Int>()
        for (stub in stubs) {
            for (call in stub.allRecordedCalls()) {
                val method = methodName(call.method.declaringClass.simpleName, call.method.name)
                counts.merge(method, 1, Int::plus)
            }
        }
        // Cleared before judging, so a failed budget does not carry its calls into the next test.
        clearAllMocks(answers = false, recordedCalls = true, childMocks = false)
        val over = busiest(counts, warnAt) ?: return
        reportDir()?.let { record(it, context, over) }
        if (over.second >= failAt) throw AssertionError(failure(over.first, over.second, failAt))
    }

    private fun record(
        dir: File,
        context: ExtensionContext,
        over: Pair<String, Int>,
    ) {
        val line =
            buildJsonObject {
                put("testClass", context.requiredTestClass.name)
                put("test", context.displayName)
                put("method", over.first)
                put("calls", over.second)
                put("warnAt", warnAt)
                put("failAt", failAt)
            }
        dir.mkdirs()
        synchronized(MockCallBudget::class.java) { dir.resolve(REPORT_FILE).appendText("$line\n") }
    }

    internal companion object {
        /** Calls of one mock method in one test from which the `mock-calls` check warns. */
        const val WARN_CALLS = 1_000

        /** Calls of one mock method in one test from which the test fails. */
        const val FAIL_CALLS = 20_000

        const val DIR_PROPERTY = "homer.mockCalls.dir"
        const val REPORT_FILE = "mock-calls.jsonl"

        /**
         * Every mock's stub, or null before the first mock exists: mockk sets its gateway up on first
         * use, and a test class that ran none has nothing to count.
         */
        fun registeredStubs(): List<Stub>? =
            try {
                (MockKGateway.implementation() as? JvmMockKGateway)?.stubRepo?.allStubs
            } catch (_: UninitializedPropertyAccessException) {
                null
            }

        fun methodName(
            type: String?,
            method: String,
        ) = "${type ?: "?"}.$method"

        /** The most-called method with its count, when that count is at least [from]. */
        fun busiest(
            counts: Map<String, Int>,
            from: Int,
        ): Pair<String, Int>? = counts.maxByOrNull { it.value }?.toPair()?.takeIf { it.second >= from }

        fun failure(
            method: String,
            calls: Int,
            limit: Int,
        ) = "$method was called $calls times in this test (the limit is $limit). A stub that answers at once " +
            "inside a polling loop busy-spins it: make the fake wait as the real call does (CLAUDE.md §6)."
    }
}
