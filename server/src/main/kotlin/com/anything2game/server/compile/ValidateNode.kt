package com.anything2game.server.compile

import com.anything2game.contract.Level
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PackageValidator
import com.anything2game.server.llm.LlmClient

data class ValidateInput(val draft: LevelDraft, val shell: LearningPackage)

data class ValidatedLevel(val level: Level, val repaired: Boolean, val droppedUnits: List<String>, val droppedVariants: Int)

/**
 * Runs the contract's [PackageValidator] on a package that holds this level. On issues it makes ONE repair call that includes
 * the issue list; what still fails afterwards is dropped (a bad variant drops only the variant, a bad unit drops the unit).
 * A level with fewer than [MIN_UNITS] surviving units fails.
 */
class ValidateNode(private val llm: LlmClient) : PipelineNode<ValidateInput, ValidatedLevel> {
    override val id = "validate"
    private val unitPath = Regex("^levels\\[0]\\.units\\[(\\d+)]")
    private val variantPath = Regex("^levels\\[0]\\.units\\[(\\d+)]\\.variants\\[(\\d+)]")

    override suspend fun run(input: ValidateInput): ValidatedLevel {
        val draft = input.draft
        var level = draft.level
        var issues = draft.parseIssues + (level?.let { validate(it, input.shell) } ?: emptyList())
        var repaired = false
        if (issues.isNotEmpty()) {
            repaired = true
            val raw = LlmSteps.repair(llm, draft.prompt, draft.raw, issues, LevelNode.MAX_TOKENS, "level:${draft.planned.levelId}")
            val (second, parseIssues) = LevelNode.parse(raw, draft.planned)
            if (second != null) level = second
            else if (level == null) throw GenerationException.failed("Level ${draft.planned.levelId} could not be repaired: ${parseIssues.firstOrNull()}")
        }
        var current = level ?: throw GenerationException.failed("Level ${draft.planned.levelId} could not be parsed.")

        val droppedUnits = mutableListOf<String>()
        var droppedVariants = 0
        repeat(MAX_DROP_ROUNDS) {
            val found = PackageValidator.validate(shell(input.shell, current))
            if (found.isEmpty()) return finish(current, repaired, droppedUnits, droppedVariants)
            val badVariants = found.mapNotNull { variantPath.find(it.path)?.destructured }.map { (u, v) -> u.toInt() to v.toInt() }.toSet()
            val badUnits = found.filter { variantPath.find(it.path) == null }.mapNotNull { unitPath.find(it.path)?.groupValues?.get(1)?.toInt() }.toSet()
            val levelWide = found.filter { unitPath.find(it.path) == null }
            if (levelWide.isNotEmpty()) throw GenerationException.failed("Level ${current.levelId} is invalid: ${levelWide.first()}")
            droppedVariants += badVariants.count { it.first !in badUnits }
            droppedUnits += badUnits.map { current.units[it].unitId }
            current = current.copy(
                units = current.units.mapIndexedNotNull { ui, unit ->
                    if (ui in badUnits) null
                    else unit.copy(variants = unit.variants.filterIndexed { vi, _ -> (ui to vi) !in badVariants })
                },
            )
            if (current.units.size < MIN_UNITS) {
                throw GenerationException.failed("Level ${current.levelId} has only ${current.units.size} valid unit(s); at least $MIN_UNITS are needed.")
            }
        }
        throw GenerationException.failed("Level ${current.levelId} still has validation issues after dropping units.")
    }

    private fun finish(level: Level, repaired: Boolean, droppedUnits: List<String>, droppedVariants: Int): ValidatedLevel {
        if (level.units.size < MIN_UNITS) throw GenerationException.failed("Level ${level.levelId} has only ${level.units.size} valid unit(s); at least $MIN_UNITS are needed.")
        return ValidatedLevel(level, repaired, droppedUnits, droppedVariants)
    }

    private fun shell(shell: LearningPackage, level: Level) = shell.copy(levels = listOf(level))

    private fun validate(level: Level, shell: LearningPackage): List<String> =
        PackageValidator.validate(shell(shell, level)).map { it.toString() }

    companion object {
        const val MIN_UNITS = 2
        private const val MAX_DROP_ROUNDS = 6
    }
}
