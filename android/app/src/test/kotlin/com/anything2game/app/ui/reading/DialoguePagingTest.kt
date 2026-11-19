package com.anything2game.app.ui.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialoguePagingTest {
    @Test fun `short text is one page`() {
        assertEquals(listOf("Hello there."), DialoguePaging.pages("Hello there."))
        assertEquals(listOf(""), DialoguePaging.pages("   "))
    }

    @Test fun `english pages break at sentence ends and stay near 110 characters`() {
        val sentence = "Retrieval practice means pulling an idea out of memory."
        val text = List(6) { sentence }.joinToString(" ")
        val pages = DialoguePaging.pages(text)
        assertTrue("more than one page: $pages", pages.size > 1)
        pages.forEach { p ->
            assertTrue("whole sentences only: $p", p.endsWith("."))
            assertTrue("page of ${p.length} chars", p.length <= 125)
        }
        assertEquals("nothing lost", text, pages.joinToString(" "))
    }

    @Test fun `chinese pages hold about 60 characters and join without spaces`() {
        val s = "检索练习的关键是从记忆中提取内容。"
        val text = s.repeat(8)
        val pages = DialoguePaging.pages(text)
        assertTrue(pages.size >= 2)
        pages.forEach { assertTrue("page of ${it.length} chars", it.length <= 66 && it.endsWith("。")) }
        assertEquals(text, pages.joinToString(""))
    }

    @Test fun `a heading line and a colon line stay with the sentence after them`() {
        val text = "检索练习\n你选了不知道，我们从这个概念开始：\n检索练习的关键是“从记忆中提取”。文章把它与重读、划线等再次看到内容的动作区分开来。重点不在有没有做题，而在是否需要回想。"
        val pages = DialoguePaging.pages(text)
        assertEquals(2, pages.size)
        assertTrue(pages[0].startsWith("检索练习\n你选了不知道，我们从这个概念开始：\n检索练习的关键是"))
        assertEquals(text.replace("\n", ""), pages.joinToString("").replace("\n", ""))
    }

    @Test fun `one very long sentence is still cut into readable pages`() {
        val text = "word ".repeat(80).trim() + "."
        val pages = DialoguePaging.pages(text)
        assertTrue(pages.size >= 3)
        pages.forEach { assertTrue(it.length <= 125) }
    }

    @Test fun `a lone short last sentence folds into the page before`() {
        val text = "A".repeat(95) + ". Ok."
        val pages = DialoguePaging.pages(text)
        assertEquals(1, pages.size)
    }

    @Test fun `a heading and a colon line stay in front of a long sentence that is cut evenly`() {
        val long = "Recurrent networks compute hidden states step by step, so every step waits for the one before it, which gets slower as sentences get longer, and that was the core motivation for a different architecture."
        val text = "Why ditch recurrence\nYou chose not sure. Let us start with this concept:\n$long"
        val pages = DialoguePaging.pages(text)
        assertTrue(pages.first().startsWith("Why ditch recurrence\nYou chose not sure."))
        assertEquals("nothing lost", text.replace("\n", " ").replace(Regex("\\s+"), " "), pages.joinToString(" ").replace("\n", " ").replace(Regex("\\s+"), " "))
        pages.forEach { assertTrue("page of ${it.length}: $it", it.length <= 135) }
        assertTrue("no page is a sliver", pages.all { it.length > 30 })
    }
}
