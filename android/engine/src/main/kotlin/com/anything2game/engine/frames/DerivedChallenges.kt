package com.anything2game.engine.frames

import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.DeduceOption
import com.anything2game.contract.Item
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.Level
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.contract.SourceRef
import com.anything2game.contract.SwipeCard
import com.anything2game.contract.SwipeItem

/** One statement taken from an already-validated item, with the reason it holds or not and where the material says so. */
data class Claim(val text: String, val holds: Boolean, val why: String, val sourceRefs: List<SourceRef>)

/**
 * Challenges a frame derives from the level's own items (docs/product/frames.md §4): the Rookie's teach-back and
 * the Escape room lock. They are read-only recombinations: no new facts, only short fixed templates around text
 * the package already carries, and every one keeps the source items' citations so "Show evidence" works.
 * Pure and deterministic: every choice comes from [stableHash] of ids, never from an unseeded random source.
 */
object DerivedChallenges {
    /** Keeps a Rookie level short. */
    const val MAX_TEACH_BACKS = 2
    /** Keeps a room lock to a few swipes. */
    const val MAX_LOCK_CARDS = 3

    /** FNV-1a over UTF-8: the same on every JVM and every run (unlike an unseeded Random or an identity hash). */
    fun stableHash(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) h = (h xor (b.toInt() and 0xff)) * 0x01000193
        return h and Int.MAX_VALUE
    }

    private fun zh(language: String) = language.lowercase().startsWith("zh")
    private fun pick(n: Int, key: String): Int = stableHash(key) % n
    /** Wraps a spoken claim in quotes, unless it already opens with a quoted phrase ("“A” comes before “B”"): then it reads as speech as it is. */
    private fun quoted(text: String, open: String, close: String) =
        if (text.startsWith("“") || text.startsWith("「")) text else open + clean(text) + close
    private fun clean(s: String) = s.trim().trimEnd('.', '。', ' ')

    // ---- Claims out of items ---------------------------------------------------------------

    /** A statement from [item] that is wrong, with why. Null when the item has no such statement or no citation for it. [salt] (e.g. the unit id) varies the pick between units. */
    fun misconceptionOf(item: Item, language: String = "en", salt: String = ""): Claim? = when (item) {
        is SwipeItem -> item.cards.filter { !it.supported && it.usable() }.let { c ->
            c.getOrNull(pick(c.size.coerceAtLeast(1), "$salt:m"))?.let { Claim(it.statement, false, it.why, it.sourceRefs) }
        }
        is DeduceItem -> item.options.filter { !it.correct && it.feedback.isNotBlank() }.let { o ->
            o.getOrNull(pick(o.size.coerceAtLeast(1), "$salt:m"))?.takeIf { item.sourceRefs.isNotEmpty() }
                ?.let { Claim(it.text, false, it.feedback, item.sourceRefs) }
        }
        is SortItem -> sortClaim(item, language, salt, holds = false)
        is OrderItem -> orderClaim(item, language, salt, holds = false)
        is CrossExamItem -> item.statements.firstOrNull { it.flawed && it.why.isNotBlank() && it.sourceRefs.isNotEmpty() }
            ?.let { Claim(it.text, false, it.why, it.sourceRefs) }
        else -> null
    }

    /** A statement from [item] that holds, with why. Mirrors [misconceptionOf]. */
    fun truthOf(item: Item, language: String = "en", salt: String = ""): Claim? = when (item) {
        is SwipeItem -> item.cards.filter { it.supported && it.usable() }.let { c ->
            c.getOrNull(pick(c.size.coerceAtLeast(1), "$salt:t"))?.let { Claim(it.statement, true, it.why, it.sourceRefs) }
        }
        is DeduceItem -> item.options.firstOrNull { it.correct && it.feedback.isNotBlank() }?.takeIf { item.sourceRefs.isNotEmpty() }
            ?.let { Claim(it.text, true, it.feedback, item.sourceRefs) }
        is SortItem -> sortClaim(item, language, salt, holds = true)
        is OrderItem -> orderClaim(item, language, salt, holds = true)
        is CrossExamItem -> item.statements.firstOrNull { !it.flawed && it.why.isNotBlank() && it.sourceRefs.isNotEmpty() }
            ?.let { Claim(it.text, true, it.why, it.sourceRefs) }
        else -> null
    }

    private fun SwipeCard.usable() = why.isNotBlank() && sourceRefs.isNotEmpty() && statement.isNotBlank()

    /** "<card> belongs under <bin>": the true claim uses the card's own bin, the wrong one another bin. The two use different cards, so a pair from one item never contradicts itself. */
    private fun sortClaim(item: SortItem, language: String, salt: String, holds: Boolean): Claim? {
        if (item.cards.isEmpty() || item.why.isBlank() || item.sourceRefs.isEmpty()) return null
        val m = pick(item.cards.size, "$salt:m")
        val card = item.cards[if (holds) (m + 1) % item.cards.size else m]
        val bin = if (holds) item.bins.firstOrNull { it.id == card.binId }
        else item.bins.filter { it.id != card.binId }.let { wrong -> wrong.getOrNull(pick(wrong.size.coerceAtLeast(1), "$salt:b")) }
        bin ?: return null
        val text = if (zh(language)) "「${clean(card.text)}」应归入「${bin.label}」" else "“${clean(card.text)}” belongs under “${bin.label}”"
        return Claim(text, holds, item.why, item.sourceRefs)
    }

    /** "<later step> comes before <earlier step>" is the wrong claim; the right one keeps the real order (a different pair). */
    private fun orderClaim(item: OrderItem, language: String, salt: String, holds: Boolean): Claim? {
        val n = item.steps.size
        if (n < 2 || item.why.isBlank() || item.sourceRefs.isEmpty()) return null
        val i = pick(n - 1, "$salt:m")
        val a = if (holds) (i + 1) % (n - 1) else i
        val b = a + 1 + pick(n - 1 - a, "$salt:o${if (holds) "t" else "m"}")
        val (first, second) = if (holds) item.steps[a] to item.steps[b] else item.steps[b] to item.steps[a]
        val text = if (zh(language)) "「${clean(first.text)}」排在「${clean(second.text)}」之前"
        else "“${clean(first.text)}” comes before “${clean(second.text)}”"
        return Claim(text, holds, item.why, item.sourceRefs)
    }

    // ---- Rookie: teach-back ------------------------------------------------------------------

    /** The first [MAX_TEACH_BACKS] units of [level] (in order) that can be taught back: a misconception plus at least one other unit to borrow a wrong explanation from. */
    fun teachBackUnitIds(level: Level): List<String> =
        level.units.filter { u -> teachBack(u, level.units.filter { it.unitId != u.unitId }, "", "en") != null }
            .take(MAX_TEACH_BACKS).map { it.unitId }

    /**
     * The rookie states [unit]'s misconception; the player picks the explanation they would give.
     * The correct option is the claim's own `why`; the others are explanations of OTHER units ([otherUnits], in priority order)
     * that are true in the reading but do not answer this point. Null when no distractor exists.
     */
    fun teachBack(unit: LearningUnit, otherUnits: List<LearningUnit>, rookieName: String, language: String): DeduceItem? {
        val claim = misconceptionOf(unit.item, language, unit.unitId) ?: return null
        val distractors = otherUnits.asSequence().filter { it.unitId != unit.unitId }
            .mapNotNull { misconceptionOf(it.item, language, it.unitId)?.why }
            .filter { it != claim.why }.distinct().take(2).toList()
        if (distractors.isEmpty()) return null
        val zh = zh(language)
        val right = if (zh) "这正是原文给出的解释。" else "That is the explanation the reading gives."
        val elsewhere = if (zh) "原文中确实如此，但这并没有回应这一点。" else "True elsewhere in the reading, but it does not answer this point."
        val texts = (listOf(claim.why to true) + distractors.map { it to false }).sortedBy { stableHash("${unit.unitId}:${it.first}") }
        return DeduceItem(
            scenario = if (zh) "$rookieName：${quoted(claim.text, "「", "」")}" else "$rookieName: ${quoted(claim.text, "“", "”")}",
            prompt = if (zh) "你会怎样向${rookieName}解释？" else "Which explanation would you give $rookieName?",
            options = texts.mapIndexed { i, (t, ok) -> DeduceOption("o${i + 1}", t, ok, if (ok) right else elsewhere) },
            sourceRefs = claim.sourceRefs,
        )
    }

    // ---- Escape: room lock -------------------------------------------------------------------

    /**
     * A short swipe lock from one room's units: one claim per unit (at most [MAX_LOCK_CARDS]), alternating "holds" and
     * "does not hold" from a stable hash. A room with a single unit contributes one true and one false claim from it.
     * Null when fewer than two claims result.
     */
    fun roomCheck(roomUnits: List<LearningUnit>, language: String): SwipeItem? {
        val h0 = stableHash(roomUnits.joinToString("|") { it.unitId })
        fun claim(u: LearningUnit, holds: Boolean) =
            (if (holds) truthOf(u.item, language, u.unitId) else misconceptionOf(u.item, language, u.unitId))
        val picked = mutableListOf<Pair<LearningUnit, Claim>>()
        for ((i, u) in roomUnits.withIndex()) {
            if (picked.size >= MAX_LOCK_CARDS) break
            val want = (h0 + i) % 2 == 0
            val c = claim(u, want) ?: claim(u, !want) ?: continue
            if (picked.none { it.second.text == c.text }) picked += u to c
        }
        if (picked.size >= 2 && picked.all { it.second.holds == picked[0].second.holds }) {
            val (u, last) = picked.last()
            claim(u, !last.holds)?.takeIf { c -> picked.none { it.second.text == c.text } }?.let { picked[picked.lastIndex] = u to it }
        }
        if (picked.size < 2) return null
        return SwipeItem(picked.mapIndexed { i, (_, c) -> SwipeCard("c${i + 1}", c.text, c.holds, c.why, c.sourceRefs) })
    }
}
