package com.anything2game.app.data

import android.content.res.AssetManager
import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.FrameSpec
import com.anything2game.engine.ports.FrameRepository
import com.anything2game.engine.ports.PackageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bundled overlays (`frame.*.json` in assets), each validated against its package before it is offered. */
class AssetFrameRepository(private val assets: AssetManager, private val packages: PackageRepository) : FrameRepository {
    private val mutex = Mutex()
    private var cache: Map<Pair<String, FrameId>, FrameOverlay>? = null

    private suspend fun all(): Map<Pair<String, FrameId>, FrameOverlay> = mutex.withLock {
        cache ?: run {
            val texts = withContext(Dispatchers.IO) {
                assets.list("").orEmpty().filter { it.startsWith("frame.") && it.endsWith(".json") }
                    .map { name -> assets.open(name).bufferedReader().use { it.readText() } }
            }
            texts.mapNotNull { text ->
                val packageId = runCatching { ContractJson.decodeFrame(text).packageId }.getOrNull() ?: return@mapNotNull null
                val pkg = packages.load(packageId).getOrNull() ?: return@mapNotNull null
                ContractJson.readValidFrame(text, pkg).getOrNull()
            }.associateBy { it.packageId to it.frameId }
        }.also { cache = it }
    }

    override suspend fun available(packageId: String): List<FrameId> {
        val present = all().keys.filter { it.first == packageId }.map { it.second }.toSet()
        return FrameId.entries.filter { !FrameSpec.of(it).needsOverlay || it in present }
    }

    override suspend fun load(packageId: String, frameId: FrameId): FrameOverlay? = all()[packageId to frameId]
}
