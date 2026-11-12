package homer.app.di

import homer.app.infra.TextCompiler
import homer.app.models.AppConfig
import homer.app.services.HookService
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import kotlin.io.path.Path

/**
 * The run-lifecycle core's Koin definition, beside [appModule] for the same reason
 * `RunnerWiring` is: that file is at its size limit (root CLAUDE.md §2). The hook rows it
 * records are fired by [HookService], whose own definition is in `HookWiring`.
 */
internal val processModule =
    module {
        single {
            val config = get<AppConfig>()
            val questionsService = get<QuestionsService>()
            ProcessManager(
                repository = get(),
                jobRegistry = get(),
                logger = get { parametersOf("homer.app.services.ProcessManager") },
                broadcaster = get(),
                workspaceRoot = Path(get<TextCompiler>().replaceArguments(config.workspaceRootPath, config.globalEnv)),
                onProcessEvicted = questionsService::removeForProcess,
                maxProcesses = config.server.maxProcesses,
                // Both resolved at call time, not construction, to break the cycle
                // ProcessManager → HookService → AgentRunStarter → ProcessExecutor →
                // ProcessManager. See ADR-0105.
                hookRecording = { get<HookService>().recording() },
                onHooksRecorded = { recorded -> get<HookService>().onRowsRecorded(recorded) },
            )
        }
    }
