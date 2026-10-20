package homer.architecture

import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import java.io.File
import kotlin.test.Test

/**
 * Report-only architecture rules (ADR-0082). Every rule restates one that app/CLAUDE.md or the root
 * CLAUDE.md documents; the rule table in app/CLAUDE.md "Architecture tests" names each source.
 */
class ArchitectureRulesTest {
    @Test
    fun `test classes mirror the package of the production code they test`() {
        val violations =
            Sources.modules.flatMap { module ->
                val main = Sources.main(module)
                val test = Sources.test(module)
                val packagesByName =
                    (
                        main.classesAndInterfacesAndObjects().map { it.name to it.packagee?.name } +
                            main.files.map { it.name to it.packagee?.name }
                    ).groupBy({ it.first }, { it.second })
                test
                    .classes(includeNested = false)
                    .filter { it.name.endsWith("Test") }
                    .mapNotNull { testClass ->
                        val subject = testClass.name.removeSuffix("Test")
                        val subjectPackages = packagesByName[subject]?.toSet() ?: return@mapNotNull null
                        val testPackage = testClass.packagee?.name
                        if (testPackage in subjectPackages) return@mapNotNull null
                        violation(
                            testClass,
                            "${testClass.name} is in package $testPackage, but $subject is in ${subjectPackages.joinToString()}",
                        )
                    }
            }
        assertNoViolations("Test classes mirror the production package (root CLAUDE.md §6)", violations)
    }

    @Test
    fun `every service has a test in its mirrored package`() {
        val services =
            Sources.appMain
                .classes(includeNested = false)
                .filter { cls -> SERVICE_PACKAGES.any { cls.resideInPackage(it) } && cls.isTestableConcreteClass() }
                .filter { cls ->
                    cls.name.endsWith("Service") ||
                        cls.name in DOCUMENTED_SERVICES ||
                        cls.parents().any { it.name.substringBefore("<") in DOCUMENTED_SERVICES }
                }
        assertNoViolations("Every service has a test (root CLAUDE.md §6)", untested(services))
    }

    @Test
    fun `every executor has a test in its mirrored package`() {
        val executors =
            Sources.appMain
                .classes(includeNested = false)
                .filter { it.resideInPackage("homer.app.executors..") && it.isTestableConcreteClass() && it.name.endsWith("Executor") }
        assertNoViolations("Every executor has a test (root CLAUDE.md §6)", untested(executors))
    }

    @Test
    fun `command executors are named and placed per the new-command-type recipe`() {
        val violations =
            Sources.appMain
                .classes()
                .filter { cls -> cls.parents().any { it.name.substringBefore("<") == "CommandExecutor" } }
                .mapNotNull { cls ->
                    val problems =
                        listOfNotNull(
                            "is not in homer.app.executors".takeUnless { cls.resideInPackage("homer.app.executors..") },
                            "name does not end with Executor".takeUnless { cls.name.endsWith("Executor") },
                            "is not declared in ${cls.name}.kt".takeUnless { cls.containingFile.name == cls.name },
                        )
                    problems.takeIf { it.isNotEmpty() }?.let { violation(cls, "CommandExecutor ${cls.name} ${it.joinToString("; ")}") }
                }
        assertNoViolations("Command executors follow app/CLAUDE.md \"Adding a new command type\"", violations)
    }

    @Test
    fun `handlers throw ValidationException instead of answering 400 inline`() {
        val violations =
            Sources.appMain.files.flatMap { file ->
                val text = File(file.path).readText()
                INLINE_BAD_REQUEST.findAll(text).map {
                    violation(file.path, lineOf(text, it.range.first), "inline 400 response; throw ValidationException instead")
                }
            }
        assertNoViolations("No inline 400 responses (app/CLAUDE.md \"Adding a new HTTP endpoint\")", violations)
    }

    @Test
    fun `route builders live in the routes package`() {
        val violations =
            Sources.appMain
                .functions(includeNested = false, includeLocal = false)
                .filter { it.receiverType?.name == "Route" && !it.resideInPackage("homer.app.routes..") }
                .map { violation(it, "Route.${it.name} is declared outside homer.app.routes") }
        assertNoViolations("Routes live in routes/ (app/CLAUDE.md \"Adding a new HTTP endpoint\")", violations)
    }

    @Test
    fun `layers below the HTTP API do not depend on it`() {
        val violations =
            Sources.appMain.files
                .filter { file -> BELOW_HTTP_PACKAGES.any { file.packagee?.name?.startsWith(it) == true } }
                .flatMap { file ->
                    file.imports
                        .filter { import -> HTTP_LAYER_IMPORTS.any { import.name.startsWith(it) } }
                        .map { violation(it, "${file.packagee?.name} imports HTTP-layer ${it.name}") }
                }
        assertNoViolations("Layered architecture (app/CLAUDE.md \"Layered architecture\")", violations)
    }

    @Test
    fun `response DTOs live in the models package`() {
        val violations =
            Sources.appMain
                .classes(includeNested = false)
                .filter { it.name.endsWith("Response") }
                .filterNot { it.resideInPackage("homer.app.models..") || it.resideInPackage("homer.app.runners.external..") }
                .map { violation(it, "${it.name} is outside homer.app.models") }
        assertNoViolations("*Response DTOs live in models/ (app/CLAUDE.md \"Directory layout\")", violations)
    }

    @Test
    fun `consumers depend on TelemetryService, not OtelTelemetryService`() {
        val violations =
            Sources.modules
                .flatMap { Sources.main(it).files }
                .filterNot { it.packagee?.name == "homer.app.di" || it.name == OTEL }
                .flatMap { file -> otelTypedDeclarations(file) }
        assertNoViolations("Depend on TelemetryService (app/CLAUDE.md \"Key service responsibilities\")", violations)
    }

    @Test
    fun `tests mock with MockK`() {
        val violations =
            Sources.modules
                .flatMap { Sources.test(it).files }
                .flatMap { file -> file.imports.filter { it.name.startsWith("org.mockito") } }
                .map { violation(it, "imports ${it.name}; use MockK") }
        assertNoViolations("MockK for mocks (root CLAUDE.md §6)", violations)
    }

    private fun KoClassDeclaration.isTestableConcreteClass(): Boolean =
        !hasPrivateModifier && !hasAbstractModifier && !hasSealedModifier && !hasDataModifier && !hasEnumModifier

    private fun untested(classes: List<KoClassDeclaration>): List<Violation> {
        val testClasses = Sources.appTest.classes(includeNested = false)
        return classes
            .filterNot { cls ->
                testClasses.any { it.name.startsWith(cls.name) && it.name.endsWith("Test") && it.packagee?.name == cls.packagee?.name }
            }.map { violation(it, "${it.name} has no ${it.name}*Test in package ${it.packagee?.name}") }
    }

    private fun otelTypedDeclarations(file: KoFileDeclaration): List<Violation> {
        val typed =
            file.properties().filter { it.type?.text?.contains(OTEL) == true } +
                file.functions().flatMap { fn -> fn.parameters.filter { it.type.text.contains(OTEL) } } +
                file.functions().filter { it.returnType?.text?.contains(OTEL) == true } +
                file.classes().flatMap { cls ->
                    (listOfNotNull(cls.primaryConstructor) + cls.secondaryConstructors)
                        .flatMap { ctor -> ctor.parameters.filter { it.type.text.contains(OTEL) } }
                }
        return typed.map { violation(it, "$OTEL in a declared type; depend on TelemetryService") }
    }

    private companion object {
        const val OTEL = "OtelTelemetryService"

        /**
         * The "Services" row of app/CLAUDE.md "Layered architecture", plus the `services/` types ADR-0083 moved to
         * other rows, whose tests stay required; an interface counts through its implementations.
         */
        val DOCUMENTED_SERVICES =
            setOf(
                "AgentsService",
                "AgentRunStarter",
                "AgentSubRunStarter",
                "ProcessManager",
                "CostMeter",
                "LockManager",
                "ParallelRunsManager",
                "TelemetryService",
                "LangfuseClient",
                "ArtifactService",
                "LogTailService",
                "LlmEventStreamService",
                "ProcessEventBroadcaster",
                "CronSchedulerService",
                "WorkspacePurgeService",
                "QuestionsService",
                "DispatchService",
                "ContinuationService",
                "LangGraphInspectService",
                "LangGraphRunStatusService",
                "Authorizer",
                "CooldownGate",
                "StartupReconciliation",
            )

        /** Where the [DOCUMENTED_SERVICES] live: `services/`, and the rows ADR-0083 moved some of them to. */
        val SERVICE_PACKAGES = listOf("homer.app.services..", "homer.app.executors..", "homer.app.infra..")

        val INLINE_BAD_REQUEST = Regex("""\brespond\w*\(\s*(status\s*=\s*)?HttpStatusCode\.BadRequest\b""")

        val BELOW_HTTP_PACKAGES =
            listOf(
                "homer.app.services",
                "homer.app.executors",
                "homer.app.infra",
                "homer.app.runners",
                "homer.app.models",
                "homer.app.config",
                "homer.app.exceptions",
            )

        val HTTP_LAYER_IMPORTS = listOf("homer.app.routes.", "homer.app.auth.", "io.ktor.server.")
    }
}
