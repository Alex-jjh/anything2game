package com.anything2game.server

import com.anything2game.contract.DocumentKind
import com.anything2game.contract.DocumentResponse
import com.anything2game.contract.ErrorCodes
import com.anything2game.contract.PageText
import com.anything2game.server.config.Limits
import com.anything2game.server.ingest.Chunker
import com.anything2game.server.ingest.IngestException
import com.anything2game.server.ingest.PdfTextExtractor
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IngestTest {

    // ---- PDF upload through the API ---------------------------------------------------------------------------

    @Test fun `a real-sized PDF (well over 4 KB) uploads - regression for the multipart part limit`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val pdf = makePdf(List(40) { i -> "Page ${i + 1}: retrieval practice beats re-reading after a delay, measured one week later." })
            assertTrue("fixture must exceed the old 4 KB part limit, was ${pdf.size}", pdf.size > 2 * 4096)
            val r = api.uploadPdf(token, pdf)
            assertEquals(HttpStatusCode.Created, r.status)
            assertEquals(40, api.decode(DocumentResponse.serializer(), r).pageCount)
        }
    }

    @Test fun `a two page PDF becomes chunks with the right page index and label`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val pdf = makePdf(listOf("Alpha page one says retrieval practice helps.", "Beta page two says re-reading is weaker."))
            val r = api.uploadPdf(token, pdf)
            assertEquals(HttpStatusCode.Created, r.status)
            val doc = api.decode(DocumentResponse.serializer(), r)
            assertEquals(2, doc.pageCount)
            assertTrue(doc.documentVersion.startsWith("sha256:"))
            assertEquals(7 + 64, doc.documentVersion.length)
            val stored = env.store.getDocument(doc.documentId)!!
            assertEquals(DocumentKind.PDF, stored.kind)
            assertEquals(listOf("c1", "c2"), stored.chunks.map { it.chunkId })
            assertEquals(listOf(0, 1), stored.chunks.map { it.pageIndex })
            assertEquals(listOf("1", "2"), stored.chunks.map { it.pageLabel })
            assertTrue(stored.chunks[0].text.contains("Alpha"))
            assertTrue(stored.chunks[1].text.contains("Beta"))
            // the same PDF text uploaded as text has the same document version
            val same = api.decode(DocumentResponse.serializer(), api.uploadText(token, stored.chunks.map { it.text }))
            assertEquals(doc.documentVersion, same.documentVersion)
        }
    }

    @Test fun `a password protected PDF is refused with ENCRYPTED_PDF`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val r = api.uploadPdf(token, makePdf(listOf("secret text"), userPassword = "hunter2"))
            assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
            assertEquals(ErrorCodes.ENCRYPTED_PDF, api.error(r).code)
            // owner-password-only (opens without a password) is encrypted too and refused the same way
            val owner = api.uploadPdf(token, makePdf(listOf("secret text"), ownerPassword = "owner"))
            assertEquals(ErrorCodes.ENCRYPTED_PDF, api.error(owner).code)
        }
    }

    @Test fun `a PDF without any text is refused with NO_TEXT_LAYER`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val r = api.uploadPdf(token, makePdf(listOf("", "")))
            assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
            assertEquals(ErrorCodes.NO_TEXT_LAYER, api.error(r).code)
            // a PDF where only some pages are scans keeps the text pages and the page numbers
            val mixed = api.decode(DocumentResponse.serializer(), api.uploadPdf(token, makePdf(listOf("", "Only the second page has text."))))
            assertEquals(2, mixed.pageCount)
            assertEquals(listOf(1), env.store.getDocument(mixed.documentId)!!.chunks.map { it.pageIndex })
        }
    }

    @Test fun `bytes that are not a PDF are rejected even when named pdf`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val r = api.uploadPdf(token, "MZ this is an executable, not a PDF".toByteArray(), fileName = "book.pdf", contentType = "application/pdf")
            assertEquals(HttpStatusCode.UnsupportedMediaType, r.status)
            assertEquals(ErrorCodes.UNSUPPORTED_TYPE, api.error(r).code)
            // the magic bytes decide, not the extension: a real PDF named .txt is accepted
            val ok = api.uploadPdf(token, makePdf(listOf("A real pdf with a misleading name.")), fileName = "notes.txt", contentType = "text/plain")
            assertEquals(HttpStatusCode.Created, ok.status)
            // %PDF- magic but garbage inside
            val broken = api.uploadPdf(token, "%PDF-1.7\nthis is not a pdf body".toByteArray())
            assertEquals(HttpStatusCode.UnsupportedMediaType, broken.status)
        }
    }

    @Test fun `PDF size and page limits are enforced`() = TestEnv(Limits(maxPdfBytes = 2_000, maxPdfPages = 2)).use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val tooManyPages = api.uploadPdf(token, makePdf(listOf("a", "b", "c")).let { it.copyOf(it.size) })
            // the three page PDF is also over 2000 bytes or over 2 pages: either way FILE_TOO_LARGE
            assertEquals(HttpStatusCode.PayloadTooLarge, tooManyPages.status)
            assertEquals(ErrorCodes.FILE_TOO_LARGE, api.error(tooManyPages).code)
            val padding = api.uploadPdf(token, "%PDF-1.4\n".toByteArray() + ByteArray(3_000) { 'x'.code.toByte() })
            assertEquals(HttpStatusCode.PayloadTooLarge, padding.status)
            assertEquals(ErrorCodes.FILE_TOO_LARGE, api.error(padding).code)
        }
    }

    @Test fun `page limit is checked on its own`() {
        val pdf = makePdf(List(3) { "page $it has some text" })
        try {
            PdfTextExtractor(maxPages = 2).extract(pdf)
            fail("expected FILE_TOO_LARGE")
        } catch (e: IngestException) {
            assertEquals(ErrorCodes.FILE_TOO_LARGE, e.code)
        }
        assertEquals(3, PdfTextExtractor(maxPages = 3).extract(pdf).size)
    }

    // ---- chunker ----------------------------------------------------------------------------------------------

    @Test fun `chunks never cross pages and ids run c1 to cn`() {
        val long = (1..40).joinToString(" ") { "Sentence number $it talks about one small idea." }
        val pages = listOf(
            PageText(0, "Short first paragraph.\n\nSecond short paragraph on the same page.", "iv"),
            PageText(1, long),
            PageText(2, "Last page."),
        )
        val chunks = Chunker.chunk(pages)
        assertEquals((1..chunks.size).map { "c$it" }, chunks.map { it.chunkId })
        assertEquals("iv", chunks.first().pageLabel)
        assertEquals(0, chunks.first().pageIndex)
        assertTrue(chunks.first().text.contains("Short first") && chunks.first().text.contains("Second short"))
        val page1 = chunks.filter { it.pageIndex == 1 }
        assertTrue("long page is split", page1.size >= 2)
        assertTrue(page1.all { it.text.length <= Chunker.MAX })
        assertTrue(page1.dropLast(1).all { it.text.length >= Chunker.MIN })
        assertEquals("2", page1.first().pageLabel)
        assertEquals(listOf(0, 1, 2), chunks.map { it.pageIndex }.distinct())
        // all text is kept, nothing is invented
        val joined = page1.joinToString(" ") { it.text }
        assertEquals(long, joined.replace("\n\n", " "))
    }

    @Test fun `Chinese text without spaces is split at sentence ends`() {
        val zh = (1..400).joinToString("") { "这是第${it}句话，说明一个想法。" }
        val chunks = Chunker.chunk(listOf(PageText(0, zh)))
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.text.length <= Chunker.MAX })
        assertEquals(zh, chunks.joinToString("") { it.text })
    }

    @Test fun `documentVersion ignores whitespace differences only`() {
        val a = Chunker.documentVersion(listOf(PageText(0, "Hello   world\n\nagain")))
        val b = Chunker.documentVersion(listOf(PageText(0, "Hello world again")))
        val c = Chunker.documentVersion(listOf(PageText(0, "Hello world once more")))
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(a))
    }
}
