package homer.app.di

import homer.app.models.AppConfig
import homer.app.services.DispatchGroupService
import homer.app.services.DispatchService
import org.koin.core.parameter.parametersOf
import org.koin.core.scope.Scope

/** The [DispatchGroupService] (ADR-0109), wired from the dispatch and question config sections. */
internal fun Scope.buildDispatchGroupService(): DispatchGroupService =
    DispatchGroupService(
        repository = get(),
        questionsService = get(),
        agentsService = get(),
        authorizer = get(),
        agentInputResolver = get(),
        processManager = get(),
        broadcaster = get(),
        logger = get { parametersOf("homer.app.services.DispatchGroupService") },
        config = get<AppConfig>().server.dispatch,
        questionsConfig = get<AppConfig>().server.questions,
    )

/** The [DispatchService] (ADR-0021), with the [DispatchGroupService] it fires GROUP dispatches through. */
internal fun Scope.buildDispatchService(): DispatchService =
    DispatchService(
        repository = get(),
        questionsService = get(),
        agentsService = get(),
        authorizer = get(),
        agentRunStarter = get(),
        agentInputResolver = get(),
        processManager = get(),
        broadcaster = get(),
        groupService = get(),
        logger = get { parametersOf("homer.app.services.DispatchService") },
        config = get<AppConfig>().server.dispatch,
    )
