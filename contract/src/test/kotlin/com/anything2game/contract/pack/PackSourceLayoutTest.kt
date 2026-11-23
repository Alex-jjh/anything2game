package com.anything2game.contract.pack

import com.anything2game.contract.ContractJson
import com.anything2game.contract.PresetSourceLayout
import com.anything2game.contract.SourceLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** layout.json (ADR-019): optional and additive to a2g.pack/v1; a pack without it reads exactly as before. */
class PackSourceLayoutTest {
    @get:Rule val tmp = TemporaryFolder()
    private val samples = File(System.getProperty("contract.samples") ?: "../samples/demo")
    private val pkg = ContractJson.decodePackage(File(samples, "package.demo.attention.json").readText())
    private val frames = samples.listFiles { f -> f.name.startsWith("frame.demo.attention.") }!!.map { ContractJson.decodeFrame(it.readText()) }
    private fun layoutOf(name: String) = ContractJson.json.decodeFromString(PresetSourceLayout.serializer(), File(samples, "layout.demo.$name.json").readText())
    private val layout = layoutOf("attention")
    private val plain = PackCodec.toBytes(PackCodec.build(pkg, frames, PackOrigin.PRESET))
    private val withLayout = PackCodec.toBytes(PackCodec.build(pkg, frames, PackOrigin.PRESET, sourceLayout = layout))

    private fun read(b: ByteArray) = PackCodec.read(ByteArrayInputStream(b))

    @Test fun `a pack with the layout round trips, and one without still reads with none`() {
        val back = read(withLayout).getOrThrow()
        assertEquals(layout, back.sourceLayout)
        assertTrue(PackCodec.LAYOUT in back.manifest.entries.map { it.path })
        assertEquals(frames.size, back.frames.size) // the frame-set check is not confused by the extra entry
        val old = read(plain).getOrThrow()
        assertNull(old.sourceLayout)
        assertTrue(PackCodec.LAYOUT !in old.manifest.entries.map { it.path })
        // Deterministic, and a pack without the entry has exactly the bytes it had before the entry existed.
        assertArrayEquals(withLayout, PackCodec.toBytes(PackCodec.build(pkg, frames.reversed(), PackOrigin.PRESET, sourceLayout = layout)))
        assertArrayEquals(plain, PackCodec.toBytes(old))
    }

    @Test fun `a layout for another package, document version or with chunks missing cannot be packed`() {
        fun fails(l: PresetSourceLayout) = runCatching { PackCodec.build(pkg, frames, PackOrigin.PRESET, sourceLayout = l) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(fails(layout.copy(documentVersion = "sha256:other")).contains("Invalid source layout"))
        assertTrue(fails(layout.copy(packageId = "demo_resnet")).contains("Invalid source layout"))
        assertTrue(fails(layout.copy(pdfSha256 = "ABC")).contains("pdfSha256"))
        assertTrue(fails(layout.copy(layout = layout.layout.copy(chunks = layout.layout.chunks.filter { it.chunkId != "c5" }))).contains("[c5]"))
        assertTrue(fails(layout.copy(pageCount = 3)).contains("3 pages"))
        val resnet = ContractJson.decodePackage(File(samples, "package.demo.resnet.json").readText())
        assertTrue(runCatching { PackCodec.build(resnet, emptyList(), PackOrigin.PRESET, sourceLayout = layout) }.isFailure)
    }

    @Test fun `a tampered or unlisted layout entry is rejected, and so is a re-checksummed one for another version`() {
        assertFailure("checksum", rewrite(withLayout) { name, b -> if (name == PackCodec.LAYOUT) b.decodeToString().replace(layout.pdfSha256, "0".repeat(64)).toByteArray() else b })
        assertFailure("do not match", rewrite(plain, extra = PackCodec.LAYOUT to PresetLayoutJson.encode(layout)))
        // Checksums consistent, content wrong: only validation can catch it.
        val foreign = PresetLayoutJson.encode(layout.copy(documentVersion = "sha256:another-edition"))
        assertFailure("source layout is for", withEntryReplaced(withLayout, PackCodec.LAYOUT, foreign))
    }

    @Test fun `packing picks up layout files by packageId and refuses orphans and duplicates`() {
        val dir = tmp.newFolder("in")
        samples.listFiles { f -> f.name == "package.demo.attention.json" || f.name == "layout.demo.attention.json" }!!.forEach { it.copyTo(File(dir, it.name)) }
        val packed = PackTool.packDirectories(listOf(dir), tmp.newFolder("out"), PackOrigin.PRESET).single()
        assertEquals(layout, packed.inputStream().use { PackCodec.read(it) }.getOrThrow().sourceLayout)
        File(samples, "layout.demo.attention.json").copyTo(File(dir, "layout.copy.json"))
        assertTrue(runCatching { PackTool.packDirectories(listOf(dir), tmp.newFolder("o2"), PackOrigin.PRESET) }.exceptionOrNull()?.message.orEmpty().contains("more than one source layout"))
        File(dir, "layout.copy.json").writeText(PresetLayoutJson.encode(layout.copy(packageId = "nope")).decodeToString())
        File(dir, "layout.demo.attention.json").delete()
        assertTrue(runCatching { PackTool.packDirectories(listOf(dir), tmp.newFolder("o3"), PackOrigin.PRESET) }.exceptionOrNull()?.message.orEmpty().contains("without a package"))
    }

    /** The shipped layouts are only useful if the tutor's own quotes land on them: every one does, outside the accepted figure chunks. */
    @Test fun `the shipped layouts place every tutor quote of their course`() {
        for (name in listOf("attention", "resnet")) {
            val p = ContractJson.decodePackage(File(samples, "package.demo.$name.json").readText())
            val l = layoutOf(name).validate(p)
            val chunks = p.sources.associateBy { it.chunkId }
            val laid = l.layout.chunks.associateBy { it.chunkId }
            val refs = refsIn(ContractJson.json.parseToJsonElement(File(samples, "tutor.demo.$name.json").readText()))
            assertTrue(name, refs.isNotEmpty())
            refs.filter { it.first !in l.unaligned }.forEach { (id, quote) ->
                assertTrue("$name $id '$quote'", SourceLayout.rects(chunks.getValue(id), laid[id], quote).isNotEmpty())
            }
            assertTrue(l.pageCount > p.sources.maxOf { it.pageIndex })
        }
    }

    /** Every {chunkId, quote} object anywhere in a tutor file, without depending on the tutor model's classes. */
    private fun refsIn(e: JsonElement): List<Pair<String, String>> = when (e) {
        is JsonObject -> {
            val id = (e["chunkId"] as? JsonPrimitive)?.content; val q = (e["quote"] as? JsonPrimitive)?.content
            listOfNotNull(if (id != null && q != null) id to q else null) + e.values.flatMap(::refsIn)
        }
        is JsonArray -> e.flatMap(::refsIn)
        else -> emptyList()
    }

    private object PresetLayoutJson { fun encode(l: PresetSourceLayout) = ContractJson.json.encodeToString(PresetSourceLayout.serializer(), l).toByteArray() }

    private fun withEntryReplaced(src: ByteArray, path: String, bytes: ByteArray): ByteArray {
        val manifest = PackCodec.readManifest(ByteArrayInputStream(src)).getOrThrow()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val updated = manifest.copy(entries = manifest.entries.map { if (it.path == path) PackEntry(path, sha, bytes.size.toLong()) else it })
        return rewrite(src) { name, b -> when (name) { path -> bytes; PackCodec.MANIFEST -> ContractJson.json.encodeToString(PackManifest.serializer(), updated).toByteArray(); else -> b } }
    }

    private fun assertFailure(expected: String, b: ByteArray) {
        val r = read(b)
        assertTrue("'${r.exceptionOrNull()?.message}' should mention '$expected'", r.isFailure && r.exceptionOrNull()?.message.orEmpty().contains(expected))
    }

    private fun rewrite(src: ByteArray, extra: Pair<String, ByteArray>? = null, edit: (String, ByteArray) -> ByteArray = { _, b -> b }): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(src)).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        extra?.let { entries[it.first] = it.second }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, b) -> z.putNextEntry(ZipEntry(name)); z.write(edit(name, b)); z.closeEntry() } }
        return out.toByteArray()
    }
}
