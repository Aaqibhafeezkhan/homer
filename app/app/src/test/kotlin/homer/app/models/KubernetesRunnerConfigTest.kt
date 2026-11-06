package homer.app.models

import com.charleskorn.kaml.Yaml
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubernetesRunnerConfigTest {
    private fun runner(extraKeys: String = ""): RunnerConfig.Kubernetes {
        val extra = extraKeys.lines().filter { it.isNotBlank() }.joinToString("") { "\n    ${it.trim()}" }
        val yaml =
            "server:\n  apiAuth: !<unsecured> {}\n" +
                "agentsSearchPath: /tmp/agents\nworkspaceRootPath: /tmp/ws\ndatabasePath: /tmp/homer.db\n" +
                "runners:\n  k8s: !<kubernetes>\n    image: busybox$extra\n"
        return Yaml.default
            .decodeFromString(AppConfig.serializer(), yaml)
            .runners
            .getValue("k8s") as RunnerConfig.Kubernetes
    }

    @Test
    fun `a kubernetes runner without the new keys decodes to Pod, no limit, no TTL`() {
        val k = runner()
        assertEquals(KubernetesWorkload.Pod, k.workload)
        assertNull(k.maxRunSeconds)
        assertNull(k.jobTtlSecondsAfterFinished)
    }

    @Test
    fun `workload Job with maxRunSeconds and a TTL decodes`() {
        val k = runner("workload: Job\nmaxRunSeconds: 10800\njobTtlSecondsAfterFinished: 600")
        assertEquals(KubernetesWorkload.Job, k.workload)
        assertEquals(10800L, k.maxRunSeconds)
        assertEquals(600L, k.jobTtlSecondsAfterFinished)
        assertEquals(600L, k.effectiveJobTtlSeconds)
    }

    @Test
    fun `effectiveJobTtlSeconds defaults to 3600`() {
        assertEquals(3600L, runner("workload: Job").effectiveJobTtlSeconds)
    }

    @Test
    fun `maxRunSeconds 0 fails the load naming the alias`() {
        val ex = assertThrows<IllegalArgumentException> { runner("maxRunSeconds: 0") }
        assertTrue(ex.message!!.contains("kubernetes runner 'k8s': maxRunSeconds must be >= 1"))
    }

    @Test
    fun `negative jobTtlSecondsAfterFinished fails the load naming the alias`() {
        val ex = assertThrows<IllegalArgumentException> { runner("workload: Job\njobTtlSecondsAfterFinished: -1") }
        assertTrue(ex.message!!.contains("kubernetes runner 'k8s': jobTtlSecondsAfterFinished must be >= 0"))
    }

    @Test
    fun `jobTtlSecondsAfterFinished without workload Job fails the load naming the alias`() {
        val ex = assertThrows<IllegalArgumentException> { runner("jobTtlSecondsAfterFinished: 60") }
        assertTrue(
            ex.message!!.contains("kubernetes runner 'k8s': jobTtlSecondsAfterFinished applies only to workload: Job"),
        )
    }

    @Test
    fun `jobTtlSecondsAfterFinished 0 with workload Job is valid`() {
        assertEquals(0L, runner("workload: Job\njobTtlSecondsAfterFinished: 0").effectiveJobTtlSeconds)
    }

    @Test
    fun `the load succeeds exactly when maxRunSeconds is at least 1 and a TTL is non-negative and Job-only`() =
        runTest {
            // Mostly around the 0 and 1 boundaries, sometimes anywhere.
            val seconds = Arb.choice(Arb.long(-2L..2L), Arb.long(-100_000L..100_000L)).orNull(0.3)
            checkAll(seconds, seconds, Arb.enum<KubernetesWorkload>()) { max, ttl, workload ->
                val keys =
                    listOfNotNull(
                        "workload: $workload",
                        max?.let { "maxRunSeconds: $it" },
                        ttl?.let { "jobTtlSecondsAfterFinished: $it" },
                    ).joinToString("\n")
                val valid = (max == null || max >= 1) && (ttl == null || (ttl >= 0 && workload == KubernetesWorkload.Job))
                assertEquals(valid, runCatching { runner(keys) }.isSuccess, keys)
            }
        }

    @Test
    fun `an unknown workload value fails to decode`() {
        assertThrows<Exception> { runner("workload: CronJob") }
    }
}
