package com.anything2game.engine.ports

import com.anything2game.contract.LearningPackage
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.session.SessionState

// Interfaces the engine needs from the outside world. :app provides Android implementations;
// tests provide in-memory ones. (Dependency inversion: the core owns the interfaces.)

/** Where packages come from: bundled samples, local cache, or the server. */
interface PackageRepository {
    suspend fun list(): List<PackageSummary>
    suspend fun load(packageId: String): Result<LearningPackage>
    suspend fun load(packageId: String, version: String): Result<LearningPackage> =
        load(packageId).mapCatching { require(it.contentVersion == version); it }
}

data class PackageSummary(
    val packageId: String,
    val title: String,
    val language: String,
    val levelCount: Int,
    /** True for samples and cached demos: the UI must label them as pre-generated. */
    val preGenerated: Boolean,
)

/** Saves the in-progress level so rotation, process death or a cold start resume at the same step. */
interface SessionStore {
    suspend fun save(state: SessionState)
    suspend fun load(packageId: String): SessionState?
    suspend fun load(packageId: String, version: String): SessionState? = load(packageId)?.takeIf { it.contentVersion == version }
    suspend fun clear(packageId: String)
}

interface MasteryStore {
    suspend fun all(packageId: String): Map<String, MasteryRecord>
    suspend fun put(packageId: String, record: MasteryRecord)
    suspend fun all(packageId: String, version: String): Map<String, MasteryRecord> = all(packageId)
    suspend fun put(packageId: String, version: String, record: MasteryRecord) = put(packageId, record)
    suspend fun legacyUnversioned(packageId: String): Map<String, MasteryRecord> = emptyMap()
}

fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Frame overlays for a package (bundled, cached or from the server). Classic needs none. */
interface FrameRepository {
    suspend fun available(packageId: String): List<com.anything2game.contract.FrameId>
    suspend fun load(packageId: String, frameId: com.anything2game.contract.FrameId): com.anything2game.contract.FrameOverlay?
    suspend fun load(packageId: String, version: String, frameId: com.anything2game.contract.FrameId) =
        load(packageId, frameId)?.takeIf { it.contentVersion == version }
}
