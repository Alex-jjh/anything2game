package com.anything2game.engine.progress

import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.progress.*
import com.anything2game.contract.space.LearningPath
import com.anything2game.engine.mastery.*

enum class NodeStatus { AVAILABLE, LOCKED, NEEDS_UPDATE, COMPLETE, REVIEW }

data class ActivityProgress(val played: Set<ActivityKey>, val completed: Set<ActivityKey>,
    val mastery: Map<ActivityKey, MasteryRecord>, val days: Set<String>) {
    val mastered get() = mastery.filterValues { it.isSolid }.keys

    fun nodes(profileId: String, path: LearningPath, installed: Set<ContentVersion>, now: Long): Map<String, NodeStatus> {
        val completeNodes = path.nodes.filter { it.activity.key(profileId) in completed }.map { it.nodeId }.toSet()
        return path.nodes.associate { node ->
            val a = node.activity
            val key = a.key(profileId)
            node.nodeId to when {
                ContentVersion(a.contentId, a.contentVersion) !in installed -> NodeStatus.NEEDS_UPDATE
                key in completed && mastery[key]?.let { it.dueAt <= now && !it.isSolid } == true -> NodeStatus.REVIEW
                key in completed -> NodeStatus.COMPLETE
                !completeNodes.containsAll(node.prerequisites) -> NodeStatus.LOCKED
                else -> NodeStatus.AVAILABLE
            }
        }
    }
}

object ProgressProjection {
    fun from(profileId: String, records: Iterable<CompletionRecord>): ActivityProgress {
        val played = mutableSetOf<ActivityKey>()
        val completed = mutableSetOf<ActivityKey>()
        val mastery = mutableMapOf<ActivityKey, MasteryRecord>()
        val days = mutableSetOf<String>()
        val policy = LeitnerPolicy()
        val unique = records.filter { it.activity.profileId == profileId }.distinctBy { it.completionId }
        // A retry of a recorded completion id always keeps the original result; tie order is input order.
        for (event in unique.sortedBy { it.timeMillis }) {
            val key = event.activity
            played += key
            if (event.outcome == ActivityOutcome.PLAYED) continue
            if (event.outcome != ActivityOutcome.WRONG) {
                completed += key
                days += event.localDate
            }
            val result = when (event.outcome) {
                ActivityOutcome.INDEPENDENT -> Outcome.CORRECT_FIRST_TRY
                ActivityOutcome.HELPED -> Outcome.CORRECT_WITH_HELP
                ActivityOutcome.WRONG -> Outcome.WRONG
                ActivityOutcome.READ, ActivityOutcome.PLAYED -> continue
            }
            mastery[key] = policy.update(mastery[key] ?: MasteryRecord(key.activityId), result, event.timeMillis)
        }
        return ActivityProgress(played, completed, mastery, days)
    }
}
