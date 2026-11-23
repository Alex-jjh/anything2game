package com.anything2game.contract.pack

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.FrameValidator
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.PresetSourceLayout
import com.anything2game.contract.tutor.TutorScript
import com.anything2game.contract.game.*
import com.anything2game.contract.space.LearningPath
import com.anything2game.contract.space.PATH_SCHEMA
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * tutor.json             optional TutorScript: pre-generated diagnostic + explanation for this course
 * layout.json            optional PresetSourceLayout: page geometry of one exact PDF of the course's document (presets only)
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

@OptIn(ExperimentalSerializationApi::class)
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val dependencies: Set<ContentVersion> = emptySet(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val requiredCapabilities: Set<String> = emptySet(),
    /** Present on server-generated activities so offline installs retain their production or fixture origin. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val modelId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fixture: Boolean = false,
)

/**
 * A pack that has been read and fully validated. [sourceLayout] lets "Show source" draw on a reader's own copy of the
 * PDF (ADR-019); only PackTool presets carry it, because a reader older than the entry refuses a pack that has it.
 */
data class ContentPack(
    val manifest: PackManifest, val pkg: LearningPackage, val frames: Map<FrameId, FrameOverlay>, val tutor: TutorScript? = null,
    val sourceLayout: PresetSourceLayout? = null,
)

/** Builds, writes and reads content packs. Pure JVM: shared by the app, the server and the build tool. */
object PackCodec {
    const val GAME = "game.json"
    const val PATH = "path.json"
    const val MANIFEST = "manifest.json"
    const val PACKAGE = "package.json"
    const val TUTOR = "tutor.json"
    const val LAYOUT = "layout.json"
    fun framePath(id: FrameId) = "frames/${id.name.lowercase()}.json"

    /** Packs come from outside the app (downloads, imports), so reading is bounded: no zip bombs, no surprise entries. */
    data class Limits(val maxEntries: Int = 16, val maxEntryBytes: Long = 8L shl 20, val maxTotalBytes: Long = 32L shl 20)

    private val allowedPaths: Set<String> = setOf(MANIFEST, PACKAGE, TUTOR, LAYOUT, GAME, PATH) + FrameId.entries.map(::framePath)
    // Fixed entry time so the same content always produces the same bytes.
    private const val ENTRY_TIME = 315_532_800_000L // 1980-01-01

    /** Validates the package, its frames, its tutor script and its source layout (if any), then describes them in a manifest. */
    fun build(pkg: LearningPackage, frames: Collection<FrameOverlay>, origin: PackOrigin, tutor: TutorScript? = null, sourceLayout: PresetSourceLayout? = null): ContentPack {
        val pkgIssues = PackageValidator.validate(pkg)
        require(pkgIssues.isEmpty()) { "Invalid package ${pkg.packageId}: " + pkgIssues.joinToString("; ") }
        val byId = frames.associateBy { it.frameId }
        require(byId.size == frames.size) { "Duplicate frame in pack ${pkg.packageId}" }
        byId.values.forEach { f ->
            val issues = FrameValidator.validate(f, pkg)
            require(issues.isEmpty()) { "Invalid frame ${f.frameId} for ${pkg.packageId}: " + issues.joinToString("; ") }
        }
        tutor?.let {
            runCatching { it.validate(pkg) }.onFailure { e -> throw IllegalArgumentException("Invalid tutor script for ${pkg.packageId}: ${e.message}", e) }
        }
        sourceLayout?.let {
            runCatching { it.validate(pkg) }.onFailure { e -> throw IllegalArgumentException("Invalid source layout for ${pkg.packageId}: ${e.message}", e) }
        }
        val ids = byId.keys.sortedBy { it.ordinal }
        val entries = buildList {
            add(entry(PACKAGE, ContractJson.encodePackage(pkg).toByteArray()))
            tutor?.let { add(entry(TUTOR, encodeTutor(it))) }
            sourceLayout?.let { add(entry(LAYOUT, encodeLayout(it))) }
            ids.forEach { add(entry(framePath(it), ContractJson.encodeFrame(byId.getValue(it)).toByteArray())) }
        }
        val manifest = PackManifest(PACK_SCHEMA_V1, pkg.packageId, pkg.contentVersion, pkg.schema, pkg.title, pkg.language, origin, ids, entries)
        return ContentPack(manifest, pkg, ids.associateWith { byId.getValue(it) }, tutor, sourceLayout)
    }

    fun write(pack: ContentPack, out: OutputStream) {
        val files = linkedMapOf(PACKAGE to ContractJson.encodePackage(pack.pkg).toByteArray())
        pack.tutor?.let { files[TUTOR] = encodeTutor(it) }
        pack.sourceLayout?.let { files[LAYOUT] = encodeLayout(it) }
        pack.manifest.frames.forEach { files[framePath(it)] = ContractJson.encodeFrame(pack.frames.getValue(it)).toByteArray() }
        writeArchive(pack.manifest, files, out)
    }

    fun toBytes(pack: ContentPack): ByteArray = ByteArrayOutputStream().also { write(pack, it) }.toByteArray()

    fun build(game: GameSpec, origin: PackOrigin): ActivityPack {
        MechanicRegistry.validate(game)
        val payload = ActivityPayload.Game(game)
        val manifest = PackManifest(PACK_SCHEMA_V2, game.gameId, game.gameVersion, GAME_SCHEMA, game.title, game.language,
            origin, emptyList(), listOf(entry(GAME, encode(payload))), requiredCapabilities = game.requiredCapabilities)
        return ActivityPack(manifest, payload)
    }

    fun build(path: LearningPath, origin: PackOrigin): ActivityPack {
        path.validate()
        val payload = ActivityPayload.Path(path)
        val manifest = PackManifest(PACK_SCHEMA_V2, path.pathId, path.pathVersion, PATH_SCHEMA, path.title, path.language,
            origin, emptyList(), listOf(entry(PATH, encode(payload))), dependencies = path.dependencies)
        return ActivityPack(manifest, payload)
    }

    private fun encode(payload: ActivityPayload) = when (payload) {
        is ActivityPayload.Game -> ContractJson.json.encodeToString(GameSpec.serializer(), payload.game)
        is ActivityPayload.Path -> ContractJson.json.encodeToString(LearningPath.serializer(), payload.path)
    }.toByteArray()

    fun write(pack: ActivityPack, out: OutputStream) {
        val name = when (pack.payload) { is ActivityPayload.Game -> GAME; is ActivityPayload.Path -> PATH }
        val bytes = encode(pack.payload)
        val manifest = pack.manifest.copy(entries = listOf(entry(name, bytes)))
        writeArchive(manifest, mapOf(name to bytes), out)
    }

    fun toBytes(pack: ActivityPack): ByteArray = ByteArrayOutputStream().also { write(pack, it) }.toByteArray()

    private fun writeArchive(manifest: PackManifest, files: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path).apply { time = ENTRY_TIME })
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST, ContractJson.json.encodeToString(PackManifest.serializer(), manifest).toByteArray())
            files.forEach(::put)
        }
    }

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
        val (manifest, files) = readArchive(input, limits)
        require(manifest.schema == PACK_SCHEMA_V1) { "Expected v1 course pack" }
        require(manifest.dependencies.isEmpty() && manifest.requiredCapabilities.isEmpty()) { "Unexpected v1 dependencies" }
        val declared = manifest.entries.associateBy { it.path }
        val pkg = ContractJson.readValidPackage(files.getValue(PACKAGE).decodeToString()).getOrThrow()
        require(pkg.packageId == manifest.packageId && pkg.contentVersion == manifest.contentVersion && pkg.schema == manifest.packageSchema) {
            "manifest does not describe the package inside"
        }
        require(manifest.frames.toSet().size == manifest.frames.size && manifest.frames.map(::framePath).toSet() == declared.keys - PACKAGE - TUTOR - LAYOUT) {
            "manifest frame list does not match the frame files"
        }
        val frames = manifest.frames.associateWith { id ->
            ContractJson.readValidFrame(files.getValue(framePath(id)).decodeToString(), pkg).getOrThrow()
                .also { require(it.frameId == id) { "frames/${id.name.lowercase()}.json holds frame ${it.frameId}" } }
        }
        val tutor = files[TUTOR]?.let { bytes ->
            ContractJson.json.decodeFromString(TutorScript.serializer(), bytes.decodeToString()).validate(pkg)
        }
        val layout = files[LAYOUT]?.let { bytes ->
            ContractJson.json.decodeFromString(PresetSourceLayout.serializer(), bytes.decodeToString()).validate(pkg)
        }
        ContentPack(manifest, pkg, frames, tutor, layout)
    }

    /** Validation is mandatory at the engine boundary. Installers may atomically move their original temporary file on success. */
    fun readActivity(input: InputStream, installed: Set<ContentVersion>, limits: Limits = Limits(),
        validateGame: (GameSpec) -> Unit): Result<ActivityPack> = runCatching {
        val (manifest, files) = readArchive(input, limits)
        require(manifest.schema == PACK_SCHEMA_V2 && manifest.frames.isEmpty()) { "Expected v2 activity pack" }
        require(MechanicRegistry.capabilities.containsAll(manifest.requiredCapabilities)) { "Unsupported runtime capability" }
        require(installed.containsAll(manifest.dependencies)) { "Missing content dependency" }
        val pack = when (manifest.packageSchema) {
            GAME_SCHEMA -> {
                require(files.keys == setOf(MANIFEST, GAME)) { "Invalid game entries" }
                val game = ContractJson.activityJson.decodeFromString(GameSpec.serializer(), files.getValue(GAME).decodeToString())
                build(game, manifest.origin).also { validateGame(game) }
            }
            PATH_SCHEMA -> {
                require(files.keys == setOf(MANIFEST, PATH)) { "Invalid path entries" }
                build(ContractJson.activityJson.decodeFromString(LearningPath.serializer(), files.getValue(PATH).decodeToString()), manifest.origin)
            }
            else -> error("Unsupported activity schema")
        }
        // Checksums refer to the incoming representation, including its legal whitespace and property order.
        // Like origin, generator provenance describes how the archive was produced; it is not derived from game rules.
        require(manifest.modelId == null || (manifest.modelId.isNotBlank() && manifest.modelId.length <= 256 &&
            manifest.modelId.none(Char::isISOControl))) { "Invalid generator identifier" }
        require(!manifest.fixture || manifest.modelId != null) { "Missing fixture generator" }
        require(pack.manifest.copy(entries = manifest.entries, modelId = manifest.modelId, fixture = manifest.fixture) == manifest) { "Manifest does not describe activity or dependencies" }
        ActivityPack(manifest, pack.payload)
    }

    private fun readArchive(input: InputStream, limits: Limits): Pair<PackManifest, Map<String, ByteArray>> {
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
        return manifest to files
    }

    private fun encodeTutor(t: TutorScript): ByteArray = ContractJson.json.encodeToString(TutorScript.serializer(), t).toByteArray()
    private fun encodeLayout(l: PresetSourceLayout): ByteArray = ContractJson.json.encodeToString(PresetSourceLayout.serializer(), l).toByteArray()

    private fun decodeManifest(bytes: ByteArray): PackManifest {
        val m = ContractJson.json.decodeFromString(PackManifest.serializer(), bytes.decodeToString())
        require(m.schema == PACK_SCHEMA_V1 || m.schema == PACK_SCHEMA_V2) { "unsupported pack schema '${m.schema}'" }
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
