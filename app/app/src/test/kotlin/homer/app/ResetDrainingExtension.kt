package homer.app

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Clears the process-wide [isDraining] flag after each test. Every boot-and-stop test that
 * leaves its `testApplication` block fires `ApplicationStopping` — the shutdown drain — which
 * sets the flag and nothing clears it; left set, a later `/readyz` assertion in this JVM would
 * see 503. Apply to every test class that boots `configureServer`; a class that clears the flag
 * in its own setup instead (`GlobalRateLimitProbeExemptionTest`) is equivalent.
 */
class ResetDrainingExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        isDraining.set(false)
    }
}
