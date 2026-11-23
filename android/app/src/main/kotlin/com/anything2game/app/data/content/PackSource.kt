package com.anything2game.app.data.content

import android.content.res.AssetManager
import com.anything2game.app.data.*
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.game.GAME_SCHEMA
import com.anything2game.contract.ContractJson
import com.anything2game.contract.pack.*
import com.anything2game.engine.game.GameRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class PackRef(val manifest: PackManifest, val sourceName: String, val installedOrigin: PackOrigin = PackOrigin.IMPORT, private val stream: () -> InputStream) {
    val version get() = ContentVersion(manifest.packageId, manifest.contentVersion)
    suspend fun open() = withContext(Dispatchers.IO) { stream().use { PackCodec.read(it) } }
    suspend fun activity(installed: Set<ContentVersion>) = withContext(Dispatchers.IO) {
        stream().use { PackCodec.readActivity(it, installed, validateGame = GameRuntime::validate) }
    }
}

interface PackSource {
    val name: String
    suspend fun list(): List<PackRef>
}

private fun ref(name: String, origin: (PackManifest) -> PackOrigin = { PackOrigin.IMPORT }, stream: () -> InputStream) =
    stream().use { PackCodec.readManifest(it) }.getOrNull()?.let { PackRef(it, name, origin(it), stream) }

class AssetPackSource(private val assets: AssetManager, private val dir: String = "packs") : PackSource {
    override val name = "preset"
    override suspend fun list(): List<PackRef> = withContext(Dispatchers.IO) {
        assets.list(dir).orEmpty().filter { it.endsWith(PACK_EXTENSION) }.sorted().mapNotNull { file ->
            ref(name, { PackOrigin.PRESET }) { assets.open("$dir/$file") }
        }
    }
}

class DirectoryPackSource(private val dir: File, override val name: String = "installed") : PackSource {
    private fun receipt(manifest: PackManifest) = File(dir, stableId(ContractJson.json.encodeToString(manifest)) + ".server-game")
    private fun files() = dir.listFiles { f -> f.isFile && f.name.endsWith(PACK_EXTENSION) }.orEmpty().sortedBy { it.name }
    override suspend fun list(): List<PackRef> = withContext(Dispatchers.IO) {
        files().mapNotNull { file -> ref(name, { manifest ->
            if (manifest.packageSchema == GAME_SCHEMA && receipt(manifest).isFile) PackOrigin.SERVER else PackOrigin.IMPORT
        }, file::inputStream) }
    }

    suspend fun install(input: InputStream, dependencies: Set<ContentVersion> = emptySet(), expected: ContentVersion? = null,
        bundled: List<PackRef> = emptyList(), beforeCoursePublish: suspend (ContentPack) -> Unit = {}, authenticatedGame: Boolean = false,
        verifyActivity: suspend (ActivityPack) -> Unit = {}): Result<PackManifest> = withContext(Dispatchers.IO) {
        dir.mutex().withLock {
            runCatching {
                check(dir.isDirectory || dir.mkdirs())
                val tmp = File.createTempFile("incoming", ".part", dir)
                try {
                    input.use { src -> tmp.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = src.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 40L shl 20) { "Archive too large" }
                            out.write(buffer, 0, count)
                        }
                        out.fd.sync()
                    } }
                    val incoming = requireNotNull(ref(name, stream = tmp::inputStream)) { "Invalid archive manifest" }
                    val version = incoming.version
                    require(expected == null || expected == version) { "Unexpected content identity" }
                    val existingFile = files().firstOrNull { ref(name, stream = it::inputStream)?.version == version }
                    val old = existingFile?.let { ref(name, stream = it::inputStream) } ?: bundled.lastOrNull { it.version == version }
                    if (incoming.manifest.schema == PACK_SCHEMA_V2) {
                        verifyActivity(incoming.activity(dependencies).getOrThrow())
                        require(old == null || incoming.manifest == old.manifest) { "Immutable version conflict" }
                        if (authenticatedGame) {
                            require(expected == version && incoming.manifest.packageSchema == GAME_SCHEMA && incoming.manifest.origin == PackOrigin.SERVER)
                            receipt(incoming.manifest).createNewFile()
                        }
                    } else {
                        val next = incoming.open().getOrThrow()
                        // The validated incoming archive can repair identical contents even when the old payload is damaged.
                        if (old != null && old.manifest != next.manifest) {
                            val previous = old.open().getOrThrow()
                            require(next.pkg.copy(levels = previous.pkg.levels) == previous.pkg &&
                                next.pkg.levels.take(previous.pkg.levels.size) == previous.pkg.levels &&
                                previous.frames.all { next.frames[it.key] == it.value } &&
                                (previous.tutor == null || next.tutor == previous.tutor) &&
                                (previous.sourceLayout == null || next.sourceLayout == previous.sourceLayout)) { "Changed existing course content" }
                        }
                        beforeCoursePublish(next)
                    }
                    tmp.promote(existingFile ?: File(dir, stableId(version.contentId, version.contentVersion) + PACK_EXTENSION))
                    incoming.manifest
                } finally { tmp.delete() }
            }
        }
    }

    suspend fun remove(packageId: String): Boolean = withContext(Dispatchers.IO) {
        dir.mutex().withLock {
            val matches = files().mapNotNull { file ->
                ref(name, stream = file::inputStream)?.manifest?.takeIf { it.packageId == packageId }?.let { file to it }
            }
            matches.isNotEmpty() && matches.all { (file, manifest) ->
                java.nio.file.Files.deleteIfExists(receipt(manifest).toPath())
                file.delete()
            }
        }
    }
}
