package homer.app

import com.charleskorn.kaml.Yaml
import homer.app.auth.CsrfProtection
import homer.app.config.logLevelSystemProps
import homer.app.config.resolveLlmPrice
import homer.app.di.appModule
import homer.app.executors.ProcessExecutor
import homer.app.infra.PasswordVerifier
import homer.app.infra.SysEnvResolver
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.RunnerConfig
import homer.app.models.flattenAuthSchemes
import homer.app.routes.ApiDeps
import homer.app.routes.apiV1Routes
import homer.app.routes.apiV1UnsecuredRoutes
import homer.app.routes.callbackRoutes
import homer.app.routes.mcpRoutes
import homer.app.routes.questionsMcpRoutes
import homer.app.routes.runnerBearerToken
import homer.app.routes.runnerProtocolRoutes
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.runners.kubernetes.KubernetesRunnerStartupSweep
import homer.app.services.AgentsService
import homer.app.services.ContinuationSweepService
import homer.app.services.CronSchedulerService
import homer.app.services.DispatchSweepService
import homer.app.services.MaintenanceModeService
import homer.app.services.StartupReconciliation
import homer.app.services.WorkspacePurgeService
import homer.app.services.persistence.DatabaseService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("homer.app.Application")
val serverId = UUID.randomUUID().toString()

/**
 * Drain flag flipped to `true` at the very first instant of the
 * `ApplicationStopping` lifecycle event (before the in-flight job join
 * loop). Read by `GET /api/v1/readyz`, which returns `503` while
 * draining and `200` otherwise. Kept separate from `/livez` so a
 * Kubernetes liveness probe does **not** see 503 during drain and
 * restart the pod mid-drain — exactly the failure that would defeat
 * `server.shutdownDrainSec`. Audit P1 #12.
 */
val isDraining: AtomicBoolean = AtomicBoolean(false)

fun main(args: Array<String>) {
    // Apply HOMER_LOG_LEVELS overrides before any DI-created logger is built.
    // Only set a property the operator hasn't pinned via -D, so a system
    // property always wins over the env var.
    logLevelSystemProps(System.getenv()).forEach { (key, value) ->
        if (System.getProperty(key) == null) System.setProperty(key, value)
    }

    val resolvedConfig = loadStartupConfig(args) ?: return

    logger.info(
        "Starting server on port {} with workspace: {}",
        resolvedConfig.server.port,
        resolvedConfig.workspaceRootPath,
    )
    logger.info("Agents search path: {}", resolvedConfig.agentsSearchPath)

    embeddedServer(Netty, port = resolvedConfig.server.port) {
        configureServer(resolvedConfig)
    }.start(wait = true)
}

/**
 * Reads, parses and `@SYSENV`-resolves the config file named by `args[0]`. Returns `null`
 * after logging and printing why startup cannot continue.
 */
internal fun loadStartupConfig(
    args: Array<String>,
    sysEnvResolver: SysEnvResolver = SysEnvResolver(),
): AppConfig? {
    val config = readStartupConfig(args) ?: return null
    return resolveStartupConfig(config, sysEnvResolver)
}

/** Loads the config file named by `args[0]`, capped at the bootstrap size; `null` = refused. */
private fun readStartupConfig(args: Array<String>): AppConfig? {
    if (args.isEmpty()) {
        logger.error("Missing required argument: config-path")
        println("Usage: <config-path>")
        println("  config-path - Path to config.json file")
        return null
    }

    val configPath = args[0]
    logger.info("Loading configuration from: {}", configPath)

    // Load configuration to get port and workspace
    val configFile = File(configPath)
    if (!configFile.exists()) {
        logger.error("Configuration file not found: {}", configPath)
        println("Error: Configuration file not found: $configPath")
        return null
    }

    // Cap the config file size at bootstrap before reading it into memory
    // and handing it to kaml. We don't know the configured cap yet (it
    // lives inside the file we're about to read), so use the same compile-
    // time default that the model declares. Operators that legitimately
    // need a larger config can split it or, once parsed, raise
    // server.maxConfigFileBytes for subsequent reads (agent files,
    // reload). Audit P1 #14.
    val bootstrapConfigSizeCap: Long = 1L * 1024 * 1024
    val configSizeBytes = configFile.length()
    if (configSizeBytes > bootstrapConfigSizeCap) {
        logger.error(
            "Configuration file {} is {} bytes; cap is {} bytes. Refusing to load.",
            configPath,
            configSizeBytes,
            bootstrapConfigSizeCap,
        )
        println("Error: Configuration file is $configSizeBytes bytes; cap is $bootstrapConfigSizeCap bytes.")
        return null
    }

    return try {
        if (configFile.extension == "json") {
            Json.decodeFromString<AppConfig>(configFile.readText())
        } else if (configFile.extension == "yaml") {
            Yaml.default.decodeFromString(
                AppConfig.serializer(),
                configFile.readText(),
            )
        } else {
            throw UnknownError("Cant parse configuration file type ${configFile.extension}")
        }
    } catch (e: Exception) {
        logger.error("Failed to parse configuration file: {}", e.message, e)
        println("Error: Failed to parse configuration file: ${e.message}")
        return null
    }.run { copy(globalEnv = globalEnv + mapOf(EnvConstants.SERVER_ID to serverId)) }
}

/** Resolves every `@SYSENV:` reference in [config]; `null` = a reference could not be resolved. */
private fun resolveStartupConfig(
    config: AppConfig,
    sysEnvResolver: SysEnvResolver,
): AppConfig? =
    try {
        val resolvedGlobalEnv = sysEnvResolver.resolveMapValues(config.globalEnv)
        // null (apiAuth omitted) passes through; configureServer fails startup on it.
        val resolvedApiAuth = config.server.apiAuth?.let { resolveAuth(it, sysEnvResolver) }
        val resolvedLangfuse =
            config.langfuse.copy(
                publicKey = sysEnvResolver.resolve(config.langfuse.publicKey),
                secretKey = sysEnvResolver.resolve(config.langfuse.secretKey),
            )
        config.copy(
            globalEnv = resolvedGlobalEnv,
            // Computed from the RAW globalEnv: after resolveMapValues the
            // placeholders are gone. Marks host-sourced values as secret so
            // they are masked in telemetry (ADR-0047 §7).
            sysEnvBackedGlobalEnvNames = sysEnvResolver.secretNamesFor(config.globalEnv),
            // Same resolution + secrecy contract as globalEnv, per runner: a
            // runner-env value pulled from the host env is a credential too.
            runners = resolveRunnerEnvs(config.runners, sysEnvResolver),
            sysEnvBackedRunnerEnvNames =
                config.runners.mapValues { (_, runner) ->
                    when (runner) {
                        is RunnerConfig.Ssh -> sysEnvResolver.secretNamesFor(runner.env)
                        is RunnerConfig.Kubernetes -> sysEnvResolver.secretNamesFor(runner.env)
                        is RunnerConfig.External -> sysEnvResolver.secretNamesFor(runner.env)
                    }
                },
            server =
                config.server.copy(
                    apiAuth = resolvedApiAuth,
                    corsAllowedOrigins = resolveCorsOrigins(config.server.corsAllowedOrigins, sysEnvResolver),
                ),
            langfuse = resolvedLangfuse,
            databasePath = sysEnvResolver.resolve(config.databasePath),
            llmPrice = resolveLlmPrice(config.llmPrice, sysEnvResolver::resolve),
        )
    } catch (e: IllegalStateException) {
        logger.error("Configuration error: {}", e.message)
        println("Error: ${e.message}")
        null
    }

/**
 * Installs the routing-scoped ContentNegotiation and wires every API / MCP
 * route under the same rate-limit / authenticate / CSRF nesting as before.
 *
 * ContentNegotiation is route-scoped (installed here, not at application level)
 * so /mcp can override it with McpJson. Ktor forbids mixing an application-level
 * install with a per-route one (DuplicatePluginException). StatusPages responds
 * outside routing and serializes apiJson explicitly.
 */
private fun Application.configureRouting(
    config: AppConfig,
    deps: ApiDeps,
    authNames: List<String>,
) {
    routing {
        install(ContentNegotiation) {
            json(apiJson)
        }

        apiV1UnsecuredRoutes(deps)
        // Token-authenticated at the route level (HMR_CALLBACK_TOKEN), NOT by
        // the operator auth schemes — keep physically outside authenticate(...)
        // so the two credential domains can never satisfy each other. ADR-0019.
        callbackRoutes(deps)
        // Domain-token principal (runners.*.token), the same structural boundary
        // as the callback token: outside authenticate(...). ADR-0066, §12.
        runnerProtocolRoutes(deps.runnerRegistry.externalDomains, deps.runnerRegistry.queue)
        if (config.server.mcp.enabled) {
            questionsMcpRoutes(deps)
        }

        if (authNames.isEmpty()) {
            rateLimit(RateLimitName("perCaller")) {
                apiV1Routes(deps)
                if (config.server.mcp.enabled) {
                    mcpRoutes(deps)
                }
            }
        } else {
            authenticate(*authNames.toTypedArray(), strategy = AuthenticationStrategy.FirstSuccessful) {
                // Must nest INSIDE authenticate: both read the principal that
                // authentication populates first. CsrfProtection guards session:
                // callers (see ADR-0016); the rate limiter keys on the same
                // principal (see ADR-0014).
                install(CsrfProtection)
                rateLimit(RateLimitName("perCaller")) {
                    apiV1Routes(deps)
                    // CSRF guard above is session-cookie-scoped (ADR-0016), so
                    // bearer/HMAC MCP clients pass without a CSRF header.
                    if (config.server.mcp.enabled) {
                        mcpRoutes(deps)
                    }
                }
            }
        }
    }
}

/**
 * Shutdown drain (Audit P0 #3): join in-flight ProcessExecutor jobs, bounded
 * by [drainTimeout], then close whatever runner sessions remain. Without the
 * drain, k8s rolling updates or a SIGTERM during local dev cancel mid-flight
 * Claude runs, leaving partial artifacts and incurring billed cost without a
 * result.
 */
internal fun drainInFlightJobsThenCloseSessions(
    applicationScope: CoroutineScope,
    activeSessions: ActiveSessionsRegistry,
    drainTimeout: Duration,
) {
    val children =
        applicationScope.coroutineContext.job.children
            .toList()
    if (children.isNotEmpty()) {
        logger.info("Draining {} in-flight process job(s), timeout={}s", children.size, drainTimeout.inWholeSeconds)
        val drained =
            runBlocking {
                withTimeoutOrNull(drainTimeout) {
                    children.joinAll()
                    true
                } ?: false
            }
        if (drained) {
            logger.info("Drain complete")
        } else {
            logger.warn(
                "Drain timed out after {}s with {} job(s) still running; engine will stop anyway",
                drainTimeout.inWholeSeconds,
                applicationScope.coroutineContext.job.children
                    .count(),
            )
        }
    }

    // Must run AFTER the join: closing a remote session deletes the pod /
    // connection out from under a run the drain is waiting for, failing it.
    // Runs that finish inside the window release their own session in the
    // executor's finally; only what is still registered here is an orphan.
    activeSessions.closeAll()
}

fun Application.configureServer(config: AppConfig) {
    validateStartupConfig(config)
    installKoin(config)
    installHttpPlugins(config)
    val databaseService = migrateDatabase(config)

    // Non-fatal: warn on HMAC keys that can run query-reading agents without binding
    // {{query}} (needs the loaded agent definitions, hence after AgentsService init).
    warnOnUnsignedQueryInputs(config, get<AgentsService>().agents.values)

    val drainTimeout = config.server.shutdownDrainSec.seconds
    val background = BackgroundServices(this)
    subscribeShutdownDrain(databaseService, background, drainTimeout)
    startBackgroundWork(databaseService, background)

    val schemes = flattenAuthSchemes(config.server.apiAuth)
    val passwordVerifier = get<PasswordVerifier>()
    val authSetup = configureAuth(config, schemes, passwordVerifier)

    warnIfUnsecured(config)

    configureRouting(config, apiDeps(config, authSetup, passwordVerifier, background, drainTimeout), authSetup.authNames)
}

/** The boot-time config checks, run before any wiring: a failed `validate*` aborts the boot. */
private fun validateStartupConfig(config: AppConfig) {
    // Abort boot on a principal still using the removed 'endpoints' allowlist.
    validateNoLegacyEndpoints(config)
    // Abort boot when apiAuth is omitted — unsecured requires explicit opt-in.
    validateExplicitApiAuth(config)
    // Abort boot on a runner-domain token that collides with an apiAuth bearer token.
    validateRunnerDomainTokens(config)
    // Abort boot on a too-short runner-domain token (checked post-resolution).
    validateRunnerDomainTokenStrength(config)
    // Abort boot before any wiring if cookie auth lacks pinned CORS origins.
    validateCorsForCookieAuth(config)
    // Abort boot on an HMAC key whose signature is replayable across endpoints.
    validateHmacSignatureScope(config)
    // Abort boot on a too-short session signing secret (checked post-resolution).
    validateSessionSecretStrength(config)
    // Non-fatal: warn on HMAC keys with no replay window.
    warnOnHmacReplayWindow(config)
    // Abort boot if Langfuse keys/content would egress over plaintext http.
    validateLangfuseServerUrl(config)
    // Abort boot on an extraUploads entry whose local path is missing (ADR-0042).
    validateExtraUploads(config)
}

/** Installs Koin with [config] as the `AppConfig` singleton next to [appModule]. */
private fun Application.installKoin(config: AppConfig) {
    install(Koin) {
        // slf4jLogger() - Optional logger
        modules(
            // Config module - provide AppConfig as singleton
            module {
                single { config }
            },
            // Application modules
            appModule,
        )
    }
}

/** Installs the application-level HTTP plugins in their load-bearing order, ending with the body limit. */
private fun Application.installHttpPlugins(config: AppConfig) {
    // Install CORS
    configureCors(config.server.corsAllowedOrigins)

    // Install hardening headers (clickjacking, MIME-sniffing, referrer, CSP)
    configureSecurityHeaders()

    // Install API access logging (writes to api.log via the homer.access logger)
    configureCallLogging()

    // Install StatusPages
    configureStatusPages()

    // Install RateLimit
    configureRateLimiting(config)

    // Must be application-level and installed BEFORE configureAuth: the HMAC
    // body-capture plugin reads the whole body in its onCall, before any
    // routing-scoped limit would attach.
    install(RequestBodyLimit) {
        bodyLimit { call -> runnerRouteBodyLimit(config, call.request.path(), call.request.runnerBearerToken()) }
    }
}

/**
 * Migrates the database and gathers planner statistics, before any other injection: a service
 * initializer can reach SQLite — AgentsService scans the agents directory on construction, and that
 * scan resolves the runner registry. See ADR-0020.
 */
private fun Application.migrateDatabase(config: AppConfig): DatabaseService {
    val databaseService = get<DatabaseService>()
    try {
        databaseService.migrate()
    } catch (e: Exception) {
        // Fail fast and loud: a boot that continues past a failed migration would
        // serve with unreadable durable state. The alternative the ordering used
        // to produce was worse but silent. See ADR-0020.
        logger.error(
            "Database migration failed for {}; refusing to boot with unreadable durable state. See ADR-0020.",
            config.databasePath,
            e,
        )
        throw e
    }
    // Planner statistics for tables that have none yet, before the first query.
    // See services/persistence/CLAUDE.md.
    databaseService.optimize()
    return databaseService
}

/**
 * The services the boot starts and the shutdown drain stops, each resolved from Koin on its first
 * use during the boot. The drain must reuse those instances: Koin closes on `ApplicationStopping`
 * before the drain's own handler runs, so nothing can be resolved there.
 */
private class BackgroundServices(
    app: Application,
) {
    val runnerRegistry by app.inject<RunnerRegistry>()
    val cronSchedulerService by app.inject<CronSchedulerService>()
    val maintenanceModeService by app.inject<MaintenanceModeService>()
    val workspacePurgeService by app.inject<WorkspacePurgeService>()
    val dispatchSweepService by app.inject<DispatchSweepService>()
    val continuationSweepService by app.inject<ContinuationSweepService>()
    val activeSessions by app.inject<ActiveSessionsRegistry>()
    val applicationScope by app.inject<CoroutineScope>(qualifier = named("applicationScope"))
}

/**
 * On `ApplicationStopping`: flips the drain flag, stops the [background] services, drains in-flight
 * jobs within [drainTimeout] and closes [databaseService] last.
 */
private fun Application.subscribeShutdownDrain(
    databaseService: DatabaseService,
    background: BackgroundServices,
    drainTimeout: Duration,
) {
    monitor.subscribe(ApplicationStopping) {
        // Flip the readiness flag **first** so /readyz starts answering 503
        // immediately, before we begin joining in-flight jobs. The load
        // balancer / Kubernetes Service then stops sending new requests to
        // this pod while the existing ones finish under the drain timeout.
        // /livez stays 200 throughout, so the kubelet does not restart the
        // pod mid-drain. Audit P1 #12.
        if (isDraining.compareAndSet(false, true)) {
            logger.info("Drain initiated; /api/v1/readyz will now answer 503")
        }

        // Stop the claim-queue consumers right after: their `while (isActive)` loops
        // never complete on their own, so leaving them running would make the
        // drain below always burn the full timeout waiting on jobs that were
        // never going to finish. See ADR-0066.
        background.runnerRegistry.stop()

        background.cronSchedulerService.stop()
        // Fail the parked maintenance-mode starts now: left alone, they would hold their
        // requests until their own wait expires, burning the whole drain window. See ADR-0092.
        background.maintenanceModeService.stop()
        background.workspacePurgeService.stop()
        background.dispatchSweepService.stop()
        background.continuationSweepService.stop()

        drainInFlightJobsThenCloseSessions(background.applicationScope, background.activeSessions, drainTimeout)

        // Close the database last — after in-flight writes have drained — so a
        // graceful shutdown never truncates the WAL mid-write. See ADR-0020.
        databaseService.close()
    }
}

/** Reconciles interrupted runs, then starts cron, the sweeps, database maintenance and the runners. */
private fun Application.startBackgroundWork(
    databaseService: DatabaseService,
    background: BackgroundServices,
) {
    // Reconcile interrupted runs BEFORE starting cron / sweep or accepting
    // traffic, so a client never observes a CREATED/WORKING row that no
    // coroutine on this instance is driving. The schema was opened and created
    // above, before the first injection. See ADR-0020.
    runBlocking { get<StartupReconciliation>().reconcile() }

    background.cronSchedulerService.start()
    background.workspacePurgeService.start(CoroutineScope(Dispatchers.Default))
    databaseService.startMaintenance(CoroutineScope(Dispatchers.Default))
    background.dispatchSweepService.start(CoroutineScope(Dispatchers.Default))
    background.continuationSweepService.start(CoroutineScope(Dispatchers.Default))
    background.runnerRegistry.start(background.applicationScope)
    runBlocking { get<KubernetesRunnerStartupSweep>().sweep() }
}

/** Logs the unsecured-mode banner when `apiAuth` is [AuthConfig.Unsecured]. */
private fun warnIfUnsecured(config: AppConfig) {
    if (config.server.apiAuth is AuthConfig.Unsecured) {
        logger.warn(
            "\n" +
                """
                !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                !!! Server api endpoints are unsecured !!!
                !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                """.trimIndent(),
        )
    }
}

/** The route dependencies: [background] and [authSetup] as the boot resolved them, the rest from Koin in argument order. */
private fun Application.apiDeps(
    config: AppConfig,
    authSetup: AuthSetup,
    passwordVerifier: PasswordVerifier,
    background: BackgroundServices,
    drainTimeout: Duration,
): ApiDeps =
    ApiDeps(
        config = config,
        authorizer = get(),
        serverId = serverId,
        isDraining = isDraining,
        applicationScope = background.applicationScope,
        drainTimeout = drainTimeout,
        processManager = get(),
        agentsService = get(),
        agentFilesService = get(),
        langGraphInspectService = get(),
        langGraphRunStatusService = get(),
        parallelRunsManager = get(),
        agentInputResolver = get(),
        agentRunStarter = get(),
        mcpToolFactory = get(),
        telemetryService = get(),
        processFactory = get(),
        processExecutor = get(),
        artifactService = get(),
        fileOperations = get(),
        cronSchedulerService = background.cronSchedulerService,
        maintenanceModeService = background.maintenanceModeService,
        workspacePurgeService = background.workspacePurgeService,
        processEventBroadcaster = get(),
        logTailService = get(),
        llmEventStreamService = get(),
        questionsService = get(),
        dispatchService = get(),
        continuationService = get(),
        resultTokenService = get(),
        questionsMcpToolFactory = get(),
        activeSessions = background.activeSessions,
        startupSweep = get(),
        sweepStatus = get(),
        runnerRegistry = background.runnerRegistry,
        costMeter = get(),
        passwordScheme = authSetup.passwordScheme,
        passwordVerifier = passwordVerifier,
        hmacBodyKey = authSetup.hmacBodyKey,
    )
