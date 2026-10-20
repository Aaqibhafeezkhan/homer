package homer.e2e.support

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll

/**
 * Base class for every E2E test: skips the whole class unless E2E_BASE_URL
 * points at a live server. This keeps `./gradlew check` (unit-test CI, local
 * pre-commit) green without a running backend — the suite only executes
 * inside the docker-compose.e2e.yml stack or when a developer exports the
 * variable explicitly.
 */
abstract class E2eTestBase {
    companion object {
        @JvmStatic
        @BeforeAll
        fun requireLiveServer() {
            Assumptions.assumeTrue(
                E2e.baseUrl.isNotBlank(),
                "E2E_BASE_URL not set — black-box E2E suite skipped",
            )
        }
    }
}
