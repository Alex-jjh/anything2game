package com.anything2game.contract.progress

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

@Serializable enum class ActivityKind { GAME, TUTOR, PRACTICE }

@Serializable
data class ActivityKey(val profileId: String, val activityKind: ActivityKind, val contentId: String,
    val contentVersion: String, val activityId: String, val rulesVersion: Int, val tutorSessionId: String? = null)

@Serializable enum class ActivityOutcome { PLAYED, WRONG, READ, HELPED, INDEPENDENT }

@Serializable
data class CompletionRecord(val completionId: String, val activity: ActivityKey, val outcome: ActivityOutcome,
    val timeMillis: Long, val zoneId: String, val localDate: String) {
    companion object {
        fun at(completionId: String, activity: ActivityKey, outcome: ActivityOutcome, timeMillis: Long, zoneId: String): CompletionRecord {
            require(completionId.isNotBlank())
            val date = Instant.ofEpochMilli(timeMillis).atZone(ZoneId.of(zoneId)).toLocalDate()
            return CompletionRecord(completionId, activity, outcome, timeMillis, zoneId, date.toString())
        }
    }
}
