package com.anything2game.server.compile

import com.anything2game.contract.ErrorCodes
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.server.store.Store
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Generates and stores frame overlays. One generation per (package, frame) at a time; a stored overlay is returned as is. */
class FrameService(private val store: Store, private val registry: FrameNodeRegistry) {
    private val log = LoggerFactory.getLogger(FrameService::class.java)
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Null when the frame needs no overlay (classic). Throws [GenerationException] when generation fails. */
    suspend fun ensure(packageId: String, frameId: FrameId): FrameOverlay? {
        val node = registry[frameId]
        val lock = locks.computeIfAbsent("$packageId.${frameId.name}") { Mutex() }
        return lock.withLock {
            val stored = store.getPackage(packageId) ?: throw GenerationException(ErrorCodes.NOT_FOUND, "The package no longer exists.", retryable = false)
            val existing = store.getFrame(packageId, frameId)
            if (existing != null && existing.contentVersion == stored.pkg.contentVersion) return@withLock existing
            val overlay = node.generate(stored.pkg) ?: return@withLock null
            // The package may have been deleted while the model was working; do not resurrect its files.
            if (store.getPackage(packageId) != null) store.saveFrame(overlay)
            log.info("package {}: frame {} generated", packageId, frameId.name.lowercase())
            overlay
        }
    }

    /** Best effort after a compile: a failing frame is logged and can be requested again through the API. */
    suspend fun generateDefaults(packageId: String, frames: List<FrameId>) {
        for (frame in frames) {
            try {
                ensure(packageId, frame)
            } catch (e: GenerationException) {
                log.warn("package {}: default frame {} failed ({})", packageId, frame.name.lowercase(), e.code)
            }
        }
    }
}
