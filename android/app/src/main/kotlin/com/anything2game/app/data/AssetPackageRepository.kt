package com.anything2game.app.data

import android.content.res.AssetManager
import com.anything2game.contract.ContractJson
import com.anything2game.contract.GenerationMode
import com.anything2game.contract.LearningPackage
import com.anything2game.engine.ports.PackageRepository
import com.anything2game.engine.ports.PackageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bundled sample packages (`package.*.json` in assets). Every package is validated before it is offered. */
class AssetPackageRepository(private val assets: AssetManager) : PackageRepository {
    private val mutex = Mutex()
    private var cache: Map<String, LearningPackage>? = null

    private suspend fun all(): Map<String, LearningPackage> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            assets.list("").orEmpty()
                .filter { it.startsWith("package.") && it.endsWith(".json") }
                .sorted()
                .mapNotNull { name -> assets.open(name).bufferedReader().use { ContractJson.readValidPackage(it.readText()).getOrNull() } }
                .associateBy { it.packageId }
        }.also { cache = it }
    }

    override suspend fun list(): List<PackageSummary> = all().values.map {
        PackageSummary(it.packageId, it.title, it.language, it.levels.size, it.generation.mode == GenerationMode.PRE_GENERATED)
    }

    override suspend fun load(packageId: String): Result<LearningPackage> =
        all()[packageId]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException(packageId))
}
