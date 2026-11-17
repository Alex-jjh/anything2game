package com.anything2game.engine.grading

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the player submitted for one item. One subtype per mechanic. */
@Serializable
sealed interface Answer

/** cardId → true if the player said "supported". */
@Serializable @SerialName("swipe")
data class SwipeAnswer(val verdicts: Map<String, Boolean>) : Answer

/** cardId → binId. */
@Serializable @SerialName("sort")
data class SortAnswer(val placements: Map<String, String>) : Answer

@Serializable @SerialName("deduce")
data class DeduceAnswer(val optionId: String) : Answer

/** Step ids in the order the player arranged them. */
@Serializable @SerialName("order")
data class OrderAnswer(val stepIds: List<String>) : Answer

/** Result of grading one answer. [parts] = cards/steps graded (1 for Deduce); [wrongPartIds] = the ones the player got wrong. */
@Serializable
data class Grade(val correct: Boolean, val wrongPartIds: Set<String>, val score: Double, val parts: Int = 1)

/** The statement the player challenged, and the passage presented as evidence. */
@Serializable @SerialName("crossexam")
data class CrossExamAnswer(val statementId: String, val chunkId: String) : Answer
