package com.anything2game.server

import com.anything2game.server.adapt.AskService
import com.anything2game.server.api.InstallAuth
import com.anything2game.server.api.Quotas
import com.anything2game.server.compile.CompileJobRunner
import com.anything2game.server.compile.FrameNodeRegistry
import com.anything2game.server.compile.FrameService
import com.anything2game.server.compile.LevelNode
import com.anything2game.server.compile.PlanNode
import com.anything2game.server.compile.PublishNode
import com.anything2game.server.compile.ValidateNode
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.ingest.PdfTextExtractor
import com.anything2game.server.llm.LlmFactory
import com.anything2game.server.llm.LlmRouter
import com.anything2game.server.llm.Tier
import com.anything2game.server.store.FileStore
import com.anything2game.server.store.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

/** The wired-up server: everything the routes need. Tests build one with a fake router and a temp data dir. */
class AppContext(
    val config: ServerConfig,
    val store: Store,
    val router: LlmRouter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val auth = InstallAuth(store)
    val quotas = Quotas(store, config.limits)
    val pdf = PdfTextExtractor(config.limits.maxPdfPages)
    val frames = FrameService(store, FrameNodeRegistry.default(router.client(Tier.FRAME)))
    val ask = AskService(router.client(Tier.FLASH), config.askTimeoutMillis)
    val runner: CompileJobRunner

    init {
        val compile = router.client(Tier.COMPILE)
        val stateLock = Mutex()
        runner = CompileJobRunner(
            store = store, planNode = PlanNode(compile), levelNode = LevelNode(compile), validateNode = ValidateNode(compile),
            publishNode = PublishNode(store, stateLock), frames = frames, defaultFrames = config.defaultFrames,
            compileModelId = compile.modelId, stateLock = stateLock, scope = scope,
        )
    }

    /** Re-queues unfinished jobs. Call once after construction, before serving. */
    fun start() = runner.resumePending()

    fun close() = runner.close()

    companion object {
        fun create(config: ServerConfig): AppContext = AppContext(config, FileStore(config.dataDir), LlmFactory.router(config))
    }
}
