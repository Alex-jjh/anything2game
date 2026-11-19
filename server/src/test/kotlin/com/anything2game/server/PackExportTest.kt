package com.anything2game.server

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.server.export.PackExporter
import com.anything2game.server.store.FileStore
import com.anything2game.server.store.StoredPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** The server exports the same pack format the app ships its presets in; the app's reader accepts it unchanged. */
class PackExportTest {
    private val examples = File(System.getProperty("contract.examples") ?: "contract/examples")

    @Test fun `finished package exports as a pack the client reader accepts`() {
        val dir = File("build/test-data/pack-" + UUID.randomUUID()).also { it.mkdirs() }
        try {
            val store = FileStore(dir.toPath())
            val pkg = Examples.retrieval
            store.savePackage(StoredPackage("owner", "job", complete = false, pkg = pkg))
            val exporter = PackExporter(store)
            assertNull("still compiling: no pack yet", exporter.export(pkg.packageId))

            store.savePackage(StoredPackage("owner", "job", complete = true, pkg = pkg))
            listOf("case", "escape").forEach { store.saveFrame(ContractJson.decodeFrame(File(examples, "frame.v1.retrieval.$it.json").readText())) }
            val pack = exporter.export(pkg.packageId)!!
            assertEquals(PackOrigin.SERVER, pack.manifest.origin)

            val back = PackCodec.read(ByteArrayInputStream(PackCodec.toBytes(pack))).getOrThrow()
            assertEquals(pkg, back.pkg)
            assertEquals(setOf(FrameId.CASE, FrameId.ESCAPE), back.frames.keys)
        } finally {
            dir.deleteRecursively()
        }
    }
}
