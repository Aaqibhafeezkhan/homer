package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Drift backstop for the bundled authoring plugin under
 * `plugins/homer-authoring/`. That plugin ships to external users without the
 * repo layout, so it carries its own copies of AGENT.md and the JSON schemas.
 * Those copies are generated artifacts — refreshed by `:app:syncAuthoringPlugin`
 * — and must stay byte-identical to their canonical sources (root §10).
 */
class AuthoringPluginBundleTest {
    // canonical source (relative to repo root) -> bundled copy (relative to repo root)
    private val bundledCopies =
        mapOf(
            "app/AGENT.md" to
                "plugins/homer-authoring/skills/homer-agent-authoring/reference/AGENT.md",
            "app/schemas/homer-agent.schema.json" to
                "plugins/homer-authoring/schemas/homer-agent.schema.json",
            "app/schemas/homer-config.schema.json" to
                "plugins/homer-authoring/schemas/homer-config.schema.json",
        )

    @Test
    fun `bundled authoring plugin copies are byte-identical to their canonical sources`() {
        for ((sourceRel, bundledRel) in bundledCopies) {
            val source = File(repoRoot, sourceRel)
            val bundled = File(repoRoot, bundledRel)
            assertEquals(
                source.readBytes().toList(),
                bundled.readBytes().toList(),
                "Bundled authoring-plugin file '$bundledRel' has drifted from '$sourceRel'. " +
                    "Run `app/gradlew -p app :app:syncAuthoringPlugin` (root §10).",
            )
        }
    }
}
