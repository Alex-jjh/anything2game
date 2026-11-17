package edu.csi301.fieldnotes

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

private fun fixture() = checkNotNull(JsonLessonRepositoryTest::class.java.getResource("/" + SAMPLE_ASSET)).readText()
private fun read(text: String) = JsonLessonRepository { text.reader() }.sample()
fun loadSample(): Lesson = read(fixture()).getOrThrow()

class JsonLessonRepositoryTest {
    private fun changed(edit: (JsonObject) -> Unit): Result<Lesson> {
        val root = JsonParser.parseString(fixture()).asJsonObject
        edit(root)
        return read(root.toString())
    }

    @Test fun packagedFixtureConnectsAnswerExplanationAndOriginalExcerpt() {
        val lesson = loadSample()
        assertEquals("courtyard-v1", lesson.revision)
        assertEquals(Step.entries.toSet(), lesson.nodeIds.keys)
        assertEquals("at_three", lesson.correctId)
        assertEquals("这天下午三点，树荫下的读数低了 3 ℃。", lesson.choices.single { it.id == lesson.correctId }.text)
        assertTrue(lesson.explanation.contains("相差 3 ℃"))
        assertEquals("courtyard-p1", lesson.nodeIds[Step.SOURCE])
        assertEquals("庭院观察手记", lesson.source.title)
        assertTrue(lesson.source.text.contains("在树荫下读到 28 ℃，在阳光下读到 31 ℃"))
    }

    @Test fun malformedOrUnavailableInputFailsWithoutPartialLesson() {
        for (text in listOf("", "null", "[]", "{", "{'format':'draft/mock-v1'}", fixture() + "{}")) {
            assertTrue(read(text).isFailure)
        }
        assertTrue(JsonLessonRepository { throw IOException("missing asset") }.sample().isFailure)
    }

    @Test fun requiredFieldsTypesAndDraftVersionAreChecked() {
        assertTrue(changed { it.remove("packageVersion") }.isFailure)
        assertTrue(changed { it.addProperty("format", "draft/mock-v2") }.isFailure)
        assertTrue(changed { it.getAsJsonObject("source").addProperty("text", 7) }.isFailure)
        assertTrue(changed { it.getAsJsonObject("review").addProperty("text", "  ") }.isFailure)
        assertTrue(changed { it.getAsJsonObject("question").remove("feedback") }.isFailure)
    }

    @Test fun rejectsDanglingAnswerSourceAndDuplicateIds() {
        for (field in listOf("correctOptionId", "sourceId")) {
            assertTrue(changed { it.getAsJsonObject("question").getAsJsonObject("feedback")
                .addProperty(field, "missing") }.isFailure)
        }
        assertTrue(changed { it.getAsJsonObject("reading").addProperty("id", "compare-readings") }.isFailure)
        assertTrue(changed { it.getAsJsonObject("question").getAsJsonArray("choices")[1].asJsonObject
            .addProperty("id", "always") }.isFailure)
    }
}
