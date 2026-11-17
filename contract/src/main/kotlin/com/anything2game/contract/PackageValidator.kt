package com.anything2game.contract

/** A problem found in a package. [path] points at the offending element, e.g. `levels[0].units[2].item.options`. */
data class Issue(val path: String, val message: String) {
    override fun toString() = "$path: $message"
}

/**
 * Structural and reference checks shared by server (before publishing) and client (before playing).
 * It cannot judge whether a passage *supports* an answer; that is a separate, semantic gate on the server.
 */
object PackageValidator {

    fun validate(pkg: LearningPackage): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun fail(path: String, message: String) { issues += Issue(path, message) }

        if (pkg.schema != PACKAGE_SCHEMA_V1) fail("schema", "unsupported schema '${pkg.schema}'")
        if (pkg.levels.isEmpty()) fail("levels", "package has no levels")
        if (pkg.sources.isEmpty()) fail("sources", "package has no source chunks")

        duplicates(pkg.sources.map { it.chunkId }).forEach { fail("sources", "duplicate chunkId '$it'") }
        pkg.sources.forEachIndexed { i, c ->
            if (c.text.isBlank()) fail("sources[$i]", "chunk '${c.chunkId}' has no text")
            if (c.pageIndex < 0) fail("sources[$i]", "negative pageIndex")
        }
        val chunks = pkg.sources.associateBy { it.chunkId }

        duplicates(pkg.levels.map { it.levelId }).forEach { fail("levels", "duplicate levelId '$it'") }
        duplicates(pkg.levels.flatMap { l -> l.units.map { it.unitId } }).forEach { fail("levels", "duplicate unitId '$it'") }

        pkg.levels.forEachIndexed { li, level ->
            val lp = "levels[$li]"
            if (level.units.isEmpty()) fail("$lp.units", "level '${level.levelId}' has no units")
            if (level.recap.points.isEmpty()) fail("$lp.recap", "recap has no points")
            level.units.forEachIndexed { ui, unit ->
                val up = "$lp.units[$ui]"
                checkItem("$up.item", unit.mechanic, unit.item, chunks, ::fail)
                unit.variants.forEachIndexed { vi, v -> checkItem("$up.variants[$vi]", unit.mechanic, v, chunks, ::fail) }
            }
        }
        return issues
    }

    private fun checkItem(
        path: String,
        mechanic: Mechanic,
        item: Item,
        chunks: Map<String, SourceChunk>,
        fail: (String, String) -> Unit,
    ) {
        if (item.mechanic != mechanic) fail(path, "item type ${item.mechanic} does not match unit mechanic $mechanic")
        when (item) {
            is SwipeItem -> {
                if (item.cards.isEmpty()) fail("$path.cards", "no cards")
                duplicates(item.cards.map { it.id }).forEach { fail("$path.cards", "duplicate card id '$it'") }
                item.cards.forEachIndexed { i, c ->
                    if (c.sourceRefs.isEmpty()) fail("$path.cards[$i]", "card '${c.id}' has no citation")
                    if (c.why.isBlank()) fail("$path.cards[$i]", "card '${c.id}' has no explanation")
                }
            }
            is SortItem -> {
                if (item.bins.size < 2) fail("$path.bins", "needs at least 2 bins")
                if (item.cards.size < 2) fail("$path.cards", "needs at least 2 cards")
                duplicates(item.bins.map { it.id }).forEach { fail("$path.bins", "duplicate bin id '$it'") }
                duplicates(item.cards.map { it.id }).forEach { fail("$path.cards", "duplicate card id '$it'") }
                val binIds = item.bins.map { it.id }.toSet()
                item.cards.forEachIndexed { i, c ->
                    if (c.binId !in binIds) fail("$path.cards[$i]", "card '${c.id}' points at unknown bin '${c.binId}'")
                }
                val used = item.cards.map { it.binId }.toSet()
                item.bins.filter { it.id !in used }.forEach { fail("$path.bins", "bin '${it.id}' has no cards") }
                if (item.sourceRefs.isEmpty()) fail(path, "no citation")
            }
            is DeduceItem -> {
                if (item.options.size < 2) fail("$path.options", "needs at least 2 options")
                duplicates(item.options.map { it.id }).forEach { fail("$path.options", "duplicate option id '$it'") }
                val correct = item.options.count { it.correct }
                if (correct != 1) fail("$path.options", "exactly one option must be correct, found $correct")
                item.options.forEachIndexed { i, o ->
                    if (o.feedback.isBlank()) fail("$path.options[$i]", "option '${o.id}' has no feedback")
                }
                if (item.sourceRefs.isEmpty()) fail(path, "no citation")
            }
            is OrderItem -> {
                if (item.steps.size < 3) fail("$path.steps", "needs at least 3 steps")
                duplicates(item.steps.map { it.id }).forEach { fail("$path.steps", "duplicate step id '$it'") }
                if (item.sourceRefs.isEmpty()) fail(path, "no citation")
            }
        }
        item.allSourceRefs.forEach { ref ->
            val chunk = chunks[ref.chunkId]
            when {
                chunk == null -> fail(path, "citation points at unknown chunk '${ref.chunkId}'")
                ref.quote.isBlank() -> fail(path, "empty quote for chunk '${ref.chunkId}'")
                normalise(ref.quote) !in normalise(chunk.text) -> fail(path, "quote not found in chunk '${ref.chunkId}': \"${ref.quote}\"")
            }
        }
    }

    /** Collapse whitespace so line breaks from PDF extraction don't break quote matching. */
    fun normalise(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun duplicates(ids: List<String>): Set<String> = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
