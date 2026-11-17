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
    suspend fun clear(packageId: String)
}

interface MasteryStore {
    suspend fun all(packageId: String): Map<String, MasteryRecord>
    suspend fun put(packageId: String, record: MasteryRecord)
}

fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}
