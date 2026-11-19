package com.anything2game.server

import com.anything2game.server.adapt.AskService
import com.anything2game.server.api.Authenticator
import com.anything2game.server.api.CompositeAuthenticator
import com.anything2game.server.api.InstallTokenAuthenticator
import com.anything2game.server.api.OidcJwtAuthenticator
import com.anything2game.server.api.Quotas
import com.anything2game.server.compile.CompileJobRunner
import com.anything2game.server.compile.FrameNodeRegistry
import com.anything2game.server.compile.FrameService
import com.anything2game.server.compile.LevelNode
import com.anything2game.server.compile.PlanNode
import com.anything2game.server.compile.PublishNode
import com.anything2game.server.compile.ValidateNode
import com.anything2game.server.compile.layered.ClassicGate
import com.anything2game.server.compile.layered.LayeredPipeline
import com.anything2game.server.compile.layered.SupportGate
import com.anything2game.server.config.PipelineKind
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.config.StoreKind
import com.anything2game.server.ingest.PdfTextExtractor
import com.anything2game.server.llm.LlmFactory
import com.anything2game.server.llm.LlmRouter
import com.anything2game.server.llm.Tier
import com.anything2game.server.store.FileStore
import com.anything2game.server.store.PostgresStore
import com.anything2game.server.store.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.UUID

/**
 * The wired-up server: everything the routes need. One AppContext = one server instance; several can share one database.
 * Tests build one with a fake router and a temp data dir. [jwtAuthenticator] defaults to the OIDC verifier of the configuration (if any).
 */
class AppContext(
    val config: ServerConfig,
    val store: Store,
    val router: LlmRouter,
    jwtAuthenticator: Authenticator? = config.oidc?.let(OidcJwtAuthenticator::fromConfig),
    val workerId: String = newWorkerId(),
    val pdf: PdfTextExtractor = PdfTextExtractor(config.limits.maxPdfPages, config.pdfTimeoutSeconds * 1000L),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val installTokens = InstallTokenAuthenticator(store)
    val authenticator: Authenticator = CompositeAuthenticator(jwtAuthenticator, installTokens)
    val quotas = Quotas(store, config.limits)
    val frames = FrameService(store, FrameNodeRegistry.default(router.client(Tier.FRAME), citedOnly = config.frameInput == "cited"))
    val ask = AskService(router.client(Tier.FLASH), config.askTimeoutMillis)
    val tutor = com.anything2game.server.tutor.TutorService(store, router.client(Tier.COMPILE), router.client(Tier.FLASH),
        config.compileProvider == com.anything2game.server.config.Provider.FAKE || config.flashProvider == com.anything2game.server.config.Provider.FAKE,
        minOf(config.llmTimeoutSeconds * 1000L, 60_000L))
    val runner: CompileJobRunner

    init {
        val compile = router.client(Tier.COMPILE)
        // Experiment flags (A2G_PIPELINE, A2G_SUPPORT_GATE). With both at their defaults nothing below is created and the classic pipeline runs as before.
        val gate = if (config.supportGate) SupportGate(router.client(Tier.FLASH)) else null
        val layered = if (config.pipeline == PipelineKind.LAYERED) {
            LayeredPipeline(compile, router.client(Tier.FLASH), gate, config.layeredParallelism)
        } else null
        val classicGate = if (gate != null && layered == null) ClassicGate(compile, gate, config.layeredParallelism) else null
        runner = CompileJobRunner(
            jobs = store, documents = store, packages = store,
            planNode = PlanNode(compile), levelNode = LevelNode(compile, config.levelPrompt), validateNode = ValidateNode(compile),
            publishNode = PublishNode(store, store, workerId), frames = frames, defaultFrames = config.defaultFrames,
            compileModelId = compile.modelId, scope = scope, workerId = workerId,
            leaseMillis = config.workerLeaseMillis, pollMillis = config.workerPollMillis, maxParallelJobs = config.workerConcurrency,
            layered = layered, classicGate = classicGate, gateEnabled = gate != null,
        )
    }

    /** Starts the job worker (it also picks up jobs that an earlier instance left unfinished) and the counter housekeeping. Idempotent. */
    fun start() {
        runner.start()
        if (housekeeping == null) {
            housekeeping = scope.launch {
                while (true) {
                    runCatching { store.purgeCounters(System.currentTimeMillis() - 3 * 86_400_000L) }
                    delay(3_600_000L)
                }
            }
        }
    }

    @Volatile private var housekeeping: kotlinx.coroutines.Job? = null

    fun close() {
        runner.close()
        scope.cancel()
        runCatching { store.close() }
    }

    companion object {
        fun create(config: ServerConfig): AppContext {
            val store: Store = when (config.storeKind) {
                StoreKind.FILE -> FileStore(config.dataDir)
                StoreKind.POSTGRES -> PostgresStore.connect(requireNotNull(config.db) { "A2G_DB_* is not configured" })
            }
            return AppContext(config, store, LlmFactory.router(config))
        }

        fun newWorkerId(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("worker")
            return "$host-${UUID.randomUUID().toString().take(8)}"
        }
    }
}
