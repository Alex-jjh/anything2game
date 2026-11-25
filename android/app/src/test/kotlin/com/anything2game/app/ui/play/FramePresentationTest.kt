package com.anything2game.app.ui.play

import androidx.test.core.app.ApplicationProvider
import com.anything2game.app.R
import com.anything2game.contract.FrameId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every frame says everything a screen needs about it, in both languages: one row per frame, nothing left to a screen's own table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FramePresentationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun fields(p: FramePresentation) = mapOf("name" to p.name, "description" to p.description, "best for" to p.bestFor,
        "recap title" to p.recapTitle, "failed title" to p.failedTitle, "route label" to p.routeLabel)

    @Test fun every_frame_has_every_field_as_a_real_text() {
        for (frame in FrameId.entries) {
            val presentation = frame.presentation()
            for ((field, id) in fields(presentation)) assertTrue("$frame has no $field", id != 0 && context.getString(id).isNotBlank())
            assertNotNull("$frame has no icon", presentation.icon)
            presentation.bossLabel?.let { assertTrue("$frame has an empty boss label", context.getString(it).isNotBlank()) }
        }
    }

    @Test fun the_frames_with_a_boss_question_name_it_and_the_others_do_not() {
        assertEquals(setOf(FrameId.CASE, FrameId.ESCAPE, FrameId.ROOKIE), FrameId.entries.filter { it.presentation().bossLabel != null }.toSet())
    }

    @Test fun no_two_frames_share_a_name_a_description_or_a_route_label() {
        for (field in listOf("name", "description", "route label")) {
            val texts = FrameId.entries.map { fields(it.presentation()).getValue(field) }.map(context::getString)
            assertEquals("two frames share a $field", texts.size, texts.toSet().size)
        }
    }

    @Test fun a_route_step_and_the_play_as_chip_name_the_same_frame() {
        // "Case" on the chip, "Case practice" on the route step: one frame, one name, the step only adds what it is.
        for (frame in listOf(FrameId.CLASSIC, FrameId.CASE, FrameId.ROOKIE, FrameId.ESCAPE, FrameId.COMPANION)) {
            val p = frame.presentation()
            assertTrue("${context.getString(p.routeLabel)} must start with ${context.getString(p.name)}", context.getString(p.routeLabel).startsWith(context.getString(p.name)))
        }
    }

    @Test fun the_chinese_texts_exist_too() {
        RuntimeEnvironment.setQualifiers("+zh")
        for (frame in FrameId.entries) for ((field, id) in fields(frame.presentation())) {
            val zh = context.getString(id)
            assertTrue("$frame $field has no Chinese text: $zh", zh.any { it.code > 0x2E80 })
        }
    }
}
