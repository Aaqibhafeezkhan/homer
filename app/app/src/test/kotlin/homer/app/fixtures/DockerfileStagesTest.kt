package homer.app.fixtures

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockerfileStagesTest {
    private val dockerfile =
        DockerfileStages(
            """
            # a comment
            FROM eclipse-temurin:25-jdk-alpine AS builder
            RUN apk add --no-cache bash zip
            FROM --platform=linux/amd64 eclipse-temurin:25-jre-alpine AS Slim
            RUN apk add --no-cache \
                curl \
                python3
            RUN pip install x && apk add --virtual .build gcc && apk del .build; apk add jq
            FROM slim AS runner
            RUN npm install -g a
            """.trimIndent(),
        )

    @Test
    fun `stages read flags, case-insensitive names and continued lines`() {
        assertEquals(listOf("builder", "slim", "runner"), dockerfile.stages.map { it.name })
        assertEquals("eclipse-temurin:25-jre-alpine", dockerfile.stage("SLIM").base)
        assertEquals(listOf("runner", "slim"), dockerfile.chain("runner").map { it.name })
        assertTrue(dockerfile.isStage("Slim"))
    }

    @Test
    fun `apk packages include chained installs and skip flags and virtual names`() {
        assertEquals(setOf("curl", "python3", "gcc", "jq"), DockerfileStages.apkPackages(dockerfile.stage("slim")))
        assertEquals(emptySet(), DockerfileStages.apkPackages(dockerfile.stage("runner")))
    }
}
