package com.anything2game.app.data.content

import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.FrameSpec
import com.anything2game.contract.GenerationMode
import com.anything2game.contract.LearningPackage
import com.anything2game.app.data.tutor.SourceLayouts
import com.anything2game.app.data.tutor.TutorScripts
import com.anything2game.contract.PresetSourceLayout
import com.anything2game.contract.tutor.TutorScript
import com.anything2game.engine.ports.FrameRepository
import com.anything2game.engine.ports.PackageRepository
import com.anything2game.engine.ports.PackageSummary
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * All content the player can see, merged from every [PackSource]. The engine only sees [PackageRepository] and
 * [FrameRepository]; it never learns whether a course was bundled, downloaded or imported.
 *
 * When two sources hold the same package, the newer contentVersion wins; on a tie the later source wins
 * (sources are given preset-first, so an installed copy replaces the bundled one).
 */
class ContentLibrary(private val sources: List<PackSource>) : PackageRepository, FrameRepository, TutorScripts, SourceLayouts {
    data class DeviceGame(val manifest: PackManifest, val spec: GameSpec, val origin: PackOrigin)
    private val mutex = Mutex()
    private var index: Map<ContentVersion, PackRef>? = null
    private val opened = HashMap<ContentVersion, ContentPack>()

    suspend fun refresh() = mutex.withLock { index = null; opened.clear() }

    private suspend fun refs(): Map<ContentVersion, PackRef> = mutex.withLock {
        index ?: sources.flatMap { it.list() }.associateBy { it.version }.also { index = it }
    }

    private suspend fun latestCourses() = refs().values.filter { it.manifest.schema == PACK_SCHEMA_V1 }
        .groupBy { it.manifest.packageId }.mapValues { (_, versions) ->
            versions.maxWith { a, b -> compareVersions(a.manifest.contentVersion, b.manifest.contentVersion) }
        }

    suspend fun manifests() = refs().values.map { it.manifest }

    suspend fun install(input: InputStream, availableDocuments: Set<ContentVersion> = emptySet(), expected: ContentVersion? = null,
        beforeCoursePublish: suspend (ContentPack) -> Unit = {}, authenticatedGame: Boolean = false,
        verifyActivity: suspend (ActivityPack) -> Unit = {}): PackManifest {
        val disk = sources.filterIsInstance<DirectoryPackSource>().last()
        val bundled = sources.filter { it !== disk }.flatMap { it.list() }
        val manifest = disk.install(input, availableVersions() + availableDocuments, expected, bundled, beforeCoursePublish, authenticatedGame, verifyActivity).getOrThrow()
        refresh()
        return manifest
    }

    /** Only verified playable games are offered, together with their offline provenance. */
    suspend fun games(language: String? = null): List<DeviceGame> =
        refs().values.filter { it.manifest.packageSchema == GAME_SCHEMA && (language == null || it.manifest.language == language) }
            .mapNotNull { ref -> game(ref.version)?.let { DeviceGame(ref.manifest, it, ref.installedOrigin) } }

    /** Where an installed version came from (bundled preset, server or import), without opening its archive. */
    suspend fun originOf(version: ContentVersion): PackOrigin? = refs()[version]?.installedOrigin

    suspend fun availableVersions(): Set<ContentVersion> = refs().values.mapNotNull { ref ->
        when {
            ref.manifest.schema == PACK_SCHEMA_V1 -> ref.version.takeIf { course(it) != null }
            ref.manifest.packageSchema == GAME_SCHEMA -> ref.version.takeIf { game(it) != null }
            else -> null
        }
    }.toSet()

    suspend fun activity(version: ContentVersion, available: Set<ContentVersion>): ActivityPack? =
        refs()[version]?.activity(available)?.getOrNull()

    suspend fun game(version: ContentVersion) = (activity(version, emptySet())?.payload as? ActivityPayload.Game)?.game

    /** Read an already installed path even after a dependency disappeared; route status checks the current inventory. */
    suspend fun path(version: ContentVersion): com.anything2game.contract.space.LearningPath? {
        val ref = refs()[version] ?: return null
        return (ref.activity(ref.manifest.dependencies).getOrNull()?.payload as? ActivityPayload.Path)?.path
    }

    suspend fun course(version: ContentVersion): ContentPack? {
        mutex.withLock { opened[version] }?.let { return it }
        val ref = refs()[version] ?: return null
        val pack = ref.open().getOrNull() ?: return null
        return mutex.withLock {
            val full = pack.copy(sourceLayout = null)
            if (index?.get(version) === ref) opened.getOrPut(version) { full } else full
        }
    }

    private suspend fun pack(packageId: String) = latestCourses()[packageId]?.let { course(it.version) }

    override suspend fun list(): List<PackageSummary> = latestCourses().values.sortedBy { it.manifest.title }.mapNotNull { ref ->
        // Listing opens each pack once (validated, cached); a pack that fails validation is simply not offered.
        val p = pack(ref.manifest.packageId) ?: return@mapNotNull null
        PackageSummary(p.pkg.packageId, p.pkg.title, p.pkg.language, p.pkg.levels.size, p.pkg.generation.mode == GenerationMode.PRE_GENERATED)
    }

    override suspend fun load(packageId: String): Result<LearningPackage> =
        pack(packageId)?.let { Result.success(it.pkg) } ?: Result.failure(NoSuchElementException(packageId))

    override suspend fun load(packageId: String, version: String): Result<LearningPackage> =
        course(ContentVersion(packageId, version))?.let { Result.success(it.pkg) } ?: Result.failure(NoSuchElementException(packageId))

    override suspend fun load(packageId: String, version: String, frameId: FrameId) = course(ContentVersion(packageId, version))?.frames?.get(frameId)

    override suspend fun available(packageId: String) = availableFrames(pack(packageId))

    suspend fun available(version: ContentVersion) = availableFrames(course(version))

    private fun availableFrames(course: ContentPack?): List<FrameId> {
        val present = course?.frames?.keys.orEmpty()
        return FrameId.entries.filter { !FrameSpec.of(it).needsOverlay || it in present }
    }

    override suspend fun load(packageId: String, frameId: FrameId): FrameOverlay? = pack(packageId)?.frames?.get(frameId)

    override suspend fun scriptFor(packageId: String): TutorScript? = pack(packageId)?.tutor

    /** Re-reads the pack (fully validated again) for its geometry; rare (attaching, opening a course) and never cached. */
    override suspend fun sourceLayoutFor(packageId: String): PresetSourceLayout? {
        if (pack(packageId)?.manifest?.entries?.any { it.path == PackCodec.LAYOUT } != true) return null
        return latestCourses()[packageId]?.open()?.getOrNull()?.sourceLayout
    }

    override suspend fun sourceLayoutFor(packageId: String, contentVersion: String): PresetSourceLayout? {
        val ref = refs()[ContentVersion(packageId, contentVersion)] ?: return null
        return ref.takeIf { it.manifest.entries.any { entry -> entry.path == PackCodec.LAYOUT } }?.open()?.getOrNull()?.sourceLayout
    }

    override suspend fun savedScript(key: String): Pair<LearningPackage, TutorScript>? {
        for (ref in refs().values.filter { it.manifest.entries.any { entry -> entry.path == PackCodec.TUTOR } }) {
            val pack = course(ref.version) ?: continue
            val script = pack.tutor ?: continue
            if (com.anything2game.app.data.tutor.localTutorKey(pack.pkg) == key) return pack.pkg to script
        }
        return null
    }

    override suspend fun scriptedPackageIds(): List<String> =
        latestCourses().values.sortedBy { it.manifest.title }.map { it.manifest.packageId }.filter { pack(it)?.tutor != null }

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
