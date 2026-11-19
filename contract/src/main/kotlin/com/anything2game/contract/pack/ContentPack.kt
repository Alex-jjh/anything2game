package com.anything2game.contract.pack

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.FrameValidator
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PackageValidator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A content pack: one course as one file — the package plus every frame overlay generated for it.
 * The same format whether it ships inside the app (preset), comes from the server, or is imported,
 * so the app never needs to know where content came from (ADR-017).
 *
 * File layout (a zip, extension [PACK_EXTENSION]):
 * ```
 * manifest.json          first entry; ids, versions, checksums of every other entry
 * package.json           LearningPackage (a2g.package/v1)
 * frames/<frameId>.json  FrameOverlay for each generated frame (classic needs none)
 * ```
 */
const val PACK_SCHEMA_V1 = "a2g.pack/v1"
const val PACK_EXTENSION = ".a2gpack"

@Serializable
enum class PackOrigin {
    /** Bundled in the app at build time. */
    @SerialName("preset") PRESET,
    /** Compiled by our server and downloaded. */
    @SerialName("server") SERVER,
    /** Received from elsewhere (shared by a classmate, sideloaded). */
    @SerialName("import") IMPORT,
}

@Serializable
data class PackEntry(val path: String, val sha256: String, val bytes: Long)

@Serializable
data class PackManifest(
    val schema: String,
    val packageId: String,
    val contentVersion: String,
    /** Schema of the package inside, so a reader can refuse a pack it cannot read before opening the rest. */
    val packageSchema: String,
    val title: String,
    val language: String,
    val origin: PackOrigin,
    val frames: List<FrameId>,
    val entries: List<PackEntry>,
)

/** A pack that has been read and fully validated. */
data class ContentPack(val manifest: PackManifest, val pkg: LearningPackage, val frames: Map<FrameId, FrameOverlay>)

/** Builds, writes and reads content packs. Pure JVM: shared by the app, the server and the build tool. */
object PackCodec {
    const val MANIFEST = "manifest.json"
    const val PACKAGE = "package.json"
    fun framePath(id: FrameId) = "frames/${id.name.lowercase()}.json"

    /** Packs come from outside the app (downloads, imports), so reading is bounded: no zip bombs, no surprise entries. */
    data class Limits(val maxEntries: Int = 16, val maxEntryBytes: Long = 8L shl 20, val maxTotalBytes: Long = 32L shl 20)

    private val allowedPaths: Set<String> = setOf(MANIFEST, PACKAGE) + FrameId.entries.map(::framePath)
    // Fixed entry time so the same content always produces the same bytes.
    private const val ENTRY_TIME = 315_532_800_000L // 1980-01-01

    /** Validates the package and its frames, then describes them in a manifest. */
    fun build(pkg: LearningPackage, frames: Collection<FrameOverlay>, origin: PackOrigin): ContentPack {
        val pkgIssues = PackageValidator.validate(pkg)
        require(pkgIssues.isEmpty()) { "Invalid package ${pkg.packageId}: " + pkgIssues.joinToString("; ") }
        val byId = frames.associateBy { it.frameId }
        require(byId.size == frames.size) { "Duplicate frame in pack ${pkg.packageId}" }
        byId.values.forEach { f ->
            val issues = FrameValidator.validate(f, pkg)
            require(issues.isEmpty()) { "Invalid frame ${f.frameId} for ${pkg.packageId}: " + issues.joinToString("; ") }
        }
        val ids = byId.keys.sortedBy { it.ordinal }
        val entries = buildList {
            add(entry(PACKAGE, ContractJson.encodePackage(pkg).toByteArray()))
            ids.forEach { add(entry(framePath(it), ContractJson.encodeFrame(byId.getValue(it)).toByteArray())) }
        }
        val manifest = PackManifest(PACK_SCHEMA_V1, pkg.packageId, pkg.contentVersion, pkg.schema, pkg.title, pkg.language, origin, ids, entries)
        return ContentPack(manifest, pkg, ids.associateWith { byId.getValue(it) })
    }

    fun write(pack: ContentPack, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path).apply { time = ENTRY_TIME })
                zip.write(bytes); zip.closeEntry()
            }
            put(MANIFEST, ContractJson.json.encodeToString(PackManifest.serializer(), pack.manifest).toByteArray())
            put(PACKAGE, ContractJson.encodePackage(pack.pkg).toByteArray())
            pack.manifest.frames.forEach { put(framePath(it), ContractJson.encodeFrame(pack.frames.getValue(it)).toByteArray()) }
        }
    }

    fun toBytes(pack: ContentPack): ByteArray = ByteArrayOutputStream().also { write(pack, it) }.toByteArray()

    /** Reads only the manifest (the first entry), for cheap listing. Checksums are checked by [read]. */
    fun readManifest(input: InputStream, limits: Limits = Limits()): Result<PackManifest> = runCatching {
        ZipInputStream(input.buffered()).use { zip ->
            val first = zip.nextEntry ?: error("empty pack")
            require(first.name == MANIFEST) { "first entry must be $MANIFEST, was '${first.name}'" }
            decodeManifest(readBounded(zip, limits.maxEntryBytes))
        }
    }

    /** Reads, verifies checksums and validates everything. Any problem → failure; a pack is never partly accepted. */
    fun read(input: InputStream, limits: Limits = Limits()): Result<ContentPack> = runCatching {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var total = 0L
            while (true) {
                val e = zip.nextEntry ?: break
                require(!e.isDirectory && e.name in allowedPaths) { "unexpected entry '${e.name}'" }
                require(e.name !in files) { "duplicate entry '${e.name}'" }
                require(files.size < limits.maxEntries) { "too many entries" }
                val bytes = readBounded(zip, limits.maxEntryBytes)
                total += bytes.size
                require(total <= limits.maxTotalBytes) { "pack too large" }
                files[e.name] = bytes
            }
        }
        require(files.keys.firstOrNull() == MANIFEST) { "first entry must be $MANIFEST" }
        val manifest = decodeManifest(files.getValue(MANIFEST))
        val declared = manifest.entries.associateBy { it.path }
        require(declared.size == manifest.entries.size) { "duplicate manifest entry" }
        require(declared.keys == files.keys - MANIFEST) { "manifest entries ${declared.keys} do not match pack contents ${files.keys - MANIFEST}" }
        declared.values.forEach { d ->
            val bytes = files.getValue(d.path)
            require(bytes.size.toLong() == d.bytes && sha256(bytes) == d.sha256) { "checksum mismatch for ${d.path}" }
        }
        val pkg = ContractJson.readValidPackage(files.getValue(PACKAGE).decodeToString()).getOrThrow()
        require(pkg.packageId == manifest.packageId && pkg.contentVersion == manifest.contentVersion && pkg.schema == manifest.packageSchema) {
            "manifest does not describe the package inside"
        }
        require(manifest.frames.toSet().size == manifest.frames.size && manifest.frames.map(::framePath).toSet() == declared.keys - PACKAGE) {
            "manifest frame list does not match the frame files"
        }
        val frames = manifest.frames.associateWith { id ->
            ContractJson.readValidFrame(files.getValue(framePath(id)).decodeToString(), pkg).getOrThrow()
                .also { require(it.frameId == id) { "frames/${id.name.lowercase()}.json holds frame ${it.frameId}" } }
        }
        ContentPack(manifest, pkg, frames)
    }

    private fun decodeManifest(bytes: ByteArray): PackManifest {
        val m = ContractJson.json.decodeFromString(PackManifest.serializer(), bytes.decodeToString())
        require(m.schema == PACK_SCHEMA_V1) { "unsupported pack schema '${m.schema}'" }
        return m
    }

    private fun entry(path: String, bytes: ByteArray) = PackEntry(path, sha256(bytes), bytes.size.toLong())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readBounded(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            require(n <= max) { "entry too large" }
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}
