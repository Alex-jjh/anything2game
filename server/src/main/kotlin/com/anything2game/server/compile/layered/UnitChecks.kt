package com.anything2game.server.compile.layered

import com.anything2game.contract.DeduceItem
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.contract.OrderItem
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.SortItem
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SwipeItem

/**
 * Deterministic checks of one generated unit (design section 3, layer L3). Returns the issues as text lines that go straight into the
 * one repair call. Wording is checked only for NUMBERS: a number on the correct side (supported swipe cards, sort cards, the correct
 * deduce option, every feedback / why text, order steps) must appear in the quotes of the unit's claims. Only a declared mutation may
 * bring in a number of its own, and it only licenses the numbers of its own part.
 */
object UnitChecks {

    private class Part(
        val id: String,
        val text: String,
        /** Feedback / why texts that belong to this part. */
        val explain: List<String>,
        /** True when the part is the wrong side of the item (an unsupported swipe card, a wrong deduce option): it must declare a mutation. */
        val wrong: Boolean,
        /** Whether a mutation may be declared for the part. Sort cards may be decoys (a mutated card in the "not in the material" bin) but need not. */
        val mayMutate: Boolean = wrong,
    )

    private fun parts(item: Item): List<Part> = when (item) {
        is SwipeItem -> item.cards.map { Part(it.id, it.statement, listOf(it.why), wrong = !it.supported) }
        is SortItem -> item.cards.map { Part(it.id, it.text, emptyList(), wrong = false, mayMutate = true) }
        is DeduceItem -> item.options.map { Part(it.id, it.text, listOf(it.feedback), wrong = !it.correct) }
        is OrderItem -> item.steps.map { Part(it.id, it.text, emptyList(), wrong = false, mayMutate = false) }
        else -> emptyList()
    }

    /** Item-level explanations (not tied to one part). */
    private fun itemExplain(item: Item): List<String> = when (item) {
        is SortItem -> listOf(item.why)
        is OrderItem -> listOf(item.why)
        else -> emptyList()
    }

    fun check(parsed: ParsedUnit, claims: List<Claim>, chunks: Map<String, SourceChunk>): List<String> {
        val unit = parsed.unit
        val issues = mutableListOf<String>()
        PackageValidator.checkUnit("unit", unit, chunks) { path, message -> issues += "$path: $message" }

        if (unit.mechanic == Mechanic.ORDER && claims.none { it.type == ClaimType.SEQUENCE }) {
            issues += "unit: an order unit must be built on a sequence claim, and none of this unit's claims is a sequence"
        }

        val items = listOf("item" to unit.item) + unit.variants.mapIndexed { i, v -> "variants[$i]" to v }
        val allParts = items.flatMap { (path, item) -> parts(item).map { path to it } }
        allParts.groupBy { it.second.id }.filterValues { it.size > 1 }.keys.forEach {
            issues += "unit: part id '$it' is used more than once across the item and its variants (ids must be unique in the whole unit)"
        }

        val claimIds = claims.map { it.claimId }.toSet()
        val partIds = allParts.map { it.second.id }.toSet()
        for (m in parsed.mutations) {
            when {
                m.partId !in partIds -> issues += "mutations: partId '${m.partId}' is not a part of this unit"
                m.claimId !in claimIds -> issues += "mutations: part '${m.partId}' names claim '${m.claimId}', which is not one of this unit's claims"
                MutationKind.parse(m.kind) == null ->
                    issues += "mutations: part '${m.partId}' has kind '${m.kind}'; use one of ${MutationKind.entries.joinToString { it.wire }}"
            }
        }

        val allowed = claims.flatMap { Numbers.tokens(it.quote) }.toSet()
        val byPart = parsed.mutations.groupBy { it.partId }
        for ((path, item) in items) {
            for (part in parts(item)) {
                val mutated = byPart.containsKey(part.id)
                if (part.wrong && !mutated) {
                    issues += "$path: part '${part.id}' is wrong but declares no mutation (add {\"partId\":\"${part.id}\",\"claimId\":...,\"kind\":...} to mutations)"
                }
                if (mutated && !part.wrong && !part.mayMutate) {
                    issues += "$path: part '${part.id}' is on the correct side but is declared as a mutation"
                }
                val own = if (mutated) Numbers.set(part.text) else emptySet()
                if (!mutated) newNumbers(part.text, allowed)?.let { issues += "$path: part '${part.id}' text brings in number(s) $it that no claim quote contains" }
                for (e in part.explain) {
                    newNumbers(e, allowed + own)?.let { issues += "$path: the feedback of part '${part.id}' brings in number(s) $it that no claim quote contains" }
                }
            }
            for (e in itemExplain(item)) {
                newNumbers(e, allowed)?.let { issues += "$path: the explanation brings in number(s) $it that no claim quote contains" }
            }
        }
        return issues
    }

    private fun newNumbers(text: String, allowed: Set<String>): List<String>? =
        Numbers.tokens(text).filter { it !in allowed }.distinct().takeIf { it.isNotEmpty() }
}
