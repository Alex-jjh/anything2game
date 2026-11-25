package com.anything2game.engine.progress

import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.progress.*
import com.anything2game.contract.space.LearningPath
import com.anything2game.engine.mastery.*

enum class NodeStatus { AVAILABLE, LOCKED, NEEDS_UPDATE, COMPLETE, REVIEW }

/**
 * What the ledger says about one activity, in the one vocabulary every screen uses.
 * NEW: never attempted. NEEDS_PRACTICE: attempted, not finished right yet. DONE: finished, not solid yet. DUE: finished and
 * due for another look. LEARNED: solid (right first time on enough visits).
 */
enum class Standing { NEW, NEEDS_PRACTICE, DONE, DUE, LEARNED }

/** A [Standing], or "in progress n of m" while a saved session of the activity is open (an open session is not in the ledger). */
data class ActivityStatus(val standing: Standing, val position: Int? = null, val total: Int? = null) {
    val inProgress get() = position != null
}

data class ActivityProgress(val played: Set<ActivityKey>, val completed: Set<ActivityKey>,
    val mastery: Map<ActivityKey, MasteryRecord>, val days: Set<String>) {
    val mastered get() = mastery.filterValues { it.isSolid }.keys

    /** The one rule for "how is this activity going". The route, the practice list and Me all read it. */
    fun standing(key: ActivityKey, now: Long): Standing {
        val record = mastery[key]
        return when {
            key !in completed -> if (record != null) Standing.NEEDS_PRACTICE else Standing.NEW
            record == null -> Standing.DONE
            record.isSolid -> Standing.LEARNED
            record.dueAt <= now -> Standing.DUE
            else -> Standing.DONE
        }
    }

    /** [standing], or the open session's place when one is saved ([open] is its position and size). */
    fun status(key: ActivityKey, now: Long, open: Pair<Int, Int>? = null): ActivityStatus =
        open?.let { ActivityStatus(standing(key, now), it.first, it.second) } ?: ActivityStatus(standing(key, now))

    /** Standing of every node of [path]; the route's own statuses are this plus prerequisites and installed versions. */
    fun standings(profileId: String, path: LearningPath, now: Long): Map<String, Standing> =
        path.nodes.associate { it.nodeId to standing(it.activity.key(profileId), now) }

    fun nodes(profileId: String, path: LearningPath, installed: Set<ContentVersion>, now: Long): Map<String, NodeStatus> {
        val standing = standings(profileId, path, now)
        fun finished(standing: Standing?) = standing == Standing.DONE || standing == Standing.LEARNED || standing == Standing.DUE
        val completeNodes = path.nodes.filter { finished(standing[it.nodeId]) }.map { it.nodeId }.toSet()
        return path.nodes.associate { node ->
            val a = node.activity
            node.nodeId to when {
                ContentVersion(a.contentId, a.contentVersion) !in installed -> NodeStatus.NEEDS_UPDATE
                standing[node.nodeId] == Standing.DUE -> NodeStatus.REVIEW
                finished(standing[node.nodeId]) -> NodeStatus.COMPLETE
                !completeNodes.containsAll(node.prerequisites) -> NodeStatus.LOCKED
                else -> NodeStatus.AVAILABLE
            }
        }
    }
}

object ProgressProjection {
    fun from(profileId: String, records: Iterable<CompletionRecord>, policy: MasteryPolicy = LeitnerPolicy()): ActivityProgress {
        val played = mutableSetOf<ActivityKey>()
        val completed = mutableSetOf<ActivityKey>()
        val mastery = mutableMapOf<ActivityKey, MasteryRecord>()
        val days = mutableSetOf<String>()
        val unique = records.filter { it.activity.profileId == profileId }.distinctBy { it.completionId }
        // A retry of a recorded completion id always keeps the original result; tie order is input order.
        for (event in unique.sortedBy { it.timeMillis }) {
            val key = event.activity
            played += key
            if (event.outcome == ActivityOutcome.PLAYED) continue
            if (event.outcome == ActivityOutcome.READ && key.activityKind != ActivityKind.TUTOR) continue
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
