package com.anything2game.contract.pack

import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import org.junit.Assert.*
import org.junit.Test

class ActivityPackTest {
    @Test fun `v1 opaque level and package identifiers survive a v2 practice path round trip`() {
        val v1 = Examples.packageV1
        val pkg = v1.copy(packageId = "旧教材", contentVersion = "版本:1", levels = v1.levels.mapIndexed { i, level ->
            if (i == 0) level.copy(levelId = "第1关") else level
        })
        val course = PackCodec.read(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.IMPORT)).inputStream()).getOrThrow()
        val activity = ActivityRef.Practice(course.pkg.packageId, course.pkg.contentVersion, course.pkg.levels.first().levelId)
        val node = PathNode("n1", "学习", activity)
        val path = LearningPath("route", "1", "space", "学习", "zh", listOf(node))
        val packed = PackCodec.toBytes(PackCodec.build(path, PackOrigin.PRESET))
        val decoded = PackCodec.readActivity(packed.inputStream(), setOf(ContentVersion("旧教材", "版本:1"))) { }.getOrThrow()
        assertEquals(path, (decoded.payload as ActivityPayload.Path).path)
        val invalidGame = node.copy(activity = ActivityRef.Game("game", "1", "第1关"))
        assertTrue(runCatching { path.copy(nodes = listOf(invalidGame)).validate() }.isFailure)
    }

    @Test fun `v1 opaque citation resolves its original document version chunk page and quote`() {
        val text = Examples.text("package.v1.example.json").replace("\"c1\"", "\"第一段\"")
        val pkg = ContractJson.readValidPackage(text).getOrThrow()
        val ref = SourceRef("第一段", "In the study, students read a short passage.")
        val scoped = pkg.scopeEvidence(ref, "e1")
        scoped.validate()
        assertEquals(EvidenceRef.Pdf("e1", "doc_demo_retrieval", "sha256:demo-retrieval-notes-v1",
            "第一段", ref.quote, 0, "1"), scoped)
    }

    @Test fun `path install needs every referenced content version`() {
        val ref = ActivityRef.Practice("pdf", "v2", "l1")
        val path = LearningPath("route", "r1", "space", "My route", "en", listOf(PathNode("n1", "Read", ref)))
        val pack = PackCodec.build(path, PackOrigin.SERVER)
        val bytes = PackCodec.toBytes(pack)
        assertTrue(PackCodec.readActivity(bytes.inputStream(), setOf(ContentVersion("pdf", "v1"))) { }.isFailure)
        val ready = PackCodec.readActivity(bytes.inputStream(), setOf(ContentVersion("pdf", "v2"))) { }.getOrThrow()
        assertEquals(path, (ready.payload as ActivityPayload.Path).path)
    }

    @Test fun `game install calls mandatory rules verification and v1 readers reject v2`() {
        val game = ReviewedGames.partition("zh")
        val bytes = PackCodec.toBytes(PackCodec.build(game, PackOrigin.PRESET))
        assertTrue(PackCodec.read(bytes.inputStream()).isFailure)
        assertTrue(PackCodec.readActivity(bytes.inputStream(), emptySet()) { error("unsolvable") }.isFailure)
        assertEquals(game, (PackCodec.readActivity(bytes.inputStream(), emptySet()) { }.getOrThrow().payload as ActivityPayload.Game).game)
        assertTrue(runCatching { PackCodec.build(game.copy(requiredCapabilities = setOf("future")), PackOrigin.SERVER) }.isFailure)
    }
}
