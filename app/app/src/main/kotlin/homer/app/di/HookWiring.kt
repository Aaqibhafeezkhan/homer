package homer.app.di

import homer.app.models.AppConfig
import homer.app.services.HookService
import homer.app.services.persistence.HookRepository
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * The agent-level status-hook layer (ADR-0105): the durable row queue and the service that fires
 * it. Beside [appModule] for the same reason `ProcessWiring` is — that file is at its size limit
 * (root CLAUDE.md §2).
 */
internal val hookModule =
    module {
        single { HookRepository(db = get()) }

        single {
            HookService(
                repository = get(),
                agentRunStarter = get(),
                agents = get(),
                authorizer = get(),
                agentInputResolver = get(),
                processManager = get(),
                logger = get { parametersOf("homer.app.services.HookService") },
                config = get<AppConfig>().server.hooks,
            )
        }
    }
