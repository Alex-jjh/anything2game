package com.anything2game.app.data.learning

import com.anything2game.contract.Level
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.tutor.TutorQuestion
import com.anything2game.contract.space.PathNode

/**
 * Arranges a bundled course into a learning path: one chapter per level, named after the level. A chapter explains the concepts
 * that belong to the level, then ends with the level's practice. Each step needs the one before it, so the route has a single
 * "next" step the way a course does.
 *
 * Frames (Case, Rookie, Escape) are different ways to play the same level. They share the level's single completion, so they are
 * not separate steps; the practice step simply opens in the frame the player prefers.
 */
internal object PresetRoute {
    /** Which level each concept belongs to: the level that cites the most of the concept's passages, never earlier than the previous concept's. */
    fun conceptLevels(pkg: LearningPackage, questions: List<TutorQuestion>): List<Int> {
        if (pkg.levels.isEmpty()) return questions.map { 0 }
        val cited = pkg.levels.map { level -> level.units.flatMap { u -> (listOf(u.item) + u.variants).flatMap { item -> item.allSourceRefs.map { it.chunkId } } }.toSet() }
        var previous = 0
        return questions.mapIndexed { i, q ->
            val overlap = cited.map { chunks -> q.sourceRefs.count { it.chunkId in chunks } }
            val best = overlap.max()
            val level = if (best > 0) overlap.indexOf(best) else i * pkg.levels.size / questions.size
            maxOf(previous, level).also { previous = it }
        }
    }

    /** [tutor] is aligned with [questions]; [practice] is aligned with the package's levels. */
    fun arrange(pkg: LearningPackage, questions: List<TutorQuestion>, tutor: List<PathNode>, practice: List<PathNode>): List<PathNode> {
        require(tutor.size == questions.size && practice.size == pkg.levels.size)
        val levelOf = conceptLevels(pkg, questions)
        val result = mutableListOf<PathNode>()
        fun add(node: PathNode, chapter: String) { result += node.copy(chapter = chapter, prerequisites = listOfNotNull(result.lastOrNull()?.nodeId)) }
        pkg.levels.forEachIndexed { index, level ->
            tutor.filterIndexed { i, _ -> levelOf[i] == index }.forEach { add(it, level.title) }
            add(practice[index], level.title)
        }
        return result
    }
}
