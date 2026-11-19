package com.anything2game.app

import com.anything2game.app.ui.play.offeredFrames
import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Practice offers frames by what they are for (docs/product/frames.md §4), not every frame that exists. */
class OfferedFramesTest {
    private val dir = File(System.getProperty("contract.samples")!!)
    private fun pkg(d: String) = ContractJson.decodePackage(File(dir, "package.demo.$d.json").readText())
    private val all = FrameId.entries

    @Test fun `companion is folded into Learn and escape needs process content`() {
        assertEquals(listOf(FrameId.CLASSIC, FrameId.CASE, FrameId.ROOKIE, FrameId.ESCAPE), offeredFrames(pkg("attention"), all)) // 2 process units
        assertEquals(listOf(FrameId.CLASSIC, FrameId.CASE, FrameId.ROOKIE), offeredFrames(pkg("resnet"), all)) // 1 of 8
        assertEquals(listOf(FrameId.CLASSIC, FrameId.CASE, FrameId.ROOKIE, FrameId.ESCAPE), offeredFrames(pkg("zh-spaced"), all)) // 1 of 4
    }

    @Test fun `only generated frames are offered`() {
        assertEquals(listOf(FrameId.CLASSIC), offeredFrames(pkg("attention"), listOf(FrameId.CLASSIC)))
    }
}
