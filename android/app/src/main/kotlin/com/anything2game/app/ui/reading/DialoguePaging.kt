package com.anything2game.app.ui.reading

/**
 * Cuts one tutor message into dialogue-box pages: whole sentences, about 170 characters of Latin text or 90 CJK
 * (what the portrait dialogue box holds at 19 sp; callers divide by the font scale),
 * characters per page. A short heading line stays with the sentence after it, and a line ending in a colon stays with what it introduces.
 */
object DialoguePaging {
    const val LATIN_BUDGET = 170
    const val CJK_BUDGET = 90

    /** A scripted turn's box also carries the concept title (and, on the first page, the lead-in): its pages hold a bit less. */
    const val TITLED_SHARE = 0.86f

    /** The pages of a scripted turn's explanation (its title and lead-in are shown above the text, not paged). */
    fun pages(parts: com.anything2game.contract.tutor.TutorRules.ScriptedParts, latinBudget: Int = LATIN_BUDGET, cjkBudget: Int = CJK_BUDGET, share: Float = TITLED_SHARE): List<String> =
        pages(parts.body, (latinBudget * share).toInt(), (cjkBudget * share).toInt())

    /** With large system text the title lines take a bigger part of the box, so a page holds less. */
    fun titledShare(fontScale: Float): Float = if (fontScale >= 1.3f) 0.72f else TITLED_SHARE

    fun pages(text: String, latinBudget: Int = LATIN_BUDGET, cjkBudget: Int = CJK_BUDGET): List<String> {
        if (text.isBlank()) return listOf("")
        val perCjk = latinBudget.toFloat() / cjkBudget
        fun weight(s: String) = s.sumOf { (if (isCjk(it)) perCjk else 1f).toDouble() }.toFloat()
        val budget = latinBudget.toFloat()
        val limit = budget * 1.1f

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            // A sentence too long for one page is cut first (evenly, at clause marks), then headings and colon lines are glued on.
            sentences(line).flatMap { u ->
                if (weight(u.text) <= limit) listOf(u)
                else split(u.text, ::weight, budget).let { parts -> parts.mapIndexed { i, t -> Piece(t, if (i == parts.lastIndex) u.sep else if (isCjk(t.last())) "" else " ") } }
            }
        }
        val units = glue(lines, ::weight, limit)

        val pages = mutableListOf<MutableList<Piece>>()
        var used = 0f
        units.forEach { u ->
            val w = weight(u.text)
            if (pages.isEmpty() || used + w > limit) { pages += mutableListOf(u); used = w } else { pages.last() += u; used += w }
        }
        // A last page of one short sentence reads like a mistake: fold it into the page before when that stays reasonable.
        if (pages.size > 1) {
            val tail = pages.last().sumOf { weight(it.text).toDouble() }.toFloat()
            val before = pages[pages.size - 2].sumOf { weight(it.text).toDouble() }.toFloat()
            if (tail < budget * 0.25f && before + tail <= budget * 1.4f) pages.removeAt(pages.lastIndex).let { pages.last() += it }
        }
        return pages.map { page -> buildString { page.forEachIndexed { i, p -> append(p.text); if (i < page.lastIndex) append(p.sep) } } }
    }

    /** A piece of text and what goes between it and the next piece on the same page. */
    private class Piece(val text: String, val sep: String)

    private val closers = "”’\"')）」』】"
    private const val sentenceEnds = "。！？!?；;…"

    /** Sentences of one line. Between sentences: nothing after CJK text, a space after Latin text; the last one ends the line. */
    private fun sentences(line: String): List<Piece> {
        val out = mutableListOf<Piece>()
        val cur = StringBuilder()
        var i = 0
        fun emit() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) out += Piece(t, if (isCjk(t.trimEnd { it in closers }.lastOrNull() ?: ' ')) "" else " ")
            cur.clear()
        }
        while (i < line.length) {
            val c = line[i]
            cur.append(c)
            if (c in sentenceEnds || (c == '.' && (i + 1 >= line.length || line[i + 1].isWhitespace() || line[i + 1] in closers))) {
                // "...", "?!" and a closing quote after the stop belong to the same sentence.
                while (i + 1 < line.length && (line[i + 1] in sentenceEnds || line[i + 1] == '.' || line[i + 1] in closers)) { i++; cur.append(line[i]) }
                emit()
            }
            i++
        }
        emit()
        return out.mapIndexed { k, p -> if (k == out.lastIndex) Piece(p.text, "\n") else p }
    }

    /** Join heading-like and colon-ending pieces to the piece that follows. */
    private fun glue(lines: List<List<Piece>>, weight: (String) -> Float, limit: Float): List<Piece> {
        val flat = lines.flatten()
        val merged = mutableListOf<Piece>()
        var i = 0
        while (i < flat.size) {
            var text = flat[i].text
            var sep = flat[i].sep
            while (i < flat.lastIndex && sticks(text, sep) && weight(text + sep + flat[i + 1].text) <= limit) { i++; text += sep + flat[i].text; sep = flat[i].sep }
            merged += Piece(text, sep)
            i++
        }
        return merged
    }

    private fun sticks(text: String, sep: String): Boolean {
        val last = text.trimEnd().lastOrNull() ?: return false
        if (last == ':' || last == '：') return true
        // A heading: one short line without any sentence ending.
        return sep == "\n" && '\n' !in text && text.length <= 24 && last !in sentenceEnds && last != '.' && last !in closers && last != ',' && last != '，'
    }

    /** One sentence too long for a page: cut it into equal parts, each ending at a clause mark or a space once it is as long as its share. */
    private fun split(text: String, weight: (String) -> Float, budget: Float): List<String> {
        val parts = Math.ceil((weight(text) / budget).toDouble()).toInt().coerceAtLeast(2)
        val target = weight(text) / parts
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur = StringBuilder() }
        text.forEach { c ->
            cur.append(c)
            val w = weight(cur.toString())
            if (w >= budget) flush() else if (w >= target * 0.9f && c in "，、：,:; ") flush()
        }
        flush()
        return out
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF
}
