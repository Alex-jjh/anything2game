package com.anything2game.engine.progress

import com.anything2game.contract.progress.*
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.space.*
import org.junit.Assert.*
import org.junit.Test

class ProgressProjectionTest {
    private val a = ActivityKey("p", ActivityKind.GAME, "doors", "1", "transfer", 1)
    private fun event(id: String, result: ActivityOutcome, hour: Long = 0) =
        CompletionRecord.at(id, a, result, hour * 3_600_000L, "America/Los_Angeles")

    @Test fun `reading completes a tutor but cannot unlock a game or practice prerequisite`() {
        val tutor = ActivityRef.Tutor("pdf", "1", "lesson")
        val practice = ActivityRef.Practice("pdf", "1", "level")
        val path = LearningPath("route", "1", "space", "Study", "en", listOf(
            PathNode("game", "Transfer", ActivityRef.Game("doors", "1", "transfer")),
            PathNode("practice", "Challenge", practice),
            PathNode("lesson", "Read", tutor),
            PathNode("after", "Continue", ActivityRef.Tutor("pdf", "1", "next"), listOf("game")),
        ))
        val reading = event("read-game", ActivityOutcome.READ)
        val invalid = ProgressProjection.from("p", listOf(reading, reading.copy(completionId = "read-practice", activity = practice.key("p"))))
        assertTrue(invalid.completed.isEmpty())
        assertTrue(invalid.mastery.isEmpty())
        assertTrue(invalid.days.isEmpty())
        assertEquals(NodeStatus.LOCKED, invalid.nodes("p", path, path.dependencies, 0)["after"])
        val valid = ProgressProjection.from("p", listOf(reading.copy(activity = tutor.key("p"))))
        assertEquals(setOf(tutor.key("p")), valid.completed)
        assertEquals(NodeStatus.COMPLETE, valid.nodes("p", path, path.dependencies, 0)["lesson"])
        assertEquals(setOf("1969-12-31"), valid.days)
        assertTrue(valid.mastery.isEmpty())
    }

    @Test fun `one completion unlocks repeated references while due review keeps completion and missing versions need update`() {
        val ref = ActivityRef.Game("doors", "1", "transfer")
        val path = LearningPath("route", "1", "space", "Route", "en", listOf(
            PathNode("first", "Doors", ref),
            PathNode("again", "Replay", ref, listOf("first")),
            PathNode("next", "Read", ActivityRef.Tutor("pdf", "1", "one"), listOf("first")),
        ))
        val versions = path.dependencies
        val empty = ProgressProjection.from("p", emptyList())
        assertEquals(NodeStatus.LOCKED, empty.nodes("p", path, versions, 0).getValue("next"))
        val done = ProgressProjection.from("p", listOf(event("one", ActivityOutcome.INDEPENDENT)))
        val status = done.nodes("p", path, versions, Long.MAX_VALUE)
        assertEquals(1, done.completed.size)
        assertEquals(NodeStatus.REVIEW, status["again"])
        assertEquals(NodeStatus.AVAILABLE, status["next"])
        assertEquals(NodeStatus.NEEDS_UPDATE, done.nodes("p", path, setOf(ContentVersion("doors", "2")), 0)["first"])
    }

    @Test fun `help and reading count for days but independent successes earn mastery across repeated appearances`() {
        val helped = event("a", ActivityOutcome.HELPED)
        val one = event("b", ActivityOutcome.INDEPENDENT, 1)
        val two = event("c", ActivityOutcome.INDEPENDENT, 24)
        val view = ProgressProjection.from("p", listOf(helped, one, one, two))
        assertEquals(1, view.completed.size)
        assertEquals(3, view.mastery.getValue(a).attempts)
        assertEquals(2, view.mastery.getValue(a).box)
        assertEquals(setOf("1969-12-31", "1970-01-01"), view.days)
        assertEquals(0, ProgressProjection.from("p", listOf(helped)).mastery.getValue(a).box)
        assertEquals(0, ProgressProjection.from("p", listOf(event("r", ActivityOutcome.READ))).mastery.size)
        assertTrue(ProgressProjection.from("p", listOf(event("x", ActivityOutcome.PLAYED))).days.isEmpty())
        assertTrue(ProgressProjection.from("other", listOf(two)).completed.isEmpty())
        assertFalse(a.copy(contentVersion = "2") in view.completed)
    }
}
