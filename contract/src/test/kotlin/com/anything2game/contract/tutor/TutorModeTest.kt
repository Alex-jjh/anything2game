package com.anything2game.contract.tutor

import com.anything2game.contract.ContractJson
import com.anything2game.contract.SourceChunk
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TutorModeTest {
    private fun session(mode: TutorMode) = TutorSession(sessionId = "t", documentId = "d", documentVersion = "v", title = "T", language = "en", mode = mode,
        questions = emptyList(), sources = listOf(SourceChunk("s", 0, "1", "text")))
    private fun wire(mode: TutorMode) = ContractJson.json.encodeToJsonElement(TutorSession.serializer(), session(mode)).jsonObject.getValue("mode").jsonPrimitive.content

    @Test fun `the wire strings are the ones every saved session and the server already use`() {
        assertEquals("local-mock", wire(TutorMode.LOCAL_MOCK))
        assertEquals("mock", wire(TutorMode.MOCK))
        assertEquals("model", wire(TutorMode.MODEL))
    }

    @Test fun `a saved session is read into the enum once, at decode`() {
        val text = ContractJson.json.encodeToString(TutorSession.serializer(), session(TutorMode.MOCK))
        for ((string, mode) in listOf("local-mock" to TutorMode.LOCAL_MOCK, "mock" to TutorMode.MOCK, "model" to TutorMode.MODEL)) {
            val read = ContractJson.json.decodeFromString(TutorSession.serializer(), text.replace("\"mode\":\"mock\"", "\"mode\":\"$string\""))
            assertEquals(mode, read.mode)
            assertEquals("round trip keeps the text", text.replace("\"mode\":\"mock\"", "\"mode\":\"$string\""), ContractJson.json.encodeToString(TutorSession.serializer(), read))
        }
    }

    @Test fun `a mode nobody knows is refused when the session is read, not shown as a model lesson`() {
        val text = ContractJson.json.encodeToString(TutorSession.serializer(), session(TutorMode.MOCK)).replace("\"mode\":\"mock\"", "\"mode\":\"oracle\"")
        assertThrows(SerializationException::class.java) { ContractJson.json.decodeFromString(TutorSession.serializer(), text) }
    }

    @Test fun `only the phone's own script counts as scripted`() {
        assertEquals(listOf(TutorMode.LOCAL_MOCK), TutorMode.entries.filter { it.scripted })
    }
}
