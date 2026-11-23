package com.anything2game.app.data

import com.anything2game.app.data.content.AssetPackSource
import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.content.DirectoryPackSource
import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.pack.PackOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.File

/** Presets (APK assets) and installed packs (server / import) go through one library with one format. */
@RunWith(RobolectricTestRunner::class)
class ContentLibraryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val samples = File(System.getProperty("contract.samples")!!)
    private val assets = RuntimeEnvironment.getApplication().assets
    private fun attention(version: String, title: String? = null) = ContractJson.decodePackage(File(samples, "package.demo.attention.json").readText())
        .let { it.copy(contentVersion = version, title = title ?: it.title) }
    private fun frames(version: String) = samples.listFiles { f -> f.name.startsWith("frame.demo.attention.") }!!
        .map { ContractJson.decodeFrame(it.readText()).copy(contentVersion = version) }
    private fun packBytes(version: String, title: String? = null) = PackCodec.toBytes(PackCodec.build(attention(version, title), frames(version), PackOrigin.SERVER))

    @Test fun `presets ship as packs and load through the library`() = runBlocking {
        val lib = ContentLibrary(listOf(AssetPackSource(assets)))
        val ids = lib.list().map { it.packageId }
        assertTrue("presets: $ids", ids.containsAll(listOf("demo_attention", "demo_resnet", "demo_mapreduce", "demo_cs231n_l1", "demo_zh_spaced")))
        assertTrue(FrameId.CASE in lib.available("demo_attention"))
        assertTrue(lib.load("demo_attention", FrameId.CASE) != null)
        assertTrue(lib.list().all { it.preGenerated })
    }

    @Test fun `an installed newer pack replaces the preset, an older one does not`() = runBlocking {
        val installed = DirectoryPackSource(tmp.newFolder("packs"))
        val lib = ContentLibrary(listOf(AssetPackSource(assets), installed))
        val presetVersion = lib.load("demo_attention").getOrThrow().contentVersion

        installed.install(ByteArrayInputStream(packBytes("$presetVersion.1", "Updated from server"))).getOrThrow()
        lib.refresh()
        assertEquals("Updated from server", lib.load("demo_attention").getOrThrow().title)

        installed.install(ByteArrayInputStream(packBytes("0", "Stale"))).getOrThrow()
        lib.refresh()
        assertEquals("$presetVersion.1", lib.load("demo_attention").getOrThrow().contentVersion)
    }

    @Test fun `a broken download never replaces a good pack`() = runBlocking {
        val dir = tmp.newFolder("packs")
        val installed = DirectoryPackSource(dir)
        installed.install(ByteArrayInputStream(packBytes("9", "Good"))).getOrThrow()
        val good = packBytes("10", "Bad")
        val truncated = good.copyOf(good.size / 2)
        assertTrue(installed.install(ByteArrayInputStream(truncated)).isFailure)
        val lib = ContentLibrary(listOf(installed))
        assertEquals("Good", lib.load("demo_attention").getOrThrow().title)
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".a2gpack") })
    }

    @Test fun `tutor scripts come out of the real preset packs and match their courses`() = runBlocking {
        val lib = ContentLibrary(listOf(AssetPackSource(assets)))
        val scripted = lib.scriptedPackageIds()
        assertTrue("scripted: $scripted", "pkg_demo_retrieval" in scripted)
        scripted.forEach { id -> lib.scriptFor(id)!!.validate(lib.load(id).getOrThrow()) }
        assertNull(lib.scriptFor("no_such_course"))
        // Without a script in the pack there is nothing to offer, and an installed pack without one hides the preset's script
        // only when it is the winning copy of the same course.
        val installed = DirectoryPackSource(tmp.newFolder("scripts"))
        installed.install(ByteArrayInputStream(packBytes("1"))).getOrThrow()
        assertNull(ContentLibrary(listOf(installed)).scriptFor("demo_attention"))
        assertTrue(ContentLibrary(listOf(installed)).scriptedPackageIds().isEmpty())
    }

    @Test fun `legacy compile can append a level without changing the saved version or earlier levels`() = runBlocking {
        val disk = DirectoryPackSource(tmp.newFolder())
        val library = ContentLibrary(listOf(disk))
        val pkg = attention("shared")
        val first = pkg.copy(levels = pkg.levels.take(1))
        require(pkg.levels.size > 1)
        suspend fun install(course: com.anything2game.contract.LearningPackage) =
            library.install(PackCodec.toBytes(PackCodec.build(course, emptyList(), PackOrigin.SERVER)).inputStream())
        install(first)
        install(pkg)
        assertEquals(pkg.levels, library.load(pkg.packageId, "shared").getOrThrow().levels)
        assertTrue("existing level numbers must stay stable", runCatching { install(pkg.copy(levels = pkg.levels.reversed())) }.isFailure)
        assertTrue(runCatching { install(first) }.isFailure)
        assertEquals(pkg.levels.first(), library.load(pkg.packageId, "shared").getOrThrow().levels.first())
    }

    @Test fun `versions compare by number, not by text`() {
        assertTrue(ContentLibrary.compareVersions("1.10", "1.9") > 0)
        assertTrue(ContentLibrary.compareVersions("2", "10") < 0)
        assertEquals(0, ContentLibrary.compareVersions("3", "3"))
    }

    @Test fun `original validated archive repairs a truncated edition but another payload cannot`() = runBlocking {
        val directory = tmp.newFolder()
        val disk = DirectoryPackSource(directory)
        val bytes = packBytes("7", "First edition")
        disk.install(bytes.inputStream()).getOrThrow()
        val installed = directory.listFiles()!!.single { it.name.endsWith(".a2gpack") }
        installed.writeBytes(bytes.copyOf(bytes.size / 2))
        assertEquals("First edition", disk.list().single().manifest.title)
        assertTrue(disk.list().single().open().isFailure)
        assertTrue(disk.install(packBytes("7", "Different lesson").inputStream()).isFailure)
        disk.install(bytes.inputStream()).getOrThrow()
        assertEquals("First edition", ContentLibrary(listOf(disk)).load("demo_attention", "7").getOrThrow().title)
    }
}
