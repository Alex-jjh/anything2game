package com.anything2game.contract.pack

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
