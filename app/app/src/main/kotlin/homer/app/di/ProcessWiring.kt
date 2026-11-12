package homer.app.di

import homer.app.infra.TextCompiler
import homer.app.models.AppConfig
import homer.app.services.HookService
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
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
                // ProcessManager. See ADR-0107.
                hookRecording = { get<HookService>().recording() },
                // Launched on the application scope, never awaited on the committing caller's
                // coroutine: one fire runs row reads, a grant check and a complete nested
                // start, and a start or kill response must not wait on the chain its run fans
                // out to — under the maintenance gate a CREATED hook's start would park the
                // response for the whole queue wait, then settle FAILED. The rows are durable
                // and claim-gated, so a fire this launch never reaches (a shutdown) is
                // replayed at boot. See ADR-0107.
                onHooksRecorded = { recorded ->
                    get<CoroutineScope>(named("applicationScope")).launch {
                        get<HookService>().onRowsRecorded(recorded)
                    }
                },
            )
        }
    }
