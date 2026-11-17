package com.anything2game.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameValidatorTest {
    private val packages = Examples.packageFiles().map { ContractJson.decodePackage(it.readText()) }.associateBy { it.packageId }
    private val frames = Examples.frameFiles().map { it.name to ContractJson.decodeFrame(it.readText()) }

    @Test fun `every example overlay is valid for its package`() {
        assertEquals(8, frames.size)
        frames.forEach { (name, o) -> assertEquals(name, emptyList<Issue>(), FrameValidator.validate(o, packages.getValue(o.packageId))) }
    }

    @Test fun `every frame that needs an overlay has one per example package`() {
        val needing = FrameSpec.ALL.values.filter { it.needsOverlay }.map { it.frameId }.toSet()
        packages.keys.forEach { pkg -> assertEquals(pkg, needing, frames.filter { it.second.packageId == pkg }.map { it.second.frameId }.toSet()) }
    }

    private fun case() = frames.first { it.second.frameId == FrameId.CASE && it.second.packageId == "pkg_demo_retrieval" }.second
    private val pkg get() = packages.getValue("pkg_demo_retrieval")

    @Test fun `case without board or boss is rejected`() {
        val o = case().copy(board = null, levels = case().levels.map { it.copy(boss = null) })
        val issues = FrameValidator.validate(o, pkg).map { it.message }
        assertTrue(issues.any { "needs a board" in it }); assertTrue(issues.any { "needs a boss" in it })
    }

    @Test fun `cross-exam needs exactly one flawed statement`() {
        val l = case().levels[0]
        val item = l.boss!!.item as CrossExamItem
        val bad = case().copy(levels = listOf(l.copy(boss = l.boss!!.copy(item = item.copy(statements = item.statements.map { it.copy(flawed = true) })))))
        assertTrue(FrameValidator.validate(bad, pkg).any { "exactly one statement" in it.message })
    }

    @Test fun `boss speaker must have the right role`() {
        val l = case().levels[0]
        val item = l.boss!!.item as CrossExamItem
        val bad = case().copy(levels = listOf(l.copy(boss = l.boss!!.copy(item = item.copy(speaker = "mira")))))
        assertTrue(FrameValidator.validate(bad, pkg).any { "must be the suspect" in it.message })
    }

    @Test fun `overlay for another content version is rejected`() {
        assertTrue(FrameValidator.validate(case().copy(contentVersion = "2"), pkg).any { it.path == "contentVersion" })
    }

    @Test fun `lines must use cast speakers and valid quotes`() {
        val l = case().levels[0]
        val bad = case().copy(levels = listOf(l.copy(intro = listOf(Line("ghost", "hi", listOf(SourceRef("c1", "not in text")))))))
        val m = FrameValidator.validate(bad, pkg).map { it.message }
        assertTrue(m.any { "unknown speaker" in it }); assertTrue(m.any { "quote not found" in it })
    }
}
