package homer.architecture

import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/**
 * The layers of app/CLAUDE.md "Layered architecture", top to bottom, plus the shared kernel every layer
 * may use. A layer may depend on itself, on any layer below it and on the kernel; the kernel only on itself.
 */
internal enum class Layer {
    HTTP,
    SERVICES,
    EXECUTION,
    EXECUTORS,
    INFRA,
    KERNEL,
    ;

    fun mayDependOn(target: Layer): Boolean = target == KERNEL || (this != KERNEL && target.ordinal >= ordinal)

    val label: String get() = if (this == HTTP) "HTTP" else name.lowercase().replaceFirstChar(Char::uppercase)

    companion object {
        /** The type names of each row of the "Layered architecture" diagram; an implementation counts as its interface. */
        private val ROWS =
            mapOf(
                SERVICES to
                    setOf(
                        "AgentsService",
                        "AgentRunStarter",
                        "AgentSubRunStarter",
                        "ProcessManager",
                        "CostMeter",
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
                    ),
                // Process: the run itself, whose WORKING write sits next to ProcessExecutor's CREATED write
                // in "Status precedence rules". LockManager, ParallelRunsManager: the in-memory gates Process holds.
                EXECUTION to
                    setOf(
                        "ProcessExecutor",
                        "AgentExecutor",
                        "CommandExecutorFactory",
                        "RunnerRegistry",
                        "RunnerClaimQueue",
                        "LockManager",
                        "ParallelRunsManager",
                        "ExternalRunnerDomain",
                        "ExternalRunnerInstance",
                        "RunnerStatusReport",
                        "Process",
                    ),
                EXECUTORS to
                    setOf(
                        "CmdExecutor",
                        "ScriptExecutor",
                        "LlmHarnessExecutor",
                        "ClaudeHarness",
                        "CodexHarness",
                        "OpencodeHarness",
                        "RunAgentExecutor",
                        "LangGraphExecutor",
                        "RunnerCmdExecutor",
                    ),
                // TelemetryService, LangfuseClient: clients over the OpenTelemetry exporter and the Langfuse HTTP API.
                INFRA to
                    setOf(
                        "ShellExecutor",
                        "Workspace",
                        "FileOperations",
                        "ResultToEnvMapper",
                        "SysEnvResolver",
                        "TelemetryService",
                        "LangfuseClient",
                    ),
            )

        private val HTTP_PACKAGES = listOf("homer.app.routes", "homer.app.auth", "homer.app.di")
        private val KERNEL_PACKAGES = listOf("homer.app.models", "homer.app.config", "homer.app.exceptions")

        /**
         * The layer of one :app source file: its row in the diagram when it declares a type named there,
         * else the default of its package (the "Directory layout" reading noted in app/CLAUDE.md
         * "Architecture tests").
         */
        fun of(file: KoFileDeclaration): Layer {
            val pkg = file.packagee?.name.orEmpty()
            return when {
                pkg.isIn(KERNEL_PACKAGES) || (pkg == "homer.app" && file.name == "EnvConstants") -> KERNEL
                pkg == "homer.app" || pkg.isIn(HTTP_PACKAGES) -> HTTP
                else -> rowOf(file) ?: packageDefault(pkg, file.name)
            }
        }

        private fun rowOf(file: KoFileDeclaration): Layer? {
            val types = file.classesAndInterfacesAndObjects(includeNested = false, includeLocal = false)
            val names =
                setOf(file.name) +
                    types.map { it.name } +
                    file.classes(includeNested = false).flatMap { cls -> cls.parents().map { it.name.substringBefore("<") } }
            return ROWS.entries.firstOrNull { (_, row) -> names.any { it in row } }?.key
        }

        private fun packageDefault(
            pkg: String,
            fileName: String,
        ): Layer =
            when {
                pkg.isIn(listOf("homer.app.services")) -> SERVICES
                pkg.isIn(listOf("homer.app.infra")) -> INFRA
                pkg.isIn(listOf("homer.app.executors")) -> EXECUTORS
                pkg.isIn(listOf("homer.app.runners.queue")) -> EXECUTION
                pkg.isIn(listOf("homer.app.runners")) -> INFRA
                else -> error("no layer for package $pkg ($fileName.kt): map it in Layers.kt")
            }

        private fun String.isIn(packages: List<String>): Boolean = packages.any { this == it || startsWith("$it.") }
    }
}

/** The files declaring each top-level name of a scope, for resolving an import to the file it reads. */
internal class DeclarationIndex(
    scope: KoScope,
) {
    private val filesByName: Map<String, List<KoFileDeclaration>> =
        scope.files
            .flatMap { file ->
                val pkg = file.packagee?.name.orEmpty()
                topLevelNames(file).map { "$pkg.$it" to file }
            }.groupBy({ it.first }, { it.second })

    /** The files [importName] reads: the longest indexed prefix wins (`a.B.Nested.X` reads `a.B`'s file). */
    fun filesOf(importName: String): List<KoFileDeclaration> {
        var name = importName
        while (name.contains('.')) {
            filesByName[name]?.let { return it.distinct() }
            name = name.substringBeforeLast('.')
        }
        return emptyList()
    }

    private fun topLevelNames(file: KoFileDeclaration): List<String> =
        file.classesAndInterfacesAndObjects(includeNested = false, includeLocal = false).map { it.name } +
            file.functions(includeNested = false, includeLocal = false).map { it.name } +
            file.properties(includeNested = false).map { it.name } +
            file.typeAliases.map { it.name }
}
