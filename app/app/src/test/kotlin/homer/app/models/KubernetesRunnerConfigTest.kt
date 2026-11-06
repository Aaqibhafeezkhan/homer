package homer.app.models

import com.charleskorn.kaml.Yaml
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
    fun `an unknown workload value fails to decode`() {
        assertThrows<Exception> { runner("workload: CronJob") }
    }
}
