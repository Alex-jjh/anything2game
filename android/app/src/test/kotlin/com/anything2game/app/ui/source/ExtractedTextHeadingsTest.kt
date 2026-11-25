package com.anything2game.app.ui.source

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

/** Headings the extractor left as wiki markup read as headings: the words, bold and larger, without the "==" marks. */
class ExtractedTextHeadingsTest {
    private fun headingWords(m: Marked) = m.text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { m.text.text.substring(it.start, it.end) }

    @Test fun `a heading line loses its marks and keeps its words as the heading`() {
        val m = marked("Intro line.\n== 研究和应用 ==\nBody text.", emptyList())
        assertEquals("Intro line.\n研究和应用\nBody text.", m.text.text)
        assertEquals(listOf("研究和应用"), headingWords(m))
        assertEquals(-1, m.first)
    }

    @Test fun `deeper headings and uneven spacing are headings too`() {
        val m = marked("=== Method ===\n==Results==\n==  Spaced out  ==", emptyList())
        assertEquals("Method\nResults\nSpaced out", m.text.text)
        assertEquals(listOf("Method", "Results", "Spaced out"), headingWords(m))
    }

    @Test fun `equals signs that are not a heading are left alone`() {
        for (text in listOf("a == b is a comparison", "== only an opening", "x ==Not a heading== y", "== one ===", "====", "== ==")) {
            val m = marked(text, emptyList())
            assertEquals(text, m.text.text)
            assertEquals(emptyList<String>(), headingWords(m))
        }
    }

    @Test fun `a quote after a heading is still marked on exactly its words, and its place is in the text as shown`() {
        val text = "Intro line.\n== 研究和应用 ==\nBody text that follows."
        val m = marked(text, listOf("text that follows"))
        val mark = m.text.spanStyles.single { it.item.background != androidx.compose.ui.graphics.Color.Unspecified }
        assertEquals("text that follows", m.text.text.substring(mark.start, mark.end))
        assertEquals(mark.start, m.first)
    }

    @Test fun `a quote inside a heading is marked within its words`() {
        val m = marked("== Research and use ==\nMore.", listOf("and use"))
        val mark = m.text.spanStyles.single { it.item.background != androidx.compose.ui.graphics.Color.Unspecified }
        assertEquals("and use", m.text.text.substring(mark.start, mark.end))
    }

    @Test fun `text with no headings is unchanged`() {
        val m = marked("Plain paragraph.\nAnother.", listOf("Another"))
        assertEquals("Plain paragraph.\nAnother.", m.text.text)
        assertEquals(17, m.first)
    }
}
