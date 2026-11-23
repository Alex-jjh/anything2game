package com.anything2game.contract.pack

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.tutor.TutorScript
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PackCodecTest {
    @get:Rule val tmp = TemporaryFolder()
    private val samples = File(System.getProperty("contract.samples") ?: "../samples/demo")
    private val examples = File(System.getProperty("contract.examples") ?: "examples")
    private val pkg = ContractJson.decodePackage(File(samples, "package.demo.attention.json").readText())
    private val frames = samples.listFiles { f -> f.name.startsWith("frame.demo.attention.") }!!.map { ContractJson.decodeFrame(it.readText()) }
    private val pack = PackCodec.build(pkg, frames, PackOrigin.PRESET)
    private val bytes = PackCodec.toBytes(pack)

    @Test fun `round trip keeps package and every frame`() {
        val back = PackCodec.read(ByteArrayInputStream(bytes)).getOrThrow()
        assertEquals(pkg, back.pkg)
        assertEquals(setOf(FrameId.CASE, FrameId.ESCAPE, FrameId.ROOKIE, FrameId.COMPANION), back.frames.keys)
        assertEquals(pack.manifest, back.manifest)
        assertEquals(PackOrigin.PRESET, PackCodec.readManifest(ByteArrayInputStream(bytes)).getOrThrow().origin)
    }

    @Test fun `same content gives the same bytes`() = assertArrayEquals(bytes, PackCodec.toBytes(PackCodec.build(pkg, frames.reversed(), PackOrigin.PRESET)))

    @Test fun `tampered entry is rejected by checksum`() {
        val tampered = rewrite(bytes) { name, b -> if (name == PackCodec.PACKAGE) b.decodeToString().replace("Transformer", "Transformor").toByteArray() else b }
        assertFailure("checksum", tampered)
    }

    @Test fun `unknown or path-like entries are rejected`() {
        assertFailure("unexpected entry", rewrite(bytes, extra = "../../evil.sh" to "x".toByteArray()))
        assertFailure("unexpected entry", rewrite(bytes, extra = "notes.txt" to "x".toByteArray()))
    }

    @Test fun `manifest must come first and describe the contents`() {
        assertFailure("first entry", rewrite(bytes, manifestLast = true))
        val noFrame = rewrite(bytes, drop = PackCodec.framePath(FrameId.CASE))
        assertFailure("do not match", noFrame)
    }

    @Test fun `oversized entries are refused before they are held in memory`() {
        assertFailure("too large", bytes, PackCodec.Limits(maxEntryBytes = 1_000))
    }

    @Test fun `a frame for another package cannot be packed`() {
        val other = ContractJson.decodeFrame(File(samples, "frame.demo.resnet.case.json").readText())
        assertTrue(runCatching { PackCodec.build(pkg, listOf(other), PackOrigin.PRESET) }.isFailure)
    }

    @Test fun `every preset in the repo packs and reads back valid`() {
        val out = tmp.newFolder("packs")
        val written = PackTool.packDirectories(listOf(samples, examples), out, PackOrigin.PRESET)
        assertTrue(written.size >= 7)
        written.forEach { f -> f.inputStream().use { PackCodec.read(it).getOrThrow() } }
    }

    // --- tutor.json: optional, additive to a2g.pack/v1 ---
    private val retrievalPkg = ContractJson.decodePackage(File(examples, "package.v1.example.json").readText())
    private val script = ContractJson.json.decodeFromString(TutorScript.serializer(), File(examples, "tutor.v1.example.json").readText())
    private val withTutor = PackCodec.toBytes(PackCodec.build(retrievalPkg, emptyList(), PackOrigin.PRESET, script))

    @Test fun `a pack with a tutor script round trips, and one without still reads`() {
        val back = PackCodec.read(ByteArrayInputStream(withTutor)).getOrThrow()
        assertEquals(script, back.tutor)
        assertTrue(PackCodec.TUTOR in back.manifest.entries.map { it.path })
        assertNull(PackCodec.read(ByteArrayInputStream(bytes)).getOrThrow().tutor)
        assertTrue(PackCodec.TUTOR !in pack.manifest.entries.map { it.path })
        assertArrayEquals(withTutor, PackCodec.toBytes(PackCodec.build(retrievalPkg, emptyList(), PackOrigin.PRESET, script)))
    }

    @Test fun `a tampered tutor entry is rejected by checksum`() {
        val tampered = rewrite(withTutor) { name, b -> if (name == PackCodec.TUTOR) b.decodeToString().replace("retrieval", "retrievaL").toByteArray() else b }
        assertFailure("checksum", tampered)
    }

    @Test fun `a tutor entry the manifest does not list is rejected`() {
        assertFailure("do not match", rewrite(bytes, extra = PackCodec.TUTOR to ContractJson.json.encodeToString(TutorScript.serializer(), script).toByteArray()))
    }

    @Test fun `a tutor script for another package or version cannot be packed`() {
        assertTrue(runCatching { PackCodec.build(pkg, frames, PackOrigin.PRESET, script) }.isFailure)
        assertTrue(runCatching { PackCodec.build(retrievalPkg, emptyList(), PackOrigin.PRESET, script.copy(contentVersion = "99")) }.isFailure)
        assertTrue(runCatching { PackCodec.build(retrievalPkg, emptyList(), PackOrigin.PRESET, script.copy(documentVersion = "other")) }.isFailure)
    }

    @Test fun `a pack whose tutor entry is for another package is rejected on read even when the checksums are consistent`() {
        val foreign = ContractJson.json.encodeToString(TutorScript.serializer(), script.copy(packageId = "demo_attention")).toByteArray()
        assertFailure("version mismatch", withTutorReplacedBy(foreign))
        val unquoted = ContractJson.json.encodeToString(TutorScript.serializer(), script).replace("Retrieval practice means", "Retrieval practice never means").toByteArray()
        assertFailure("Quote is not in source", withTutorReplacedBy(unquoted))
    }

    @Test fun `packing picks up tutor files by packageId and refuses orphans and duplicates`() {
        val dir = tmp.newFolder("in")
        examples.listFiles { f -> f.name == "package.v1.example.json" || f.name == "tutor.v1.example.json" }!!.forEach { it.copyTo(File(dir, it.name)) }
        val out = tmp.newFolder("out")
        val packed = PackTool.packDirectories(listOf(dir), out, PackOrigin.PRESET).single()
        assertEquals(script, packed.inputStream().use { PackCodec.read(it) }.getOrThrow().tutor)

        File(examples, "tutor.v1.example.json").copyTo(File(dir, "tutor.copy.json"))
        assertTrue(runCatching { PackTool.packDirectories(listOf(dir), tmp.newFolder("o2"), PackOrigin.PRESET) }.exceptionOrNull()?.message.orEmpty().contains("more than one tutor"))
        File(dir, "tutor.copy.json").writeText(ContractJson.json.encodeToString(TutorScript.serializer(), script.copy(packageId = "nope")))
        File(dir, "tutor.v1.example.json").delete()
        assertTrue(runCatching { PackTool.packDirectories(listOf(dir), tmp.newFolder("o3"), PackOrigin.PRESET) }.exceptionOrNull()?.message.orEmpty().contains("without a package"))
    }

    @Test fun `the retrieval preset ships with its tutor script`() {
        val out = tmp.newFolder("presets")
        val packs = PackTool.packDirectories(listOf(samples, examples), out, PackOrigin.PRESET)
        val retrieval = packs.single { it.name == PackTool.fileName("pkg_demo_retrieval") }.inputStream().use { PackCodec.read(it) }.getOrThrow()
        assertEquals(script, retrieval.tutor)
        assertTrue(retrieval.tutor!!.blueprint.concepts.all { it.question.facet != null })
    }

    @Test fun `v2 rejects unknown formats path entries checksum changes and oversized content before dispatch`() {
        val game = com.anything2game.contract.game.ReviewedGames.partition("en")
        val src = PackCodec.toBytes(PackCodec.build(game, PackOrigin.PRESET))
        listOf(
            rewrite(src, extra = "../other" to byteArrayOf()),
            rewrite(src) { n, b -> if (n == PackCodec.GAME) b + 32.toByte() else b },
            rewrite(src) { n, b -> if (n == PackCodec.MANIFEST) b.decodeToString().replace(PACK_SCHEMA_V2, "future").toByteArray() else b },
        ).forEach { assertTrue(PackCodec.readActivity(it.inputStream(), emptySet()) { }.isFailure) }
        assertTrue(PackCodec.readActivity(src.inputStream(), emptySet(), PackCodec.Limits(maxEntryBytes = 128)) { }.isFailure)
    }

    @Test fun `v2 rejects undeclared rule fields even with a valid checksum`() {
        val game = com.anything2game.contract.game.ReviewedGames.partition("en")
        val pack = PackCodec.build(game, PackOrigin.PRESET)
        val valid = ContractJson.json.encodeToString(com.anything2game.contract.game.GameSpec.serializer(), game)
        val altered = valid.replaceFirst("\"queryBudget\":4", "\"queryBudget\":4,\"expression\":\"double-budget\"")
        assertNotEquals(valid, altered)
        val bytes = altered.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = pack.manifest.copy(entries = listOf(PackEntry(PackCodec.GAME, hash, bytes.size.toLong())))
        val archive = rewrite(PackCodec.toBytes(pack)) { name, _ ->
            if (name == PackCodec.GAME) bytes else ContractJson.json.encodeToString(PackManifest.serializer(), manifest).toByteArray()
        }
        val error = PackCodec.readActivity(archive.inputStream(), emptySet()) { }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("expression"))
    }

    @Test fun `rewriting a validated noncanonical game keeps its new checksum valid`() {
        val game = com.anything2game.contract.game.ReviewedGames.partition("en")
        val pack = PackCodec.build(game, PackOrigin.PRESET)
        val src = PackCodec.toBytes(pack)
        val data = ("  " + ContractJson.json.encodeToString(com.anything2game.contract.game.GameSpec.serializer(), game)).toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val manifest = pack.manifest.copy(entries = listOf(PackEntry(PackCodec.GAME, hash, data.size.toLong())))
        val original = rewrite(src) { name, _ -> if (name == PackCodec.GAME) data else ContractJson.json.encodeToString(PackManifest.serializer(), manifest).toByteArray() }
        val read = PackCodec.readActivity(original.inputStream(), emptySet()) { }.getOrThrow()
        assertEquals(read.payload, PackCodec.readActivity(PackCodec.toBytes(read).inputStream(), emptySet()) { }.getOrThrow().payload)
    }

    /** A pack in which tutor.json was swapped and the manifest recomputed, so only the content rules can catch it. */
    private fun withTutorReplacedBy(newTutor: ByteArray): ByteArray {
        val manifest = PackCodec.readManifest(ByteArrayInputStream(withTutor)).getOrThrow()
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val updated = manifest.copy(entries = manifest.entries.map { if (it.path == PackCodec.TUTOR) PackEntry(it.path, sha(newTutor), newTutor.size.toLong()) else it })
        return rewrite(withTutor) { name, b ->
            when (name) {
                PackCodec.TUTOR -> newTutor
                PackCodec.MANIFEST -> ContractJson.json.encodeToString(PackManifest.serializer(), updated).toByteArray()
                else -> b
            }
        }
    }

    private fun assertFailure(expected: String, b: ByteArray, limits: PackCodec.Limits = PackCodec.Limits()) {
        val r = PackCodec.read(ByteArrayInputStream(b), limits)
        assertTrue("expected failure", r.isFailure)
        assertTrue("'${r.exceptionOrNull()?.message}' should mention '$expected'", r.exceptionOrNull()?.message.orEmpty().contains(expected))
    }

    private fun rewrite(
        src: ByteArray,
        extra: Pair<String, ByteArray>? = null,
        drop: String? = null,
        manifestLast: Boolean = false,
        edit: (String, ByteArray) -> ByteArray = { _, b -> b },
    ): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(src)).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        val order = entries.keys.filter { it != drop }.let { if (manifestLast) it.filter { k -> k != PackCodec.MANIFEST } + PackCodec.MANIFEST else it }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            order.forEach { k -> z.putNextEntry(ZipEntry(k)); z.write(edit(k, entries.getValue(k))); z.closeEntry() }
            extra?.let { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() }
        }
        return out.toByteArray()
    }
}
