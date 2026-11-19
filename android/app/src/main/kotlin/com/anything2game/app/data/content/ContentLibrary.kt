package com.anything2game.app.data.content

import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.FrameSpec
import com.anything2game.contract.GenerationMode
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.pack.ContentPack
import com.anything2game.engine.ports.FrameRepository
import com.anything2game.engine.ports.PackageRepository
import com.anything2game.engine.ports.PackageSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * All content the player can see, merged from every [PackSource]. The engine only sees [PackageRepository] and
 * [FrameRepository]; it never learns whether a course was bundled, downloaded or imported.
 *
 * When two sources hold the same package, the newer contentVersion wins; on a tie the later source wins
 * (sources are given preset-first, so an installed copy replaces the bundled one).
 */
class ContentLibrary(private val sources: List<PackSource>) : PackageRepository, FrameRepository {
    private val mutex = Mutex()
    private var index: Map<String, PackRef>? = null
    private val opened = HashMap<String, ContentPack>()

    /** Forget what was listed, e.g. after a pack was installed or removed. */
    suspend fun refresh() = mutex.withLock { index = null; opened.clear() }

    private suspend fun refs(): Map<String, PackRef> = mutex.withLock {
        index ?: buildMap<String, PackRef> {
            sources.forEach { source ->
                runCatching { source.list() }.getOrDefault(emptyList()).forEach { ref ->
                    val current = get(ref.manifest.packageId)
                    if (current == null || compareVersions(ref.manifest.contentVersion, current.manifest.contentVersion) >= 0) put(ref.manifest.packageId, ref)
                }
            }
        }.also { index = it }
    }

    private suspend fun pack(packageId: String): ContentPack? {
        mutex.withLock { opened[packageId] }?.let { return it }
        val ref = refs()[packageId] ?: return null
        val pack = ref.open().getOrNull() ?: return null
        return mutex.withLock { opened.getOrPut(packageId) { pack } }
    }

    override suspend fun list(): List<PackageSummary> = refs().values.sortedBy { it.manifest.title }.mapNotNull { ref ->
        // Listing opens each pack once (validated, cached); a pack that fails validation is simply not offered.
        val p = pack(ref.manifest.packageId) ?: return@mapNotNull null
        PackageSummary(p.pkg.packageId, p.pkg.title, p.pkg.language, p.pkg.levels.size, p.pkg.generation.mode == GenerationMode.PRE_GENERATED)
    }

    override suspend fun load(packageId: String): Result<LearningPackage> =
        pack(packageId)?.let { Result.success(it.pkg) } ?: Result.failure(NoSuchElementException(packageId))

    override suspend fun available(packageId: String): List<FrameId> {
        val present = pack(packageId)?.frames?.keys.orEmpty()
        return FrameId.entries.filter { !FrameSpec.of(it).needsOverlay || it in present }
    }

    override suspend fun load(packageId: String, frameId: FrameId): FrameOverlay? = pack(packageId)?.frames?.get(frameId)

    internal companion object {
        /** "2" > "10"? No: numeric parts compare as numbers ("1.10" > "1.9"); otherwise as text. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-'); val pb = b.split('.', '-')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i) ?: return -1; val y = pb.getOrNull(i) ?: return 1
                val c = (x.toLongOrNull() to y.toLongOrNull()).let { (nx, ny) -> if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y) }
                if (c != 0) return c
            }
            return 0
        }
    }
}
