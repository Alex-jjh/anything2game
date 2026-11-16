package edu.csi301.fieldnotes

import org.junit.Assert.*
import org.junit.Test

class ProgressTest {
    private val lesson = loadSample()

    @Test fun eachCheckpointRestoresIncludingWrongAnswers() {
        for (option in lesson.choices) {
            val reading = Progress()
            val question = reading.toQuestion()
            val feedback = question.answer(option.id, lesson)
            val source = feedback.openSource()
            val review = source.finish()
            assertEquals(listOf(Step.READING, Step.QUESTION, Step.FEEDBACK, Step.SOURCE, Step.REVIEW),
                listOf(reading, question, feedback, source, review).map { it.step })
            assertEquals(option.id, review.answerId)
            for (checkpoint in listOf(reading, question, feedback, source, review)) {
                assertEquals(checkpoint, Progress.decode(checkpoint.encode(lesson), lesson))
            }
        }
    }

    @Test fun answerCanOnlyBeRecordedOnceAndAtQuestion() {
        val reading = Progress()
        assertEquals(reading, reading.answer(lesson.correctId, lesson))
        val question = reading.toQuestion()
        assertEquals(question, question.finish())
        assertEquals(question, question.answer("unknown", lesson))
        val wrong = question.answer("always", lesson)
        assertEquals(wrong, wrong.answer(lesson.correctId, lesson))
    }

    @Test fun rejectsStaleDamagedAndInconsistentBookmarks() {
        val id = lesson.revision
        val invalid = listOf(null, "", "v0|QUESTION|", "$id|GONE|",
            "$id|QUESTION|always", "$id|FEEDBACK|", "$id|SOURCE|unknown", "$id|REVIEW|always|extra")
        for (raw in invalid) assertNull(raw, Progress.decode(raw, lesson))
    }

    @Test fun readsAllLegacyStepsWithoutRewritingThem() {
        for (step in Step.entries) {
            val answer = if (step in listOf(Step.READING, Step.QUESTION)) null else "always"
            val old = "courtyard-v1|" + step.name + "|" + answer.orEmpty()
            val records = mutableMapOf("courtyard-v1" to old)
            val restored = readBookmark(records, lesson)
            assertEquals(Bookmark(Progress(step, answer)), restored)
            assertEquals(old, records["courtyard-v1"])
            val encoded = restored.progress!!.encode(lesson)
            assertTrue(encoded.contains("|node:" + lesson.nodeIds.getValue(step) + "|"))
            assertEquals(restored.progress, Progress.decode(encoded, lesson))
        }
    }

    @Test fun newPackageKeepsOtherVersionsSeparate() {
        val old = mapOf("courtyard-v1" to "courtyard-v1|FEEDBACK|always")
        val newer = lesson.copy(revision = "courtyard-v2")
        val bookmark = readBookmark(old, newer)
        assertNull(bookmark.progress)
        assertNotNull(bookmark.message)
        assertFalse(bookmark.blocked)
        val both = old + (newer.revision to Progress().encode(newer))
        assertEquals("always", readBookmark(both, lesson).progress?.answerId)
        assertEquals(Progress(), readBookmark(both, newer).progress)
    }

    @Test fun unreadableCurrentSlotIsProtectedIncludingWrongValueType() {
        for (raw in listOf("v0|QUESTION|", "courtyard-v1|node:removed|always", 19)) {
            val records = mutableMapOf(lesson.revision to raw)
            assertTrue(readBookmark(records, lesson).blocked)
            assertEquals(raw, records[lesson.revision])
        }
    }
}
