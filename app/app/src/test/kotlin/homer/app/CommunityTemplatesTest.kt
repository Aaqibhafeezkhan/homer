package homer.app

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guard for the community surface GitHub reads: the two issue forms, the issue-template chooser
 * config and the pull request template. Each file must stay parseable and keep the fields, options
 * and links the repository promises — a form GitHub cannot parse silently disappears from "New
 * issue" — and every one of them must carry the public-content reminder, because a report or a pull
 * request description is published the moment it is submitted. See ADR-0047 and ADR-0085.
 */
class CommunityTemplatesTest {
    private val bugReport = ".github/ISSUE_TEMPLATE/bug_report.yml"
    private val featureRequest = ".github/ISSUE_TEMPLATE/feature_request.yml"
    private val config = ".github/ISSUE_TEMPLATE/config.yml"
    private val pullRequestTemplate = ".github/pull_request_template.md"

    /** What the reminder names, and what the fields taking pasted output repeat. */
    private val privateCategories = "internal hostnames, ticket IDs, credentials, tokens or private logs"
    private val reminder = "Everything written here is public: no $privateCategories."

    @Test
    fun `every template carries the public-content reminder`() {
        for (path in listOf(bugReport, featureRequest, config, pullRequestTemplate)) {
            assertTrue(
                text(path).contains(reminder),
                "$path dropped the public-content reminder. Anything posted there is public at once, so the " +
                    "warning has to stay (ADR-0047, ADR-0085).",
            )
        }
    }

    @Test
    fun `issue forms are valid YAML with a name, a description and a body`() {
        for (path in listOf(bugReport, featureRequest)) {
            val form = document(path)
            assertTrue(form.entry("name") is YamlScalar, "$path has no scalar `name`.")
            assertTrue(form.entry("description") is YamlScalar, "$path has no scalar `description`.")
            assertTrue(form.entry("body") is YamlList, "$path has no `body` list of fields.")
        }
    }

    @Test
    fun `the bug form asks for the version, the runner, a reproducing agent, expected versus actual and the logs`() {
        assertEquals(
            listOf("version", "runner", "agent-yaml", "expected-actual", "logs"),
            bodyFields(bugReport).mapNotNull { it.scalarOf("id") },
            "$bugReport no longer collects the fields the form exists for.",
        )
    }

    @Test
    fun `every field that collects input carries an id`() {
        for (path in listOf(bugReport, featureRequest)) {
            val anonymous = bodyFields(path).filter { it.scalarOf("type") != "markdown" && it.scalarOf("id") == null }
            assertTrue(
                anonymous.isEmpty(),
                "$path: fields without an id: ${anonymous.mapNotNull { it.scalarOf("type") }}. GitHub accepts an " +
                    "id-less field, but every id-list assertion in this class skips it, so a new field pasted " +
                    "without an id would escape them all.",
            )
        }
    }

    @Test
    fun `the forms require the fields triage cannot work without`() {
        assertEquals(
            listOf("version", "runner", "agent-yaml", "expected-actual"),
            requiredFieldIds(bugReport),
            "$bugReport: a report missing one of these cannot be triaged, so the field stays required — `logs` is " +
                "the one field a reporter may leave empty.",
        )
        assertEquals(
            listOf("problem", "proposed-behaviour"),
            requiredFieldIds(featureRequest),
            "$featureRequest: a request missing the problem or the proposal cannot be triaged — `alternatives` is " +
                "the one field a reporter may leave empty.",
        )
    }

    @Test
    fun `the agent and log fields ask GitHub to render what is pasted into them`() {
        assertEquals(
            "yaml",
            renderHint(bugReport, "agent-yaml"),
            "$bugReport: `agent-yaml` takes a pasted agent definition, so it must keep `render: yaml`.",
        )
        assertEquals(
            "shell",
            renderHint(bugReport, "logs"),
            "$bugReport: `logs` takes pasted log lines, so it must keep `render: shell`.",
        )
    }

    @Test
    fun `the bug form offers exactly the three runners Homer ships`() {
        val runner = bodyFields(bugReport).field("runner") ?: fail("$bugReport no longer asks which runner runs the agent.")
        assertEquals("dropdown", runner.scalarOf("type"), "$bugReport: the runner field is no longer a dropdown.")
        assertEquals(
            listOf("local", "ssh", "kubernetes"),
            runner.nested("attributes", bugReport).items("options", bugReport).map { (it as YamlScalar).content },
            "$bugReport: the options must be the three backends Homer ships (ADR-0003, ADR-0032, ADR-0043).",
        )
        assertTrue(
            runner
                .nested("attributes", bugReport)
                .scalarOf("description")
                .orEmpty()
                .contains("external runner"),
            "$bugReport: the closed list is the three per-agent backends; the description must say so, so it does " +
                "not read as a stale model that omits external runner instances.",
        )
    }

    @Test
    fun `the agent and log fields warn that their content is public`() {
        for (id in listOf("agent-yaml", "logs")) {
            val description = bodyFields(bugReport).field(id)?.nested("attributes", bugReport)?.scalarOf("description")
            assertTrue(
                description != null && description.contains(privateCategories),
                "$bugReport: `$id` takes pasted content, so its description must warn that $privateCategories do not " +
                    "belong in it (ADR-0047).",
            )
        }
    }

    @Test
    fun `the feature form asks for the problem, the proposal and the alternatives`() {
        assertEquals(
            listOf("problem", "proposed-behaviour", "alternatives"),
            bodyFields(featureRequest).mapNotNull { it.scalarOf("id") },
            "$featureRequest no longer collects the fields the form exists for.",
        )
    }

    @Test
    fun `the chooser disables blank issues and offers Discussions and private disclosure`() {
        val chooser = document(config)
        assertEquals(
            "false",
            chooser.scalarOf("blank_issues_enabled"),
            "$config must keep blank issues disabled, so every report arrives through a form.",
        )
        val links = chooser.maps("contact_links", config)
        assertEquals(
            listOf("Questions and help", "Report a vulnerability"),
            links.mapNotNull { it.scalarOf("name") },
            "$config must keep both contact links: without them the chooser offers no way to ask a question.",
        )
        assertEquals(
            "https://github.com/livesport/homer/discussions/categories/q-a",
            links.first().scalarOf("url"),
            "$config must send questions to the Discussions Q&A category.",
        )
    }

    @Test
    fun `the private disclosure link is the advisory form SECURITY md points at`() {
        val disclosure =
            document(config)
                .maps("contact_links", config)
                .firstOrNull { it.scalarOf("name") == "Report a vulnerability" }
                ?: fail("$config no longer links the private vulnerability report.")
        val advisory =
            Regex("""https://\S*?/security/advisories/new""")
                .find(File(repoRoot, "SECURITY.md").readText())
                ?.value
        assertEquals(
            advisory,
            disclosure.scalarOf("url"),
            "$config must reuse the advisory URL SECURITY.md sends reporters to, not a second one.",
        )
    }

    @Test
    fun `the pull request template asks for the accepted issue and the verify command`() {
        val template = text(pullRequestTemplate)
        assertTrue(
            template.contains("Closes #"),
            "$pullRequestTemplate must prompt for the linked accepted issue the `PR policy` check looks for " +
                "(ADR-0090).",
        )
        assertTrue(
            template.contains("CONTRIBUTING.md"),
            "$pullRequestTemplate must point at the verify commands in CONTRIBUTING.md rather than restating them.",
        )
    }

    private fun text(path: String) = File(repoRoot, path).readText()

    private fun document(path: String): YamlMap = Yaml.default.parseToYamlNode(text(path)) as YamlMap

    private fun YamlMap.entry(key: String): YamlNode? = entries.entries.firstOrNull { it.key.content == key }?.value

    private fun YamlMap.scalarOf(key: String): String? = (entry(key) as? YamlScalar)?.content

    private fun YamlMap.nested(
        key: String,
        path: String,
    ): YamlMap = entry(key) as? YamlMap ?: fail("$path: `$key` is missing or is not a map.")

    private fun YamlMap.items(
        key: String,
        path: String,
    ): List<YamlNode> = (entry(key) as? YamlList)?.items ?: fail("$path: `$key` is missing or is not a list.")

    private fun YamlMap.maps(
        key: String,
        path: String,
    ): List<YamlMap> = items(key, path).map { it as YamlMap }

    /** The `body` fields of an issue form; the reminder blocks carry no `id` and are left out. */
    private fun bodyFields(path: String): List<YamlMap> = document(path).maps("body", path)

    private fun List<YamlMap>.field(id: String): YamlMap? = firstOrNull { it.scalarOf("id") == id }

    /** The ids of the `body` fields a form marks `required: true`, in form order. */
    private fun requiredFieldIds(path: String): List<String> =
        bodyFields(path)
            .filter { (it.entry("validations") as? YamlMap)?.scalarOf("required") == "true" }
            .mapNotNull { it.scalarOf("id") }

    private fun renderHint(
        path: String,
        id: String,
    ): String? = bodyFields(path).field(id)?.nested("attributes", path)?.scalarOf("render")
}
