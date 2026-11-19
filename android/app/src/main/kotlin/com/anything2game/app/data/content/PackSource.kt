package com.anything2game.app.data.content

import android.content.res.AssetManager
import com.anything2game.contract.pack.ContentPack
import com.anything2game.contract.pack.PACK_EXTENSION
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.pack.PackManifest
import com.anything2game.contract.pack.PackTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A pack found in some source: its manifest (cheap to read) and how to open the whole thing. */
class PackRef(val manifest: PackManifest, val sourceName: String, internal val open: suspend () -> Result<ContentPack>)

/**
 * Where content packs live. Every source yields the same [PackRef]s and the same validated [ContentPack]s,
 * so presets, server downloads and imports are interchangeable to the rest of the app.
 */
interface PackSource {
    val name: String
    suspend fun list(): List<PackRef>
}

/** Packs that ship inside the APK (`assets/packs/<id>.a2gpack`, made at build time from samples/ by PackTool). */
class AssetPackSource(private val assets: AssetManager, private val dir: String = "packs") : PackSource {
    override val name = "preset"
    override suspend fun list(): List<PackRef> = withContext(Dispatchers.IO) {
        assets.list(dir).orEmpty().filter { it.endsWith(PACK_EXTENSION) }.sorted().mapNotNull { file ->
            val path = "$dir/$file"
            assets.open(path).use { PackCodec.readManifest(it) }.getOrNull()?.let { m ->
                PackRef(m, name) { withContext(Dispatchers.IO) { assets.open(path).use { PackCodec.read(it) } } }
            }
        }
    }
}

/**
 * Packs installed on the device (downloaded from our server, or imported). [install] validates the whole pack
 * before anything is written, then swaps it in atomically — a broken download never replaces a good pack.
 */
class DirectoryPackSource(private val dir: File, override val name: String = "installed") : PackSource {
    override suspend fun list(): List<PackRef> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(PACK_EXTENSION) }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            f.inputStream().use { PackCodec.readManifest(it) }.getOrNull()?.let { m ->
                PackRef(m, name) { withContext(Dispatchers.IO) { f.inputStream().use { PackCodec.read(it) } } }
            }
        }
    }

    suspend fun install(input: InputStream): Result<PackManifest> = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val tmp = File.createTempFile("incoming", ".part", dir)
            try {
                input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
                val pack = tmp.inputStream().use { PackCodec.read(it) }.getOrThrow()
                val target = File(dir, PackTool.fileName(pack.manifest.packageId))
                check(tmp.renameTo(target) || run { target.delete(); tmp.renameTo(target) }) { "could not install ${target.name}" }
                pack.manifest
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun remove(packageId: String): Boolean = withContext(Dispatchers.IO) { File(dir, PackTool.fileName(packageId)).delete() }
}
